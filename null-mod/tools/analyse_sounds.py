#!/usr/bin/env python3
"""Listens to NULL's sounds for what does not belong in a horror mod: lasers, beeps and sweeps.

Decodes every Ogg file with ffmpeg and follows the spectral peaks of each file through time
(partial tracking at four time resolutions, so 20 ms "zips", stepped 8-bit "bloops", beeps and
ten-second risers are all seen). A peak only counts when it stands well above the spectrum around
it, belongs to a loud part of the file and carries a real share of the energy ("purity"): noise,
rumble, creaks, breath and voices do not make tracks like that, synthetic tones do. Every track is
then classified:

  GLIDE    a tonal component that moves more than an octave within half a second (the "pew" of a
           laser, a falling whistle, a power-down whine, a square-wave chirp, a cartoon "bloop");
  SWEEP    a tonal component that moves more than an octave in one direction over a longer time
           (a riser, a long fall);
  WHISTLE  a pure, prominent tone that wanders in pitch for a while (a theremin, a synthetic whistle);
  BEEP     a dead steady pure tone from 150 ms on, BLIP a shorter one (interface blips, data beeps,
           test-tone rings). Voices, bells and chimes waver or share their energy and are not beeps.

Sub-bass is ignored (a drum thump falling from 70 to 30 Hz is a thud, not a laser), and so are the
notes of the music: music.* files and the menu's music loop are only checked for glides and sweeps.

--selftest renders known offenders (a laser "pew", a 20 ms chirp, risers, a theremin, square chirps,
beeps, blips, a power-down whine, an 8-bit "bloop", a ring) and natural sounds (noise, thumps,
creaks, whispers, voices, a piano chord, knocks, an explosion, a heartbeat, breathing, footsteps)
with tools/gen_sounds.py's building blocks and fails unless every offender and no natural sound is
flagged.

Usage: python3 tools/analyse_sounds.py [FILES OR DIRECTORIES ...] [--all] [--png DIR] [--verbose]
       python3 tools/analyse_sounds.py --selftest
Without paths it checks the folders tools/gen_sounds.py writes; --all also reports every other
folder under assets/null/sounds (other areas' folders are reported as notes, never failed). Exit
status 1 if any checked file of gen_sounds.py is flagged.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import os
import pathlib
import subprocess
import sys

import numpy as np

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOUND_DIR = ROOT / "src" / "main" / "resources" / "assets" / "null" / "sounds"
# Folders written by tools/gen_sounds.py (the other areas own field/, it/, stalkers/ ...).
OWN_GROUPS = ("ambient", "block", "dimension", "event", "fakeplayer", "menu", "moon", "music", "nullroot",
              "transition", "visitor")
# Music: notes are allowed, glides and sweeps are not.
MUSIC_FILES = ("music/", "menu/ambience")
KINDS = ("GLIDE", "SWEEP", "WHISTLE", "BEEP", "BLIP")

LOW_HZ = 250.0          # below this, pitch movement is a thump or a rumble
HIGH_HZ = 14000.0
FLOOR_DB = 45.0         # frames quieter than the loudest frame by more than this are ignored
PEAK_RANGE_DB = 30.0    # peaks weaker than the frame's strongest by more than this are ignored
MIN_PROMINENCE_DB = 10.0
MAX_PEAKS = 6
RESOLUTIONS = (
    # (window, hop, largest pitch step between frames in semitones, frames a track may miss), at 44.1 kHz
    (512, 64, 3.0, 2),      # fast: chirps, "pew"s and blips
    (1024, 128, 2.0, 4),    # stepped or broken glides (8-bit "bloops", gated chirps)
    (2048, 256, 1.2, 1),    # medium: beeps, glides
    (4096, 1024, 1.0, 3),   # slow: long sweeps and risers
)


def decode(path: pathlib.Path) -> tuple[np.ndarray, int]:
    probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=sample_rate",
                            "-of", "csv=p=0", str(path)], capture_output=True, text=True, check=True)
    sr = int(probe.stdout.strip().split(",")[0])
    raw = subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-i", str(path), "-f", "f32le", "-ac", "1",
                          "-ar", str(sr), "pipe:1"], capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype="<f4").astype(np.float64), sr


def stft_power(x: np.ndarray, n_fft: int, hop: int) -> np.ndarray:
    if len(x) < n_fft:
        x = np.pad(x, (0, n_fft - len(x)))
    frames = np.lib.stride_tricks.sliding_window_view(x, n_fft)[::hop] * np.hanning(n_fft)
    return np.abs(np.fft.rfft(frames, axis=1)) ** 2


def peaks_per_frame(power: np.ndarray, sr: int, n_fft: int):
    """Returns per frame a list of (freq_hz, level_db, prominence_db, purity)."""
    bins = power.shape[1]
    freqs = np.arange(bins) * sr / n_fft
    s_db = 10.0 * np.log10(power + 1e-20)
    band = (freqs >= 150.0) & (freqs <= min(16000.0, sr / 2 - 1))
    frame_power = power[:, band].sum(axis=1) + 1e-20
    frame_db = 10.0 * np.log10(frame_power)
    loud = frame_db >= frame_db.max() - FLOOR_DB
    # Spectral envelope: mean dB of the neighbourhood (+-w bins) without the peak's own +-3 bins.
    w = max(8, int(round(220.0 / (sr / n_fft))))
    csum = np.concatenate([np.zeros((s_db.shape[0], 1)), np.cumsum(s_db, axis=1)], axis=1)
    idx = np.arange(bins)
    lo = np.clip(idx - w, 0, bins)
    hi = np.clip(idx + w + 1, 0, bins)
    clo = np.clip(idx - 3, 0, bins)
    chi = np.clip(idx + 4, 0, bins)
    total = csum[:, hi] - csum[:, lo]
    centre = csum[:, chi] - csum[:, clo]
    count = (hi - lo) - (chi - clo)
    env = (total - centre) / np.maximum(count, 1)
    prom = s_db - env
    local_max = np.zeros_like(s_db, dtype=bool)
    local_max[:, 2:-2] = ((s_db[:, 2:-2] >= s_db[:, 1:-3]) & (s_db[:, 2:-2] >= s_db[:, 3:-1])
                          & (s_db[:, 2:-2] >= s_db[:, :-4]) & (s_db[:, 2:-2] >= s_db[:, 4:]))
    in_range = (freqs >= LOW_HZ * 0.8) & (freqs <= HIGH_HZ)
    frame_max = np.max(np.where(band[None, :], s_db, -400.0), axis=1)
    cand = local_max & in_range[None, :] & (prom >= MIN_PROMINENCE_DB) & (s_db >= frame_max[:, None] - PEAK_RANGE_DB)
    cand &= loud[:, None]
    out = []
    for t in range(power.shape[0]):
        ks = np.nonzero(cand[t])[0]
        if len(ks) == 0:
            out.append([])
            continue
        if len(ks) > MAX_PEAKS:
            ks = ks[np.argsort(-s_db[t, ks])[:MAX_PEAKS]]
        frame = []
        for k in ks:
            a, b, c = s_db[t, k - 1], s_db[t, k], s_db[t, k + 1]
            denom = a - 2 * b + c
            delta = 0.5 * (a - c) / denom if abs(denom) > 1e-9 else 0.0
            f = (k + float(np.clip(delta, -0.5, 0.5))) * sr / n_fft
            purity = float(power[t, max(0, k - 2):k + 3].sum() / frame_power[t])
            frame.append((f, float(b), float(prom[t, k]), purity))
        out.append(frame)
    return out


def track(frames, dt: float, max_step_st: float, max_miss: int = 1):
    """Links peaks frame to frame into tracks (nearest pitch within max_step_st semitones); a track
    ends after more than max_miss frames without a peak."""
    finished = []
    active = []   # each: dict(t0, f[], prom[], pur[], miss)
    for t, peaks in enumerate(frames):
        used = set()
        for tr in active:
            last = tr["f"][-1]
            best, best_d = None, max_step_st
            for i, p in enumerate(peaks):
                if i in used:
                    continue
                d = abs(12.0 * np.log2(p[0] / last))
                if d <= best_d:
                    best, best_d = i, d
            if best is not None:
                used.add(best)
                p = peaks[best]
                tr["f"].append(p[0])
                tr["prom"].append(p[2])
                tr["pur"].append(p[3])
                tr["miss"] = 0
            else:
                tr["miss"] += 1
        still = []
        for tr in active:
            if tr["miss"] > max_miss:
                finished.append(tr)
            else:
                still.append(tr)
        active = still
        for i, p in enumerate(peaks):
            if i not in used:
                active.append({"t0": t * dt, "f": [p[0]], "prom": [p[2]], "pur": [p[3]], "miss": 0})
    finished.extend(active)
    return finished


def classify(tr, dt: float, music: bool):
    f = np.asarray(tr["f"])
    n = len(f)
    dur = n * dt
    if n < 3 or f.max() < 300.0:
        return None
    purity = float(np.mean(tr["pur"]))
    prom = float(np.mean(tr["prom"]))
    semis = 12.0 * np.log2(f / f[0])
    # Largest movement within any half second.
    window = max(1, int(0.5 / dt))
    span = 0.0
    for i in range(0, n, max(1, n // 400)):
        seg = semis[i:i + window]
        span = max(span, float(seg.max() - seg.min()))
    net = float(semis[-1] - semis[0])
    spread = float(semis.max() - semis.min())
    # How directly the pitch moves: 1 for a clean glide, small for a peak wandering through noise.
    travel = float(np.sum(np.abs(np.diff(semis)))) + 1e-9
    direct = abs(net) / travel
    info = {"t": tr["t0"], "dur": dur, "f0": float(f[0]), "f1": float(f[-1]), "fmin": float(f.min()),
            "fmax": float(f.max()), "span_st": span, "net_st": net, "spread_st": spread, "purity": purity,
            "prom": prom, "direct": direct}
    # A chirp: more than an octave within half a second (even a 15 ms "zip" of two octaves is heard as one).
    if span >= 12.0 and direct >= 0.6 and (
            (dur >= 0.04 and purity >= 0.2 and prom >= 12.0)
            or (dur >= 0.015 and n >= 10 and purity >= 0.35 and prom >= 20.0)
            # a harmonic-rich tone (square wave, buzz): each partial holds little of the energy, but
            # it glides for a long time, very directly
            or (dur >= 0.1 and direct >= 0.75 and purity >= 0.1 and prom >= 15.0)):
        return "GLIDE", info
    # A riser or a fall: more than an octave in one direction over a longer time (any tonal partial).
    if abs(net) >= 12.0 and dur >= 0.5 and direct >= 0.5 and purity >= 0.08 and prom >= 15.0:
        return "SWEEP", info
    # A pure tone that wanders in pitch for a while: a theremin, a synthetic whistle.
    if not music and dur >= 0.4 and spread >= 2.0 and purity >= 0.8 and prom >= 35.0:
        return "WHISTLE", info
    # A steady pure tone (a beep from 150 ms on, a blip below): almost all of the sound's energy in one
    # partial standing far above everything around it, with a dead steady pitch (voices always waver).
    if not music and spread < 0.6 and purity >= 0.75 and prom >= 28.0 and dur >= 0.02:
        return ("BEEP" if dur >= 0.15 else "BLIP"), info
    return None


def analyse_signal(x: np.ndarray, sr: int, music: bool = False) -> dict:
    """Finds glides, sweeps, whistles, beeps and blips in a mono signal: {kind: (key, worst info, count)}."""
    scale = sr / 44100.0
    found = []
    for window, hop, step, miss in RESOLUTIONS:
        n_fft = max(64, int(window * scale) // 2 * 2)
        h = max(16, int(hop * scale))
        power = stft_power(x, n_fft, h)
        frames = peaks_per_frame(power, sr, n_fft)
        dt = h / sr
        for tr in track(frames, dt, step, miss):
            c = classify(tr, dt, music)
            if c:
                found.append(c)
    # One line per kind: the worst instance (largest movement for glides, sweeps and whistles, purest
    # and longest for beeps).
    summary = {}
    for kind, info in found:
        key = {"GLIDE": info["span_st"], "SWEEP": abs(info["net_st"]), "WHISTLE": info["spread_st"]}.get(
            kind, info["purity"] * info["dur"])
        count = summary[kind][2] + 1 if kind in summary else 1
        if kind not in summary or key > summary[kind][0]:
            summary[kind] = (key, info, count)
        else:
            summary[kind] = (summary[kind][0], summary[kind][1], count)
    return summary


def describe(summary: dict) -> str:
    parts = []
    for kind in KINDS:
        if kind in summary:
            _, i, count = summary[kind]
            move = f"net {i['net_st']:+.1f} st" if kind == "SWEEP" else f"span {i['span_st']:.1f} st" if kind == "GLIDE" \
                else f"spread {i['spread_st']:.1f} st"
            parts.append(f"{kind} x{count} (worst at {i['t']:.2f}s: {i['f0']:.0f}->{i['f1']:.0f} Hz, {i['dur'] * 1000:.0f} ms, "
                         f"{move}, purity {i['purity']:.2f}, prominence {i['prom']:.0f} dB)")
    return "; ".join(parts)


def analyse(path: pathlib.Path):
    x, sr = decode(path)
    rel = path.relative_to(SOUND_DIR).as_posix() if path.is_relative_to(SOUND_DIR) else str(path)
    music = rel.startswith(MUSIC_FILES)
    summary = analyse_signal(x, sr, music)
    peak_db = 20 * np.log10(np.max(np.abs(x)) + 1e-12)
    return rel, len(x) / sr, peak_db, summary, sr, x


def spectrogram(path_png: pathlib.Path, x: np.ndarray, sr: int, title: str):
    from PIL import Image, ImageDraw
    n_fft, hop = 1024, 128
    if len(x) < n_fft:
        x = np.pad(x, (0, n_fft - len(x)))
    frames = np.lib.stride_tricks.sliding_window_view(x, n_fft)[::hop] * np.hanning(n_fft)
    mag = 20 * np.log10(np.abs(np.fft.rfft(frames, axis=1)) + 1e-9)
    fmax = min(sr / 2, 12000.0)
    bins = int(fmax / (sr / n_fft))
    img = np.clip((mag[:, :bins] - (mag.max() - 75.0)) / 75.0, 0, 1).T[::-1]
    width = int(np.clip(len(x) / sr * 200, 160, 1600))
    pic = Image.fromarray((img * 255).astype(np.uint8)).resize((width, 220), Image.BILINEAR)
    lut = np.zeros((256, 3), dtype=np.uint8)
    for i in range(256):
        v = i / 255.0
        lut[i] = (min(255, int(255 * min(1, v * 1.6))), int(255 * max(0, v * 1.5 - 0.5) ** 1.2), int(255 * 0.35 * np.sin(np.pi * v)))
    pic = Image.fromarray(lut[np.asarray(pic)])
    sheet = Image.new("RGB", (width, 236), (0, 0, 0))
    sheet.paste(pic, (0, 16))
    ImageDraw.Draw(sheet).text((2, 2), f"{title}  {len(x) / sr:.2f}s  0-{fmax / 1000:.0f} kHz", fill=(255, 255, 0))
    sheet.save(path_png)


# ---- self-test ------------------------------------------------------------------------------------

def selftest() -> int:
    """Known offenders must be flagged, natural sounds must pass."""
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
    import gen_sounds as g
    sr = 44100

    def rng(key):
        return g.rng_for("analyse-selftest/" + key)

    def chirp(n, f0, f1, tau):
        t = g.tvec(n)
        return np.sin(g.phase_of(f1 + (f0 - f1) * np.exp(-t / tau)))

    def pew():
        out = np.zeros(g.ns(0.6))
        for j in range(3):
            m = g.ns(0.2)
            g.place(out, chirp(m, 6000 + 500 * j, 300, 0.03) * g.attack_decay(m, 0.0004, 0.08), g.ns(0.004 * j))
        return out

    def zip_chirp():
        out = np.zeros(g.ns(0.5))
        m = g.ns(0.06)
        g.place(out, chirp(m, 2400, 180, 0.012) * g.attack_decay(m, 0.0005, 0.02) * 0.8, 0)
        g.place(out, g.thump(rng("zip"), g.ns(0.4), 95, 45, 0.03, 0.08, sr) * 0.9, 0)
        return out

    def riser():
        return np.sin(g.phase_of(200 * 8 ** (g.tvec(g.ns(6.0)) / 6.0)))

    def low_riser():
        dur = 10.5
        n = g.ns(dur)
        t = g.tvec(n)
        f = 32.0 * (220.0 / 32.0) ** ((t / dur) ** 1.6)
        drive = 0.6 + 5.0 * (t / dur) ** 2
        tone = np.sin(g.phase_of(f)) + 0.8 * np.sin(g.phase_of(f + 1.5 * (t / dur))) + 0.4 * np.sin(g.phase_of(f * 1.5 + 0.7))
        return np.tanh(tone * drive) / np.tanh(drive) * g.env_points(n, [(0, 0), (2, .25), (7, .7), (9.5, 1), (dur, 0)])

    def theremin():
        t = g.tvec(g.ns(5.0))
        semis = np.interp(t, np.linspace(0, 5, 8), np.cumsum(rng("theremin").choice([-2, -1, 1, 2, 3], size=8)))
        return np.sin(g.phase_of(760.0 * 2 ** (semis / 12.0) * (1 + 0.006 * np.sin(g.TAU * 5.2 * t))))

    def square_chirp():
        m = g.ns(0.1)
        return np.concatenate([g.square_raw(np.geomspace(500, 3500, m), m, sr) * 0.4, np.zeros(g.ns(0.2))])

    def beep():
        return np.concatenate([g.sine(1000.0, g.ns(0.3)), np.zeros(g.ns(0.1))])

    def blip():
        return np.concatenate([g.sine(2400.0, g.ns(0.07)) * np.exp(-g.tvec(g.ns(0.07)) / 0.012), np.zeros(g.ns(0.2))])

    def whine():
        m = g.ns(1.5)
        f = 40 + 1800 * np.exp(-g.tvec(m) / 0.28)
        return (np.sin(g.phase_of(f)) + 0.3 * np.sin(g.phase_of(f * 2.01))) * g.attack_decay(m, 0.01, 0.5)

    def bloop():
        parts, f = [], 480.0
        for _ in range(12):
            m = g.ns(0.024)
            parts.append(g.bitcrush(g.square_raw(f, m, sr, 0.35) * 0.6 * g.hann(m) ** 0.1, 4, 2))
            f *= 0.93
        return np.concatenate(parts + [np.zeros(g.ns(0.1))])

    def ring():
        n = g.ns(1.6)
        return g.sine(5600.0, n) * g.env_points(n, [(0, 0), (0.3, 1), (1.6, 0)])

    def voices():
        r = rng("voices")
        out = np.zeros(g.ns(5.0))
        for f0 in (98, 165, 215):
            s = g.voice(r, 3.5, f0=f0)
            g.place(out, s / np.max(np.abs(s)), g.ns(r.uniform(0, 1.4)))
        out = g.spectral(out, g.r_mul(g.r_hp(160, 2), g.r_lp(1800, 3)), sr)
        return g.reverb(out, r, 2.2, wet=0.75, dry=0.45, bright=0.2, predelay=0.04)

    offenders = {"laser pew": pew, "20 ms chirp": zip_chirp, "riser": riser, "low distorted riser": low_riser,
                 "theremin": theremin, "square chirp": square_chirp, "beep": beep, "blip": blip,
                 "power-down whine": whine, "8-bit bloop": bloop, "test-tone ring": ring}
    natural = {
        "noise": lambda: g.white(g.ns(1.0), rng("noise")) * 0.3,
        "thump": lambda: g.thump(rng("thump"), g.ns(1.0), 70, 32, 0.08, 0.25, sr),
        "creak": lambda: g.friction_creak(rng("creak"), 1.5, [(0, 20), (0.75, 40), (1.5, 15)], [300, 700], 10.0, sr=sr),
        "whisper": lambda: g.whisper(rng("whisper"), 2.5),
        "voice": lambda: g.voice(rng("voice"), 3.0, f0=128.0),
        "distant voices": voices,
        "piano chord": lambda: sum(g.piano_note(rng("piano"), g.midi_hz(m), 2.5, sr, 0.7) for m in (57, 60, 64)),
        "door knock": lambda: g.door_knock(rng("knock"), 1.0, sr),
        "breathing": lambda: g.breath_cycle(rng("breath"), 1.0, 1.4, 0.2, sr, rasp=0.6, darkness=0.72),
        "footsteps": lambda: g.SPECS["event.footsteps_player"]["fn"](rng("steps"), 0, sr),
        "explosion": lambda: g.SPECS["event.explosion_distant"]["fn"](rng("boom"), 0, sr),
        "heartbeat": lambda: g.SPECS["ambient.heartbeat"]["fn"](rng("heart"), 0, sr),
    }
    failures = 0
    for expect, cases in ((True, offenders), (False, natural)):
        for name, make in cases.items():
            x = np.asarray(make(), dtype=np.float64)
            x = x / (np.max(np.abs(x)) + 1e-12) * 0.8
            summary = analyse_signal(x, sr)
            ok = bool(summary) == expect
            failures += 0 if ok else 1
            print(f"{'ok  ' if ok else 'FAIL'} {'offender' if expect else 'natural ':<8} {name:<20} {describe(summary)}")
    print(f"self-test: {failures} failure(s)")
    return 1 if failures else 0


# ---- main -----------------------------------------------------------------------------------------

def collect(args) -> list[pathlib.Path]:
    if args.paths:
        files = []
        for p in args.paths:
            p = pathlib.Path(p).resolve()
            files.extend(sorted(p.rglob("*.ogg")) if p.is_dir() else [p])
        return files
    groups = sorted(d.name for d in SOUND_DIR.iterdir() if d.is_dir()) if args.all else OWN_GROUPS
    return [f for g in groups for f in sorted((SOUND_DIR / g).rglob("*.ogg"))]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("paths", nargs="*")
    parser.add_argument("--all", action="store_true", help="also report the other areas' folders")
    parser.add_argument("--png", type=pathlib.Path, help="write a spectrogram of every flagged file here")
    parser.add_argument("--verbose", action="store_true", help="print clean files too")
    parser.add_argument("--selftest", action="store_true", help="check the detector against known sounds")
    parser.add_argument("--jobs", type=int, default=max(1, os.cpu_count() or 2))
    args = parser.parse_args()
    if args.selftest:
        return selftest()
    files = collect(args)
    with concurrent.futures.ProcessPoolExecutor(max_workers=args.jobs) as pool:
        results = list(pool.map(analyse, files))
    failed = 0
    flagged = 0
    for (rel, seconds, peak_db, summary, sr, x) in results:
        own = rel.split("/", 1)[0] in OWN_GROUPS
        if summary:
            flagged += 1
            failed += 1 if own else 0
            print(f"{'FLAG' if own else 'note'} {rel:<38} {describe(summary)}")
            if args.png:
                args.png.mkdir(parents=True, exist_ok=True)
                spectrogram(args.png / (rel.replace("/", "_").replace(".ogg", ".png")), x, sr, rel)
        elif args.verbose:
            print(f"ok   {rel:<38} {seconds:6.2f}s peak {peak_db:6.2f} dBFS")
    print(f"{len(results)} files checked, {flagged} flagged ({failed} of them generated by gen_sounds.py)")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
