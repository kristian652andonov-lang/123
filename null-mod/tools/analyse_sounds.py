#!/usr/bin/env python3
"""Listens to NULL's sounds for what does not belong in a horror mod: lasers, beeps and sweeps.

Decodes every Ogg file with ffmpeg and follows the spectral peaks of each file through time
(partial tracking, at a fast and a slow time resolution so both short chirps and slow sweeps are
seen). A peak only counts when it stands well above the spectrum around it, belongs to a loud part
of the file and carries a real share of the energy ("purity"): noise, rumble, creaks and voices do
not make tracks like that, synthetic tones do. Every track is then classified:

  GLIDE  a tonal component that moves more than an octave within half a second (the "pew" of a laser,
         a falling whistle, a power-down whine, a cartoon gulp);
  SWEEP  a tonal component that moves more than an octave in one direction over a longer time (a riser
         or a theremin-like whistle);
  BEEP   a steady pure tone from 150 ms on, BLIP a shorter one (interface blips, data beeps, rings).

Sub-bass is ignored (a drum thump falling from 70 to 30 Hz is a thud, not a laser), and so are the
notes of the music tracks: music is allowed to have notes, so music.* files and the menu's music loop
are only checked for glides and sweeps.

Usage: python3 tools/analyse_sounds.py [FILES OR DIRECTORIES ...] [--png DIR] [--all] [--verbose]
Without paths it checks the folders tools/gen_sounds.py writes; --all checks every folder under
assets/null/sounds (other areas' folders are reported, never failed). Exit status 1 if any checked
file of gen_sounds.py is flagged.
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

LOW_HZ = 250.0          # below this, pitch movement is a thump or a rumble
HIGH_HZ = 14000.0
FLOOR_DB = 45.0         # frames quieter than the loudest frame by more than this are ignored
PEAK_RANGE_DB = 30.0    # peaks weaker than the frame's strongest by more than this are ignored
MIN_PROMINENCE_DB = 10.0
MAX_PEAKS = 6


def decode(path: pathlib.Path) -> tuple[np.ndarray, int]:
    probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=sample_rate",
                            "-of", "csv=p=0", str(path)], capture_output=True, text=True, check=True)
    sr = int(probe.stdout.strip().split(",")[0])
    raw = subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-i", str(path), "-f", "f32le", "-ac", "1",
                          "-ar", str(sr), "pipe:1"], capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype="<f4").astype(np.float64), sr


def stft_db(x: np.ndarray, n_fft: int, hop: int) -> np.ndarray:
    if len(x) < n_fft:
        x = np.pad(x, (0, n_fft - len(x)))
    frames = np.lib.stride_tricks.sliding_window_view(x, n_fft)[::hop] * np.hanning(n_fft)
    power = np.abs(np.fft.rfft(frames, axis=1)) ** 2
    return power


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


def track(frames, dt: float, max_step_st: float):
    """Links peaks frame to frame into tracks (nearest pitch within max_step_st semitones)."""
    finished = []
    active = []   # each: dict(t0, f[], lvl[], prom[], pur[], miss)
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
            if tr["miss"] > 1:
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
    # How directly the pitch moves: 1 for a clean glide, small for a peak wandering through noise.
    travel = float(np.sum(np.abs(np.diff(semis)))) + 1e-9
    direct = abs(net) / travel
    info = {"t": tr["t0"], "dur": dur, "f0": float(f[0]), "f1": float(f[-1]), "fmin": float(f.min()),
            "fmax": float(f.max()), "span_st": span, "net_st": net, "purity": purity, "prom": prom, "direct": direct}
    if span >= 12.0 and dur >= 0.04 and direct >= 0.6 and purity >= 0.2 and prom >= 12.0:
        return "GLIDE", info
    if abs(net) >= 12.0 and dur >= 0.5 and direct >= 0.5 and purity >= 0.15 and prom >= 12.0:
        return "SWEEP", info
    if not music and float(semis.max() - semis.min()) < 1.0 and purity >= 0.55 and prom >= 18.0 and dur >= 0.02:
        return ("BEEP" if dur >= 0.15 else "BLIP"), info
    return None


def analyse(path: pathlib.Path):
    x, sr = decode(path)
    rel = path.relative_to(SOUND_DIR).as_posix() if path.is_relative_to(SOUND_DIR) else str(path)
    music = rel.startswith(MUSIC_FILES)
    scale = sr / 44100.0
    found = []
    # Fast resolution for chirps and blips, slow resolution for beeps and sweeps.
    for n_fft, hop, step in ((int(512 * scale) // 2 * 2, max(16, int(64 * scale)), 1.6),
                             (int(2048 * scale) // 2 * 2, max(64, int(256 * scale)), 1.2)):
        power = stft_db(x, n_fft, hop)
        frames = peaks_per_frame(power, sr, n_fft)
        dt = hop / sr
        for tr in track(frames, dt, step):
            c = classify(tr, dt, music)
            if c:
                found.append(c)
    # One line per kind: the worst instance (largest span for glides/sweeps, purest for beeps).
    summary = {}
    for kind, info in found:
        key = info["span_st"] if kind in ("GLIDE", "SWEEP") else info["purity"] * info["dur"]
        if kind not in summary or key > summary[kind][0]:
            summary[kind] = (key, info, summary.get(kind, (0, None, 0))[2] + 1)
        else:
            summary[kind] = (summary[kind][0], summary[kind][1], summary[kind][2] + 1)
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


def collect(args) -> list[pathlib.Path]:
    if args.paths:
        files = []
        for p in args.paths:
            p = pathlib.Path(p)
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
    parser.add_argument("--jobs", type=int, default=max(1, os.cpu_count() or 2))
    args = parser.parse_args()
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
            parts = []
            for kind in ("GLIDE", "SWEEP", "BEEP", "BLIP"):
                if kind in summary:
                    _, i, count = summary[kind]
                    parts.append(f"{kind} x{count} (worst at {i['t']:.2f}s: {i['f0']:.0f}->{i['f1']:.0f} Hz, "
                                 f"{i['dur'] * 1000:.0f} ms, span {i['span_st']:.1f} st, purity {i['purity']:.2f}, "
                                 f"prominence {i['prom']:.0f} dB)")
            print(f"{'FLAG' if own else 'note'} {rel:<38} " + "; ".join(parts))
            if args.png:
                args.png.mkdir(parents=True, exist_ok=True)
                spectrogram(args.png / (rel.replace("/", "_").replace(".ogg", ".png")), x, sr, rel)
        elif args.verbose:
            print(f"ok   {rel:<38} {seconds:6.2f}s peak {peak_db:6.2f} dBFS")
    print(f"{len(results)} files checked, {flagged} flagged ({failed} of them generated by gen_sounds.py)")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
