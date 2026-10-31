#!/usr/bin/env python3
"""Synthesises the figure's sounds ("stalkers", numpy only, no samples) and describes them for sounds.json.

Writes src/main/resources/assets/null/sounds/stalkers/<name>_<n>.ogg (mono, 44.1 kHz, Ogg Vorbis via
ffmpeg/libvorbis in bit-exact mode, so a re-run produces identical files) and the event fragment
assets-src/sounds/stalkers.json; then run tools/merge_sounds.py. Every variant has its own fixed seed.

  stalker.door_creak        an old wooden door opening slowly: stick-slip friction through the wood,
                            a low groan of the hinge, never a whistle
  stalker.footsteps         one heavy, slow footfall on earth: a dull thud, the ground giving, grit
  stalker.dig               a pickaxe striking stone somewhere in the rock: the crack of the blow and
                            the iron, muffled by the stone between, then the echo of the tunnels
  stalker.breath_glass      slow breathing against a window pane: a long wet inhale, a rattling
                            exhale, dulled by the glass
  stalker.glass_tap         a fingernail on a pane, twice: hard little knocks and the glass's short ring
  stalker.runner_footsteps  one footfall of something sprinting: a hard slap and thud, grit thrown up

Horror only: resonances are short and inharmonic (knocks, never a beep), noises are shaped, nothing
glides. --analyze DIR writes a spectrogram of every file and checks each (after decoding the
encoded file) for long pure tones (beeps), tonal components gliding more than an octave within half
a second (lasers, whistles) and clipping.

usage: python3 tools/gen_sounds_stalkers.py [--analyze DIR]
"""
import argparse
import json
import pathlib
import shutil
import subprocess
import sys

import numpy as np

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOUND_DIR = ROOT / "src" / "main" / "resources" / "assets" / "null" / "sounds" / "stalkers"
FRAGMENT = ROOT / "assets-src" / "sounds" / "stalkers.json"
SR = 44100
PEAK_DB = -1.5
QUALITY = 5


# ====================================================================== DSP

def secs(n):
    return np.arange(n) / SR


def samples(seconds):
    return int(round(seconds * SR))


def spectrum_filter(x, response):
    """Zero-phase filtering in the frequency domain: response(freqs) -> gain."""
    n = len(x)
    size = 1 << int(np.ceil(np.log2(n + 1)))
    spec = np.fft.rfft(x, size)
    freqs = np.fft.rfftfreq(size, 1.0 / SR)
    return np.fft.irfft(spec * response(np.maximum(freqs, 1e-3)), size)[:n]


def lowpass(x, fc, order=2):
    return spectrum_filter(x, lambda f: 1.0 / np.sqrt(1.0 + (f / fc) ** (2 * order)))


def highpass(x, fc, order=2):
    return spectrum_filter(x, lambda f: (f / fc) ** order / np.sqrt(1.0 + (f / fc) ** (2 * order)))


def bandpass(x, fc, q):
    return spectrum_filter(x, lambda f: 1.0 / np.sqrt(1.0 + q * q * (f / fc - fc / f) ** 2))


def coloured(rng, n, slope):
    """Noise with a spectral slope (0 white, -1 pink, -2 brown), normalised."""
    x = spectrum_filter(rng.standard_normal(n), lambda f: np.where(f < 15.0, 0.0, (f / 100.0) ** (slope / 2.0)))
    return x / (np.std(x) + 1e-12)


def stft_shape(x, gain_fn, win=2048, hop=512):
    """Time-varying filter: gain_fn(time_seconds, freqs) -> gain per bin, overlap-added."""
    n = len(x)
    window = np.hanning(win)
    padded = np.concatenate([np.zeros(win), x, np.zeros(win)])
    out = np.zeros_like(padded)
    norm = np.zeros_like(padded)
    freqs = np.fft.rfftfreq(win, 1.0 / SR)
    for start in range(0, len(padded) - win, hop):
        frame = padded[start:start + win] * window
        t = (start + win / 2 - win) / SR
        spec = np.fft.rfft(frame) * gain_fn(t, np.maximum(freqs, 1e-3))
        out[start:start + win] += np.fft.irfft(spec, win) * window
        norm[start:start + win] += window * window
    return (out / np.maximum(norm, 1e-6))[win:win + n]


def envelope(n, points):
    """Piecewise linear envelope from (time_s, value) points."""
    times = np.array([p[0] for p in points]) * SR
    values = np.array([p[1] for p in points], dtype=float)
    return np.interp(np.arange(n), times, values)


def decay(n, tau, attack=0.002):
    t = secs(n)
    return np.minimum(1.0, t / max(attack, 1e-4)) * np.exp(-t / tau)


def smooth_noise(rng, n, rate_hz):
    """Slow random wandering (never monotonic), unit variance."""
    points = max(4, int(n / SR * rate_hz) + 4)
    ctrl = rng.standard_normal(points)
    x = np.interp(np.linspace(0, points - 1, n), np.arange(points), ctrl)
    x = lowpass(x, rate_hz * 0.7, 1)
    return x / (np.std(x) + 1e-12)


def modes(rng, n, at, freq_range, count, tau_range, amp=1.0):
    """A knock: a few inharmonic, quickly dying resonances starting at sample `at` (never a beep)."""
    out = np.zeros(n)
    if at >= n:
        return out
    length = min(n - at, samples(0.25))
    t = secs(length)
    hit = np.zeros(length)
    for _ in range(count):
        f = rng.uniform(*freq_range)
        tau = rng.uniform(*tau_range)
        hit += rng.uniform(0.4, 1.0) * np.sin(2 * np.pi * f * t + rng.uniform(0, 6.28)) * np.exp(-t / tau)
    out[at:at + length] += amp * hit
    return out


def burst(rng, n, at, tau, low, high, amp=1.0):
    """A filtered noise burst (a crack, a slap, a scuff) starting at sample `at`."""
    out = np.zeros(n)
    if at >= n:
        return out
    length = min(n - at, samples(max(0.05, tau * 8)))
    noise = rng.standard_normal(length) * decay(length, tau, 0.0008)
    noise = spectrum_filter(noise, lambda f: (f / low) ** 2 / (1 + (f / low) ** 2) / np.sqrt(1 + (f / high) ** 4))
    out[at:at + length] += amp * noise / (np.max(np.abs(noise)) + 1e-12)
    return out


def thud(rng, n, at, f_start, f_end, tau, amp=1.0):
    """A body thump: a low, quickly dying swell whose pitch sinks a little (sub-bass, a thud, not a tone)."""
    out = np.zeros(n)
    if at >= n:
        return out
    length = min(n - at, samples(tau * 7))
    t = secs(length)
    f = f_end + (f_start - f_end) * np.exp(-t / (tau * 0.6))
    phase = 2 * np.pi * np.cumsum(f) / SR
    body = np.sin(phase) * decay(length, tau, 0.003)
    out[at:at + length] += amp * body
    return out


def crackle(rng, n, density, env, low=1200.0, high=7000.0):
    """Dense, irregular crackling (grit, crumbling stone): sparse impulses, filtered."""
    impulses = (rng.random(n) < density / SR) * rng.standard_normal(n) * env
    x = spectrum_filter(impulses, lambda f: (f / low) ** 2 / (1 + (f / low) ** 2) / np.sqrt(1 + (f / high) ** 4))
    return x / (np.max(np.abs(x)) + 1e-12)


def reverb(rng, x, rt60, mix, size=1.0, damping=3000.0, predelay=0.0):
    """Synthetic convolution reverb: decaying noise, darker as it decays."""
    n_ir = samples(rt60 * 1.2)
    t = secs(n_ir)
    ir = rng.standard_normal(n_ir) * np.exp(-6.9 * t / rt60)
    bright = highpass(ir, damping, 1) * np.exp(-6.9 * t / (rt60 * 0.35))
    dark = lowpass(ir, damping, 1)
    ir = dark + bright
    spread = np.zeros(n_ir)
    for _ in range(max(1, int(12 * size))):
        delay = int(rng.integers(0, max(1, samples(0.08 * size))))
        spread[delay:] += rng.uniform(-1, 1) * ir[:n_ir - delay]
    ir = spread
    ir = np.concatenate([np.zeros(samples(predelay)), ir])
    ir /= np.sqrt(np.sum(ir ** 2)) + 1e-12
    size_fft = 1 << int(np.ceil(np.log2(len(x) + len(ir))))
    wet = np.fft.irfft(np.fft.rfft(x, size_fft) * np.fft.rfft(ir, size_fft), size_fft)[:len(x) + len(ir) - 1]
    dry = np.concatenate([x, np.zeros(len(ir) - 1)])
    wet *= np.std(dry) / (np.std(wet) + 1e-12)
    return (1 - mix) * dry + mix * wet


def saturate(x, drive):
    return np.tanh(drive * x) / np.tanh(drive)


def fade_out(x, seconds):
    n = min(len(x), samples(seconds))
    x = x.copy()
    x[-n:] *= np.linspace(1.0, 0.0, n) ** 2
    return x


def trim_silence(x, threshold_db=-70.0):
    level = 10 ** (threshold_db / 20.0) * np.max(np.abs(x))
    idx = np.nonzero(np.abs(x) > level)[0]
    end = idx[-1] + samples(0.02) if len(idx) else len(x)
    return x[:min(len(x), end)]


def finish(x, peak_db=PEAK_DB, tail=0.05):
    x = trim_silence(x)
    x = x - np.mean(x)
    x = fade_out(x, tail)
    fade_in = min(len(x), samples(0.002))
    x[:fade_in] *= np.linspace(0, 1, fade_in)
    return x / (np.max(np.abs(x)) + 1e-12) * 10 ** (peak_db / 20.0)


# ====================================================================== the sounds

def door_creak(rng):
    """Stick-slip: the hinge catches and lets go dozens of times a second, irregularly; every slip
    knocks the wood's own resonances. The slip rate wanders inside less than an octave."""
    seconds = rng.uniform(1.5, 2.3)
    n = samples(seconds)
    rate = 34.0 + 9.0 * smooth_noise(rng, n, 2.5) + 3.0 * smooth_noise(rng, n, 11.0)
    rate = np.clip(rate, 22.0, 52.0)
    pulses = np.zeros(n)
    at = samples(0.05)
    while at < n:
        period = SR / rate[at] * rng.uniform(0.8, 1.25)
        pulses[at] = rng.uniform(0.35, 1.0) * (1.0 if rng.random() > 0.12 else 2.2)
        at += max(1, int(period))
    # Each slip is a tiny click, not an ideal impulse.
    click = np.exp(-secs(samples(0.004)) / 0.0007)
    excitation = np.convolve(pulses, click)[:n]
    wood = np.zeros(n)
    for fc, q, gain in ((190, 5, 1.0), (430, 7, 0.9), (780, 9, 0.7), (1210, 10, 0.55), (1870, 12, 0.35), (2650, 12, 0.22)):
        f = fc * rng.uniform(0.88, 1.12)
        wood += gain * bandpass(excitation, f, q)
    groan = lowpass(excitation, 260.0, 2) * 0.8
    body = wood + groan
    shape = envelope(n, [(0.0, 0.0), (0.12, 0.7), (0.35 * seconds, 1.0), (0.7 * seconds, 0.85), (seconds - 0.25, 0.6), (seconds, 0.0)])
    shape *= 1.0 + 0.35 * smooth_noise(rng, n, 4.0)
    x = body * np.clip(shape, 0.0, None)
    x = saturate(x / (np.max(np.abs(x)) + 1e-12), 1.6)
    x = reverb(rng, x, 0.7, 0.18, size=0.6, damping=2500.0)
    return finish(x, tail=0.15)


def footstep(rng):
    """A heavy, slow footfall on earth: the thud of the weight, the ground giving, a little grit."""
    n = samples(0.9)
    at = samples(0.01)
    x = thud(rng, n, at, rng.uniform(80, 95), rng.uniform(46, 56), 0.07, 1.0)
    x += 0.55 * lowpass(burst(rng, n, at, 0.03, 60.0, 700.0), 500.0, 2)
    x += 0.18 * burst(rng, n, at + samples(0.004), 0.012, 400.0, 2400.0)
    grit_env = envelope(n, [(0.0, 0.0), (0.01, 1.0), (0.08, 0.5), (0.2, 0.0), (0.9, 0.0)])
    x += 0.12 * crackle(rng, n, 900.0, grit_env, 1500.0, 6000.0)
    # The settling of the weight: a second, softer press.
    x += 0.25 * thud(rng, n, at + samples(rng.uniform(0.09, 0.13)), 70.0, 45.0, 0.05)
    x = reverb(rng, x, 0.45, 0.14, size=0.5, damping=1800.0)
    return finish(x, tail=0.12)


def dig(rng):
    """A pickaxe striking stone through the rock: the crack and the short ring of iron, dulled, then
    the tunnels answering."""
    n = samples(2.4)
    at = samples(0.01)
    x = 0.9 * burst(rng, n, at, 0.0015, 1200.0, 9000.0)
    x += 0.8 * burst(rng, n, at + samples(0.001), 0.02, 700.0, 3200.0)
    x += 0.45 * modes(rng, n, at, (1700.0, 3600.0), 5, (0.012, 0.035))
    x += 0.7 * thud(rng, n, at, 210.0, 140.0, 0.035)
    # Chips falling after the blow.
    debris_env = envelope(n, [(0.0, 0.0), (0.03, 0.0), (0.06, 1.0), (0.35, 0.3), (0.6, 0.0), (2.4, 0.0)])
    x += 0.2 * crackle(rng, n, 140.0, debris_env, 900.0, 5000.0)
    # Muffled by the stone between.
    x = lowpass(x, rng.uniform(1500.0, 1900.0), 2)
    x = reverb(rng, x, rng.uniform(1.5, 2.0), 0.5, size=1.6, damping=1300.0, predelay=0.035)
    return finish(x, tail=0.4)


def breath_glass(rng):
    """Slow breathing right against a window: a long wet inhale, a short hold, a rattling exhale;
    the glass takes the top off it."""
    inhale = rng.uniform(1.0, 1.3)
    hold = rng.uniform(0.15, 0.3)
    exhale = rng.uniform(1.2, 1.6)
    seconds = inhale + hold + exhale + 0.3
    n = samples(seconds)
    noise = coloured(rng, n, -0.6)
    t_out = inhale + hold

    def throat(t, f):
        if t < inhale:
            table = ((620.0, 260.0, 1.0), (1500.0, 420.0, 0.6), (2600.0, 600.0, 0.35))
        else:
            table = ((420.0, 220.0, 1.0), (1100.0, 380.0, 0.7), (2300.0, 520.0, 0.3))
        g = np.full_like(f, 0.03)
        for fc, bw, amp in table:
            g = g + amp / (1.0 + ((f - fc) / (bw * 0.5)) ** 2)
        return g

    air = stft_shape(noise, throat)
    shape = envelope(n, [(0.0, 0.0), (inhale * 0.7, 0.75), (inhale, 0.9), (inhale + 0.05, 0.0), (t_out, 0.0),
                         (t_out + 0.12, 1.0), (t_out + exhale * 0.6, 0.7), (t_out + exhale, 0.0), (seconds, 0.0)])
    x = air * shape * (1.0 + 0.25 * smooth_noise(rng, n, 7.0))
    # The wet rattle in the exhale: irregular low pulses (no pitch to speak of).
    rattle = np.zeros(n)
    at = samples(t_out + 0.1)
    end = samples(t_out + exhale * 0.85)
    while at < end:
        length = samples(rng.uniform(0.012, 0.03))
        if at + length >= n:
            break
        rattle[at:at + length] += rng.uniform(0.3, 1.0) * np.hanning(length)
        at += samples(rng.uniform(0.018, 0.05))
    rattle = lowpass(bandpass(rattle * coloured(rng, n, -1.0), 180.0, 1.2), 600.0, 2)
    x += 0.5 * rattle / (np.max(np.abs(rattle)) + 1e-12) * np.max(np.abs(x))
    # Through the glass: the highs are gone, a faint knock of breath on the pane at the exhale.
    x = lowpass(x, 1150.0, 2)
    x += 0.12 * np.max(np.abs(x)) * lowpass(burst(rng, n, samples(t_out + 0.08), 0.03, 80.0, 400.0), 300.0, 2)
    x = reverb(rng, x, 0.35, 0.1, size=0.4, damping=1500.0)
    return finish(x, peak_db=-3.0, tail=0.2)


def glass_tap(rng):
    """A fingernail on a pane, twice: hard little knocks and the glass's short, bright ring."""
    n = samples(0.7)
    x = np.zeros(n)
    gap = rng.uniform(0.16, 0.24)
    for k, at_s in enumerate((0.01, 0.01 + gap)):
        at = samples(at_s)
        amp = 1.0 if k == 0 else rng.uniform(0.7, 0.9)
        x += amp * 0.8 * burst(rng, n, at, 0.0008, 2500.0, 12000.0)
        x += amp * 0.6 * modes(rng, n, at, (1300.0, 4200.0), 6, (0.006, 0.022))
        x += amp * 0.35 * thud(rng, n, at, 260.0, 200.0, 0.012)
    x = reverb(rng, x, 0.4, 0.15, size=0.4, damping=4000.0)
    return finish(x, tail=0.08)


def runner_step(rng):
    """One footfall of something sprinting: a hard slap, the thud under it, grit thrown up."""
    n = samples(0.45)
    at = samples(0.005)
    x = 1.0 * thud(rng, n, at, rng.uniform(95, 115), rng.uniform(55, 65), 0.045)
    x += 0.75 * burst(rng, n, at, 0.008, 250.0, 2000.0)
    x += 0.3 * burst(rng, n, at + samples(0.002), 0.004, 1500.0, 7000.0)
    scuff_env = envelope(n, [(0.0, 0.0), (0.006, 1.0), (0.05, 0.6), (0.12, 0.0), (0.45, 0.0)])
    x += 0.22 * crackle(rng, n, 2200.0, scuff_env, 1800.0, 7500.0)
    x = saturate(x / (np.max(np.abs(x)) + 1e-12), 1.4)
    x = reverb(rng, x, 0.3, 0.1, size=0.4, damping=2500.0)
    return finish(x, tail=0.06)


# event, generator, variants, seed
SOUNDS = [
    ("stalker.door_creak", door_creak, 4, 501),
    ("stalker.footsteps", footstep, 4, 502),
    ("stalker.dig", dig, 4, 503),
    ("stalker.breath_glass", breath_glass, 3, 504),
    ("stalker.glass_tap", glass_tap, 3, 505),
    ("stalker.runner_footsteps", runner_step, 5, 506),
]


# ====================================================================== files

def encode(signal, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    data = np.ascontiguousarray(signal, dtype="<f4").tobytes()
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "f32le", "-ar", str(SR), "-ac", "1", "-i", "pipe:0",
           "-map_metadata", "-1", "-c:a", "libvorbis", "-q:a", str(QUALITY), "-ac", "1",
           "-flags:a", "+bitexact", "-fflags", "+bitexact", str(path)]
    subprocess.run(cmd, input=data, check=True)


def decode(path):
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-i", str(path), "-f", "f32le", "-ar", str(SR), "-ac", "1", "pipe:1"]
    raw = subprocess.run(cmd, capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype="<f4").astype(np.float64)


def render_all():
    fragment = {}
    produced = []
    if SOUND_DIR.exists():
        for old in SOUND_DIR.glob("*.ogg"):
            old.unlink()
    for event, generator, variants, seed in SOUNDS:
        stem = event.split(".", 1)[1]
        names = []
        for i in range(variants):
            rng = np.random.default_rng(seed * 1000 + i)
            signal = generator(rng)
            name = f"{stem}_{i + 1}"
            path = SOUND_DIR / f"{name}.ogg"
            encode(signal, path)
            # Vorbis can overshoot a little: make sure the decoded peak stays under -1 dBFS.
            for _ in range(4):
                peak = np.max(np.abs(decode(path)))
                if 20 * np.log10(peak + 1e-12) <= -1.0:
                    break
                signal = signal * (10 ** (-1.2 / 20.0) / peak)
                encode(signal, path)
            names.append({"name": f"null:stalkers/{name}"})
            produced.append((event, path))
        fragment[event] = {"subtitle": f"subtitles.null.{event}", "sounds": names}
    FRAGMENT.parent.mkdir(parents=True, exist_ok=True)
    FRAGMENT.write_text(json.dumps(fragment, indent=2) + "\n", encoding="utf-8")
    return produced


# ====================================================================== analysis

def stft(x, win=2048, hop=256):
    window = np.hanning(win)
    frames = [np.fft.rfft(x[s:s + win] * window) for s in range(0, max(1, len(x) - win), hop)]
    return np.array(frames), np.fft.rfftfreq(win, 1.0 / SR), hop, win


def analyse(path):
    """Looks for what a laser or a beep is: a sinusoid. A frame counts as tonal when most of its
    energy above the sub-bass sits in one narrow peak (purity) and that peak's instantaneous
    frequency (from the phase advance between frames) is steady. A beep is such a tone lasting over
    0.15 s; a sweep is one gliding more than an octave within half a second."""
    x = decode(path)
    if len(x) < 4096:
        x = np.concatenate([x, np.zeros(4096 - len(x))])
    spec, freqs, hop, win = stft(x)
    power = np.abs(spec) ** 2
    dt = hop / SR
    band = np.nonzero((freqs > 150) & (freqs < 12000))[0]
    peak_db = 20 * np.log10(np.max(np.abs(x)) + 1e-12)
    frame_db = 10 * np.log10(power[:, band].sum(axis=1) + 1e-18)
    loud = frame_db > frame_db.max() - 35
    peaks = []
    for i in range(len(power)):
        p = power[i, band]
        j = int(np.argmax(p))
        k = band[j]
        purity = p[max(0, j - 3):j + 4].sum() / (p.sum() + 1e-18)
        peaks.append((k, purity))
    inst = np.zeros(len(power))
    for i in range(1, len(power)):
        k = peaks[i][0]
        expected = 2 * np.pi * k * hop / win
        delta = np.angle(spec[i, k]) - np.angle(spec[i - 1, k]) - expected
        delta = (delta + np.pi) % (2 * np.pi) - np.pi
        inst[i] = (k + delta * win / (2 * np.pi * hop)) * SR / win
    tonal = []
    for i in range(len(power)):
        steady = 1 < i < len(power) - 1 and abs(inst[i] - inst[i - 1]) < 4.0 and abs(inst[i + 1] - inst[i]) < 4.0
        tonal.append((bool(loud[i] and peaks[i][1] > 0.6 and steady), float(inst[i]), float(peaks[i][1])))
    glides = []
    window = int(0.5 / dt)
    for i in range(len(tonal)):
        if not tonal[i][0]:
            continue
        run = [tonal[i][1]]
        for j in range(i + 1, min(len(tonal), i + window)):
            if not tonal[j][0]:
                break
            run.append(tonal[j][1])
            if abs(np.log2(max(run[-1], 1.0) / max(run[0], 1.0))) > 1.0:
                glides.append((i * dt, run[0], run[-1]))
                break
    longest = 0
    current = 0
    for flag, _, _ in tonal:
        current = current + 1 if flag else 0
        longest = max(longest, current)
    purity = [t[2] for t, l in zip(tonal, loud) if l]
    return {
        "seconds": len(x) / SR,
        "peak_db": peak_db,
        "rms_db": 20 * np.log10(np.sqrt(np.mean(x ** 2)) + 1e-12),
        "tonal_frames": sum(1 for t in tonal if t[0]),
        "longest_tone_s": longest * dt,
        "glides": glides,
        "max_purity": max(purity) if purity else 0.0,
    }, np.abs(spec), freqs, dt


def spectrogram_png(mag, freqs, dt, path, title):
    from PIL import Image, ImageDraw
    db = 20 * np.log10(mag.T + 1e-9)
    db = np.clip((db - (db.max() - 80)) / 80, 0, 1)
    keep = freqs <= 10000
    db = db[keep][::-1]
    rows = 360
    fk = freqs[keep][::-1]
    targets = 10000 * (np.linspace(1, 0, rows) ** 2.2) + 20
    idx = np.clip(np.searchsorted(-fk, -targets), 0, len(fk) - 1)
    img = db[idx]
    cols = min(900, max(1, img.shape[1]))
    xi = np.linspace(0, img.shape[1] - 1, cols).astype(int)
    img = img[:, xi]
    rgb = np.stack([img ** 0.8 * 255, img ** 1.6 * 200, img ** 3 * 120], -1).astype(np.uint8)
    pic = Image.fromarray(rgb, "RGB").resize((900, rows))
    canvas = Image.new("RGB", (900, rows + 18), (0, 0, 0))
    canvas.paste(pic, (0, 18))
    ImageDraw.Draw(canvas).text((4, 3), f"{title}  ({mag.shape[0] * dt:.2f} s, 0-10 kHz, log-ish)", fill=(220, 220, 220))
    canvas.save(path)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--analyze", type=pathlib.Path, help="directory for spectrograms and the report")
    args = parser.parse_args()
    if shutil.which("ffmpeg") is None:
        print("ffmpeg is required", file=sys.stderr)
        return 1
    produced = render_all()
    print(f"{len(produced)} files -> {SOUND_DIR.relative_to(ROOT)}, events -> {FRAGMENT.relative_to(ROOT)}")
    if args.analyze:
        args.analyze.mkdir(parents=True, exist_ok=True)
        problems = 0
        lines = []
        sheets = []
        for event, path in produced:
            report, mag, freqs, dt = analyse(path)
            png = args.analyze / f"{path.stem}.png"
            spectrogram_png(mag, freqs, dt, png, f"{event} / {path.name}")
            sheets.append(png)
            bad = report["glides"] or report["longest_tone_s"] > 0.15 or report["peak_db"] > -1.0
            problems += 1 if bad else 0
            lines.append(f"{'FLAG' if bad else 'ok  '} {path.name:24s} {report['seconds']:5.2f}s peak {report['peak_db']:6.2f} dBFS "
                         f"rms {report['rms_db']:6.1f} tonal frames {report['tonal_frames']:3d} longest tone {report['longest_tone_s']:.3f}s "
                         f"glides {len(report['glides'])} max purity {report['max_purity']:.2f}")
        (args.analyze / "report.txt").write_text("\n".join(lines) + "\n")
        print("\n".join(lines))
        print(f"{problems} file(s) flagged")
        from PIL import Image
        images = [Image.open(p) for p in sheets]
        width = max(i.width for i in images)
        for start in range(0, len(images), 6):
            chunk = images[start:start + 6]
            sheet = Image.new("RGB", (width, sum(i.height for i in chunk)), (0, 0, 0))
            y = 0
            for image in chunk:
                sheet.paste(image, (0, y))
                y += image.height
            sheet.save(args.analyze / f"sheet_{start // 6 + 1}.png")
    return 0


if __name__ == "__main__":
    sys.exit(main())
