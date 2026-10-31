#!/usr/bin/env python3
"""Synthesises the sounds of the field (Stage 6, HARVEST) into
src/main/resources/assets/null/sounds/field/ and describes them in assets-src/sounds/field.json
(sounds.json entry format; run tools/merge_sounds.py afterwards, this script does it at the end).

Every sound is made here from noise, resonators and envelopes with numpy (no samples):

  field.wind         loop: wind hissing through endless wheat, gusting, the stalks rustling
  field.drone        loop: a low unease under the day (two close low tones beating, a breathing band)
  field.night_air    loop: the night, almost nothing: a faint pressure far down in the ears
  field.crow         distant crows
  field.creak        old wood creaking under strain
  field.barn_door    a barn door banging somewhere far off, rattling its latch
  field.chimes       faint, out-of-tune wind chimes
  field.sun_crack    the sun cracking, far above: a groaning strain and a deep fracture
  field.sun_shatter  the sun breaking apart: a sucking swell, a layered rupture, the light dying
  field.dawn         the light coming back: a slow, uneasy swell
  field.toll         one stroke of a distant bell, when the countdown begins
  field.footsteps    enormous footsteps far out in the dark
  field.drag         something heavy dragged through the wheat
  field.breath       slow, heavy breathing out in the field
  field.heartbeat    one heartbeat (played faster and louder as the timer runs out)

Horror only: no chirps, no beeps, no gliding tones. Anything tonal (bell, chimes, drone) stays at a
fixed pitch. Loops are built from exactly periodic material (circular filtering), so the end flows
into the start. Mono for sounds placed in the world, stereo for the loops and the non-positional
moments. Peaks at most -1 dBFS after encoding (checked by decoding again).

Deterministic: every variant has its own fixed seed, ffmpeg runs bit-exact; running it again gives
identical files. `--analyse DIR` writes spectrograms and a report (tonal glides, peaks, loudness).

Usage: python3 tools/gen_sounds_field.py [--analyse DIR] [--no-merge]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import subprocess
import sys

import numpy as np

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOUND_DIR = ROOT / "src" / "main" / "resources" / "assets" / "null" / "sounds" / "field"
FRAGMENT = ROOT / "assets-src" / "sounds" / "field.json"
SR = 44100
TAU = 2.0 * np.pi


# ============================================================================== DSP

def rng_for(key: str) -> np.random.Generator:
    seed = int.from_bytes(hashlib.sha256(("null-field:" + key).encode()).digest()[:8], "little")
    return np.random.default_rng(seed)


def ns(seconds: float) -> int:
    return max(1, int(round(seconds * SR)))


def tvec(n: int) -> np.ndarray:
    return np.arange(n) / SR


def next_pow2(n: int) -> int:
    return 1 << int(max(1, n - 1)).bit_length()


def spectral(x: np.ndarray, response, circular: bool = False, pad: float = 0.3) -> np.ndarray:
    """Zero-phase filtering in the frequency domain (circular for loop material)."""
    if circular:
        f = np.fft.rfftfreq(len(x), 1.0 / SR)
        return np.fft.irfft(np.fft.rfft(x) * response(f), len(x))
    p = min(ns(pad), len(x))
    size = next_pow2(len(x) + 2 * p)
    buf = np.zeros(size)
    buf[p:p + len(x)] = x
    f = np.fft.rfftfreq(size, 1.0 / SR)
    return np.fft.irfft(np.fft.rfft(buf) * response(f), size)[p:p + len(x)]


def r_lp(fc, order=2):
    return lambda f: 1.0 / np.sqrt(1.0 + (f / fc) ** (2 * order))


def r_hp(fc, order=2):
    def resp(f):
        with np.errstate(divide="ignore"):
            return 1.0 / np.sqrt(1.0 + (fc / np.maximum(f, 1e-6)) ** (2 * order))
    return resp


def r_bp(fc, q):
    def resp(f):
        with np.errstate(divide="ignore", invalid="ignore"):
            x = (np.maximum(f, 1e-6) / fc - fc / np.maximum(f, 1e-6)) * q
        return 1.0 / np.sqrt(1.0 + x * x)
    return resp


def r_mul(*responses):
    def resp(f):
        out = np.ones_like(f)
        for r in responses:
            out = out * r(f)
        return out
    return resp


def lowpass(x, fc, order=2, circular=False):
    return spectral(x, r_lp(fc, order), circular)


def highpass(x, fc, order=2, circular=False):
    return spectral(x, r_hp(fc, order), circular)


def bandpass(x, fc, q, circular=False):
    return spectral(x, r_bp(fc, q), circular)


def smooth_random(n, rng, rate_hz, periodic=False):
    """A random curve in [0, 1] with features about `rate_hz` per second (periodic for loops)."""
    points = max(4, int(n / SR * rate_hz) + 2)
    values = rng.random(points)
    if periodic:
        values[-1] = values[0]
        pos = np.linspace(0, points - 1, n, endpoint=False)
    else:
        pos = np.linspace(0, points - 1, n)
    i = np.floor(pos).astype(int)
    i = np.minimum(i, points - 2)
    f = pos - i
    f = f * f * (3.0 - 2.0 * f)
    return values[i] * (1 - f) + values[i + 1] * f


def env_points(n, points):
    """Linear envelope through (seconds, value) points."""
    t = tvec(n)
    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    return np.interp(t, xs, ys)


def decay(n, tau):
    return np.exp(-tvec(n) / tau)


def attack_decay(n, attack, tau):
    t = tvec(n)
    return np.minimum(1.0, t / max(attack, 1e-4)) * np.exp(-np.maximum(t - attack, 0.0) / tau)


def place(out, sig, start, gain=1.0, wrap=False):
    if wrap:
        idx = (np.arange(len(sig)) + start) % len(out)
        np.add.at(out, idx, sig * gain)
        return
    if start >= len(out):
        return
    if start < 0:
        sig = sig[-start:]
        start = 0
    end = min(len(out), start + len(sig))
    out[start:end] += sig[:end - start] * gain


def fftconv(x, h):
    n = len(x) + len(h) - 1
    size = next_pow2(n)
    return np.fft.irfft(np.fft.rfft(x, size) * np.fft.rfft(h, size), size)[:n]


def reverb_ir(rng, rt60, bright=0.5, predelay=0.02, early=8, size=1.0):
    n = ns(rt60 * 1.2 + predelay)
    t = tvec(n)
    noise = rng.standard_normal(n)
    low = lowpass(noise, 500)
    high = highpass(noise, 3500)
    mid = noise - low - high
    ir = (low * np.exp(-6.91 * t / (rt60 * 1.15)) + mid * np.exp(-6.91 * t / rt60)
          + high * np.exp(-6.91 * t / (rt60 * (0.2 + 0.4 * bright))) * (0.3 + 0.6 * bright))
    pd = ns(predelay)
    ir[:pd] = 0.0
    onset = min(n - pd, ns(0.03 * size))
    if onset > 0:
        ir[pd:pd + onset] *= np.linspace(0, 1, onset) ** 2
    for _ in range(early):
        k = pd + ns(rng.uniform(0.004, 0.08) * size)
        if k < n:
            ir[k] += rng.choice([-1, 1]) * rng.uniform(3, 8)
    return ir / np.sqrt(np.sum(ir * ir))


def reverb(x, rng, rt60, wet=0.3, dry=1.0, bright=0.5, predelay=0.02, size=1.0):
    w = fftconv(x, reverb_ir(rng, rt60, bright, predelay, size=size))
    out = np.zeros(len(w))
    out[:len(x)] += dry * x
    return out + wet * w


def circ_reverb(x, rng, rt60, wet=0.3, bright=0.5):
    """Reverb for loop material: the tail wraps around to the start."""
    ir = reverb_ir(rng, rt60, bright)[:len(x)]
    w = np.fft.irfft(np.fft.rfft(x) * np.fft.rfft(ir, len(x)), len(x))
    return x + wet * w


def resonator_bank(excitation, modes):
    """Sum of decaying sinusoidal modes (freq, decay seconds, gain) driven by an excitation."""
    n = len(excitation)
    out = np.zeros(n)
    t = tvec(n)
    for freq, tau, gain in modes:
        ir_len = min(n, ns(tau * 6.0))
        ir = np.sin(TAU * freq * t[:ir_len]) * np.exp(-t[:ir_len] / tau) * gain
        out += fftconv(excitation, ir)[:n]
    return out


def thump(n, f_body, tau, rng, sub=0.6):
    """A heavy, dull impact: low body resonance, a sub-bass push and a burst of earthy noise."""
    t = tvec(n)
    body = np.sin(TAU * f_body * t + 0.3) * np.exp(-t / tau)
    subb = np.sin(TAU * f_body * 0.5 * t) * np.exp(-t / (tau * 1.4)) * sub
    noise = lowpass(rng.standard_normal(n), 400.0) * attack_decay(n, 0.002, 0.035) * 0.8
    return body + subb + noise


def bass_exciter(x, amount=0.5, cutoff=140.0):
    """Adds harmonics of the sub-bass so small speakers still convey a rumble."""
    low = lowpass(x, cutoff)
    peak = float(np.max(np.abs(low))) + 1e-12
    harm = np.tanh(4.0 * low / peak) * peak
    harm = spectral(harm, r_mul(r_hp(cutoff * 1.1), r_lp(900.0)))
    return x + amount * harm


def crackles(n, rng, rate, center=2500.0, q=1.5, envelope=None):
    """Granular dry crackling (stalks, debris): sparse tiny impulses through a resonance."""
    density = rate / SR
    if envelope is not None:
        density = density * envelope
    pulses = (rng.random(n) < density) * rng.uniform(0.2, 1.0, n) * rng.choice([-1, 1], n)
    return bandpass(pulses, center, q)


def db(v):
    return 20.0 * np.log10(max(v, 1e-12))


def rms(x):
    return float(np.sqrt(np.mean(np.square(x))))


def normalise(x, peak_db=-1.5, rms_db=None):
    peak = float(np.max(np.abs(x))) + 1e-12
    gain = 10 ** (peak_db / 20.0) / peak
    if rms_db is not None:
        g2 = 10 ** (rms_db / 20.0) / (rms(x) + 1e-12)
        gain = min(gain, g2)
    return x * gain


def fade(x, fin=0.004, fout=0.02):
    a = min(len(x), ns(fin))
    b = min(len(x), ns(fout))
    y = np.array(x, copy=True)
    if a > 0:
        y[:a] *= np.linspace(0, 1, a)
    if b > 0:
        y[-b:] *= np.linspace(1, 0, b) if y.ndim == 1 else np.linspace(1, 0, b)[:, None]
    return y


def periodic_freq(f, length_s):
    """The nearest frequency with a whole number of cycles in a loop of this length."""
    return max(1, round(f * length_s)) / length_s


def stereo_from(rng, mono_fn, width=0.6):
    """Two decorrelated renderings blended for width."""
    a = mono_fn(rng)
    b = mono_fn(rng)
    m = (a + b) * 0.5
    s = (a - b) * 0.5 * width
    return np.stack([m + s, m - s], axis=1)


# ============================================================================== loops

def s_wind(rng):
    length = 24.0
    n = ns(length)

    def channel(r):
        gust = smooth_random(n, r, 0.18, periodic=True)
        gust = 0.35 + 0.65 * gust ** 1.4
        flutter = smooth_random(n, r, 2.2, periodic=True)
        noise = r.standard_normal(n)
        body = spectral(noise, r_mul(r_lp(1500.0, 2), r_hp(120.0, 1)), circular=True) * gust * 0.9
        hiss = spectral(r.standard_normal(n), r_mul(r_bp(4200.0, 0.9), r_hp(2200.0, 2), r_lp(7000.0, 2)), circular=True)
        hiss *= (gust ** 2) * (0.6 + 0.4 * flutter)
        low = spectral(r.standard_normal(n), r_lp(110.0, 2), circular=True) * (0.5 + 0.5 * gust) * 1.6
        # Stalks: dry crackling that thickens with the gusts.
        stalk_env = gust ** 2
        pulses = (r.random(n) < (900.0 / SR) * stalk_env) * r.uniform(0.2, 1.0, n) * r.choice([-1, 1], n)
        stalks = spectral(pulses, r_mul(r_bp(3200.0, 1.2), r_hp(1500.0, 1)), circular=True) * 0.9
        return body + hiss * 0.4 + low + stalks

    sig = stereo_from(rng, channel, width=0.85)
    for c in range(2):
        sig[:, c] = circ_reverb(sig[:, c], rng, 1.4, wet=0.15)
    return normalise(sig, -3.0, rms_db=-20.0)


def s_drone(rng):
    length = 20.0
    n = ns(length)
    t = tvec(n)

    def channel(r):
        f1 = periodic_freq(55.0, length)
        f2 = periodic_freq(57.6, length)
        f3 = periodic_freq(110.0 * 1.06, length)
        swell = 0.6 + 0.4 * smooth_random(n, r, 0.12, periodic=True)
        tones = (np.sin(TAU * f1 * t) + 0.8 * np.sin(TAU * f2 * t + 1.0)) * swell
        tones += 0.18 * np.sin(TAU * f3 * t + 2.0) * smooth_random(n, r, 0.2, periodic=True)
        band = spectral(r.standard_normal(n), r_mul(r_bp(140.0, 1.5), r_lp(400.0)), circular=True)
        band *= 0.5 + 0.5 * smooth_random(n, r, 0.25, periodic=True)
        air = spectral(r.standard_normal(n), r_mul(r_bp(900.0, 0.9), r_lp(2000.0)), circular=True) * 0.12
        return tones * 0.55 + band * 1.4 + air

    sig = stereo_from(rng, channel, width=0.5)
    for c in range(2):
        sig[:, c] = circ_reverb(sig[:, c], rng, 2.5, wet=0.25)
    return normalise(sig, -4.0, rms_db=-21.0)


def s_night_air(rng):
    length = 20.0
    n = ns(length)

    def channel(r):
        press = spectral(r.standard_normal(n), r_mul(r_lp(60.0, 3), r_hp(18.0, 2)), circular=True)
        press *= 0.6 + 0.4 * smooth_random(n, r, 0.08, periodic=True)
        far = spectral(r.standard_normal(n), r_mul(r_bp(300.0, 0.7), r_lp(700.0, 2)), circular=True)
        far *= 0.15 * smooth_random(n, r, 0.1, periodic=True) ** 3
        return press + far

    sig = stereo_from(rng, channel, width=0.9)
    return normalise(sig, -8.0, rms_db=-30.0)


# ============================================================================== the day

def caw(rng, dur, f0):
    """One harsh, nasal caw: a rough, unsteady voice (pitch jitter, a subharmonic that breaks it
    up, breath noise) through crow-like resonances; a fast attack and a ragged end."""
    n = ns(dur)
    t = tvec(n)
    jitter = 1.0 + 0.09 * (smooth_random(n, rng, 70.0) - 0.5)
    contour = 1.0 - 0.05 * (t / dur)
    phase = np.cumsum(f0 * jitter * contour / SR)
    saw = 2.0 * (phase % 1.0) - 1.0
    sub = 2.0 * ((phase * 0.5) % 1.0) - 1.0
    shimmer = 0.6 + 0.4 * smooth_random(n, rng, 90.0)
    src = (saw * 0.8 + sub * 0.45) * shimmer + 0.9 * rng.standard_normal(n)
    voiced = (bandpass(src, 1100.0, 2.5) * 1.2 + bandpass(src, 1900.0, 3.0) * 0.9
              + bandpass(src, 2900.0, 4.0) * 0.5 + bandpass(src, 600.0, 2.0) * 0.4)
    env = env_points(n, [(0, 0), (0.015, 1.0), (dur * 0.45, 0.9), (dur * 0.85, 0.35), (dur, 0.0)])
    env *= 0.75 + 0.25 * smooth_random(n, rng, 25.0)
    return voiced * env


def s_crow(rng, v):
    n = ns(2.8)
    out = np.zeros(n)
    t0 = 0.05
    for _ in range(2 + v % 2 + rng.integers(0, 2)):
        d = rng.uniform(0.22, 0.38)
        place(out, caw(rng, d, rng.uniform(420.0, 560.0)), ns(t0), rng.uniform(0.7, 1.0))
        t0 += d + rng.uniform(0.18, 0.4)
    out = lowpass(out, 3200.0)
    out = reverb(out, rng, 1.4, wet=0.3, bright=0.3, predelay=0.04, size=2.0)[:n]
    return fade(normalise(out, -2.0), 0.005, 0.3)


def s_creak(rng, v):
    dur = rng.uniform(1.2, 2.0)
    n = ns(dur)
    t = tvec(n)
    # Stick-slip: bursts of pulses whose rate wanders (a rate, not a pitch: the wood keeps its tone).
    rate = 22.0 + 12.0 * smooth_random(n, rng, 3.0)
    phase = np.cumsum(rate / SR)
    ticks = np.diff(np.floor(phase), prepend=0.0) > 0
    pulses = ticks * rng.uniform(0.4, 1.0, n)
    env = env_points(n, [(0, 0), (0.1, 1.0), (dur * 0.7, 0.8), (dur, 0.0)]) * (0.6 + 0.4 * smooth_random(n, rng, 5.0))
    modes = [(rng.uniform(380, 460), 0.03, 1.0), (rng.uniform(820, 980), 0.02, 0.6),
             (rng.uniform(1500, 1800), 0.012, 0.35), (rng.uniform(190, 230), 0.05, 0.5)]
    wood = resonator_bank(pulses * env, modes)
    grit = bandpass(rng.standard_normal(n), 2600.0, 1.2) * env * 0.05
    out = wood + grit
    out = reverb(out, rng, 0.9, wet=0.25, bright=0.4)[:n]
    return fade(normalise(out, -2.0), 0.01, 0.15)


def s_barn_door(rng, v):
    n = ns(3.2)
    out = np.zeros(n)
    bang_modes = [(rng.uniform(85, 105), 0.12, 1.0), (rng.uniform(160, 190), 0.08, 0.7), (rng.uniform(290, 340), 0.05, 0.45),
                  (rng.uniform(520, 600), 0.03, 0.25)]
    times = [0.08, 0.08 + rng.uniform(0.35, 0.6)]
    for k, start in enumerate(times):
        exc = np.zeros(ns(1.2))
        exc[:ns(0.004)] = rng.standard_normal(ns(0.004)) * (1.0 if k == 0 else 0.45)
        bang = resonator_bank(exc, bang_modes)
        place(out, bang, ns(start))
        # The latch rattles after each bang.
        rattle_n = ns(0.35)
        rattle = crackles(rattle_n, rng, 160.0, center=1900.0, q=3.0) * decay(rattle_n, 0.09)
        place(out, rattle, ns(start + 0.01), 0.6 if k == 0 else 0.3)
    out = lowpass(out, 2500.0)
    out = reverb(out, rng, 2.6, wet=0.6, bright=0.25, predelay=0.06, size=3.0)[:n]
    out = bass_exciter(out, 0.35, 120.0)
    return fade(normalise(out, -1.5), 0.002, 0.4)


def s_chimes(rng, v):
    n = ns(5.0)
    out = np.zeros(n)
    # Out of tune with each other: a cluster that never resolves.
    bases = rng.choice([523.0, 554.0, 622.0, 739.0, 784.0, 830.0], size=4, replace=False)
    ratios = [1.0, 2.76, 5.40, 8.93]
    t0 = 0.05
    for _ in range(4 + rng.integers(0, 3)):
        f = float(rng.choice(bases)) * rng.uniform(0.995, 1.005)
        m = ns(3.5)
        tt = tvec(m)
        strike = np.zeros(m)
        for i, r in enumerate(ratios):
            strike += np.sin(TAU * f * r * tt + rng.uniform(0, TAU)) * np.exp(-tt / (1.6 / (1 + i * 0.9))) * (0.9 / (1 + i))
        strike *= np.minimum(1.0, tt / 0.002)
        place(out, strike, ns(t0), rng.uniform(0.3, 0.8))
        t0 += rng.uniform(0.15, 0.7)
    out = lowpass(out, 5000.0)
    out = reverb(out, rng, 2.0, wet=0.45, bright=0.5, predelay=0.03)[:n]
    return fade(normalise(out, -3.0), 0.002, 0.8)


def fracture(rng, n, size=1.0):
    """A deep fracture far away: a low boom with a burst of grinding crackle, felt more than heard."""
    t = tvec(n)
    boom = np.sin(TAU * 32.0 * t) * np.exp(-t / (0.55 * size)) + 0.6 * np.sin(TAU * 47.0 * t + 0.7) * np.exp(-t / (0.35 * size))
    boom *= np.minimum(1.0, t / 0.01)
    burst_env = attack_decay(n, 0.004, 0.18 * size)
    burst = lowpass(rng.standard_normal(n), 900.0) * burst_env * 0.9
    crack = crackles(n, rng, 2500.0, center=700.0, q=0.8, envelope=attack_decay(n, 0.002, 0.25 * size)) * 1.5
    return boom + burst + crack


def strain(rng, n):
    """A slow groaning strain: low filtered noise, swelling and sagging."""
    groan = spectral(rng.standard_normal(n), r_mul(r_bp(85.0, 2.5), r_lp(300.0)))
    mod = 0.4 + 0.6 * smooth_random(n, rng, 1.6) ** 2
    grind = bandpass(rng.standard_normal(n), 420.0, 1.5) * smooth_random(n, rng, 6.0) ** 3 * 0.35
    return (groan * 3.0 + grind) * mod


def s_sun_crack(rng, v):
    n = ns(5.0)
    out = np.zeros(n)
    s = strain(rng, ns(2.2)) * env_points(ns(2.2), [(0, 0), (1.4, 1.0), (2.2, 0.3)])
    place(out, s, 0)
    place(out, fracture(rng, ns(3.4), size=1.0 + 0.2 * v), ns(1.4), 1.3)
    debris_n = ns(2.6)
    debris = crackles(debris_n, rng, 600.0, center=1200.0, q=1.0, envelope=decay(debris_n, 0.9)) * 0.6
    place(out, debris, ns(1.6))
    out = lowpass(out, 3000.0)
    out = reverb(out, rng, 4.0, wet=0.55, bright=0.2, predelay=0.08, size=3.0)[:n]
    out = bass_exciter(out, 0.5)
    return fade(normalise(out, -1.5), 0.05, 0.6)


def s_sun_shatter(rng, v):
    length = 8.0
    n = ns(length)

    def channel(r):
        out = np.zeros(n)
        # A sucking swell of noise into the break (a crescendo, no pitch).
        swell_n = ns(1.3)
        swell = spectral(r.standard_normal(swell_n), r_mul(r_lp(2500.0), r_hp(60.0))) * (np.linspace(0, 1, swell_n) ** 3)
        place(out, swell, 0, 0.8)
        hit = ns(1.3)
        for k in range(5):
            place(out, fracture(r, ns(4.0), size=1.4 - 0.15 * k), hit + ns(r.uniform(0.0, 0.5) * k), 1.2 / (1 + 0.4 * k))
        shards_n = ns(4.5)
        shards = crackles(shards_n, r, 5000.0, center=2200.0, q=0.9, envelope=decay(shards_n, 1.0)) * 0.5
        shards += crackles(shards_n, r, 1800.0, center=600.0, q=0.7, envelope=decay(shards_n, 1.6)) * 0.7
        place(out, shards, hit + ns(0.05))
        # The light dying: a hollow, falling-away roar that leaves an empty ringing low tail.
        tail_n = ns(5.5)
        tail = spectral(r.standard_normal(tail_n), r_mul(r_bp(110.0, 1.0), r_lp(500.0))) * decay(tail_n, 1.8) * 1.4
        place(out, tail, hit + ns(0.2))
        return out

    sig = stereo_from(rng, channel, width=0.8)
    for c in range(2):
        x = reverb(sig[:, c], rng, 5.0, wet=0.6, bright=0.2, predelay=0.05, size=4.0)[:n]
        sig[:, c] = bass_exciter(x, 0.45)
    sig = np.stack([fade(sig[:, c], 0.01, 1.2) for c in range(2)], axis=1)
    return normalise(sig, -1.2)


def s_dawn(rng, v):
    length = 7.0
    n = ns(length)
    t = tvec(n)

    def channel(r):
        env = env_points(n, [(0, 0), (3.5, 1.0), (length, 0.0)])
        breath = spectral(r.standard_normal(n), r_mul(r_bp(700.0, 0.6), r_lp(2500.0), r_hp(150.0))) * env
        # A low, still chord fading in and out: relief that does not feel safe (a minor second inside).
        chord = (np.sin(TAU * 98.0 * t) + 0.7 * np.sin(TAU * 146.8 * t + 0.4) + 0.35 * np.sin(TAU * 155.6 * t + 1.1)) * env ** 1.5
        return breath * 0.9 + chord * 0.12

    sig = stereo_from(rng, channel, width=0.7)
    for c in range(2):
        sig[:, c] = reverb(sig[:, c], rng, 3.0, wet=0.35, bright=0.35)[:n]
    return normalise(sig, -4.0)


# ============================================================================== the night

def s_toll(rng, v):
    n = ns(9.0)
    t = tvec(n)
    prime = 182.0
    # A heavy bell's partials (hum, prime, minor third, fifth, nominal and inharmonic uppers).
    partials = [(0.5, 3.2, 0.7), (1.0, 2.6, 1.0), (1.19, 2.0, 0.6), (1.5, 1.6, 0.35), (2.0, 1.4, 0.5),
                (2.61, 0.9, 0.25), (3.24, 0.6, 0.18), (4.18, 0.4, 0.1)]
    out = np.zeros(n)
    for ratio, tau, gain in partials:
        f = prime * ratio
        beat = 1.0 + 0.012 * np.sin(TAU * rng.uniform(0.3, 0.9) * t)
        out += np.sin(TAU * f * t + rng.uniform(0, TAU)) * np.exp(-t / tau) * gain * beat
    clang = bandpass(rng.standard_normal(n), 1800.0, 1.0) * attack_decay(n, 0.001, 0.03) * 0.5
    out = (out + clang) * np.minimum(1.0, t / 0.003)
    out = lowpass(out, 2400.0)
    out = reverb(out, rng, 4.5, wet=0.55, bright=0.3, predelay=0.09, size=4.0)[:n]
    return fade(normalise(out, -2.0), 0.002, 1.5)


def s_footsteps(rng, v):
    n = ns(4.0)
    out = np.zeros(n)
    t0 = 0.08
    for k in range(3 if v != 1 else 2):
        step_n = ns(1.6)
        step = thump(step_n, rng.uniform(42.0, 52.0), 0.32, rng, sub=0.8)
        crunch = crackles(step_n, rng, 1600.0, center=900.0, q=0.9, envelope=attack_decay(step_n, 0.01, 0.12)) * 0.5
        place(out, step + crunch, ns(t0), rng.uniform(0.75, 1.0))
        t0 += rng.uniform(1.0, 1.35)
    out = lowpass(out, 1500.0)
    out = reverb(out, rng, 3.0, wet=0.5, bright=0.2, predelay=0.07, size=3.0)[:n]
    out = bass_exciter(out, 0.7, 110.0)
    return fade(normalise(out, -1.5), 0.002, 0.5)


def s_drag(rng, v):
    dur = 3.6
    n = ns(dur)
    pulls = np.zeros(n)
    t0 = 0.05
    while t0 < dur - 0.6:
        d = rng.uniform(0.6, 1.0)
        m = ns(d)
        pulls[ns(t0):ns(t0) + m] += np.sin(np.linspace(0, np.pi, m)) ** 1.5
        t0 += d + rng.uniform(0.15, 0.45)
    pulls = np.minimum(pulls, 1.0)
    rustle = spectral(rng.standard_normal(n), r_mul(r_bp(1800.0, 0.7), r_hp(400.0))) * pulls * 0.6
    stalks = crackles(n, rng, 1400.0, center=2800.0, q=1.2, envelope=pulls) * 1.2
    scrape = spectral(rng.standard_normal(n), r_mul(r_bp(220.0, 1.2), r_lp(600.0))) * pulls * 1.4
    out = rustle + stalks + scrape
    out = lowpass(out, 4000.0)
    out = reverb(out, rng, 1.5, wet=0.3, bright=0.3, predelay=0.03)[:n]
    return fade(normalise(out, -2.0), 0.05, 0.3)


def breath_cycle(rng, inhale, exhale, gap, darkness=1.0):
    n = ns(inhale + exhale + gap)
    out = np.zeros(n)
    for start, dur, centre, gain in ((0.0, inhale, 900.0, 0.55), (inhale + gap * 0.3, exhale, 520.0, 1.0)):
        m = ns(dur)
        env = np.sin(np.linspace(0, np.pi, m)) ** 1.3
        src = rng.standard_normal(m)
        voice = (bandpass(src, centre / darkness, 1.6) + 0.6 * bandpass(src, centre * 2.4 / darkness, 2.0)
                 + 0.4 * lowpass(src, 300.0))
        rasp = 1.0 + 0.6 * smooth_random(m, rng, 28.0)
        place(out, voice * env * rasp * gain, ns(start))
    return out


def s_breath(rng, v):
    n = ns(4.6)
    out = np.zeros(n)
    t0 = 0.1
    for _ in range(2):
        cyc = breath_cycle(rng, rng.uniform(0.9, 1.2), rng.uniform(1.0, 1.4), rng.uniform(0.15, 0.3), darkness=1.5)
        place(out, cyc, ns(t0))
        t0 += len(cyc) / SR + rng.uniform(0.05, 0.2)
    out = lowpass(out, 2600.0)
    out = reverb(out, rng, 1.2, wet=0.3, bright=0.3)[:n]
    return fade(normalise(out, -3.0), 0.05, 0.3)


def s_heartbeat(rng, v):
    n = ns(0.7)
    out = np.zeros(n)
    place(out, thump(ns(0.4), 54.0, 0.07, rng, sub=0.5), ns(0.01), 1.0)
    place(out, thump(ns(0.35), 66.0, 0.055, rng, sub=0.3), ns(0.25), 0.7)
    out = lowpass(out, 400.0)
    out = bass_exciter(out, 0.8, 100.0)
    return fade(normalise(out, -1.5), 0.001, 0.1)


# ============================================================================== catalogue

SOUNDS = {
    # name: (function, variants, stereo, loop, subtitle, attenuation)
    "wind": (s_wind, 1, True, True, "Wind hisses through the wheat", 64),
    "drone": (s_drone, 1, True, True, "A low unease", 32),
    "night_air": (s_night_air, 1, True, True, "Silence presses in", 32),
    "crow": (s_crow, 3, False, False, "Crows caw far away", 96),
    "creak": (s_creak, 3, False, False, "Wood creaks", 32),
    "barn_door": (s_barn_door, 2, False, False, "A door bangs in the distance", 128),
    "chimes": (s_chimes, 2, False, False, "Wind chimes", 48),
    "sun_crack": (s_sun_crack, 3, False, False, "The sun cracks", 256),
    "sun_shatter": (s_sun_shatter, 1, True, False, "The sun shatters", 256),
    "dawn": (s_dawn, 1, True, False, "The light returns", 64),
    "toll": (s_toll, 1, False, False, "A distant bell tolls", 256),
    "footsteps": (s_footsteps, 3, False, False, "Enormous footsteps", 160),
    "drag": (s_drag, 2, False, False, "Something is dragged through the wheat", 64),
    "breath": (s_breath, 2, False, False, "Heavy breathing", 32),
    "heartbeat": (s_heartbeat, 1, False, False, "Heartbeat", 16),
}


def render(name: str, index: int) -> np.ndarray:
    fn, variants, stereo, loop, _, _ = SOUNDS[name]
    rng = rng_for(f"{name}#{index}")
    sig = np.asarray(fn(rng) if loop else fn(rng, index), dtype=np.float64)
    if not np.all(np.isfinite(sig)):
        raise RuntimeError(f"{name} #{index}: non-finite samples")
    if (sig.ndim == 2) != stereo:
        raise RuntimeError(f"{name} #{index}: stereo mismatch")
    # Remove DC and anything below hearing (circularly for loops).
    if sig.ndim == 2:
        sig = np.stack([highpass(sig[:, c] - sig[:, c].mean(), 20.0, 2, circular=loop) for c in range(2)], axis=1)
    else:
        sig = highpass(sig - sig.mean(), 20.0, 2, circular=loop)
    return sig


def encode(sig: np.ndarray, path: pathlib.Path):
    path.parent.mkdir(parents=True, exist_ok=True)
    channels = 2 if sig.ndim == 2 else 1
    data = np.ascontiguousarray(sig, dtype="<f4").tobytes()
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "f32le", "-ar", str(SR), "-ac", str(channels),
           "-i", "pipe:0", "-map_metadata", "-1", "-c:a", "libvorbis", "-q:a", "5", "-ac", str(channels),
           "-flags:a", "+bitexact", "-fflags", "+bitexact", str(path)]
    subprocess.run(cmd, input=data, check=True)


def decode(path: pathlib.Path, channels: int) -> np.ndarray:
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-i", str(path), "-f", "f32le", "-ar", str(SR), "-ac", str(channels), "pipe:1"]
    raw = subprocess.run(cmd, capture_output=True, check=True).stdout
    data = np.frombuffer(raw, dtype="<f4").astype(np.float64)
    return data.reshape(-1, channels) if channels > 1 else data


def encode_checked(sig, path):
    channels = 2 if sig.ndim == 2 else 1
    for attempt in range(4):
        encode(sig, path)
        dec = decode(path, channels)
        peak = float(np.max(np.abs(dec)))
        if db(peak) <= -1.0 or attempt == 3:
            return dec
        sig = sig * 10 ** ((-1.3 - db(peak)) / 20.0)
    raise AssertionError("unreachable")


def file_name(name, index, variants):
    return name if variants == 1 else f"{name}_{index + 1}"


def generate():
    fragment = {}
    rendered = {}
    for name, (fn, variants, stereo, loop, subtitle, attenuation) in SOUNDS.items():
        entries = []
        for index in range(variants):
            sig = render(name, index)
            stem = file_name(name, index, variants)
            path = SOUND_DIR / f"{stem}.ogg"
            dec = encode_checked(sig, path)
            rendered[f"field.{name}#{index}"] = (dec, loop)
            entry = {"name": f"null:field/{stem}", "attenuation_distance": attenuation}
            if loop or len(dec) / SR > 10.0:
                entry["stream"] = True
            entries.append(entry)
        fragment[f"field.{name}"] = {"subtitle": f"subtitles.null.field.{name}", "sounds": entries}
    FRAGMENT.parent.mkdir(parents=True, exist_ok=True)
    FRAGMENT.write_text(json.dumps(fragment, indent=2) + "\n", encoding="utf-8")
    return rendered


# ============================================================================== analysis

def glide_report(x, name):
    """Flags tones that glide: a strongly tonal spectral peak that moves continuously (frame to frame
    by less than a semitone and a half) through more than an octave within half a second. That is the
    shape of a chirp or a 'pew' sweep, which this mod must never sound like. A peak that merely jumps
    between two fixed components (a bell's partials, a low band and a hum) is not a glide."""
    mono = x if x.ndim == 1 else x.mean(axis=1)
    win = 2048
    hop = 256
    if len(mono) < win * 2:
        return []
    w = np.hanning(win)
    freqs = np.fft.rfftfreq(win, 1.0 / SR)
    band = np.where((freqs > 80) & (freqs < 12000))[0]
    track = []
    loudest = 0.0
    spectra = []
    for start in range(0, len(mono) - win, hop):
        spec = np.abs(np.fft.rfft(mono[start:start + win] * w))
        spectra.append(spec)
        loudest = max(loudest, float(spec[band].max()))
    for spec in spectra:
        sub = spec[band]
        i = int(np.argmax(sub))
        peak = sub[i]
        tonal = peak / (float(np.median(sub)) + 1e-12) > 40.0 and peak > 1e-3 * loudest
        if not tonal or i == 0 or i == len(sub) - 1:
            track.append(None)
            continue
        a, b, c = np.log(sub[i - 1] + 1e-12), np.log(peak + 1e-12), np.log(sub[i + 1] + 1e-12)
        offset = 0.5 * (a - c) / (a - 2 * b + c) if (a - 2 * b + c) != 0 else 0.0
        track.append(float(freqs[band[0] + i] + offset * (freqs[1] - freqs[0])))
    flags = []
    span = int(0.5 * SR / hop)
    k = 0
    while k < len(track):
        if track[k] is None:
            k += 1
            continue
        j = k + 1
        found = False
        while j < len(track) and j - k <= span and track[j] is not None \
                and abs(np.log2(track[j] / track[j - 1])) < 0.125:
            if abs(np.log2(track[j] / track[k])) >= 1.0:
                flags.append((round(k * hop / SR, 3), round(track[k]), round(track[j])))
                found = True
                break
            j += 1
        k = j if found else k + 1
    return flags


def analyse(rendered, directory: pathlib.Path):
    from PIL import Image
    directory.mkdir(parents=True, exist_ok=True)
    lines = []
    tiles = []
    for key, (x, loop) in rendered.items():
        mono = x if x.ndim == 1 else x.mean(axis=1)
        dur = len(mono) / SR
        peak = db(float(np.max(np.abs(x))))
        loud = db(rms(mono))
        spec = np.abs(np.fft.rfft(mono * np.hanning(len(mono))))
        freqs = np.fft.rfftfreq(len(mono), 1.0 / SR)
        centroid = float(np.sum(freqs * spec) / (np.sum(spec) + 1e-12))
        glides = glide_report(x, key)
        seam = ""
        if loop:
            chans = [x] if x.ndim == 1 else [x[:, c] for c in range(x.shape[1])]
            jumps = []
            for ch in chans:
                d = np.abs(np.diff(ch))
                jumps.append(abs(ch[0] - ch[-1]) / (np.percentile(d, 99.9) + 1e-12))
            seam = f" seam {max(jumps):.2f}"
        lines.append(f"{key:22s} {dur:5.2f}s peak {peak:6.1f} dBFS rms {loud:6.1f} dBFS centroid {centroid:6.0f} Hz"
                     f" glides {len(glides)}{seam}" + ("  <-- GLIDE " + str(glides[:3]) if glides else ""))
        # Spectrogram tile (log frequency, 40 Hz - 16 kHz).
        win = 2048
        hop = max(256, len(mono) // 400)
        frames = []
        for start in range(0, max(1, len(mono) - win), hop):
            frames.append(np.abs(np.fft.rfft(mono[start:start + win] * np.hanning(win))))
        s = np.array(frames).T
        f = np.fft.rfftfreq(win, 1.0 / SR)
        rows = np.geomspace(40, 16000, 160)
        idx = np.clip(np.searchsorted(f, rows), 0, len(f) - 1)
        img = 20 * np.log10(s[idx] + 1e-9)
        img = np.clip((img - (img.max() - 80)) / 80, 0, 1)[::-1]
        tile = Image.fromarray((img * 255).astype(np.uint8)).resize((400, 160))
        tiles.append((key, tile))
    report = "\n".join(lines)
    (directory / "field_sounds_report.txt").write_text(report + "\n", encoding="utf-8")
    from PIL import ImageDraw
    cols = 3
    rows_n = (len(tiles) + cols - 1) // cols
    sheet = Image.new("L", (cols * 410, rows_n * 180), 0)
    draw = ImageDraw.Draw(sheet)
    for i, (key, tile) in enumerate(tiles):
        x0 = (i % cols) * 410
        y0 = (i // cols) * 180
        sheet.paste(tile, (x0, y0 + 16))
        draw.text((x0 + 4, y0 + 2), key, fill=255)
    sheet.save(directory / "field_spectrograms.png")
    print(report)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--analyse", type=pathlib.Path)
    parser.add_argument("--no-merge", action="store_true")
    args = parser.parse_args()
    rendered = generate()
    print(f"{sum(v[1] for v in SOUNDS.values())} files in {len(SOUNDS)} events -> {SOUND_DIR.relative_to(ROOT)}")
    if args.analyse:
        analyse(rendered, args.analyse)
    if not args.no_merge:
        result = subprocess.run([sys.executable, str(ROOT / "tools" / "merge_sounds.py")])
        if result.returncode != 0:
            sys.exit(result.returncode)


if __name__ == "__main__":
    main()
