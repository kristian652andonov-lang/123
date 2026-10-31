#!/usr/bin/env python3
"""Synthesises IT's sounds (numpy only, no samples) and describes them for sounds.json.

Writes src/main/resources/assets/null/sounds/it/<name>[_<n>].ogg (mono, 44.1 kHz, Ogg Vorbis via
ffmpeg/libvorbis in bit-exact mode, so a re-run produces identical files) and the event fragment
assets-src/sounds/it.json; then run tools/merge_sounds.py. Every variant has its own fixed seed.

  it.step     an enormous footfall, foot or hand: a ground-shaking thud (body under 120 Hz), a wet heavy
              impact, a slight flesh slap, earth and wheat crushed, a short dark tail. Short on purpose:
              at a gallop they come eight to the second, so they must not smear into each other
  it.howl     long and far away as it arrives: a rough, inhuman voice through a huge empty space
  it.breath   wet breathing close by: a gurgling inhale, a rattling exhale
  it.click    a bone clicking or a joint cracking (rare and faint now; movement is all footfalls)
  it.scream   at the window: a harsh, broken, layered scream
  it.scrape   bone blades dragged over wood and glass
  it.grab     the seize: a rush of air, a wet slap, a squeeze
  it.pierce   the hand through the chest: bones crunching, a squelch
  it.tear     wet tearing, sinews snapping
  it.swallow  a heavy gulp sliding down
  it.retreat  a low moan going away, the last heavy steps

Horror only: voices are rough and noisy with jittering pitch (never a smooth glide), resonances
are short and inharmonic (knocks, cracks, never a beep). --analyze DIR writes a spectrogram of
every file and checks all of them for strong tonal components that glide more than an octave in
half a second, for pure-tone beeps and for clipping, after decoding the encoded files.

usage: python3 tools/gen_sounds_it.py [--analyze DIR]
"""
import argparse
import json
import pathlib
import shutil
import subprocess
import sys

import numpy as np

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOUND_DIR = ROOT / "src" / "main" / "resources" / "assets" / "null" / "sounds" / "it"
FRAGMENT = ROOT / "assets-src" / "sounds" / "it.json"
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


def formants(x, table):
    """Vowel-like resonances: table(t) -> list of (freq, bandwidth, gain)."""
    def gain(t, f):
        g = np.full_like(f, 0.02)
        for fc, bw, amp in table(t):
            g = g + amp / (1.0 + ((f - fc) / (bw * 0.5)) ** 2)
        return g
    return stft_shape(x, gain)


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


def voice(rng, n, f0, jitter=0.02, shimmer=0.15, sub=0.35, harmonics=60, breath=0.25, wander=6.0):
    """A rough, buzzing voice source: a band-limited pulse with jittering pitch (random, never a
    sweep), shimmering loudness, a subharmonic growl and breath noise. f0 may be an array."""
    f0 = np.broadcast_to(np.asarray(f0, dtype=float), (n,)).copy()
    f0 *= 1.0 + jitter * smooth_noise(rng, n, wander) + 0.3 * jitter * smooth_noise(rng, n, 40.0)
    phase = 2.0 * np.pi * np.cumsum(f0) / SR
    out = np.zeros(n)
    nyquist = SR / 2.0
    for k in range(1, harmonics + 1):
        alive = (k * f0) < nyquist * 0.9
        if not alive.any():
            break
        out += np.where(alive, np.sin(k * phase + rng.uniform(0, 2 * np.pi)) / (k ** 0.9), 0.0)
        if sub > 0.0:
            # The subharmonic: every other cycle louder, a growl an octave below.
            out += sub * np.where((k - 0.5) * f0 < nyquist * 0.9, np.sin((k - 0.5) * phase) / (k ** 1.1), 0.0)
    out *= 1.0 + shimmer * smooth_noise(rng, n, 18.0)
    noise = highpass(coloured(rng, n, -0.5), 400.0, 1)
    out = out / (np.std(out) + 1e-12) + breath * noise
    return out


def clicks(rng, n, times, freq_range=(700, 3200), modes=4, tau_range=(0.004, 0.02), amps=None):
    """Bone clicks: a few inharmonic, quickly dying resonances per click (knocks, never beeps)."""
    out = np.zeros(n)
    for i, at in enumerate(times):
        start = samples(at)
        if start >= n:
            continue
        length = min(n - start, samples(0.12))
        t = secs(length)
        click = np.zeros(length)
        for _ in range(modes):
            f = rng.uniform(*freq_range)
            tau = rng.uniform(*tau_range)
            click += rng.uniform(0.4, 1.0) * np.sin(2 * np.pi * f * t + rng.uniform(0, 6.28)) * np.exp(-t / tau)
        burst = rng.standard_normal(length) * np.exp(-t / 0.0015)
        click += 0.8 * highpass(burst, 1500.0, 1)
        amp = 1.0 if amps is None else amps[i]
        out[start:start + length] += amp * click
    return out


def crackle(rng, n, density, env, low=1200.0, high=7000.0):
    """Dense, irregular crackling (crushed stalks, breaking bone): sparse impulses, filtered."""
    impulses = (rng.random(n) < density / SR) * rng.standard_normal(n) * env
    x = spectrum_filter(impulses, lambda f: (f / low) ** 2 / (1 + (f / low) ** 2) / np.sqrt(1 + (f / high) ** 4))
    return x / (np.max(np.abs(x)) + 1e-12)


def gurgle(rng, n, rate, low=120.0, high=600.0):
    """Liquid moving in a throat: random short resonant blips at low pitch, irregularly spaced."""
    out = np.zeros(n)
    at = 0
    while True:
        at += int(SR / rate * rng.uniform(0.3, 1.8))
        if at >= n:
            break
        length = min(n - at, samples(rng.uniform(0.02, 0.07)))
        t = secs(length)
        f = rng.uniform(low, high)
        blip = np.sin(2 * np.pi * f * t * (1.0 + 0.25 * t / max(t[-1], 1e-3))) * np.sin(np.pi * t / max(t[-1], 1e-3)) ** 2
        out[at:at + length] += rng.uniform(0.3, 1.0) * blip
    return lowpass(out, 900.0, 2)


def reverb(rng, x, rt60, mix, size=1.0, damping=3000.0, predelay=0.0):
    """Synthetic convolution reverb: decaying noise, darker as it decays."""
    n_ir = samples(rt60 * 1.2)
    t = secs(n_ir)
    ir = rng.standard_normal(n_ir) * np.exp(-6.9 * t / rt60)
    # Highs die faster than lows.
    bright = highpass(ir, damping, 1) * np.exp(-6.9 * t / (rt60 * 0.35))
    dark = lowpass(ir, damping, 1)
    ir = dark + bright
    # Early reflections: a few scattered copies of the tail.
    spread = np.zeros(n_ir)
    for _ in range(max(1, int(12 * size))):
        delay = int(rng.integers(0, max(1, samples(0.08 * size))))
        spread[delay:] += rng.uniform(-1, 1) * ir[:n_ir - delay]
    ir = spread
    pre = samples(predelay)
    ir = np.concatenate([np.zeros(pre), ir])
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

def _unit(x):
    return x / (np.max(np.abs(x)) + 1e-12)


def _rms(x):
    return np.sqrt(np.mean(x ** 2)) + 1e-12


def _level(x):
    """x scaled to unit rms."""
    return x / _rms(x)


def step(rng):
    """One footfall of something enormous, a foot or a hand. Noise only, so nothing in it is a
    tone and nothing sweeps: sub-bass shake and thud (everything below ~120 Hz carries the weight),
    a knock above them for small speakers, a wet squelch with bubbles popping under it, a slight
    flesh slap, then earth and wheat crunching under the weight. Ends in a short dark tail."""
    n = samples(0.8)
    w = rng.uniform(0.85, 1.2)  # how much weight this one carries
    # The ground shake and the body of the blow, both under 120 Hz.
    sub = lowpass(highpass(coloured(rng, n, -1.5), 28.0, 2), 68.0, 3) * decay(n, 0.12 * w, 0.006)
    thud = lowpass(highpass(coloured(rng, n, -1.0), 44.0, 2), 112.0 + 14.0 * (w - 1.0), 3) * decay(n, 0.07 * w, 0.0025)
    # The chest of the blow, a little higher: a knock, never a pitch.
    knock = bandpass(coloured(rng, n, -1.0), 170.0, 0.9) * decay(n, 0.03, 0.002)
    # Wet: a squelch whose cutoff falls as it settles, and bubbles popping under it.
    squelch = coloured(rng, n, -1.0) * envelope(n, [(0, 0), (0.003, 1.0), (0.035, 0.7), (0.11, 0.0)])
    squelch = stft_shape(squelch, lambda tt, f: 1.0 / np.sqrt(1.0 + (f / (250.0 + 1700.0 * np.exp(-tt / 0.03))) ** 4))
    bubbles = np.zeros(n)
    for _ in range(int(rng.integers(2, 5))):
        at = samples(rng.uniform(0.012, 0.13))
        length = samples(0.05)
        pop = bandpass(rng.standard_normal(length), rng.uniform(180.0, 520.0), 1.6) * decay(length, rng.uniform(0.008, 0.02), 0.001)
        bubbles[at:at + length] += rng.uniform(0.4, 1.0) * _unit(pop)
    # A slight slap of flesh on the ground.
    slap = bandpass(coloured(rng, n, 0.0), 2300.0, 0.8) * decay(n, 0.006, 0.0005)
    # Earth and dry wheat crushed: dense crackle, and single crunches thinning out.
    earth = crackle(rng, n, 2600.0, envelope(n, [(0, 0), (0.006, 1), (0.07, 0.65), (0.22, 0.2), (0.4, 0)]), 380.0, 4200.0)
    stalks = crackle(rng, n, 1100.0, envelope(n, [(0.0, 0), (0.025, 1), (0.16, 0.55), (0.34, 0)]), 1600.0, 7500.0)
    crunch = np.zeros(n)
    for at in np.sort(rng.exponential(0.07, int(rng.integers(7, 12)))):
        if at > 0.32:
            continue
        start = samples(0.012 + at)
        length = min(n - start, samples(0.012))
        burst = highpass(rng.standard_normal(length), rng.uniform(700.0, 1800.0), 1) * np.exp(-secs(length) / rng.uniform(0.002, 0.005))
        crunch[start:start + length] += rng.uniform(0.3, 1.0) * np.exp(-at / 0.18) * burst
    # Levels by loudness (rms), so every variant has the same balance: the body under 120 Hz carries
    # about three quarters of the energy, the rest is the wet, the slap and the crushing on top.
    body = 0.8 * _level(thud) + 0.6 * _level(sub)
    x = body / _rms(body)
    x += 0.38 * _level(knock) + 0.3 * _level(squelch) + 0.16 * _level(bubbles) + 0.13 * _level(slap)
    x += 0.27 * _level(earth) + 0.16 * _level(stalks) + 0.22 * _level(crunch)
    x = np.concatenate([np.zeros(samples(0.006)), x])[:n]
    # Saturate by loudness, not by the highest spike, so the crunches cannot take the weight out of the thud.
    x = saturate(x / (4.5 * _rms(x)), 1.8)
    x = reverb(rng, x, 0.42, 0.2, size=1.0, damping=800.0)
    x = lowpass(x, 6500.0, 1)
    return finish(x, tail=0.1)


def howl(rng):
    n = samples(7.4)
    t = secs(n)
    # Mostly steady and rough; it rises a few semitones into the cry and sinks again, slowly.
    contour = envelope(n, [(0, -2.5), (1.6, 1.0), (4.6, 0.6), (7.4, -3.5)])
    f0 = 112.0 * 2 ** (contour / 12.0)
    low = voice(rng, n, f0, jitter=0.04, shimmer=0.3, sub=0.6, breath=0.65)
    high = voice(rng, n, f0 * 2 ** (1.0 / 12.0) * 1.5, jitter=0.045, shimmer=0.35, sub=0.2, breath=0.75)

    def vowel(tt):
        # "oo" opening to "aa" and closing again: the howl is in the throat's shape.
        open_ = np.clip((tt - 0.6) / 1.8, 0, 1) * np.clip((6.4 - tt) / 2.2, 0, 1)
        return [(300 + 450 * open_, 260, 1.0), (750 + 500 * open_, 300, 0.7), (2300, 500, 0.25 * (0.3 + open_))]
    # A breathy roar through the same throat: never a clean hoot.
    roar = formants(coloured(rng, n, -1.0), vowel)
    x = formants(low, vowel) + 0.45 * formants(high, vowel)
    x = x / np.std(x) + 0.55 * roar / np.std(roar)
    swell = envelope(n, [(0, 0), (0.9, 0.7), (2.0, 1.0), (4.8, 0.85), (6.5, 0.4), (7.4, 0.0)])
    swell *= 1.0 + 0.18 * smooth_noise(rng, n, 3.0)
    x *= np.clip(swell, 0, None)
    x = saturate(x / np.max(np.abs(x)), 1.4)
    # Far away: the highs are gone, the field swallows it.
    x = lowpass(x, 1800.0, 2)
    x = reverb(rng, x, 3.6, 0.55, size=3.0, damping=900.0, predelay=0.06)
    return finish(x, tail=1.2)


def breath(rng):
    n_in = samples(rng.uniform(0.85, 1.05))
    gap = samples(rng.uniform(0.12, 0.25))
    n_out = samples(rng.uniform(1.1, 1.4))
    n = n_in + gap + n_out
    x = np.zeros(n)
    inhale = formants(coloured(rng, n_in, -0.5), lambda tt: [(900, 500, 1.0), (1700, 600, 0.5), (3000, 900, 0.15)])
    inhale *= envelope(n_in, [(0, 0), (n_in / SR * 0.7, 1.0), (n_in / SR, 0.1)])
    exhale = formants(coloured(rng, n_out, -1.0), lambda tt: [(500, 300, 1.0), (1100, 500, 0.5), (2500, 700, 0.12)])
    rattle = 1.0 + 0.7 * np.sin(2 * np.pi * rng.uniform(26, 38) * secs(n_out) + 2.0 * smooth_noise(rng, n_out, 8.0))
    exhale *= rattle * envelope(n_out, [(0, 0), (0.08, 1.0), (n_out / SR * 0.6, 0.7), (n_out / SR, 0.0)])
    x[:n_in] += 0.8 * inhale / np.max(np.abs(inhale))
    x[n_in + gap:] += exhale / np.max(np.abs(exhale))
    wet = gurgle(rng, n, rng.uniform(14, 24))
    x += 0.45 * wet / (np.max(np.abs(wet)) + 1e-12) * envelope(n, [(0, 0.3), (n_in / SR, 1.0), (n / SR, 0.6)])
    x = lowpass(x, 5000.0, 1)
    x = reverb(rng, x, 0.5, 0.12, size=0.6, damping=2500.0)
    return finish(x, peak_db=-3.0)


def click(rng):
    count = int(rng.integers(2, 7))
    n = samples(0.9)
    times = np.cumsum(rng.uniform(0.02, 0.13, count))
    amps = rng.uniform(0.4, 1.0, count)
    x = clicks(rng, n, times, (650, 3400), 4, (0.003, 0.016), amps)
    pop_at = samples(times[0] + rng.uniform(0.0, 0.03))
    pop = lowpass(coloured(rng, n, -1.0), 300.0, 2) * np.concatenate([np.zeros(pop_at), decay(n - pop_at, 0.03)])
    x = x / np.max(np.abs(x)) + 0.4 * pop / (np.max(np.abs(pop)) + 1e-12)
    x = reverb(rng, x, 0.35, 0.15, size=0.5)
    return finish(x, peak_db=-4.0)


def scream(rng):
    n = samples(rng.uniform(2.5, 2.9))
    contour = envelope(n, [(0, -1.0), (0.25, 1.0), (1.6, 0.6), (n / SR, -2.5)])
    f0 = rng.uniform(300, 360) * 2 ** (contour / 12.0)
    shriek = voice(rng, n, f0, jitter=0.045, shimmer=0.35, sub=0.8, breath=0.9, wander=9.0)
    roar = voice(rng, n, f0 / rng.uniform(2.7, 3.1), jitter=0.04, shimmer=0.3, sub=0.7, breath=0.6)

    def aah(tt):
        return [(850, 160, 1.0), (1500, 220, 0.8), (2900, 350, 0.45), (3800, 500, 0.25)]

    x = formants(shriek, aah) + 0.7 * formants(roar, lambda tt: [(500, 150, 1.0), (1100, 250, 0.6), (2500, 400, 0.2)])
    rasp = bandpass(coloured(rng, n, -0.3), 2500.0, 0.8)
    x = x / np.max(np.abs(x)) + 0.35 * rasp / np.max(np.abs(rasp))
    x *= envelope(n, [(0, 0), (0.06, 1.0), (0.4, 0.85), (n / SR - 0.6, 0.75), (n / SR, 0.0)])
    x *= 1.0 + 0.3 * smooth_noise(rng, n, 11.0)
    x = saturate(x / np.max(np.abs(x)), 3.0)
    x = lowpass(x, 7000.0, 2)
    x = reverb(rng, x, 1.4, 0.3, size=1.2, damping=2500.0, predelay=0.02)
    return finish(x, tail=0.4)


def scrape(rng):
    n = samples(rng.uniform(1.4, 1.8))
    t = secs(n)
    drag = envelope(n, [(0, 0), (0.08, 1.0), (n / SR * 0.7, 0.8), (n / SR, 0.0)])
    # Stick and slip: the blade catches and lets go, irregularly.
    rate = 40.0 + 25.0 * smooth_noise(rng, n, 2.0)
    phase = np.cumsum(np.maximum(rate, 8.0)) / SR
    catches = np.abs(np.diff(np.floor(phase), prepend=0.0)) > 0
    slips = catches * rng.uniform(0.3, 1.0, n)
    wood = spectrum_filter(slips + 0.08 * rng.standard_normal(n), lambda f: 1.0 / np.sqrt(1 + 6.0 * (f / 650 - 650 / f) ** 2))
    wood += 0.6 * clicks(rng, n, np.sort(rng.uniform(0, n / SR, 18)), (250, 1100), 3, (0.01, 0.04), rng.uniform(0.2, 0.8, 18))
    grit = bandpass(coloured(rng, n, 0.0), 3200.0, 1.2) * (0.5 + 0.5 * np.abs(smooth_noise(rng, n, 30.0)))
    x = wood / np.max(np.abs(wood)) + 0.55 * grit / np.max(np.abs(grit))
    x *= drag
    x = reverb(rng, x, 0.6, 0.18, size=0.7, damping=3000.0)
    return finish(x, peak_db=-2.0)


def grab(rng):
    n = samples(0.95)
    rush = bandpass(coloured(rng, n, -0.6), 900.0, 0.7) * envelope(n, [(0, 0), (0.12, 1.0), (0.17, 0.2), (0.3, 0.0)])
    hit = samples(0.17)
    slap = np.concatenate([np.zeros(hit), lowpass(coloured(rng, n - hit, -0.5), 1600.0, 2) * decay(n - hit, 0.035, 0.001)])
    squeeze = np.concatenate([np.zeros(hit + samples(0.03)),
                              crackle(rng, n - hit - samples(0.03), 900.0, decay(n - hit - samples(0.03), 0.22), 600, 4500)])
    wet = np.concatenate([np.zeros(hit), gurgle(rng, n - hit, 30.0, 150, 500) * decay(n - hit, 0.3)])
    thump = np.concatenate([np.zeros(hit), lowpass(coloured(rng, n - hit, -2.0), 120.0, 3) * decay(n - hit, 0.09)])
    x = (0.5 * rush / np.max(np.abs(rush)) + slap / np.max(np.abs(slap)) + 0.5 * squeeze
         + 0.35 * wet / (np.max(np.abs(wet)) + 1e-12) + 0.8 * thump / np.max(np.abs(thump)))
    x = saturate(x / np.max(np.abs(x)), 1.5)
    x = reverb(rng, x, 0.5, 0.15, size=0.6)
    return finish(x)


def pierce(rng):
    n = samples(1.3)
    hit = samples(0.01)
    crunch = crackle(rng, n, 5000.0, envelope(n, [(0, 0), (0.005, 1.0), (0.06, 0.7), (0.25, 0.25), (0.5, 0.0)]), 400, 6000)
    cracks = clicks(rng, n, np.sort(rng.uniform(0.0, 0.18, 7)), (300, 1800), 4, (0.008, 0.03), rng.uniform(0.5, 1.0, 7))
    thud = lowpass(coloured(rng, n, -2.0), 140.0, 3) * decay(n, 0.12, 0.003)
    squelch = gurgle(rng, n, 45.0, 120, 450) * envelope(n, [(0, 0), (0.05, 1.0), (0.5, 0.5), (1.1, 0.0)])
    sludge = lowpass(coloured(rng, n, -1.0), 700.0, 2) * envelope(n, [(0, 0), (0.04, 0.8), (0.4, 0.3), (1.0, 0.0)])
    x = (crunch + 0.8 * cracks / np.max(np.abs(cracks)) + 1.0 * thud / np.max(np.abs(thud))
         + 0.55 * squelch / (np.max(np.abs(squelch)) + 1e-12) + 0.4 * sludge / np.max(np.abs(sludge)))
    x = np.concatenate([np.zeros(hit), x])[:n]
    x = saturate(x / np.max(np.abs(x)), 2.0)
    x = reverb(rng, x, 0.45, 0.12, size=0.5)
    return finish(x)


def tear(rng):
    n = samples(rng.uniform(1.2, 1.5))
    density = envelope(n, [(0, 0), (0.1, 1.0), (0.5, 0.8), (n / SR - 0.2, 0.3), (n / SR, 0.0)])
    rips = crackle(rng, n, 2600.0, density, 900, 7000)
    strands = np.sort(rng.uniform(0.1, n / SR - 0.15, 6))
    snaps = clicks(rng, n, strands, (220, 700), 3, (0.01, 0.035), rng.uniform(0.5, 1.0, 6))
    wet = gurgle(rng, n, 38.0, 100, 420) * density
    smear = bandpass(coloured(rng, n, -0.8), 1300.0, 0.9) * density * (0.6 + 0.4 * np.abs(smooth_noise(rng, n, 16.0)))
    x = (rips + 0.6 * snaps / np.max(np.abs(snaps)) + 0.6 * wet / (np.max(np.abs(wet)) + 1e-12)
         + 0.5 * smear / np.max(np.abs(smear)))
    x = saturate(x / np.max(np.abs(x)), 1.4)
    x = reverb(rng, x, 0.5, 0.12, size=0.5)
    return finish(x)


def swallow(rng):
    n = samples(1.4)
    gulp_at = samples(0.12)
    gulp = np.zeros(n)
    length = samples(0.16)
    t = secs(length)
    body = lowpass(coloured(rng, length, -1.0), 260.0, 3) * np.sin(np.pi * t / t[-1]) ** 1.5
    gulp[gulp_at:gulp_at + length] = body / np.max(np.abs(body))
    knock = clicks(rng, n, [0.1, 0.27], (180, 520), 3, (0.012, 0.04), [0.6, 0.4])
    slide = gurgle(rng, n, 26.0, 90, 300) * envelope(n, [(0, 0), (0.25, 1.0), (0.9, 0.5), (1.35, 0.0)])
    throat = lowpass(coloured(rng, n, -1.5), 400.0, 2) * envelope(n, [(0, 0), (0.15, 0.7), (0.35, 0.2), (1.2, 0.0)])
    x = 1.0 * gulp + 0.4 * knock / np.max(np.abs(knock)) + 0.6 * slide / (np.max(np.abs(slide)) + 1e-12)
    x += 0.4 * throat / np.max(np.abs(throat))
    x = reverb(rng, x, 0.4, 0.1, size=0.4)
    return finish(x)


def retreat(rng):
    n = samples(6.5)
    contour = envelope(n, [(0, 0.0), (2.5, -1.0), (6.5, -2.5)])
    moan = voice(rng, n, 72.0 * 2 ** (contour / 12.0), jitter=0.04, shimmer=0.35, sub=0.7, breath=0.7)
    moan = formants(moan, lambda tt: [(320, 240, 1.0), (800, 300, 0.6), (2200, 500, 0.12)])
    moan *= envelope(n, [(0, 0), (0.6, 1.0), (3.0, 0.6), (6.5, 0.0)])
    steps = np.zeros(n)
    for i, at in enumerate((0.9, 2.1, 3.2, 4.2, 5.1)):
        start = samples(at)
        length = n - start
        thud = lowpass(coloured(rng, length, -2.0), 90.0, 3) * decay(length, 0.25, 0.005)
        steps[start:] += (0.9 - 0.15 * i) * thud / np.max(np.abs(thud))
    x = moan / np.max(np.abs(moan)) + 0.8 * steps
    # Going away: quieter and darker as it goes.
    x = stft_shape(x, lambda tt, f: 1.0 / np.sqrt(1.0 + (f / (1400.0 * 0.5 ** (tt / 2.5))) ** 4))
    x = reverb(rng, x, 3.0, 0.5, size=2.5, damping=800.0, predelay=0.04)
    return finish(x, tail=1.5)


SOUNDS = [
    # event, generator, variants, seed base
    ("it.step", step, 6, 0x5354),
    ("it.howl", howl, 2, 0x484F),
    ("it.breath", breath, 3, 0x4252),
    ("it.click", click, 4, 0x434C),
    ("it.scream", scream, 2, 0x5343),
    ("it.scrape", scrape, 3, 0x5341),
    ("it.grab", grab, 2, 0x4752),
    ("it.pierce", pierce, 2, 0x5049),
    ("it.tear", tear, 2, 0x5445),
    ("it.swallow", swallow, 2, 0x5357),
    ("it.retreat", retreat, 1, 0x5245),
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
    for event, generator, variants, seed in SOUNDS:
        stem = event.split(".", 1)[1]
        names = []
        for i in range(variants):
            rng = np.random.default_rng(seed * 1000 + i)
            signal = generator(rng)
            name = stem if variants == 1 else f"{stem}_{i + 1}"
            path = SOUND_DIR / f"{name}.ogg"
            encode(signal, path)
            # Vorbis can overshoot a little: make sure the decoded peak stays under -1 dBFS.
            for _ in range(4):
                peak = np.max(np.abs(decode(path)))
                if 20 * np.log10(peak + 1e-12) <= -1.0:
                    break
                signal = signal * (10 ** (-1.2 / 20.0) / peak)
                encode(signal, path)
            names.append({"name": f"null:it/{name}"})
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


def stft_mag(x, win=2048, hop=256):
    spec, freqs, hop, win = stft(x, win, hop)
    return np.abs(spec), freqs, hop / SR


def analyse(path):
    """Looks for what a laser or a beep is: a sinusoid. A frame counts as tonal when most of its
    energy above the sub-bass sits in one narrow peak (purity) AND that peak behaves like a
    sinusoid rather than narrowband noise: its instantaneous frequency (from the phase advance
    between frames) is steady from frame to frame. A beep is such a tone lasting over 0.15 s; a
    sweep is one gliding steadily more than an octave within half a second."""
    x = decode(path)
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
    # Log-ish frequency axis: more room for the lows where these sounds live.
    rows = 360
    fk = freqs[keep][::-1]
    targets = 10000 * (np.linspace(1, 0, rows) ** 2.2) + 20
    idx = np.clip(np.searchsorted(-fk, -targets), 0, len(fk) - 1)
    img = db[idx]
    cols = min(900, img.shape[1])
    xi = np.linspace(0, img.shape[1] - 1, cols).astype(int)
    img = img[:, xi]
    rgb = np.stack([img ** 0.8 * 255, img ** 1.6 * 200, img ** 3 * 120], -1).astype(np.uint8)
    pic = Image.fromarray(rgb, "RGB").resize((cols, rows))
    canvas = Image.new("RGB", (cols, rows + 18), (0, 0, 0))
    canvas.paste(pic, (0, 18))
    ImageDraw.Draw(canvas).text((4, 3), f"{title}  ({mag.shape[0] * dt:.1f} s, 0-10 kHz, log-ish)", fill=(220, 220, 220))
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
            lines.append(f"{'FLAG' if bad else 'ok  '} {path.name:16s} {report['seconds']:5.2f}s peak {report['peak_db']:6.2f} dBFS "
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
