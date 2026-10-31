#!/usr/bin/env python3
"""Synthesises every sound NULL plays and writes assets/null/sounds.json.

For each SoundEvent in registry/NullSounds.java this script renders 1-5 variants with numpy only
(filtered and coloured noise, granular textures, FM/AM and additive synthesis, formant-shaped
noise for whispers and voices, modal resonators for knocks/creaks/glass, synthetic convolution
reverb, varispeed for wow & flutter), encodes them to Ogg Vorbis with ffmpeg/libvorbis under
src/main/resources/assets/null/sounds/<group>/<name>[_<n>].ogg and writes
src/main/resources/assets/null/sounds.json plus the subtitle translations
(assets-src/lang/assets.json). No external samples are used anywhere.

Rules the output follows:
  * positional sounds are mono (Minecraft only spatialises mono); music.* and menu.ambience are
    stereo and streamed; anything longer than ~10 s is streamed;
  * loops (ambient.low_hum, dimension.hum, nullroot.capture, menu.ambience) are synthesised as
    exactly periodic signals (circular processing), so the end flows into the start without a
    click; the seam is measured and reported;
  * peaks are at most -1 dBFS after encoding (checked by decoding every file again),
    ambient.static_faint sits at about -30 dBFS RMS.

The script is deterministic: every variant is rendered from its own fixed seed and ffmpeg is run
in bit-exact mode, so running it again produces byte-identical files.

Usage: python3 tools/gen_sounds.py [--only EVENT_PREFIX ...] [--jobs N] [--spectrograms DIR]
"""
from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys

import numpy as np

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "src" / "main" / "resources" / "assets" / "null"
SOUND_DIR = RES / "sounds"
SOUNDS_JSON = RES / "sounds.json"
LANG_FRAGMENT = ROOT / "assets-src" / "lang" / "assets.json"
NULL_SOUNDS_JAVA = ROOT / "src" / "main" / "java" / "dev" / "kristian" / "nullmod" / "registry" / "NullSounds.java"
MODID = "null"

SR = 44100
TAU = 2.0 * np.pi


# =============================================================================================
# DSP toolkit
# =============================================================================================

def rng_for(key: str) -> np.random.Generator:
    seed = int.from_bytes(hashlib.sha256(("null-sound:" + key).encode()).digest()[:8], "little")
    return np.random.default_rng(seed)


def ns(seconds: float, sr: int = SR) -> int:
    return max(1, int(round(seconds * sr)))


def tvec(n: int, sr: int = SR) -> np.ndarray:
    return np.arange(n) / sr


def next_pow2(n: int) -> int:
    return 1 << int(max(1, n - 1)).bit_length()


def fftconv(x: np.ndarray, h: np.ndarray) -> np.ndarray:
    """Full linear convolution via FFT."""
    n = len(x) + len(h) - 1
    size = next_pow2(n)
    return np.fft.irfft(np.fft.rfft(x, size) * np.fft.rfft(h, size), size)[:n]


def circconv(x: np.ndarray, h: np.ndarray) -> np.ndarray:
    """Circular convolution (for periodic loop material): the tail wraps onto the head."""
    n = len(x)
    if len(h) > n:
        h = h[:n]
    return np.fft.irfft(np.fft.rfft(x) * np.fft.rfft(h, n), n)


def place(out: np.ndarray, sig: np.ndarray, start: int, gain: float = 1.0, wrap: bool = False):
    """Adds sig into out at start (clipped at the end, or wrapped around for loops)."""
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


# ---- zero-phase spectral filters (for noise beds and anything without sharp transients) ------

def spectral(x: np.ndarray, response, sr: int = SR, circular: bool = False, pad: float = 0.3) -> np.ndarray:
    if circular:
        f = np.fft.rfftfreq(len(x), 1.0 / sr)
        return np.fft.irfft(np.fft.rfft(x) * response(f), len(x))
    p = min(ns(pad, sr), len(x))
    size = next_pow2(len(x) + 2 * p)
    buf = np.zeros(size)
    buf[p:p + len(x)] = x
    f = np.fft.rfftfreq(size, 1.0 / sr)
    return np.fft.irfft(np.fft.rfft(buf) * response(f), size)[p:p + len(x)]


def r_lp(fc, order=2):
    return lambda f: 1.0 / np.sqrt(1.0 + (f / fc) ** (2 * order))


def r_hp(fc, order=2):
    def resp(f):
        ff = np.maximum(f, 1e-6)
        return np.where(f > 0, 1.0 / np.sqrt(1.0 + (fc / ff) ** (2 * order)), 0.0)
    return resp


def r_bp(fc, q):
    def resp(f):
        ff = np.maximum(f, 1e-6)
        return 1.0 / np.sqrt(1.0 + q * q * (ff / fc - fc / ff) ** 2)
    return resp


def r_mul(*responses):
    def resp(f):
        out = np.ones_like(f)
        for r in responses:
            out = out * r(f)
        return out
    return resp


def lowpass(x, fc, order=2, sr=SR, circular=False):
    return spectral(x, r_lp(fc, order), sr, circular)


def highpass(x, fc, order=2, sr=SR, circular=False):
    return spectral(x, r_hp(fc, order), sr, circular)


def bandpass(x, fc, q, sr=SR, circular=False):
    return spectral(x, r_bp(fc, q), sr, circular)


# ---- causal biquads (for transients: no pre-ringing) ---------------------------------------

def biquad_coefs(kind, fc, q=0.7071, gain_db=0.0, sr=SR):
    w0 = TAU * min(fc, sr * 0.49) / sr
    c = np.cos(w0)
    alpha = np.sin(w0) / (2.0 * q)
    big_a = 10 ** (gain_db / 40.0)
    if kind == "lp":
        b = [(1 - c) / 2, 1 - c, (1 - c) / 2]
        a = [1 + alpha, -2 * c, 1 - alpha]
    elif kind == "hp":
        b = [(1 + c) / 2, -(1 + c), (1 + c) / 2]
        a = [1 + alpha, -2 * c, 1 - alpha]
    elif kind == "bp":
        b = [alpha, 0.0, -alpha]
        a = [1 + alpha, -2 * c, 1 - alpha]
    elif kind == "peak":
        b = [1 + alpha * big_a, -2 * c, 1 - alpha * big_a]
        a = [1 + alpha / big_a, -2 * c, 1 - alpha / big_a]
    else:
        raise ValueError(kind)
    return np.array(b) / a[0], np.array(a) / a[0]


def impulse_response(b, a, max_len, tol=1e-7):
    b0, b1, b2 = b
    a1, a2 = a[1], a[2]
    out = np.zeros(max_len)
    x1 = x2 = y1 = y2 = 0.0
    quiet = 0
    for n in range(max_len):
        xn = 1.0 if n == 0 else 0.0
        yn = b0 * xn + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2, x1 = x1, xn
        y2, y1 = y1, yn
        out[n] = yn
        if n > 16 and abs(yn) < tol:
            quiet += 1
            if quiet > 96:
                return out[:n + 1]
        else:
            quiet = 0
    return out


_IR_CACHE: dict = {}


def iir(x, kind, fc, q=0.7071, gain_db=0.0, sr=SR):
    key = (kind, round(fc, 4), round(q, 5), round(gain_db, 4), sr)
    h = _IR_CACHE.get(key)
    if h is None:
        b, a = biquad_coefs(kind, fc, q, gain_db, sr)
        h = impulse_response(b, a, ns(4.0, sr))
        _IR_CACHE[key] = h
    return fftconv(x, h)[:len(x)]


# ---- time-varying spectral shaping (formants, moving resonances) ---------------------------

def stft_shape(x, gain_fn, n_fft=1024, hop=256, sr=SR):
    """Overlap-add STFT filter. gain_fn(times[F], freqs[B]) -> gains[F, B] (times relative to x)."""
    n = len(x)
    win = np.sqrt(np.hanning(n_fft + 1)[:-1])
    pad = n_fft
    xp = np.concatenate([np.zeros(pad), x, np.zeros(pad + hop)])
    frames = np.lib.stride_tricks.sliding_window_view(xp, n_fft)[::hop]
    count = frames.shape[0]
    t = (np.arange(count) * hop + n_fft / 2 - pad) / sr
    f = np.fft.rfftfreq(n_fft, 1.0 / sr)
    spec = np.fft.rfft(frames * win, axis=1) * gain_fn(t, f)
    y = np.fft.irfft(spec, n_fft, axis=1) * win
    out = np.zeros(count * hop + n_fft)
    for j in range(n_fft // hop):
        out[j * hop:j * hop + count * hop] += y[:, j * hop:(j + 1) * hop].reshape(-1)
    out /= n_fft / (2.0 * hop)
    return out[pad:pad + n]


# ---- noise, control signals, envelopes ----------------------------------------------------

def white(n, rng):
    return rng.standard_normal(n)


def coloured(n, rng, exponent, sr=SR, circular=False):
    """1/f^exponent noise (1 = pink, 2 = brown), unit RMS."""
    x = rng.standard_normal(n)
    resp = lambda f: np.where(f > 0, 1.0 / np.maximum(f, 5.0) ** (exponent / 2.0), 0.0)
    y = spectral(x, resp, sr, circular=circular)
    return y / (np.sqrt(np.mean(y * y)) + 1e-12)


def smooth_random(n, rng, rate_hz, sr=SR, periodic=False):
    """Smooth random control in -1..1 changing about rate_hz times per second."""
    knots = max(2, int(np.ceil(n / sr * rate_hz)) + 2)
    vals = rng.uniform(-1, 1, knots)
    if periodic:
        vals[-1] = vals[0]
        pos = np.arange(n) * (knots - 1) / n
    else:
        pos = np.linspace(0, knots - 1, n)
    i0 = np.floor(pos).astype(int)
    i1 = np.minimum(i0 + 1, knots - 1)
    fr = pos - i0
    fr = fr * fr * (3 - 2 * fr)
    return vals[i0] * (1 - fr) + vals[i1] * fr


def env_points(n, points, sr=SR):
    """Piecewise-linear envelope through (time_s, value) points."""
    ts = np.array([p[0] for p in points]) * sr
    vs = np.array([p[1] for p in points], dtype=float)
    return np.interp(np.arange(n), ts, vs)


def exp_decay(n, tau, sr=SR):
    return np.exp(-tvec(n, sr) / tau)


def attack_decay(n, attack, tau, sr=SR):
    t = tvec(n, sr)
    return (1 - np.exp(-t / max(attack, 1e-5))) * np.exp(-t / tau)


def fade(x, fin=0.003, fout=0.01, sr=SR):
    y = x.copy()
    a = min(len(y) // 2, ns(fin, sr))
    b = min(len(y) // 2, ns(fout, sr))
    if a > 0:
        y[:a] *= np.sin(np.linspace(0, np.pi / 2, a)) ** 2
    if b > 0:
        y[-b:] *= np.cos(np.linspace(0, np.pi / 2, b)) ** 2
    return y


def hann(n):
    return np.hanning(n + 2)[1:-1] if n > 2 else np.ones(n)


# ---- oscillators ----------------------------------------------------------------------------

def phase_of(freq, sr=SR):
    freq = np.asarray(freq, dtype=float)
    return TAU * np.cumsum(freq) / sr


def sine(freq, n=None, sr=SR, phase0=0.0):
    if np.isscalar(freq):
        return np.sin(TAU * freq * tvec(n, sr) + phase0)
    return np.sin(phase_of(freq, sr) + phase0)


def additive(freq, n, partials, sr=SR, phase=None):
    """Band-limited sum of harmonics: partials = [(ratio, amp), ...] of a (possibly varying) freq."""
    ph = TAU * freq * tvec(n, sr) if np.isscalar(freq) else phase_of(freq, sr)
    fmax = freq if np.isscalar(freq) else np.max(freq)
    out = np.zeros(n)
    for ratio, amp in partials:
        if ratio * fmax < sr * 0.45:
            out += amp * np.sin(ph * ratio + (0 if phase is None else phase * ratio))
    return out


def square_raw(freq, n, sr=SR, duty=0.5):
    """Naive (aliasing) square: the harsh digital buzz is intended."""
    ph = (np.cumsum(np.broadcast_to(np.asarray(freq, float), (n,))) / sr) % 1.0
    return np.where(ph < duty, 1.0, -1.0)


# ---- nonlinear / digital ------------------------------------------------------------------

def soft_clip(x, drive=1.0):
    return np.tanh(drive * x) / np.tanh(drive)


def bitcrush(x, bits, hold=1):
    y = x
    if hold > 1:
        idx = (np.arange(len(x)) // hold) * hold
        y = x[idx]
    q = 2 ** (bits - 1)
    return np.round(y * q) / q


def varispeed(x, rate):
    """Reads x at a variable rate (array of per-output-sample increments): pitch and speed change
    together, like a tape or a record. Cubic (Catmull-Rom) interpolation."""
    rate = np.asarray(rate, dtype=float)
    if rate.ndim == 0:
        rate = np.full(len(x), float(rate))
    pos = np.concatenate([[0.0], np.cumsum(rate[:-1])])
    pos = pos[pos < len(x) - 3]
    i = np.floor(pos).astype(int)
    fr = pos - i
    xp = np.concatenate([[x[0]], x, [x[-1], x[-1]]])
    p0, p1, p2, p3 = xp[i], xp[i + 1], xp[i + 2], xp[i + 3]
    return p1 + 0.5 * fr * (p2 - p0 + fr * (2 * p0 - 5 * p1 + 4 * p2 - p3 + fr * (3 * (p1 - p2) + p3 - p0)))


def resample_pitch(x, factor):
    """Plays x factor times faster (factor 0.5 = an octave down and twice as long)."""
    n_out = int(len(x) / factor) - 4
    return varispeed(x, np.full(max(n_out, 1), factor))


# ---- room ---------------------------------------------------------------------------------

def reverb_ir(rng, rt60, sr=SR, bright=0.5, predelay=0.012, early=6, size=1.0):
    n = ns(rt60 * 1.15 + predelay, sr)
    t = tvec(n, sr)
    noise = rng.standard_normal(n)
    low = lowpass(noise, 500, 2, sr)
    high = highpass(noise, 3500, 2, sr)
    mid = noise - low - high
    ir = (low * np.exp(-6.91 * t / (rt60 * 1.2))
          + mid * np.exp(-6.91 * t / rt60)
          + high * np.exp(-6.91 * t / (rt60 * (0.25 + 0.45 * bright))) * (0.35 + 0.65 * bright))
    pd = ns(predelay, sr)
    ir[:pd] = 0.0
    onset = min(n - pd, ns(0.02 * size, sr))
    if onset > 0:
        ir[pd:pd + onset] *= np.linspace(0, 1, onset) ** 2
    for _ in range(early):
        k = pd + ns(rng.uniform(0.002, 0.045) * size, sr)
        if k < n:
            ir[k] += rng.choice([-1, 1]) * rng.uniform(4, 10)
    return ir / np.sqrt(np.sum(ir * ir))


def reverb(x, rng, rt60, wet=0.3, dry=1.0, bright=0.5, predelay=0.012, sr=SR, size=1.0, tail=True):
    ir = reverb_ir(rng, rt60, sr, bright, predelay, size=size)
    w = fftconv(x, ir)
    if not tail:
        w = w[:len(x)]
    out = np.zeros(len(w))
    out[:len(x)] += dry * x
    return out + wet * w


# ---- finishing ----------------------------------------------------------------------------

def db(v):
    return 20.0 * np.log10(max(v, 1e-12))


def rms(x):
    return float(np.sqrt(np.mean(np.square(x))))


def normalise(x, peak_db=-1.5, rms_db=None):
    peak = float(np.max(np.abs(x))) + 1e-12
    if rms_db is not None:
        gain = 10 ** (rms_db / 20.0) / (rms(x) + 1e-12)
        if peak * gain > 10 ** (peak_db / 20.0):
            gain = 10 ** (peak_db / 20.0) / peak
    else:
        gain = 10 ** (peak_db / 20.0) / peak
    return x * gain


def compress(x, sr=SR, threshold_db=-18.0, ratio=3.0, window=0.012, smooth=0.01):
    """Feed-forward RMS compressor; the threshold is relative to the loudest window, so it only
    tames the crest factor (plosive spikes, attack transients) and never needs tuning per level."""
    w = max(1, ns(window, sr))
    power = np.convolve(x * x, np.ones(w) / w, mode="same")
    env = np.sqrt(power) + 1e-12
    thr = 10 ** (threshold_db / 20.0) * float(np.max(env))
    g = np.where(env > thr, (env / thr) ** (1.0 / ratio - 1.0), 1.0)
    k = max(1, ns(smooth, sr))
    g = np.convolve(np.pad(g, k, mode="edge"), np.ones(k) / k, mode="same")[k:-k]
    return x * g


def bass_exciter(x, sr=SR, amount=0.5, cutoff=140.0, drive=4.0, circular=False):
    """Adds harmonics of the sub-bass (100-900 Hz) so small speakers and headphones still convey
    a rumble or a thump (the missing-fundamental effect)."""
    low = lowpass(x, cutoff, 2, sr, circular)
    peak = float(np.max(np.abs(low))) + 1e-12
    harm = np.tanh(drive * low / peak) * peak
    harm = spectral(harm, r_mul(r_hp(cutoff * 1.1, 2), r_lp(900.0, 2)), sr, circular=circular)
    return x + amount * harm


def pan(x, position):
    """Constant-power pan, position -1 (left) .. 1 (right)."""
    a = (position + 1) * np.pi / 4
    return np.stack([x * np.cos(a), x * np.sin(a)], axis=1)


def tile_process(period_signal, process, copies=3):
    """Runs a local (finite-memory) process on a periodic signal tiled `copies` times and keeps the
    middle period, so the result is exactly periodic: used to build seamless loops."""
    n = len(period_signal) if period_signal.ndim == 1 else period_signal.shape[0]
    tiled = np.concatenate([period_signal] * copies, axis=0)
    out = process(tiled)
    mid = copies // 2
    return out[mid * n:(mid + 1) * n]


# =============================================================================================
# Building blocks shared by several sounds
# =============================================================================================

VOWELS = {
    "a": (800, 1250, 2600), "e": (480, 1850, 2600), "i": (320, 2250, 2950), "o": (520, 900, 2450),
    "u": (360, 760, 2350), "@": (520, 1450, 2550), "ae": (680, 1650, 2500), "y": (330, 1700, 2400),
}
FRICATIVES = {
    "s": (6500, 2200, 1.0), "sh": (3200, 1200, 0.9), "f": (4500, 3500, 0.35), "h": (None, None, 0.45),
    "th": (5200, 3000, 0.3), "t": (4200, 2500, 0.8), "k": (2400, 1100, 0.75), "p": (1200, 1600, 0.6),
}


def speech_plan(rng, dur, rate=1.0, pause_prob=0.12, start=0.08, scale=1.0, sr_ctrl=400):
    """Random syllable plan for unintelligible speech: control tracks sampled at sr_ctrl.
    Returns dict of arrays: amp (vowel energy), F1-F3 (formant tracks), fric/fric_c/fric_w
    (fricative energy, centre, width) and phrase (1 while the phrase is being spoken)."""
    m = int(dur * sr_ctrl) + 1
    amp = np.zeros(m)
    fric = np.zeros(m)
    fric_c = np.full(m, 4000.0)
    fric_w = np.full(m, 2000.0)
    phrase = np.zeros(m)
    targets = []   # (time, F1, F2, F3)
    t = start
    vowel_names = list(VOWELS)
    last = VOWELS["@"]
    run_start = t

    def mark_phrase(a, b):
        i0, i1 = int(a * sr_ctrl), int(min(dur, b) * sr_ctrl)
        if i1 > i0 + 2:
            phrase[i0:i1] = np.maximum(phrase[i0:i1], np.hanning(i1 - i0 + 2)[1:-1] ** 0.25)

    while t < dur - 0.2:
        if rng.random() < 0.55:
            kind = str(rng.choice(list(FRICATIVES)))
            fc, fw, level = FRICATIVES[kind]
            plosive = kind in ("t", "k", "p")
            length = (rng.uniform(0.012, 0.025) if plosive else rng.uniform(0.05, 0.12)) / rate
            i0, i1 = int(t * sr_ctrl), int(min(dur, t + length) * sr_ctrl)
            if i1 > i0:
                shape = np.hanning(i1 - i0 + 2)[1:-1] if not plosive else np.exp(-np.linspace(0, 3, i1 - i0))
                fric[i0:i1] = np.maximum(fric[i0:i1], level * (0.55 if plosive else 1.0) * shape)
                if fc is None:
                    fc, fw = last[1], 1800
                fric_c[i0:i1] = fc * scale
                fric_w[i0:i1] = fw
            t += length * (0.7 if plosive else 0.85)
        v1 = VOWELS[str(rng.choice(vowel_names))]
        v2 = VOWELS[str(rng.choice(vowel_names))] if rng.random() < 0.35 else v1
        length = rng.uniform(0.11, 0.24) / rate
        i0, i1 = int(t * sr_ctrl), int(min(dur, t + length) * sr_ctrl)
        if i1 > i0 + 2:
            level = rng.uniform(0.6, 1.0)
            amp[i0:i1] = np.maximum(amp[i0:i1], level * np.hanning(i1 - i0 + 2)[1:-1] ** 0.35)
        targets.append((t, *v1))
        targets.append((t + length, *v2))
        last = v2
        t += length
        if rng.random() < 0.2:
            kind = str(rng.choice(["s", "sh", "f", "t", "k"]))
            fc, fw, level = FRICATIVES[kind]
            length = rng.uniform(0.04, 0.09) / rate
            i0, i1 = int(t * sr_ctrl), int(min(dur, t + length) * sr_ctrl)
            if i1 > i0:
                fric[i0:i1] = np.maximum(fric[i0:i1], 0.7 * level * np.hanning(i1 - i0 + 2)[1:-1])
                fric_c[i0:i1] = fc * scale
                fric_w[i0:i1] = fw
            t += length
        t += rng.uniform(0.01, 0.06) / rate
        if rng.random() < pause_prob:
            mark_phrase(run_start, t)
            t += rng.uniform(0.15, 0.35)
            run_start = t
    mark_phrase(run_start, t)
    if not targets:
        targets = [(0.0, *VOWELS["@"])]
    tt = np.array([p[0] for p in targets])
    ctrl_t = np.arange(m) / sr_ctrl
    formants = [np.interp(ctrl_t, tt, np.array([p[k] for p in targets])) * scale for k in (1, 2, 3)]
    # Smooth the formant tracks (articulators do not jump).
    kernel = np.hanning(int(0.035 * sr_ctrl) * 2 + 1)
    kernel /= kernel.sum()
    formants = [np.convolve(np.pad(fm, len(kernel), mode="edge"), kernel, mode="same")[len(kernel):-len(kernel)]
                for fm in formants]
    return {"amp": amp, "F1": formants[0], "F2": formants[1], "F3": formants[2],
            "fric": fric, "fric_c": fric_c, "fric_w": fric_w, "phrase": phrase, "rate": sr_ctrl}


def speech_gain(plan, voiced=False, breath_floor=0.05, f4=3500.0):
    rate = plan["rate"]
    if voiced:
        weights, bw = (1.0, 0.75, 0.45, 0.22), (90.0, 130.0, 190.0, 400.0)
    else:   # whispered vowels: weak F1, energy in F2/F3, broader formants
        weights, bw = (0.55, 0.9, 0.65, 0.35), (200.0, 260.0, 340.0, 500.0)

    def gain(t, f):
        idx = np.clip((t * rate).astype(int), 0, len(plan["amp"]) - 1)
        a = plan["amp"][idx][:, None]
        F1 = plan["F1"][idx][:, None]
        F2 = plan["F2"][idx][:, None]
        F3 = plan["F3"][idx][:, None]
        ff = f[None, :]
        env = (weights[0] * np.exp(-0.5 * ((ff - F1) / bw[0]) ** 2)
               + weights[1] * np.exp(-0.5 * ((ff - F2) / bw[1]) ** 2)
               + weights[2] * np.exp(-0.5 * ((ff - F3) / bw[2]) ** 2)
               + weights[3] * np.exp(-0.5 * ((ff - f4) / bw[3]) ** 2))
        tilt = 1.0 / (1.0 + (ff / 4500.0) ** 2) if voiced else 1.0 / (1.0 + (ff / 8000.0) ** 2)
        g = a * (env * tilt + breath_floor * tilt)
        fr = plan["fric"][idx][:, None]
        fc = plan["fric_c"][idx][:, None]
        fw = plan["fric_w"][idx][:, None]
        g = g + fr * np.exp(-0.5 * ((ff - fc) / fw) ** 2) * (1.0 if not voiced else 0.6)
        if not voiced:   # faint breath that carries between the syllables of a phrase
            g = g + plan["phrase"][idx][:, None] * 0.045 * np.exp(-0.5 * ((ff - 1700.0) / 1500.0) ** 2)
        hp = 1.0 / (1.0 + (180.0 / np.maximum(ff, 1.0)) ** 4)
        return g * hp
    return gain


def whisper(rng, dur, rate=1.0, scale=1.0, pause_prob=0.15, breathiness=0.05, sr=SR):
    n = ns(dur, sr)
    plan = speech_plan(rng, dur, rate=rate, scale=scale, pause_prob=pause_prob)
    src = white(n, rng)
    y = stft_shape(src, speech_gain(plan, voiced=False, breath_floor=breathiness), sr=sr)
    return compress(y, sr, threshold_db=-14.0, ratio=3.0)


def voice(rng, dur, f0=140.0, rate=1.0, scale=1.0, pause_prob=0.2, breath=0.25, sr=SR, jitter=0.012):
    """Voiced, unintelligible speech: a glottal source with intonation shaped by formants."""
    n = ns(dur, sr)
    plan = speech_plan(rng, dur, rate=rate, scale=scale, pause_prob=pause_prob)
    t = tvec(n, sr)
    intonation = 1.0 + 0.09 * smooth_random(n, rng, 2.2, sr) + 0.03 * np.sin(TAU * 0.3 * t)
    vib = 1.0 + jitter * smooth_random(n, rng, 35.0, sr)
    f = f0 * intonation * vib
    src = additive(f, n, [(k, 1.0 / k ** 1.15) for k in range(1, 60)], sr)
    src += breath * white(n, rng) * 0.3
    return compress(stft_shape(src, speech_gain(plan, voiced=True), sr=sr), sr, threshold_db=-12.0, ratio=2.5)


def footstep(rng, weight=1.0, crunch=1.0, sr=SR, surface="dirt"):
    n = ns(0.32, sr)
    out = np.zeros(n)
    # Heel then toe: two soft impacts.
    for k, (delay, level) in enumerate([(0.0, 1.0), (rng.uniform(0.035, 0.07), rng.uniform(0.45, 0.7))]):
        m = ns(0.16, sr)
        body = white(m, rng) * attack_decay(m, 0.0015, 0.018 + 0.01 * weight, sr)
        body = iir(body, "lp", 420 + 380 * (1 - weight), 0.8, sr=sr)
        thump = sine(rng.uniform(62, 88) * (1.15 - 0.25 * weight), m, sr) * attack_decay(m, 0.002, 0.028 * weight + 0.01, sr)
        place(out, (body * 1.6 + thump * 0.9 * weight) * level, ns(delay, sr))
    # Grit: tiny grains of crushed dirt / gravel.
    grains = int(rng.integers(14, 32) * crunch)
    for _ in range(grains):
        start = ns(rng.uniform(0.0, 0.09) ** 1.4 * 1.2, sr)
        m = ns(rng.uniform(0.0006, 0.004), sr)
        g = white(m, rng) * hann(m) * rng.uniform(0.1, 0.55) * crunch
        place(out, g, start)
    lo, hi = (900, 5200) if surface == "dirt" else (1400, 7000)
    grit = out - iir(out, "lp", lo, 0.7, sr=sr)
    out = iir(out, "lp", 1600, 0.7, sr=sr) + iir(grit, "lp", hi, 0.7, sr=sr) * 0.8
    return fade(out, 0.0005, 0.06, sr)


def door_knock(rng, strength=1.0, sr=SR, modes=None):
    n = ns(0.45, sr)
    t = tvec(n, sr)
    if modes is None:
        modes = [(92, 0.10, 1.0), (167, 0.07, 0.8), (251, 0.05, 0.6), (402, 0.032, 0.45),
                 (633, 0.02, 0.3), (975, 0.012, 0.22), (1540, 0.007, 0.12)]
    h = np.zeros(n)
    for f, tau, amp in modes:
        fj = f * rng.uniform(0.97, 1.03)
        h += amp * np.exp(-t / (tau * rng.uniform(0.85, 1.15))) * np.sin(TAU * fj * t + rng.uniform(0, TAU))
    m = ns(0.004, sr)
    exc = white(m, rng) * np.exp(-np.linspace(0, 5, m))
    exc = iir(exc, "lp", 2500 + 1500 * strength, 0.7, sr=sr)
    body = fftconv(exc, h)[:n] * strength
    click = np.zeros(n)
    k = ns(0.0012, sr)
    click[:k] = white(k, rng) * np.exp(-np.linspace(0, 6, k)) * 0.35 * strength
    return fade(body + iir(click, "hp", 1800, 0.7, sr=sr), 0.0003, 0.05, sr)


def friction_creak(rng, dur, rate_points, res_freqs, res_q=10.0, sr=SR, jitter=0.15, grit=0.25):
    """Stick-slip creak: an irregular pulse train (rate in Hz follows rate_points) exciting resonances."""
    n = ns(dur, sr)
    rate = env_points(n, rate_points, sr) * (1.0 + jitter * smooth_random(n, rng, 18.0, sr))
    ph = np.cumsum(np.maximum(rate, 1.0)) / sr
    pulses = np.zeros(n)
    hits = np.nonzero(np.diff(np.floor(ph)) > 0)[0]
    pulses[hits] = rng.uniform(0.5, 1.0, len(hits))
    pulses += grit * white(n, rng) * 0.02
    out = np.zeros(n)
    for fc in res_freqs:
        out += iir(pulses, "bp", fc * rng.uniform(0.95, 1.05), res_q, sr=sr)
    return fade(out, 0.0, min(0.06, dur * 0.2), sr)


def squelch(rng, dur, centre=900.0, density=14.0, sr=SR, wetness=1.0):
    """Wet organic texture: short bubbly resonant blips and gated band noise."""
    n = ns(dur, sr)
    out = np.zeros(n)
    count = int(dur * density)
    for _ in range(count):
        m = ns(rng.uniform(0.012, 0.05), sr)
        f0 = centre * rng.uniform(0.4, 1.4)
        f1 = f0 * rng.uniform(1.4, 2.6)
        t = tvec(m, sr)
        f = f0 + (f1 - f0) * (t / t[-1]) ** 1.5
        blip = np.sin(phase_of(f, sr)) * hann(m) ** 1.5
        place(out, blip * rng.uniform(0.2, 1.0) * wetness, ns(rng.uniform(0, dur), sr) - m // 2)
    band = bandpass(white(n, rng), centre, 2.5, sr) * (0.5 + 0.5 * np.clip(smooth_random(n, rng, 12, sr) * 2, -1, 1))
    return out + 0.35 * band * wetness


def glass_tinkle(rng, f0, level=1.0, sr=SR, decay=0.35):
    n = ns(decay * 3.0, sr)
    t = tvec(n, sr)
    ratios = [1.0, 2.32, 4.25, 6.63, 9.38]
    out = np.zeros(n)
    for k, r in enumerate(ratios):
        f = f0 * r * rng.uniform(0.985, 1.015)
        if f > sr * 0.45:
            continue
        out += np.exp(-t / (decay / (1 + 0.8 * k))) * np.sin(TAU * f * t + rng.uniform(0, TAU)) / (1 + k * 0.6)
    k = ns(0.0008, sr)
    out[:k] += white(k, rng) * 0.5
    return fade(out * level, 0.0, decay * 0.9, sr)


def ice_crack(rng, sr=SR, size=1.0):
    """Dispersive crack (the eerie 'pew' of cracking ice): high frequencies first, then low."""
    n = ns(0.5 + 0.4 * size, sr)
    out = np.zeros(n)
    k = ns(0.0015, sr)
    out[:k] += white(k, rng) * np.exp(-np.linspace(0, 5, k)) * 1.2
    for j in range(int(rng.integers(2, 4))):
        f_start = rng.uniform(5000, 8000)
        f_end = rng.uniform(180, 520) * (1.3 - 0.4 * size)
        tau = rng.uniform(0.02, 0.05) * (0.8 + 0.6 * size)
        m = ns(rng.uniform(0.12, 0.3) * (0.8 + 0.5 * size), sr)
        c = chirp_exp(m, f_start, f_end, tau, sr) * attack_decay(m, 0.0004, rng.uniform(0.04, 0.11) * (0.8 + 0.5 * size), sr)
        c = fade(c, 0.0, m / sr * 0.35, sr)
        place(out, c * rng.uniform(0.5, 1.0), ns(j * rng.uniform(0.002, 0.012), sr))
    return out


def thump(rng, n, f_start=70.0, f_end=32.0, glide=0.08, tau=0.25, sr=SR):
    t = tvec(n, sr)
    f = f_end + (f_start - f_end) * np.exp(-t / glide)
    return fade(np.sin(phase_of(f, sr)) * attack_decay(n, 0.003, tau, sr), 0.0, min(0.15, n / sr * 0.3), sr)


def rumble_bed(rng, n, cutoff=110.0, sr=SR, circular=False):
    b = coloured(n, rng, 2.0, sr, circular)
    return spectral(b, r_mul(r_lp(cutoff, 3), r_hp(22, 2)), sr, circular=circular)


def stutter(grain, repeats, decay=1.0, gap=0):
    parts = []
    g = 1.0
    for _ in range(repeats):
        parts.append(grain * g)
        if gap:
            parts.append(np.zeros(gap))
        g *= decay
    return np.concatenate(parts)


# =============================================================================================
# Sound catalogue
# =============================================================================================

SPECS: dict[str, dict] = {}


def sound(event, *, variants=3, subtitle=None, stream=False, stereo=False, attenuation=None,
          sr=SR, peak=-1.5, rms_db=None, quality=4, loop=False, fades=(0.002, 0.01)):
    def wrap(fn):
        SPECS[event] = dict(fn=fn, variants=variants, subtitle=subtitle, stream=stream, stereo=stereo,
                            attenuation=attenuation, sr=sr, peak=peak, rms=rms_db, quality=quality,
                            loop=loop, fades=fades)
        return fn
    return wrap


# ---- Ambience -------------------------------------------------------------------------------

@sound("ambient.static_faint", variants=3, subtitle="Faint static", attenuation=24, rms_db=-30.0, fades=(0.6, 0.9))
def s_static_faint(rng, v, sr):
    dur = 5.0 + 0.8 * v + rng.uniform(0, 0.8)
    n = ns(dur, sr)
    hiss = spectral(white(n, rng), r_mul(r_hp(260, 2), r_lp(rng.uniform(5000, 7500), 3)), sr)
    hiss *= 0.72 + 0.28 * smooth_random(n, rng, 1.3, sr)
    crackle = np.zeros(n)
    for _ in range(int(dur * rng.uniform(6, 11))):
        m = ns(rng.uniform(0.0003, 0.0016), sr)
        amp = min(4.0, rng.pareto(2.5) + 0.3)
        place(crackle, white(m, rng) * hann(m) * amp, ns(rng.uniform(0, dur), sr))
    crackle = highpass(crackle, 1200, 2, sr)
    hum = sine(rng.choice([50.0, 60.0]), n, sr) * 0.04 + sine(rng.choice([100.0, 120.0]), n, sr) * 0.02
    return hiss + 0.9 * crackle + hum * (0.5 + 0.5 * smooth_random(n, rng, 0.4, sr))


@sound("ambient.static_burst", variants=3, subtitle="Static crackles", attenuation=32, peak=-1.5, fades=(0.001, 0.006))
def s_static_burst(rng, v, sr):
    dur = [0.7, 1.0, 1.35][v]
    n = ns(dur, sr)
    x = white(n, rng)
    out = np.zeros(n)
    pos = 0
    while pos < n:
        seg = ns(rng.uniform(0.02, 0.12), sr)
        chunk = x[pos:pos + seg]
        mode = rng.random()
        if mode < 0.3:
            chunk = bitcrush(chunk * 0.6, int(rng.integers(3, 6)), int(rng.integers(2, 9)))
        elif mode < 0.45:
            chunk = chunk * 0.08
        elif mode < 0.6:
            chunk = chunk * square_raw(rng.uniform(30, 90), len(chunk), sr) * 0.8
        out[pos:pos + len(chunk)] = chunk * rng.uniform(0.6, 1.0)
        pos += seg
    out = spectral(out, r_mul(r_hp(150, 2), r_lp(9000, 2)), sr)
    buzz = square_raw(rng.choice([50.0, 60.0]) * rng.choice([1, 2]), n, sr) * 0.12
    env = env_points(n, [(0, 0.0), (0.004, 1.0), (dur * rng.uniform(0.5, 0.8), 0.85), (dur, 0.0)], sr)
    return (out + iir(buzz, "lp", 900, 0.7, sr=sr)) * env


@sound("ambient.digital_interference", variants=3, subtitle="Interference buzzes", attenuation=24, peak=-2.0)
def s_digital_interference(rng, v, sr):
    dur = [2.6, 3.2, 3.8][v]
    n = ns(dur, sr)
    t = tvec(n, sr)
    # GSM-like TDMA buzz: 217 Hz pulse train with short duty, plus its 8-slot sub-pattern.
    tdma = (((t * 217.0) % 1.0) < 0.125).astype(float)
    tdma = tdma - np.mean(tdma)
    sub = (((t * 1733.0) % 1.0) < 0.5).astype(float) - 0.5
    buzz = tdma + 0.25 * sub * tdma
    gate = np.zeros(n)
    pos = ns(rng.uniform(0.05, 0.2), sr)
    while pos < n:
        burst = ns(rng.choice([0.03, 0.05, 0.08, 0.15, 0.3, 0.6], p=[0.25, 0.2, 0.2, 0.15, 0.12, 0.08]), sr)
        gate[pos:pos + burst] = rng.uniform(0.5, 1.0)
        pos += burst + ns(rng.choice([0.02, 0.04, 0.09, 0.2, 0.45], p=[0.3, 0.25, 0.2, 0.15, 0.1]), sr)
    gate = lowpass(gate, 180, 2, sr)
    out = buzz * gate
    # Data chirps.
    for _ in range(int(rng.integers(2, 6))):
        m = ns(rng.uniform(0.04, 0.16), sr)
        f = np.geomspace(rng.uniform(400, 1200), rng.uniform(1500, 4200), m)
        if rng.random() < 0.5:
            f = f[::-1]
        c = square_raw(f, m, sr) * hann(m) * 0.35
        place(out, bitcrush(c, 4, int(rng.integers(1, 4))), ns(rng.uniform(0, dur - 0.2), sr))
    # Crushed clicks.
    for _ in range(int(dur * 7)):
        m = ns(rng.uniform(0.001, 0.004), sr)
        place(out, np.sign(white(m, rng)) * rng.uniform(0.2, 0.7), ns(rng.uniform(0, dur), sr))
    out = bitcrush(out * 0.7, 6, 2)
    bed = spectral(white(n, rng), r_mul(r_hp(400, 2), r_lp(6000, 2)), sr) * 0.05
    out = spectral(out, r_mul(r_hp(120, 2), r_lp(7000, 2)), sr) + bed
    return out * env_points(n, [(0, 0), (0.05, 1), (dur - 0.15, 1), (dur, 0)], sr)


def low_hum_loop(rng, v, sr, length):
    n = ns(length, sr)
    t = tvec(n, sr)
    base = [41.0, 36.5][v]          # every frequency is a multiple of 1/12 Hz: the 12 s loop is exactly periodic
    parts = [(base, 1.0), (base + 1.0 / 3.0, 0.8), (2 * base + 0.5, 0.36), (3 * base, 0.2),
             (4 * base + 0.25, 0.1), (5 * base, 0.06), (6 * base + 1.0 / 6.0, 0.035),
             (100.0, 0.035), (150.0 + 0.25, 0.02)]
    tone = np.zeros(n)
    for f, a in parts:
        tone += a * np.sin(TAU * f * t + rng.uniform(0, TAU))
    am = 1.0 + 0.16 * np.sin(TAU * t / 6.0) + 0.08 * np.sin(TAU * t / 4.0 + 1.3)
    tone = soft_clip(tone * am * 0.5, 1.6)
    tone = bass_exciter(tone, sr, 0.35, 120.0, circular=True)
    bed = rumble_bed(rng, n, 140.0, sr, circular=True)
    bed *= 1.0 + 0.3 * np.sin(TAU * t / 3.0 + 0.4)
    return tone + 0.18 * bed


@sound("ambient.low_hum", variants=2, subtitle="A low hum", attenuation=32, sr=22050, rms_db=-16.0, peak=-3.0,
       quality=4, loop=True, stream=True)
def s_low_hum(rng, v, sr):
    return low_hum_loop(rng, v, sr, 12.0)


@sound("ambient.rumble", variants=3, subtitle="The ground rumbles", attenuation=48, sr=22050, peak=-1.5)
def s_rumble(rng, v, sr):
    dur = [5.5, 6.5, 8.0][v]
    n = ns(dur, sr)
    bed = rumble_bed(rng, n, rng.uniform(85, 140), sr)
    swell = env_points(n, [(0, 0), (rng.uniform(0.8, 1.8), 1.0), (dur * 0.6, 0.8), (dur, 0)], sr)
    swell *= 0.75 + 0.25 * smooth_random(n, rng, 0.9, sr)
    t = tvec(n, sr)
    f = np.interp(t, [0, dur], [rng.uniform(44, 52), rng.uniform(30, 36)])
    groan = soft_clip(np.sin(phase_of(f, sr)) * 1.5, 2.0) * swell * 0.35
    crumble = np.zeros(n)
    for _ in range(int(dur * 5)):
        m = ns(rng.uniform(0.005, 0.03), sr)
        place(crumble, white(m, rng) * hann(m) * rng.uniform(0.1, 0.5), ns(rng.uniform(0.3, dur - 0.5), sr))
    crumble = bandpass(crumble, 450, 1.2, sr)
    crumble = reverb(crumble, rng, 2.0, wet=0.6, dry=0.4, bright=0.2, sr=sr)[:n]
    return bass_exciter(bed * swell + groan, sr, 0.5, 140.0) + crumble * 0.6 * swell


@sound("ambient.underground", variants=3, subtitle="Something moves below", attenuation=32, peak=-2.0)
def s_underground(rng, v, sr):
    dur = [5.0, 6.0, 6.5][v]
    n = ns(dur, sr)
    out = rumble_bed(rng, n, 160, sr) * 0.25 * (0.7 + 0.3 * smooth_random(n, rng, 0.5, sr))
    if v in (0, 2):
        # A slow groaning creak deep in the rock.
        st = rng.uniform(0.5, 1.5)
        cdur = rng.uniform(1.6, 2.6)
        creak = friction_creak(rng, cdur, [(0, 18), (cdur * 0.5, 34), (cdur, 12)], [210, 330, 470], 12.0, sr=sr)
        creak *= env_points(len(creak), [(0, 0), (0.3, 1), (cdur - 0.4, 0.8), (cdur, 0)], sr)
        place(out, creak / (np.max(np.abs(creak)) + 1e-9) * 0.7, ns(st, sr))
    if v in (0, 1):
        for _ in range(int(rng.integers(2, 4))):
            k = door_knock(rng, 0.8, sr, modes=[(55, 0.12, 1.0), (90, 0.08, 0.7), (143, 0.05, 0.4), (260, 0.02, 0.2)])
            place(out, lowpass(k, 500, 2, sr) * rng.uniform(0.5, 0.9), ns(rng.uniform(0.3, dur - 1.5), sr))
    if v == 1:
        for _ in range(int(rng.integers(3, 6))):
            m = ns(0.12, sr)
            f = np.geomspace(rng.uniform(900, 1300), rng.uniform(1800, 2600), m)
            drip = np.sin(phase_of(f, sr)) * attack_decay(m, 0.001, 0.025, sr)
            place(out, drip * rng.uniform(0.15, 0.35), ns(rng.uniform(0.2, dur - 0.5), sr))
    if v == 2:
        grind = np.zeros(n)
        for _ in range(int(dur * 40)):
            m = ns(rng.uniform(0.003, 0.02), sr)
            place(grind, white(m, rng) * hann(m) * rng.uniform(0.1, 1.0), ns(rng.uniform(1.0, dur - 1.0), sr))
        grind = bandpass(grind, 260, 1.5, sr) * env_points(n, [(0, 0), (1.5, 0.8), (dur - 1.2, 0.5), (dur, 0)], sr)
        out += grind * 0.6
    out = reverb(out, rng, 2.8, wet=0.45, dry=0.8, bright=0.25, sr=sr)
    return out[:n + ns(1.0, sr)]


@sound("ambient.reversed", variants=3, subtitle="Sound runs backwards", attenuation=24, peak=-2.0, fades=(0.05, 0.006))
def s_reversed(rng, v, sr):
    if v == 0:
        src = whisper(rng, 0.9, rate=1.2, sr=sr)
        rt = 2.6
    elif v == 1:
        n = ns(3.0, sr)
        t = tvec(n, sr)
        src = np.zeros(n)
        for f, tau, a in [(311, 2.2, 1.0), (806, 1.4, 0.6), (1463, 0.9, 0.45), (2214, 0.6, 0.3), (3120, 0.35, 0.2)]:
            src += a * np.exp(-t / tau) * np.sin(TAU * f * rng.uniform(0.99, 1.01) * t)
        rt = 2.0
    else:
        n = ns(2.5, sr)
        src = np.zeros(n)
        for midi in [57, 60, 64, 65]:
            f = 440.0 * 2 ** ((midi - 69) / 12.0) * rng.uniform(0.992, 1.008)
            src += piano_note(rng, f, 2.5, sr, velocity=0.6)[:n]
        rt = 2.2
    src = fade(src, 0.0, 0.5, sr)
    wet = reverb(src, rng, rt, wet=0.8, dry=0.5, bright=0.4, sr=sr)
    out = wet[::-1].copy()
    lead = ns(rng.uniform(0.1, 0.3), sr)
    return np.concatenate([np.zeros(lead), out])


@sound("ambient.whisper", variants=4, subtitle="Whispering", attenuation=24, peak=-3.0, fades=(0.02, 0.08))
def s_whisper(rng, v, sr):
    dur = [2.2, 2.8, 3.4, 2.5][v]
    scale = [1.0, 0.92, 1.08, 0.97][v]
    w = whisper(rng, dur, rate=rng.uniform(0.9, 1.25), scale=scale, sr=sr)
    w = spectral(w, r_mul(r_hp(220, 2), r_lp(9000, 2)), sr)
    return reverb(w, rng, 0.7, wet=0.18, dry=1.0, bright=0.5, sr=sr)


@sound("ambient.voices_distant", variants=3, subtitle="Distant voices", attenuation=40, peak=-3.0, fades=(0.2, 0.4))
def s_voices_distant(rng, v, sr):
    dur = [4.5, 5.5, 6.0][v]
    n = ns(dur, sr)
    out = np.zeros(n)
    voices = [2, 3, 4][v]
    for k in range(voices):
        f0 = rng.choice([98, 112, 128, 165, 190, 215]) * rng.uniform(0.95, 1.05)
        vd = rng.uniform(1.4, dur - 0.4)
        vs = rng.uniform(0, dur - vd)
        sig = voice(rng, vd, f0=f0, rate=rng.uniform(0.85, 1.15), scale=rng.uniform(0.9, 1.12), sr=sr)
        place(out, sig / (np.max(np.abs(sig)) + 1e-9) * rng.uniform(0.5, 1.0), ns(vs, sr))
    out = spectral(out, r_mul(r_hp(160, 2), r_lp(rng.uniform(1400, 2200), 3)), sr)
    out *= 0.7 + 0.3 * smooth_random(n, rng, 0.8, sr)
    out = reverb(out, rng, 2.2, wet=0.75, dry=0.45, bright=0.2, predelay=0.04, sr=sr)
    return out[:n + ns(1.2, sr)]


@sound("ambient.heartbeat", variants=2, subtitle="Heartbeat", attenuation=16, sr=22050, peak=-1.5, fades=(0.002, 0.2))
def s_heartbeat(rng, v, sr):
    bpm = [64.0, 79.0][v]
    beats = [5, 6][v]
    period = 60.0 / bpm
    dur = period * beats + 0.6
    n = ns(dur, sr)
    out = np.zeros(n)
    for b in range(beats):
        if v == 1 and b == 3:
            continue   # a skipped beat
        jitter = rng.uniform(-0.03, 0.03) * (2.0 if v == 1 else 1.0)
        start = b * period + jitter + 0.05
        m = ns(0.35, sr)
        lub = thump(rng, m, rng.uniform(64, 70), 42, 0.05, 0.075, sr)
        dub = thump(rng, m, rng.uniform(72, 80), 50, 0.04, 0.055, sr)
        click = iir(white(ns(0.003, sr), rng) * 0.3, "lp", 900, 0.7, sr=sr)
        place(out, lub + np.pad(click, (0, m - len(click))), ns(start, sr))
        place(out, dub * 0.7, ns(start + 0.17 + rng.uniform(-0.01, 0.01), sr))
    out = soft_clip(out * 1.4, 1.5)
    out = bass_exciter(out, sr, 0.35, 110.0)
    return lowpass(out, 650, 2, sr)


@sound("ambient.wrong_wind", variants=3, subtitle="Wind moans", attenuation=32, peak=-3.0, fades=(0.6, 0.8))
def s_wrong_wind(rng, v, sr):
    dur = [5.5, 6.5, 7.5][v]
    n = ns(dur, sr)
    src = coloured(n, rng, 1.0, sr)
    rate = 50
    ctrl_n = int(dur * rate) + 2
    ct = np.arange(ctrl_n) / rate
    gust = np.clip(0.55 + 0.45 * smooth_random(ctrl_n, rng, 0.45, sr=rate), 0.05, 1.0)
    # The howl: a narrow resonance whose pitch rises and falls with the gusts...
    howl = 260.0 + 520.0 * gust ** 1.5
    # ...and two broader ones drifting between vowel formants, so the wind almost speaks.
    vowel_seq = [VOWELS[k] for k in rng.choice(["o", "u", "a", "o", "@", "u"], size=5)]
    knot_t = np.linspace(0, dur, len(vowel_seq))
    f1 = np.interp(ct, knot_t, [vv[0] for vv in vowel_seq]) * 0.8
    f2 = np.interp(ct, knot_t, [vv[1] for vv in vowel_seq]) * 0.8

    def gain(t, f):
        idx = np.clip((t * rate).astype(int), 0, ctrl_n - 1)
        ff = f[None, :]
        gu = gust[idx][:, None]
        hw = howl[idx][:, None]
        g = 0.1 / (1 + (ff / (700.0 + 900.0 * gu)) ** 2)
        g = g + np.exp(-0.5 * ((ff - hw) / (hw * 0.07)) ** 2)
        g = g + 0.45 * np.exp(-0.5 * ((ff - f1[idx][:, None]) / 90.0) ** 2)
        g = g + 0.35 * np.exp(-0.5 * ((ff - f2[idx][:, None]) / 140.0) ** 2)
        return g * gu ** 1.2
    wind = stft_shape(src, gain, n_fft=2048, hop=512, sr=sr)
    t = tvec(n, sr)
    # A whistle that glides in stepped, almost vocal intervals.
    steps = np.cumsum(rng.choice([-2, -1, 1, 2, 3], size=8))
    knots = np.linspace(0, dur, len(steps))
    semis = np.interp(t, knots, steps)
    whistle_f = 760.0 * 2 ** (semis / 12.0) * (1 + 0.006 * np.sin(TAU * 5.2 * t))
    whistle = np.sin(phase_of(whistle_f, sr)) * (0.5 + 0.5 * smooth_random(n, rng, 0.7, sr)) ** 2
    out = wind / (rms(wind) + 1e-9) * 0.25 + whistle * 0.09
    if v == 1:   # the wind stops dead for a moment, then carries on
        k0 = ns(dur * rng.uniform(0.45, 0.6), sr)
        k1 = k0 + ns(rng.uniform(0.25, 0.4), sr)
        gate = np.ones(n)
        gate[k0:k1] = 0.02
        out *= lowpass(gate, 60, 2, sr)
    if v == 2:   # a stretch of it runs backwards
        k0 = ns(dur * 0.4, sr)
        k1 = k0 + ns(1.2, sr)
        seg = out[k0:k1][::-1] * hann(k1 - k0) ** 0.3
        out[k0:k1] = out[k0:k1] * (1 - hann(k1 - k0) ** 0.3) + seg
    return out


# ---- One-off events ----------------------------------------------------------------------------

@sound("event.footsteps_distorted", variants=3, subtitle="Footsteps", attenuation=32, peak=-2.0)
def s_footsteps_distorted(rng, v, sr):
    steps = [5, 6, 4][v]
    times = np.cumsum(rng.uniform(0.34, 0.62, steps)) - 0.3
    dur = times[-1] + 0.9
    n = ns(dur, sr)
    out = np.zeros(n)
    stutter_at = int(rng.integers(1, steps))
    for i, st in enumerate(times):
        fs = footstep(rng, weight=rng.uniform(0.8, 1.3), crunch=rng.uniform(0.7, 1.2), sr=sr)
        fs = resample_pitch(fs, rng.uniform(0.62, 0.8))
        if i == stutter_at:
            g = fs[:ns(0.045, sr)]
            fs = np.concatenate([stutter(g * hann(len(g)) ** 0.2, int(rng.integers(3, 6)), 0.9), fs])
        if rng.random() < 0.3:
            fs = bitcrush(fs / (np.max(np.abs(fs)) + 1e-9) * 0.8, 5, 3)
        place(out, fs * rng.uniform(0.7, 1.0), ns(st, sr))
    out = spectral(out, r_mul(r_hp(70, 2), r_lp(3000, 2)), sr)
    return reverb(out, rng, 1.3, wet=0.3, dry=1.0, bright=0.3, sr=sr)[:n + ns(0.6, sr)]


@sound("event.footsteps_player", variants=3, subtitle="Footsteps", attenuation=24, peak=-2.0)
def s_footsteps_player(rng, v, sr):
    steps = [4, 5, 3][v]
    pace = rng.uniform(0.44, 0.5)
    times = 0.05 + np.arange(steps) * pace + rng.uniform(-0.02, 0.02, steps)
    if v == 2:
        times[-1] += 0.35   # a hesitation, then one last step
    dur = times[-1] + 0.6
    n = ns(dur, sr)
    out = np.zeros(n)
    for st in times:
        place(out, footstep(rng, weight=rng.uniform(0.9, 1.1), crunch=rng.uniform(1.0, 1.4), sr=sr, surface="grass"),
              ns(st, sr), rng.uniform(0.8, 1.0))
    return reverb(out, rng, 0.5, wet=0.12, dry=1.0, bright=0.5, sr=sr)[:n + ns(0.3, sr)]


@sound("event.explosion_distant", variants=3, subtitle="Distant explosion", attenuation=64, sr=32000, peak=-1.2, fades=(0.001, 0.6))
def s_explosion_distant(rng, v, sr):
    dur = [4.5, 5.5, 6.0][v]
    n = ns(dur, sr)
    out = np.zeros(n)
    boom = thump(rng, ns(1.8, sr), rng.uniform(60, 75), 28, 0.12, 0.4, sr)
    m = ns(1.0, sr)
    blast = white(m, rng) * attack_decay(m, 0.004, 0.16, sr)
    blast = iir(blast, "lp", rng.uniform(650, 900), 0.7, sr=sr) + iir(blast, "lp", 180, 0.9, sr=sr) * 2.0
    place(out, boom, 0)
    place(out, blast * 1.6, 0)
    tail = rumble_bed(rng, n, rng.uniform(260, 360), sr) * attack_decay(n, 0.2, rng.uniform(1.0, 1.6), sr)
    tail *= 0.75 + 0.25 * smooth_random(n, rng, 2.0, sr)
    out += tail * 0.5
    crackle = np.zeros(n)
    for _ in range(40):
        m = ns(rng.uniform(0.002, 0.01), sr)
        place(crackle, white(m, rng) * hann(m) * rng.uniform(0.1, 0.6), ns(rng.uniform(0.05, 1.4) ** 1.5, sr))
    out += lowpass(crackle, 1500, 2, sr) * 0.35
    for delay, g in [(rng.uniform(0.35, 0.6), 0.35), (rng.uniform(0.9, 1.4), 0.2)]:
        echo = lowpass(out[:ns(1.2, sr)], 400, 2, sr) * g
        place(out, echo, ns(delay, sr))
    out = bass_exciter(out, sr, 0.6, 150.0)
    return reverb(out, rng, 2.5, wet=0.35, dry=1.0, bright=0.15, sr=sr)[:n]


@sound("event.knock", variants=3, subtitle="Knocking", attenuation=24, peak=-2.0, fades=(0.001, 0.3))
def s_knock(rng, v, sr):
    pattern = [[0.0, 0.26, 0.52], [0.0, 0.22, 1.05], [0.0, 0.16, 0.32, 0.48, 1.35]][v]
    strengths = [[1.0, 0.95, 1.0], [1.0, 0.9, 0.7], [0.8, 0.85, 0.8, 0.9, 0.55]][v]
    dur = pattern[-1] + 1.0
    n = ns(dur, sr)
    out = np.zeros(n)
    for st, s in zip(pattern, strengths):
        k = door_knock(rng, 1.0, sr)
        place(out, k / (np.max(np.abs(k)) + 1e-9) * s * rng.uniform(0.92, 1.0), ns(st + 0.02 + rng.uniform(-0.012, 0.012), sr))
    return reverb(out, rng, 0.6, wet=0.22, dry=1.0, bright=0.35, sr=sr)[:n]


@sound("event.glitch", variants=4, subtitle="Glitching", attenuation=24, peak=-2.0, fades=(0.001, 0.004))
def s_glitch(rng, v, sr):
    if v == 0:
        parts = []
        f = rng.uniform(380, 520)
        for k in range(int(rng.integers(9, 14))):
            m = ns(0.024, sr)
            g = square_raw(f, m, sr, duty=rng.uniform(0.2, 0.5)) * 0.6 + white(m, rng) * 0.25
            parts.append(bitcrush(g * hann(m) ** 0.1, 4, 2))
            f *= rng.uniform(0.9, 0.98)
        return np.concatenate(parts)
    if v == 1:
        n = ns(0.38, sr)
        x = white(n, rng) * 0.7
        hold = np.interp(np.arange(n), [0, n], [1, 28]).astype(int)
        out = np.zeros(n)
        i = 0
        while i < n:
            h = max(1, hold[i])
            out[i:i + h] = x[i]
            i += h
        return bitcrush(out, 4) * env_points(n, [(0, 1), (0.3, 0.8), (0.38, 0)], sr)
    if v == 2:
        n = ns(0.55, sr)
        out = np.zeros(n)
        pos = 0
        while pos < n:
            m = ns(rng.uniform(0.008, 0.03), sr)
            tone = square_raw(rng.choice([330, 440, 587, 880, 1175, 1760, 2349]) * rng.uniform(0.97, 1.03), m, sr)
            out[pos:pos + m] = tone[:max(0, min(m, n - pos))] * (0 if rng.random() < 0.15 else rng.uniform(0.4, 0.8))
            pos += m
        return bitcrush(spectral(out, r_lp(6000, 2), sr), 5, 2)
    n = ns(0.75, sr)
    out = np.zeros(n)
    k = ns(0.002, sr)
    out[:k] = 1.0
    swell = white(ns(0.22, sr), rng) * np.linspace(0, 1, ns(0.22, sr)) ** 3
    place(out, highpass(swell, 3000, 2, sr) * 0.7, ns(0.03, sr))
    g = white(ns(0.03, sr), rng) * 0.6 + square_raw(250, ns(0.03, sr), sr) * 0.4
    place(out, stutter(bitcrush(g, 4, 3), 8, 0.85), ns(0.26, sr))
    return out


@sound("event.blackout", variants=2, subtitle="Power fails", attenuation=24, peak=-1.5, fades=(0.001, 0.2))
def s_blackout(rng, v, sr):
    dur = [2.2, 2.0][v]
    n = ns(dur, sr)
    out = np.zeros(n)
    pre = ns(0.18, sr)
    hum = (sine(50, pre, sr) * 0.3 + sine(100, pre, sr) * 0.15 + sine(150, pre, sr) * 0.1) * np.linspace(0.6, 1, pre)
    place(out, hum, 0)
    clunk = door_knock(rng, 1.2, sr, modes=[(70, 0.08, 1.0), (180, 0.04, 0.5), (1900, 0.01, 0.25), (3400, 0.006, 0.2)])
    place(out, clunk, pre)
    if v == 0:
        m = ns(1.5, sr)
        f = 40 + 1800 * np.exp(-tvec(m, sr) / 0.28)
        whine = (np.sin(phase_of(f, sr)) + 0.3 * np.sin(phase_of(f * 2.01, sr))) * attack_decay(m, 0.01, 0.5, sr)
        place(out, whine * 0.35, pre)
    else:
        m = ns(1.4, sr)
        place(out, thump(rng, m, 82, 24, 0.15, 0.45, sr) * 1.0, pre)
        ring = sine(rng.uniform(4800, 6200), ns(1.6, sr), sr) * env_points(ns(1.6, sr), [(0, 0), (0.3, 0.02), (1.6, 0)], sr)
        place(out, ring, pre + ns(0.2, sr))
    return bass_exciter(out, sr, 0.4, 130.0)


@sound("event.screen_tear", variants=3, subtitle="Something tears", attenuation=24, peak=-2.0, fades=(0.001, 0.02))
def s_screen_tear(rng, v, sr):
    dur = [0.5, 0.7, 0.95][v]
    n = ns(dur, sr)
    src = white(n, rng)
    chop = (smooth_random(n, rng, rng.uniform(120, 260), sr) > rng.uniform(-0.2, 0.3)).astype(float)
    src *= lowpass(chop, 2500, 1, sr) + 0.15
    f_hi, f_lo = rng.uniform(3500, 6000), rng.uniform(250, 500)

    def gain(t, f):
        centre = f_lo + (f_hi - f_lo) * np.exp(-np.clip(t, 0, None) / (dur * 0.3))
        return 1.0 / np.sqrt(1 + 4.0 * (f[None, :] / centre[:, None] - centre[:, None] / np.maximum(f[None, :], 1)) ** 2)
    out = stft_shape(src, gain, n_fft=512, hop=128, sr=sr)
    for _ in range(int(rng.integers(3, 8))):
        k = ns(rng.uniform(0, dur), sr)
        place(out, np.sign(white(ns(0.002, sr), rng)) * 0.6, k)
    out = bitcrush(out / (np.max(np.abs(out)) + 1e-9) * 0.8, 6, 2)
    return out * env_points(n, [(0, 0.2), (0.01, 1), (dur * 0.7, 0.8), (dur, 0)], sr)


@sound("event.freeze", variants=2, subtitle="Sound stutters", attenuation=24, peak=-2.0, fades=(0.001, 0.003))
def s_freeze(rng, v, sr):
    def chunk_source(m):
        t = tvec(m, sr)
        s = 0.5 * np.sin(TAU * rng.uniform(180, 320) * t) + 0.35 * white(m, rng)
        s += 0.4 * np.sin(TAU * rng.uniform(900, 1400) * t) * np.exp(-t / 0.02)
        return spectral(s, r_lp(5000, 2), sr)
    m = ns(rng.uniform(0.045, 0.07), sr)
    loop1 = chunk_source(m)
    loop1 *= np.minimum(1, np.minimum(np.arange(m), m - np.arange(m)) / 30.0)
    out = stutter(loop1, int(rng.integers(16, 24)), 0.995)
    if v == 1:
        m2 = ns(rng.uniform(0.03, 0.05), sr)
        loop2 = chunk_source(m2)
        loop2 *= np.minimum(1, np.minimum(np.arange(m2), m2 - np.arange(m2)) / 30.0)
        out = np.concatenate([out, np.zeros(ns(0.12, sr)), stutter(loop2, int(rng.integers(10, 16)), 0.99)])
    return out


@sound("event.disconnect", variants=2, subtitle="Connection lost", attenuation=16, peak=-2.0, fades=(0.001, 0.01))
def s_disconnect(rng, v, sr):
    n = ns([1.5, 1.8][v], sr)
    out = np.zeros(n)
    if v == 0:
        # A data beep that sags, chokes into a stutter and dies; a relay click; a fizz that cuts.
        m = ns(0.34, sr)
        f = 1046.5 * np.exp(-tvec(m, sr) * np.log(2) * 2.5)
        beep = square_raw(f, m, sr, duty=0.3) * env_points(m, [(0, 0), (0.004, 1), (0.26, 0.8), (0.34, 0)], sr)
        k = ns(0.16, sr)
        beep[k:] *= square_raw(38.0, m - k, sr, 0.5) * 0.5 + 0.5
        place(out, bitcrush(beep * 0.5, 5, 2), 0)
        clk = door_knock(rng, 0.7, sr, modes=[(120, 0.03, 1.0), (2600, 0.004, 0.5)])
        place(out, clk, ns(0.36, sr))
        m = ns(0.7, sr)
        fizz = spectral(white(m, rng), r_mul(r_hp(800, 2), r_lp(7000, 2)), sr) * np.linspace(1, 0, m) ** 2 * 0.3
        place(out, fizz, ns(0.38, sr))
    else:
        m = ns(0.9, sr)
        x = white(m, rng)
        x = varispeed(x, np.linspace(1.0, 0.15, m))
        x = spectral(x, r_lp(5000, 2), sr) * np.linspace(1, 0.2, len(x))
        place(out, x * 0.5, 0)
        place(out, thump(rng, ns(0.6, sr), 110, 40, 0.05, 0.12, sr), ns(0.85, sr))
    return out


@sound("event.sky_flicker", variants=3, subtitle="The sky crackles", attenuation=32, peak=-3.0, fades=(0.001, 0.02))
def s_sky_flicker(rng, v, sr):
    dur = [0.35, 0.5, 0.65][v]
    n = ns(dur, sr)
    t = tvec(n, sr)
    buzz = np.abs(np.sin(TAU * rng.choice([50.0, 60.0]) * t)) - 0.63
    buzz = spectral(buzz, r_lp(4000, 2), sr)
    gate = np.zeros(n)
    pos = 0
    while pos < n:
        on = ns(rng.uniform(0.015, 0.07), sr)
        gate[pos:pos + on] = rng.uniform(0.4, 1.0)
        pos += on + ns(rng.uniform(0.01, 0.06), sr)
    out = buzz * gate
    for _ in range(int(rng.integers(4, 10))):
        m = ns(rng.uniform(0.001, 0.006), sr)
        place(out, highpass(white(m, rng), 2000, 2, sr) * rng.uniform(0.4, 1.0), ns(rng.uniform(0, dur), sr))
    tink = sine(rng.uniform(3200, 4200), ns(0.12, sr), sr) * exp_decay(ns(0.12, sr), 0.025, sr) * 0.2
    place(out, tink, ns(rng.uniform(0, dur * 0.5), sr))
    return out


def breath_cycle(rng, inhale, exhale, gap, sr, rasp=0.0, shaky=0.0, darkness=1.0):
    n_in, n_gap, n_out = ns(inhale, sr), ns(gap, sr), ns(exhale, sr)
    n = n_in + n_gap + n_out
    src = white(n, rng)
    env = np.zeros(n)
    env[:n_in] = np.sin(np.linspace(0, np.pi, n_in)) ** 1.5 * np.linspace(0.6, 1.0, n_in)
    env[n_in + n_gap:] = np.sin(np.linspace(0, np.pi, n_out)) ** 1.2 * np.linspace(1.0, 0.5, n_out) * 1.15
    if shaky > 0:
        env *= 1 + shaky * np.sin(TAU * rng.uniform(6, 9) * tvec(n, sr)) * (tvec(n, sr) > inhale)
    if rasp > 0:
        rr = 1 + rasp * (square_raw(rng.uniform(32, 48), n, sr, 0.3) * 0.5 + 0.5)
        rr[:n_in] = 1
        src = src * rr
    in_c, out_c = 1400 * darkness, 700 * darkness

    def gain(t, f):
        frac = np.clip(t / (n / sr), 0, 1)[:, None]
        inhaling = (t < inhale)[:, None]
        centre = np.where(inhaling, in_c + 600 * frac, out_c)
        ff = f[None, :]
        body = np.exp(-0.5 * ((ff - centre) / (centre * 0.7)) ** 2)
        air = np.exp(-0.5 * ((ff - 3800 * darkness) / (2200 * darkness)) ** 2) * np.where(inhaling, 0.45, 0.28)
        g = body + air + 0.15 * np.exp(-0.5 * ((ff - 2600 * darkness) / 600) ** 2)
        return g / (1 + (60.0 / np.maximum(ff, 1)) ** 4)
    y = stft_shape(src * env, gain, sr=sr)
    clicks = np.zeros(n)
    for _ in range(int(rng.integers(1, 4))):
        m = ns(0.003, sr)
        place(clicks, white(m, rng) * hann(m) * 0.2, ns(rng.uniform(0, 0.08), sr))
    return y + bandpass(clicks, 2500, 2, sr)


@sound("event.breath_behind", variants=3, subtitle="Something breathes", attenuation=16, peak=-3.0, fades=(0.05, 0.15))
def s_breath_behind(rng, v, sr):
    parts = []
    for k in range([1, 1, 2][v]):
        parts.append(breath_cycle(rng, rng.uniform(0.8, 1.2), rng.uniform(1.1, 1.6), rng.uniform(0.1, 0.3), sr,
                                  shaky=0.35 if (v == 2 and k == 1) else 0.0, darkness=[1.0, 0.85, 0.95][v]))
        parts.append(np.zeros(ns(rng.uniform(0.2, 0.5), sr)))
    out = np.concatenate(parts)
    return reverb(out, rng, 0.35, wet=0.08, dry=1.0, sr=sr)


# ---- The moon --------------------------------------------------------------------------------

@sound("moon.crack", variants=3, subtitle="The sky cracks", attenuation=64, peak=-1.5, fades=(0.0005, 0.5))
def s_moon_crack(rng, v, sr):
    dur = [1.6, 2.0, 2.4][v]
    n = ns(dur, sr)
    out = np.zeros(n)
    place(out, ice_crack(rng, sr, size=1.2), 0)
    for _ in range(int(rng.integers(3, 8))):
        place(out, ice_crack(rng, sr, size=rng.uniform(0.3, 0.7)) * rng.uniform(0.15, 0.45), ns(rng.uniform(0.05, dur * 0.5), sr))
    place(out, thump(rng, ns(0.9, sr), 55, 30, 0.1, 0.3, sr) * 0.4, ns(0.005, sr))
    return reverb(out, rng, 2.6, wet=0.38, dry=1.0, bright=0.6, predelay=0.03, sr=sr)[:n + ns(0.8, sr)]


@sound("moon.fragment", variants=3, subtitle="Something breaks away above", attenuation=64, peak=-2.0, fades=(0.0005, 0.6))
def s_moon_fragment(rng, v, sr):
    dur = [1.8, 2.2, 2.5][v]
    n = ns(dur, sr)
    out = np.zeros(n)
    place(out, ice_crack(rng, sr, size=0.8) * 0.8, 0)
    for _ in range(int(rng.integers(10, 22))):
        place(out, glass_tinkle(rng, rng.uniform(2200, 6000), rng.uniform(0.08, 0.35), sr, rng.uniform(0.08, 0.3)),
              ns(0.03 + rng.uniform(0, 0.45) ** 1.3, sr))
    m = ns(1.6, sr)
    t = tvec(m, sr)
    f = rng.uniform(1100, 1500) * 2 ** (-t * rng.uniform(0.35, 0.6))
    fall = (np.sin(phase_of(f, sr)) + 0.4 * np.sin(phase_of(f * 2.76, sr)) + 0.2 * np.sin(phase_of(f * 5.4, sr)))
    fall *= attack_decay(m, 0.02, 0.5, sr) * 0.22
    place(out, fall, ns(0.12, sr))
    return reverb(out, rng, 2.8, wet=0.42, dry=1.0, bright=0.6, predelay=0.04, sr=sr)[:n + ns(0.8, sr)]


@sound("moon.shatter", variants=2, subtitle="The sky shatters", attenuation=96, peak=-1.2, fades=(0.0005, 1.0))
def s_moon_shatter(rng, v, sr):
    dur = [5.0, 6.0][v]
    n = ns(dur, sr)
    out = np.zeros(n)
    for k in range(3):
        place(out, ice_crack(rng, sr, size=1.3) * rng.uniform(0.7, 1.0), ns(k * rng.uniform(0.04, 0.12), sr))
    burst = white(ns(0.9, sr), rng) * attack_decay(ns(0.9, sr), 0.01, 0.2, sr)
    place(out, spectral(burst, r_mul(r_hp(1800, 2), r_lp(11000, 2)), sr) * 0.35, ns(0.15, sr))
    for _ in range(int(rng.integers(70, 120))):
        place(out, glass_tinkle(rng, rng.uniform(1500, 7000), rng.uniform(0.05, 0.3), sr, rng.uniform(0.06, 0.35)),
              ns(0.15 + rng.uniform(0, 1.0) ** 2 * 1.4, sr))
    place(out, thump(rng, ns(2.0, sr), 72, 26, 0.18, 0.6, sr) * 1.1, ns(0.12, sr))
    tail = rumble_bed(rng, n, 160, sr) * attack_decay(n, 0.3, 1.4, sr)
    out += tail * 0.45
    out = bass_exciter(out, sr, 0.35, 140.0)
    return reverb(out, rng, 3.2, wet=0.4, dry=1.0, bright=0.5, predelay=0.04, sr=sr)[:n]


@sound("moon.gone", variants=1, subtitle="The sky goes quiet", attenuation=128, sr=32000, peak=-1.2, stream=True,
       quality=3, fades=(0.001, 2.0))
def s_moon_gone(rng, v, sr):
    dur = 13.0
    n = ns(dur, sr)
    t = tvec(n, sr)
    out = np.zeros(n)
    place(out, thump(rng, ns(3.0, sr), 62, 22, 0.3, 0.9, sr) * 1.2, 0)
    whoomp = lowpass(white(ns(2.0, sr), rng), 220, 2, sr) * attack_decay(ns(2.0, sr), 0.08, 0.5, sr)
    place(out, whoomp * 1.4, 0)
    bed = rumble_bed(rng, n, 220, sr)
    bed *= env_points(n, [(0, 0), (0.6, 1.0), (4.0, 0.85), (8.0, 0.55), (dur, 0)], sr)
    bed *= 0.8 + 0.2 * smooth_random(n, rng, 0.5, sr)
    out += bed * 0.8
    drone = np.zeros(n)
    for ratio, amp in [(1.0, 1.0), (1.498, 0.5), (2.13, 0.35), (2.93, 0.2), (4.07, 0.12)]:
        f = 196.0 * ratio * 2 ** (-t / dur * 1.2)
        drone += amp * np.sin(phase_of(f * (1 + 0.003 * np.sin(TAU * 0.13 * t * ratio)), sr))
    drone *= env_points(n, [(0, 0), (1.5, 0.0), (4.0, 0.3), (9.0, 0.24), (dur, 0)], sr)
    out = bass_exciter(out, sr, 0.4, 140.0) + drone
    for _ in range(6):
        place(out, ice_crack(rng, sr, size=0.6) * rng.uniform(0.05, 0.15), ns(rng.uniform(0.2, 2.5), sr))
    return reverb(out, rng, 3.5, wet=0.35, dry=1.0, bright=0.3, predelay=0.05, sr=sr)[:n]


# ---- NULLROOT ---------------------------------------------------------------------------------

@sound("nullroot.idle", variants=3, subtitle="Something wet creaks", attenuation=20, peak=-3.0, fades=(0.05, 0.2))
def s_nullroot_idle(rng, v, sr):
    dur = [2.2, 2.8, 3.4][v]
    n = ns(dur, sr)
    out = np.zeros(n)
    for _ in range(int(rng.integers(1, 3))):
        cd = rng.uniform(0.6, 1.4)
        creak = friction_creak(rng, cd, [(0, rng.uniform(15, 30)), (cd * 0.5, rng.uniform(40, 70)), (cd, rng.uniform(10, 25))],
                               [rng.uniform(260, 420), rng.uniform(600, 900)], rng.uniform(8, 14), sr=sr)
        creak *= hann(len(creak)) ** 0.5
        place(out, creak / (np.max(np.abs(creak)) + 1e-9) * rng.uniform(0.4, 0.8), ns(rng.uniform(0, dur - cd), sr))
    sq = squelch(rng, dur, rng.uniform(500, 900), rng.uniform(6, 12), sr)
    out += sq / (np.max(np.abs(sq)) + 1e-9) * 0.45 * (0.6 + 0.4 * smooth_random(n, rng, 1.5, sr))
    t = tvec(n, sr)
    breath = lowpass(white(n, rng), 300, 2, sr) * (0.5 + 0.5 * np.sin(TAU * rng.uniform(0.5, 0.9) * t - 1.5)) ** 2
    out += breath * 0.25
    out = compress(out, sr, threshold_db=-16.0, ratio=2.5)
    return reverb(out, rng, 0.8, wet=0.2, dry=1.0, bright=0.3, sr=sr)


@sound("nullroot.reach", variants=3, subtitle="Something stretches", attenuation=20, peak=-2.0, fades=(0.01, 0.1))
def s_nullroot_reach(rng, v, sr):
    dur = [0.9, 1.2, 1.5][v]
    n = ns(dur, sr)
    creak = friction_creak(rng, dur, [(0, 25), (dur, rng.uniform(110, 170))],
                           [rng.uniform(380, 520), rng.uniform(900, 1300)], 9.0, sr=sr)
    creak = creak / (np.max(np.abs(creak)) + 1e-9) * env_points(n, [(0, 0), (0.1, 0.8), (dur * 0.85, 1.0), (dur, 0)], sr)
    slither = bandpass(white(n, rng), rng.uniform(1500, 2500), 1.5, sr)
    slither *= np.clip(smooth_random(n, rng, 40, sr) * 1.5, 0, 1) * env_points(n, [(0, 0), (0.15, 1), (dur, 0.3)], sr)
    whoosh = lowpass(white(n, rng), 500, 2, sr) * np.sin(np.linspace(0, np.pi, n)) ** 2
    sq = squelch(rng, dur, 700, 10, sr)
    return creak * 0.8 + slither / (rms(slither) + 1e-9) * 0.06 + whoosh * 0.4 + sq / (np.max(np.abs(sq)) + 1e-9) * 0.25


@sound("nullroot.grab", variants=3, subtitle="Something grabs", attenuation=24, peak=-1.2, fades=(0.0005, 0.08))
def s_nullroot_grab(rng, v, sr):
    dur = [0.7, 0.9, 1.1][v]
    n = ns(dur, sr)
    out = np.zeros(n)
    snap = highpass(white(ns(0.012, sr), rng), 1200, 2, sr) * np.exp(-np.linspace(0, 6, ns(0.012, sr)))
    place(out, snap * 1.2, 0)
    place(out, chirp_exp(ns(0.06, sr), 2400, 180, 0.012, sr) * attack_decay(ns(0.06, sr), 0.0005, 0.02, sr) * 0.8, 0)
    place(out, thump(rng, ns(0.4, sr), 95, 45, 0.03, 0.08, sr) * 0.9, 0)
    cd = dur - 0.08
    squeeze = friction_creak(rng, cd, [(0, 40), (cd, rng.uniform(140, 220))], [rng.uniform(300, 420), rng.uniform(700, 950)], 10.0, sr=sr)
    squeeze = squeeze / (np.max(np.abs(squeeze)) + 1e-9) * env_points(len(squeeze), [(0, 0), (0.05, 0.9), (cd, 0)], sr)
    place(out, squeeze * 0.8, ns(0.05, sr))
    sq = squelch(rng, dur, 900, 25, sr)
    out += sq / (np.max(np.abs(sq)) + 1e-9) * 0.45 * env_points(n, [(0, 1), (dur, 0.2)], sr)
    out = compress(out, sr, threshold_db=-16.0, ratio=4.0, window=0.008)
    return reverb(out, rng, 0.5, wet=0.12, dry=1.0, sr=sr)


@sound("nullroot.pulse", variants=3, subtitle="The ground pulses", attenuation=24, sr=32000, peak=-1.5, fades=(0.001, 0.3))
def s_nullroot_pulse(rng, v, sr):
    dur = [1.2, 1.6, 2.0][v]
    n = ns(dur, sr)
    out = np.zeros(n)
    pulses = [1, 2, 2][v]
    for k in range(pulses):
        st = k * rng.uniform(0.32, 0.45)
        place(out, thump(rng, ns(0.9, sr), rng.uniform(58, 66), 34, 0.06, 0.18, sr) * (1.0 if k == 0 else 0.65), ns(st, sr))
        sq = squelch(rng, 0.35, 500, 30, sr)
        place(out, sq / (np.max(np.abs(sq)) + 1e-9) * 0.3 * hann(len(sq)), ns(st + 0.02, sr))
    gurgle = squelch(rng, dur, 260, 12, sr)
    out += gurgle / (np.max(np.abs(gurgle)) + 1e-9) * 0.15 * env_points(n, [(0, 0), (0.3, 1), (dur, 0)], sr)
    out = soft_clip(out * 1.3, 1.3)
    out = bass_exciter(out, sr, 0.5, 130.0)
    return lowpass(out, 3000, 2, sr)


@sound("nullroot.capture", variants=1, subtitle="Something tightens", attenuation=16, peak=-2.0, rms_db=-15.0,
       loop=True, quality=4)
def s_nullroot_capture(rng, v, sr):
    length = 6.0           # 9 throbs at 1.5 Hz
    n = ns(length, sr)

    def period_source():
        t = tvec(n, sr)
        src = np.zeros(n)
        for k in range(9):
            st = ns(k / 1.5, sr)
            place(src, thump(rng, ns(0.5, sr), 60, 38, 0.05, 0.12, sr) * (1.0 if k % 3 == 0 else 0.7), st, wrap=True)
        creak_rate = 55 + 25 * np.sin(TAU * t / 3.0) + 15 * np.sin(TAU * t / 1.2 + 1.0)
        ph = np.cumsum(creak_rate) / sr
        ph *= np.round(ph[-1]) / ph[-1]            # whole number of pulses per period
        pulses = np.zeros(n)
        hits = np.nonzero(np.diff(np.floor(ph)) > 0)[0]
        pulses[hits] = rng.uniform(0.5, 1.0, len(hits))
        squeeze_env = 0.6 + 0.4 * np.sin(TAU * t / 2.0) ** 2
        noise = rng.standard_normal(n)
        return src, pulses * squeeze_env, noise

    throb, pulses, noise = period_source()
    t = tvec(n, sr)

    def process(x3):
        th, pu, no = x3[:, 0], x3[:, 1], x3[:, 2]
        creak = iir(pu, "bp", 340, 11, sr=sr) + iir(pu, "bp", 820, 9, sr=sr) * 0.7
        tt = np.arange(len(no)) / sr
        # 3.5, 35/6 and 49/6 Hz all complete whole cycles in the 6 s period.
        wobble = (np.sin(TAU * 3.5 * tt) + 0.7 * np.sin(TAU * (35.0 / 6.0) * tt + 1.3)
                  + 0.5 * np.sin(TAU * (49.0 / 6.0) * tt + 2.1))
        wet = bandpass(no, 950, 2.0, sr) * np.clip(0.4 + 0.35 * wobble, 0.0, 1.2)
        drone = bandpass(no, 120, 3.0, sr)
        return th * 1.0 + creak / 3.0 + wet * 0.12 + drone * 0.3

    stacked = np.stack([throb, pulses, noise], axis=1)
    return tile_process(stacked, process)


@sound("nullroot.consume", variants=2, subtitle="Something swallows", attenuation=24, peak=-1.5, fades=(0.005, 0.3))
def s_nullroot_consume(rng, v, sr):
    dur = [2.2, 2.8][v]
    n = ns(dur, sr)
    out = np.zeros(n)
    tear = np.zeros(n)
    for _ in range(int(rng.integers(40, 80))):
        m = ns(rng.uniform(0.002, 0.012), sr)
        place(tear, white(m, rng) * hann(m) * rng.uniform(0.2, 1.0), ns(rng.uniform(0, 0.9), sr))
    out += bandpass(tear, 900, 1.2, sr) * 0.8
    for k in range(int(rng.integers(2, 4))):
        place(out, door_knock(rng, 0.7, sr, modes=[(140, 0.03, 1.0), (390, 0.02, 0.6), (1100, 0.008, 0.3)]) * 0.6,
              ns(rng.uniform(0.05, 0.8), sr))
    m = ns(0.6, sr)
    gulp_f = 320 * np.exp(-tvec(m, sr) / 0.15) + 70
    gulp = np.sin(phase_of(gulp_f, sr)) * attack_decay(m, 0.01, 0.18, sr)
    gulp = iir(gulp + 0.2 * white(m, rng) * attack_decay(m, 0.01, 0.08, sr), "lp", 900, 1.2, sr=sr)
    place(out, gulp * 1.2, ns(dur * 0.45, sr))
    sq = squelch(rng, dur, 600, 18, sr)
    out += sq / (np.max(np.abs(sq)) + 1e-9) * 0.35 * env_points(n, [(0, 0.3), (dur * 0.5, 1), (dur, 0)], sr)
    place(out, thump(rng, ns(0.8, sr), 70, 35, 0.05, 0.2, sr) * 0.8, ns(dur * 0.7, sr))
    out = compress(bass_exciter(out, sr, 0.4, 130.0), sr, threshold_db=-18.0, ratio=3.0)
    return reverb(out, rng, 0.9, wet=0.18, dry=1.0, bright=0.3, sr=sr)


@sound("nullroot.hurt", variants=3, subtitle="Something shrieks", attenuation=20, peak=-1.5, fades=(0.002, 0.05))
def s_nullroot_hurt(rng, v, sr):
    dur = [0.45, 0.6, 0.75][v]
    n = ns(dur, sr)
    rate0 = rng.uniform(220, 380)
    squeal = friction_creak(rng, dur, [(0, rate0 * 0.7), (0.06, rate0), (dur, rate0 * 0.45)],
                            [rng.uniform(900, 1300), rng.uniform(2000, 2600)], 6.0, sr=sr, jitter=0.25)
    squeal = squeal / (np.max(np.abs(squeal)) + 1e-9)
    squeal *= (1 + 0.4 * np.sin(TAU * rng.uniform(18, 28) * tvec(n, sr))) * env_points(n, [(0, 0), (0.02, 1), (dur, 0)], sr)
    splat = bandpass(white(ns(0.08, sr), rng), 800, 1.5, sr) * exp_decay(ns(0.08, sr), 0.02, sr)
    out = squeal * 0.7
    place(out, splat * 0.9, 0)
    return soft_clip(out * 1.2, 1.4)


@sound("nullroot.death", variants=2, subtitle="Something withers", attenuation=24, peak=-1.5, fades=(0.005, 0.4))
def s_nullroot_death(rng, v, sr):
    dur = [2.4, 3.0][v]
    n = ns(dur, sr)
    creak = friction_creak(rng, dur * 0.8, [(0, rng.uniform(140, 180)), (dur * 0.8, 14)],
                           [rng.uniform(500, 700), rng.uniform(1100, 1400)], 10.0, sr=sr)
    creak = creak / (np.max(np.abs(creak)) + 1e-9)
    t = tvec(len(creak), sr)
    creak *= np.exp(-t / (dur * 0.5))
    out = np.zeros(n)
    place(out, creak * 0.7, 0)
    hiss = white(n, rng)

    def gain(tt, f):
        fc = 3000 * np.exp(-np.clip(tt, 0, None) / (dur * 0.35))[:, None] + 200
        return 1 / (1 + (f[None, :] / fc) ** 2)
    hiss = stft_shape(hiss, gain, sr=sr) * env_points(n, [(0, 0), (0.2, 1), (dur * 0.7, 0.4), (dur, 0)], sr)
    out += hiss / (rms(hiss) + 1e-9) * 0.05
    sq = squelch(rng, dur, 500, 14, sr)
    out += sq / (np.max(np.abs(sq)) + 1e-9) * 0.3 * env_points(n, [(0, 0.8), (dur, 0)], sr)
    place(out, thump(rng, ns(0.7, sr), 80, 38, 0.04, 0.15, sr) * 0.9, ns(dur * 0.62, sr))
    return reverb(out, rng, 1.0, wet=0.2, dry=1.0, bright=0.3, sr=sr)


@sound("nullroot.grow", variants=3, subtitle="Something grows", attenuation=20, peak=-2.5, fades=(0.05, 0.25))
def s_nullroot_grow(rng, v, sr):
    dur = [1.6, 2.0, 2.5][v]
    n = ns(dur, sr)
    out = np.zeros(n)
    density = env_points(n, [(0, 0.1), (dur * 0.5, 1.0), (dur, 0.1)], sr)
    count = int(dur * 120)
    for _ in range(count):
        st = ns(rng.uniform(0, dur), sr)
        if rng.random() > density[min(st, n - 1)]:
            continue
        m = ns(rng.uniform(0.0008, 0.004), sr)
        place(out, white(m, rng) * hann(m) * rng.uniform(0.1, 0.8), st)
    out = bandpass(out, rng.uniform(1800, 2800), 1.0, sr) * 1.5
    cd = dur * 0.8
    creak = friction_creak(rng, cd, [(0, 20), (cd, rng.uniform(70, 110))], [rng.uniform(300, 450), rng.uniform(700, 1000)], 12.0, sr=sr)
    creak = creak / (np.max(np.abs(creak)) + 1e-9) * np.sin(np.linspace(0, np.pi, len(creak)))
    place(out, creak * 0.45, ns(dur * 0.1, sr))
    sq = squelch(rng, dur, 450, 6, sr)
    out += sq / (np.max(np.abs(sq)) + 1e-9) * 0.25
    return out


# ---- Visitors and simulated players ------------------------------------------------------

@sound("visitor.vanish", variants=3, subtitle="Air shifts", attenuation=48, peak=-3.0, fades=(0.01, 0.004))
def s_visitor_vanish(rng, v, sr):
    dur = [0.55, 0.7, 0.9][v]
    n = ns(dur, sr)
    rush = lowpass(white(n, rng), rng.uniform(1500, 2600), 2, sr) * np.linspace(0, 1, n) ** 3
    shimmer = np.zeros(n)
    for _ in range(5):
        m = ns(0.3, sr)
        tone = sine(rng.uniform(1800, 4200), m, sr) * exp_decay(m, 0.08, sr)
        place(shimmer, tone[::-1] * 0.1, n - m)
    out = rush * 0.6 + shimmer
    pop = lowpass(white(ns(0.006, sr), rng), 1200, 2, sr) * hann(ns(0.006, sr))
    place(out, pop * 0.8, n - ns(0.008, sr))
    return out


@sound("visitor.appear", variants=3, subtitle="Something is there", attenuation=48, peak=-3.0, fades=(0.005, 0.25))
def s_visitor_appear(rng, v, sr):
    dur = [0.9, 1.1, 1.3][v]
    n = ns(dur, sr)
    out = thump(rng, n, 85, 40, 0.04, 0.2, sr) * 0.8
    fizz = spectral(white(n, rng), r_mul(r_hp(2500, 2), r_lp(9000, 2)), sr)
    fizz *= env_points(n, [(0, 0), (0.15, 1), (dur, 0)], sr) * (0.6 + 0.4 * smooth_random(n, rng, 25, sr))
    out += fizz * 0.12
    out += sine(rng.uniform(110, 130), n, sr) * env_points(n, [(0, 0), (0.2, 0.15), (dur, 0)], sr)
    return out


@sound("visitor.breath", variants=3, subtitle="Heavy breathing", attenuation=24, peak=-3.0, fades=(0.05, 0.2))
def s_visitor_breath(rng, v, sr):
    out = breath_cycle(rng, rng.uniform(0.9, 1.3), rng.uniform(1.3, 1.8), rng.uniform(0.15, 0.35), sr,
                       rasp=[0.6, 0.9, 0.4][v], darkness=0.72)
    return reverb(out, rng, 0.4, wet=0.1, dry=1.0, sr=sr)


@sound("fakeplayer.whisper", variants=3, subtitle="Someone whispers", attenuation=20, peak=-3.0, fades=(0.02, 0.006))
def s_fakeplayer_whisper(rng, v, sr):
    dur = [1.6, 2.0, 2.4][v]
    w = whisper(rng, dur, rate=rng.uniform(1.0, 1.3), scale=0.9, sr=sr)
    vo = voice(rng, dur, f0=rng.uniform(92, 108), rate=1.1, scale=0.9, sr=sr)
    out = w / (rms(w) + 1e-9) + vo / (rms(vo) + 1e-9) * 0.12
    k = ns(dur - rng.uniform(0.15, 0.3), sr)
    out[k:] = bitcrush(out[k:] / (np.max(np.abs(out)) + 1e-9), 4, int(rng.integers(3, 7))) * np.max(np.abs(out))
    return spectral(out, r_mul(r_hp(200, 2), r_lp(8000, 2)), sr)


# ---- The NULL dimension and the transition into it --------------------------------------

@sound("transition.collapse", variants=1, subtitle="Everything falls silent", attenuation=16, peak=-1.2, fades=(0.3, 0.002))
def s_transition_collapse(rng, v, sr):
    dur = 5.0
    cut = 4.55
    n = ns(dur, sr)
    t = tvec(n, sr)
    body = np.zeros(n)
    for midi, a in [(38, 1.0), (45, 0.8), (50, 0.6), (51, 0.45), (57, 0.35), (62, 0.25)]:
        f = 440.0 * 2 ** ((midi - 69) / 12.0)
        body += a * additive(f * (1 + 0.004 * smooth_random(n, rng, 1.0, sr)), n, [(1, 1.0), (2, 0.5), (3, 0.3), (4, 0.15)], sr)
    body += coloured(n, rng, 1.0, sr) * 0.5
    crash = white(ns(2.0, sr), rng) * exp_decay(ns(2.0, sr), 0.5, sr)
    swell = reverb(crash, rng, 3.0, wet=1.0, dry=0.0, bright=0.6, sr=sr)[::-1]
    sw = np.zeros(n)
    place(sw, swell[-ns(cut, sr):], 0)
    mix_sig = body * 0.25 + sw / (np.max(np.abs(sw)) + 1e-9) * 0.8
    env = (t / cut) ** 2.5 * (t < cut)

    def gain(tt, f):
        frac = np.clip(tt / cut, 0, 1)[:, None]
        fc = 12000 * (1 - frac) ** 2.2 + 120
        return 1 / (1 + (f[None, :] / fc) ** 3)
    out = stft_shape(mix_sig * env, gain, sr=sr)
    rate = 1.0 - 0.45 * np.clip((t - 2.5) / (cut - 2.5), 0, 1) ** 2
    out = varispeed(out, rate)
    k = ns(cut, sr)
    out = out[:n]
    out[k:] = 0
    out[k - ns(0.004, sr):k] *= np.linspace(1, 0, ns(0.004, sr))
    return out


@sound("transition.arrival", variants=1, subtitle="A tone rises", attenuation=16, peak=-1.5, rms_db=-14.0, stream=True, quality=4,
       fades=(1.0, 1.5))
def s_transition_arrival(rng, v, sr):
    dur = 10.5
    n = ns(dur, sr)
    t = tvec(n, sr)
    f = 32.0 * (220.0 / 32.0) ** ((t / dur) ** 1.6)
    drive = 0.6 + 5.0 * (t / dur) ** 2
    tone = np.sin(phase_of(f, sr)) + 0.8 * np.sin(phase_of(f * 1.0 + 1.5 * (t / dur), sr))
    tone += 0.4 * np.sin(phase_of(f * 1.5 + 0.7, sr))
    dist = np.tanh(tone * drive) / np.tanh(drive)
    noise = spectral(white(n, rng), r_mul(r_hp(300, 2), r_lp(5000, 2)), sr) * 0.04 * (t / dur)
    out = dist * env_points(n, [(0, 0), (2.0, 0.25), (7.0, 0.7), (9.5, 1.0), (dur, 0.0)], sr) + noise
    return spectral(out, r_lp(7000, 2), sr)


def dimension_hum_loop(rng, sr, length):
    n = ns(length, sr)
    t = tvec(n, sr)
    tone = np.zeros(n)
    # Every frequency is a multiple of 1/length, every LFO period divides length: exactly periodic.
    for f, a in [(55.0, 1.0), (55.6, 0.85), (110.25, 0.4), (82.5, 0.35), (164.8, 0.12), (220.4, 0.06)]:
        tone += a * np.sin(TAU * f * t + rng.uniform(0, TAU))
    tone *= 1.0 + 0.2 * np.sin(TAU * t / 10.0) + 0.1 * np.sin(TAU * t / 4.0 + 0.7)
    whine = np.sin(TAU * 3150.0 * t + 0.002 * np.sin(TAU * t / 5.0) * 3150 * 5) * 0.012 * (0.5 + 0.5 * np.sin(TAU * t / 20.0))
    tone = soft_clip(tone * 0.45, 1.4) + whine
    data = bitcrush(rng.standard_normal(n) * 0.3, 5, 4)
    data = spectral(data, r_bp(2200, 1.5), sr, circular=True)
    gate = (np.clip(np.sin(TAU * t / 2.5) * 3 - 2, 0, 1) * (0.55 + 0.45 * np.sin(TAU * t * 3.0 / 20.0 + 0.5))
            + np.clip(np.sin(TAU * t / 4.0 + 1.0) * 4 - 3, 0, 1) * 0.6)   # periods 2.5, 20/3 and 4 s divide 20 s
    data *= gate * 0.12
    bed = rumble_bed(rng, n, 200, sr, circular=True) * 0.25
    return tone + data + bed


@sound("dimension.hum", variants=1, subtitle="Data hums", attenuation=32, sr=22050, rms_db=-16.0, peak=-3.0,
       loop=True, stream=True, quality=4)
def s_dimension_hum(rng, v, sr):
    return dimension_hum_loop(rng, sr, 20.0)


@sound("dimension.shift", variants=3, subtitle="Something shifts", attenuation=48, peak=-1.5, fades=(0.01, 0.3))
def s_dimension_shift(rng, v, sr):
    dur = [1.6, 2.0, 2.4][v]
    n = ns(dur, sr)
    out = np.zeros(n)
    pre = ns(0.35, sr)
    swell = highpass(white(pre, rng), 1500, 2, sr) * np.linspace(0, 1, pre) ** 3 * 0.3
    place(out, swell, 0)
    hit = pre
    place(out, thump(rng, ns(1.2, sr), 90, 30, 0.08, 0.3, sr), hit)
    clank_modes = [(rng.uniform(180, 240), 0.4, 1.0), (rng.uniform(520, 640), 0.25, 0.6),
                   (rng.uniform(1300, 1600), 0.15, 0.4), (rng.uniform(2700, 3300), 0.08, 0.25)]
    place(out, door_knock(rng, 1.0, sr, modes=clank_modes) * 0.8, hit)
    grind_n = n - hit
    src = white(grind_n, rng)
    holds = np.repeat(rng.integers(2, 14, size=grind_n // 600 + 2), 600)[:grind_n]
    idx = np.arange(grind_n) // holds * holds
    grind = bitcrush(src[np.clip(idx, 0, grind_n - 1)] * 0.5, 5)
    grind = lowpass(grind, 2500, 2, sr) * attack_decay(grind_n, 0.02, dur * 0.35, sr)
    grind = varispeed(np.concatenate([grind, np.zeros(grind_n)]), np.linspace(1.0, 0.5, grind_n))
    place(out, grind * 0.5, hit)
    return reverb(out, rng, 2.0, wet=0.3, dry=1.0, bright=0.4, sr=sr)[:n + ns(0.6, sr)]


@sound("dimension.blackout", variants=2, subtitle="The lights die", attenuation=48, peak=-1.2, fades=(0.001, 0.4))
def s_dimension_blackout(rng, v, sr):
    dur = [2.0, 2.4][v]
    n = ns(dur, sr)
    out = np.zeros(n)
    place(out, door_knock(rng, 1.3, sr, modes=[(60, 0.12, 1.0), (140, 0.06, 0.6), (2200, 0.01, 0.3), (4100, 0.006, 0.2)]), 0)
    place(out, thump(rng, ns(1.6, sr), 75, 22, 0.12, 0.5, sr) * 1.1, 0)
    m = ns(0.8, sr)
    f = 60 + 900 * np.exp(-tvec(m, sr) / 0.12)
    place(out, np.sin(phase_of(f, sr)) * attack_decay(m, 0.003, 0.2, sr) * 0.25, ns(0.01, sr))
    ring = sine(rng.uniform(5200, 6800), n, sr) * env_points(n, [(0, 0), (0.4, 0.015), (dur, 0)], sr)
    return bass_exciter(out, sr, 0.45, 130.0) + ring


# ---- Music -----------------------------------------------------------------------------------

def midi_hz(m):
    return 440.0 * 2 ** ((m - 69) / 12.0)


def piano_note(rng, f, dur, sr, velocity=0.8, detune_cents=0.0):
    n = ns(dur, sr)
    t = tvec(n, sr)
    f = f * 2 ** (detune_cents / 1200.0)
    out = np.zeros(n)
    inharm = 0.0004
    for k in range(1, 16):
        fk = f * k * np.sqrt(1 + inharm * k * k)
        if fk > sr * 0.45:
            break
        amp = (1.0 / k ** 1.3) * (0.55 + 0.45 * velocity) ** (k * 0.4)
        tau = 2.8 / (1 + 0.45 * k) * (220.0 / max(f, 60.0)) ** 0.35
        beat = 1 + 0.0007 * k * rng.uniform(-1, 1)
        out += amp * np.exp(-t / tau) * (np.sin(TAU * fk * t) + 0.5 * np.sin(TAU * fk * beat * t + rng.uniform(0, TAU))) / 1.5
    hammer = spectral(white(ns(0.03, sr), rng), r_bp(f * 4, 1.0), sr) * np.exp(-np.linspace(0, 8, ns(0.03, sr))) * 0.15
    out[:len(hammer)] += hammer
    return out * velocity * attack_decay(n, 0.002, 1e9, sr) * np.minimum(1, (n - np.arange(n)) / ns(0.05, sr))


def music_box_note(rng, f, dur, sr, velocity=0.8, detune_cents=0.0):
    n = ns(dur, sr)
    t = tvec(n, sr)
    f = f * 2 ** (detune_cents / 1200.0)
    out = (np.exp(-t / 2.2) * np.sin(TAU * f * t)
           + 0.18 * np.exp(-t / 0.9) * np.sin(TAU * 2.0 * f * t)
           + 0.35 * np.exp(-t / 0.25) * np.sin(TAU * 6.27 * f * t)
           + 0.12 * np.exp(-t / 0.06) * np.sin(TAU * 17.55 * f * t if 17.55 * f < sr * 0.45 else 0 * t))
    k = ns(0.0015, sr)
    out[:k] += white(k, rng) * 0.3
    return out * velocity * np.minimum(1, (n - np.arange(n)) / ns(0.05, sr))


def tape(x, rng, sr, wow=0.004, flutter=0.0012, drift=0.0, sag=None):
    """Wow (slow pitch wander), flutter (fast) and an optional slow sag (array of extra rate)."""
    n = len(x)
    t = tvec(n, sr)
    rate = 1.0 + wow * np.sin(TAU * 0.55 * t + rng.uniform(0, TAU)) + wow * 0.5 * smooth_random(n, rng, 0.3, sr)
    rate += flutter * np.sin(TAU * 6.8 * t) + flutter * 0.6 * smooth_random(n, rng, 9.0, sr)
    if sag is not None:
        rate = rate * sag
    return varispeed(x, rate)


def dropouts(x, rng, sr, count, min_len=0.05, max_len=0.3, depth=0.0, start=0.0):
    out = x.copy()
    n = len(x)
    for _ in range(count):
        k = ns(rng.uniform(start, n / sr - max_len - 0.1), sr)
        m = ns(rng.uniform(min_len, max_len), sr)
        g = np.ones(m) * depth
        edge = min(m // 2, ns(0.01, sr))
        g[:edge] = np.linspace(1, depth, edge)
        g[-edge:] = np.linspace(depth, 1, edge)
        out[k:k + m] *= g[:len(out[k:k + m])]
    return out


def skips(x, rng, sr, count, start=10.0):
    """CD-like skips: a short chunk repeats once or twice."""
    out = list(x)
    out = np.asarray(out)
    pieces = []
    pos = 0
    n = len(x)
    marks = sorted(ns(rng.uniform(start, n / sr - 2.0), sr) for _ in range(count))
    for mk in marks:
        if mk <= pos:
            continue
        m = ns(rng.uniform(0.08, 0.25), sr)
        pieces.append(out[pos:mk])
        chunk = out[mk - m:mk] * np.minimum(1, np.minimum(np.arange(m), m - np.arange(m)) / 60.0)
        for _ in range(int(rng.integers(1, 3))):
            pieces.append(chunk)
        pos = mk
    pieces.append(out[pos:])
    return np.concatenate(pieces)


def hiss(n, rng, sr, level):
    return spectral(white(n, rng), r_mul(r_hp(1500, 1), r_lp(9000, 2)), sr) * level


def render_stereo(events, total, sr, rng):
    """events: list of (start_s, mono_signal, pan)."""
    out = np.zeros((ns(total, sr), 2))
    for st, sig, p in events:
        s = pan(sig, p)
        k = ns(st, sr)
        end = min(len(out), k + len(s))
        if k < len(out):
            out[k:end] += s[:end - k]
    return out


@sound("music.distorted_1", variants=1, stereo=True, stream=True, quality=3, peak=-1.5, rms_db=-19.0, fades=(0.5, 3.0))
def s_music_distorted_1(rng, v, sr):
    # A music-box lullaby in A minor (3/4, ~68 bpm) that slowly goes wrong.
    beat = 60.0 / 68.0
    phrase_a = [(69, 1), (72, 1), (76, 1), (74, 1), (72, 1), (71, 1), (69, 1), (64, 1), (69, 1), (68, 3)]
    phrase_b = [(69, 1), (72, 1), (76, 1), (77, 1), (76, 1), (74, 1), (72, 1), (71, 1), (69, 1), (76, 3)]
    phrase_c = [(81, 1), (79, 1), (77, 1), (76, 2), (72, 1), (74, 1), (71, 1), (68, 1), (69, 3)]
    bass = {0: 45, 3: 41, 6: 48, 9: 40}
    song = phrase_a + phrase_b + phrase_a + phrase_c + phrase_b + phrase_a
    events = []
    t = 1.0
    bar_beat = 0
    total_beats = sum(d for _, d in song)
    for i, (m, d) in enumerate(song):
        progress = bar_beat / total_beats
        wrong = progress > 0.45 and rng.random() < 0.08 + 0.25 * (progress - 0.45)
        note = m + (int(rng.choice([-1, 1])) if wrong else 0)
        detune = rng.uniform(-12, 12) + (rng.uniform(-40, 40) if progress > 0.6 and rng.random() < 0.2 else 0)
        sig = music_box_note(rng, midi_hz(note), 3.5, sr, rng.uniform(0.6, 0.85), detune)
        events.append((t + rng.uniform(-0.015, 0.015), sig, rng.uniform(-0.35, 0.35)))
        if bar_beat % 12 in bass:
            b = bass[bar_beat % 12]
            events.append((t, music_box_note(rng, midi_hz(b + 12), 3.5, sr, 0.45, rng.uniform(-8, 8)), rng.uniform(-0.2, 0.2)))
        t += d * beat * (1.0 + 0.1 * max(0.0, progress - 0.7))
        bar_beat += d
    total = t + 4.0
    st = render_stereo(events, total, sr, rng)
    n = st.shape[0]
    sag = 1.0 - 0.12 * np.clip((tvec(n, sr) / (n / sr) - 0.78) / 0.22, 0, 1) ** 2
    left = tape(st[:, 0], rng_for("md1/wowL"), sr, 0.005, 0.0015, sag=sag)
    right = tape(st[:, 1], rng_for("md1/wowL"), sr, 0.005, 0.0015, sag=sag)
    m = min(len(left), len(right))
    left, right = left[:m], right[:m]
    left = reverb(left, rng_for("md1/revL"), 2.4, wet=0.32, dry=0.9, bright=0.4, sr=sr)
    right = reverb(right, rng_for("md1/revR"), 2.4, wet=0.32, dry=0.9, bright=0.4, sr=sr)
    m = min(len(left), len(right))
    out = np.stack([left[:m], right[:m]], axis=1)
    hs = np.stack([hiss(m, rng, sr, 0.004), hiss(m, rng, sr, 0.004)], axis=1)
    out = out + hs
    for ch in range(2):
        out[:, ch] = dropouts(out[:, ch], rng_for("md1/drop"), sr, 6, 0.06, 0.35, 0.0, start=20.0)
    return out


@sound("music.distorted_2", variants=1, stereo=True, stream=True, quality=3, peak=-1.5, rms_db=-19.0, fades=(1.0, 3.0))
def s_music_distorted_2(rng, v, sr):
    # Slow piano in D minor: arpeggiated chords with a sparse melody, gently out of tune, with skips.
    beat = 60.0 / 58.0
    chords = [[50, 57, 62, 65], [46, 53, 58, 62], [41, 53, 57, 60], [48, 55, 60, 64],
              [50, 57, 62, 65], [46, 53, 58, 62], [43, 50, 55, 58], [45, 52, 57, 61]]
    melody = {0: [(74, 2), (72, 1), (69, 1)], 1: [(70, 3), (69, 1)], 2: [(72, 2), (69, 2)], 3: [(67, 3), (64, 1)],
              4: [(74, 2), (77, 1), (76, 1)], 5: [(74, 3), (70, 1)], 6: [(70, 2), (67, 2)], 7: [(69, 4)]}
    events = []
    t = 0.8
    for rep in range(2):
        for ci, chord in enumerate(chords):
            for k, note in enumerate(chord):
                st = t + k * beat * 0.5 + rng.uniform(-0.02, 0.02)
                detune = rng.uniform(-6, 6) + (rng.uniform(-35, 35) if rep == 1 and rng.random() < 0.3 else 0)
                events.append((st, piano_note(rng, midi_hz(note), 4.0, sr, rng.uniform(0.35, 0.5), detune), -0.3 + 0.2 * k))
            mt = t
            for note, d in melody[ci]:
                if rep == 1 and rng.random() < 0.18:
                    note += int(rng.choice([-1, 1]))
                events.append((mt + rng.uniform(-0.02, 0.02), piano_note(rng, midi_hz(note), 3.5, sr, rng.uniform(0.55, 0.7),
                                                                       rng.uniform(-8, 8)), 0.15))
                mt += d * beat
            t += 4 * beat
    total = t + 5.0
    st = render_stereo(events, total, sr, rng)
    n = st.shape[0]
    sag = 1.0 - 0.06 * np.clip((tvec(n, sr) - (n / sr) * 0.55) / ((n / sr) * 0.45), 0, 1)
    chans = []
    for ch, key in ((0, "L"), (1, "R")):
        x = tape(st[:, ch], rng_for("md2/wow"), sr, 0.0035, 0.001, sag=sag)
        x = reverb(x, rng_for("md2/rev" + key), 2.8, wet=0.35, dry=0.85, bright=0.35, sr=sr)
        chans.append(x)
    m = min(len(c) for c in chans)
    out = np.stack([c[:m] for c in chans], axis=1)
    out = out + np.stack([hiss(m, rng, sr, 0.003), hiss(m, rng, sr, 0.003)], axis=1)
    skipped = [skips(out[:, ch], rng_for("md2/skip"), sr, 3, start=25.0) for ch in range(2)]
    m = min(len(s) for s in skipped)
    out = np.stack([s[:m] for s in skipped], axis=1)
    for ch in range(2):
        out[:, ch] = dropouts(out[:, ch], rng_for("md2/drop"), sr, 4, 0.08, 0.4, 0.0, start=30.0)
    return out


@sound("music.null_theme", variants=1, stereo=True, stream=True, quality=3, peak=-1.5, rms_db=-19.0, fades=(2.0, 4.0))
def s_music_null_theme(rng, v, sr):
    # Low drone, reversed piano swells before sparse notes, a slowed music-box motif, digital decay.
    total = 82.0
    n = ns(total, sr)
    t = tvec(n, sr)
    drone = np.zeros((n, 2))
    for f, a, p in [(36.71, 1.0, -0.2), (36.95, 0.8, 0.2), (55.0, 0.5, -0.1), (55.2, 0.45, 0.1), (73.4, 0.18, 0.0)]:
        sig = np.sin(TAU * f * t + rng.uniform(0, TAU)) * a
        drone += pan(sig, p)
    drone *= (0.75 + 0.25 * np.sin(TAU * t / 16.0))[:, None]
    drone = np.stack([lowpass(soft_clip(drone[:, c] * 0.6, 1.5), 400, 2, sr) for c in range(2)], axis=1) * 0.35
    drone *= env_points(n, [(0, 0), (6, 1), (total - 8, 1), (total, 0)], sr)[:, None]
    events = []
    notes = [(8.0, 62), (14.0, 65), (20.0, 64), (25.0, 61), (31.0, 62), (37.0, 69), (43.0, 68), (49.0, 65),
             (55.0, 62), (60.0, 61), (65.0, 57), (71.0, 50)]
    for st, m in notes:
        note = piano_note(rng, midi_hz(m), 4.5, sr, 0.6, rng.uniform(-15, 15))
        rev = reverb(piano_note(rng, midi_hz(m), 2.5, sr, 0.5), rng, 2.5, wet=1.0, dry=0.3, sr=sr)[::-1]
        events.append((st - len(rev) / sr, rev * 0.35, rng.uniform(-0.6, 0.6)))
        events.append((st, note, rng.uniform(-0.3, 0.3)))
    motif = [76, 72, 69, 71, 68]
    mt = 28.0
    for rep in range(3):
        for i, m in enumerate(motif):
            sig = music_box_note(rng, midi_hz(m), 3.0, sr, 0.5, rng.uniform(-20, 20))
            sig = resample_pitch(sig, 0.5)
            events.append((mt + i * 1.1, sig * 0.5, 0.5 if rep % 2 == 0 else -0.5))
        mt += 12.0
    body = render_stereo(events, total, sr, rng)
    body = np.stack([reverb(body[:, c], rng_for(f"nt/rev{c}"), 3.5, wet=0.4, dry=0.8, bright=0.3, sr=sr)[:n]
                     for c in range(2)], axis=1)
    out = drone + body
    for st in (46.0, 63.0, 74.0):
        k0, k1 = ns(st, sr), ns(st + rng.uniform(0.8, 1.6), sr)
        for c in range(2):
            out[k0:k1, c] = bitcrush(out[k0:k1, c] * 2.0, 5, int(rng.integers(3, 8))) * 0.5
    out = out + np.stack([hiss(n, rng, sr, 0.004), hiss(n, rng, sr, 0.004)], axis=1)
    out[:, 0] = dropouts(out[:, 0], rng_for("nt/drop"), sr, 3, 0.1, 0.5, 0.0, start=35.0)
    out[:, 1] = dropouts(out[:, 1], rng_for("nt/drop"), sr, 3, 0.1, 0.5, 0.0, start=35.0)
    return out


# ---- Corrupted blocks --------------------------------------------------------------------

def digital_tick(rng, sr, level=0.5):
    m = ns(rng.uniform(0.002, 0.006), sr)
    s = square_raw(rng.choice([1200, 1800, 2400, 3200]), m, sr) * hann(m) ** 0.3
    return bitcrush(s * level, 3, 2)


@sound("block.corrupted.break", variants=4, subtitle="Block breaks wrong", attenuation=16, peak=-1.5, fades=(0.0005, 0.06))
def s_corrupted_break(rng, v, sr):
    dur = rng.uniform(0.32, 0.45)
    n = ns(dur, sr)
    out = np.zeros(n)
    for _ in range(int(rng.integers(25, 45))):
        m = ns(rng.uniform(0.001, 0.006), sr)
        place(out, white(m, rng) * hann(m) * rng.uniform(0.2, 1.0), ns(rng.uniform(0, 0.12) ** 1.3 * 2.0, sr))
    out = iir(out, "lp", 3500, 0.7, sr=sr) + iir(out, "bp", 900, 1.0, sr=sr)
    place(out, thump(rng, ns(0.2, sr), 120, 70, 0.02, 0.04, sr) * 0.7, 0)
    crushed = bitcrush(out / (np.max(np.abs(out)) + 1e-9) * 0.9, int(rng.integers(3, 5)), int(rng.integers(3, 8)))
    k = ns(rng.uniform(0.04, 0.12), sr)
    out[k:] = crushed[k:] * 0.8
    for _ in range(int(rng.integers(1, 3))):
        place(out, digital_tick(rng, sr, 0.6), ns(rng.uniform(0.02, dur * 0.6), sr))
    return out


@sound("block.corrupted.step", variants=5, subtitle="Footsteps", attenuation=16, peak=-2.0, fades=(0.0005, 0.03))
def s_corrupted_step(rng, v, sr):
    fs = footstep(rng, weight=rng.uniform(0.7, 1.0), crunch=rng.uniform(0.6, 1.0), sr=sr)[:ns(0.2, sr)]
    fs = fs / (np.max(np.abs(fs)) + 1e-9)
    place(fs, digital_tick(rng, sr, 0.35), ns(rng.uniform(0.0, 0.03), sr))
    if v % 2 == 0:
        fs = bitcrush(fs * 0.9, 5, int(rng.integers(2, 4)))
    return fs


@sound("block.corrupted.place", variants=4, subtitle="Block placed", attenuation=16, peak=-1.5, fades=(0.0005, 0.05))
def s_corrupted_place(rng, v, sr):
    n = ns(0.3, sr)
    out = thump(rng, n, rng.uniform(140, 180), 80, 0.015, 0.035, sr) * 0.8
    knock = door_knock(rng, 0.8, sr, modes=[(210, 0.02, 1.0), (520, 0.012, 0.5), (1300, 0.006, 0.3)])
    place(out, knock * 0.7, 0)
    m = ns(0.03, sr)
    blip = square_raw(rng.choice([660, 880, 990]), m, sr, 0.25) * np.linspace(1, 0, m)
    place(out, bitcrush(blip * 0.3, 3, 3), ns(rng.uniform(0.02, 0.06), sr))
    return out


@sound("block.corrupted.hit", variants=4, subtitle="Block breaking", attenuation=16, peak=-2.0, fades=(0.0005, 0.03))
def s_corrupted_hit(rng, v, sr):
    n = ns(rng.uniform(0.1, 0.16), sr)
    out = np.zeros(n)
    for _ in range(int(rng.integers(8, 16))):
        m = ns(rng.uniform(0.0008, 0.004), sr)
        place(out, white(m, rng) * hann(m) * rng.uniform(0.2, 1.0), ns(rng.uniform(0, 0.03), sr))
    out = iir(out, "bp", rng.uniform(700, 1400), 0.9, sr=sr)
    place(out, digital_tick(rng, sr, 0.5), ns(rng.uniform(0, 0.02), sr))
    return out


# ---- Main menu -----------------------------------------------------------------------------

def menu_ambience_loop(rng, sr, length):
    n = ns(length, sr)
    t = tvec(n, sr)
    chans = []
    for ch in range(2):
        pad_sig = np.zeros(n)
        # Frequencies are multiples of 1/length (0.025 Hz): the loop is exactly periodic.
        for f, a in [(43.65, 1.0), (43.75 + 0.025 * ch, 0.8), (65.4, 0.5), (65.5 + 0.025 * ch, 0.4),
                     (87.3, 0.25), (103.825, 0.12), (130.8 + 0.05 * ch, 0.08)]:
            pad_sig += a * np.sin(TAU * f * t + rng.uniform(0, TAU))
        pad_sig *= 0.75 + 0.25 * np.sin(TAU * t / 20.0 + ch)
        tape_hiss = spectral(rng.standard_normal(n), r_mul(r_hp(2000, 1), r_lp(8000, 2)), sr, circular=True) * 0.02
        air = spectral(rng.standard_normal(n), r_bp(380, 1.2), sr, circular=True) * 0.05
        air *= 0.5 + 0.5 * np.sin(TAU * t / 8.0 + ch * 2.0)
        chans.append(soft_clip(pad_sig * 0.35, 1.3) + tape_hiss + air)
    sig = np.stack(chans, axis=1)
    # Sparse distant events, placed with wrap-around so they may straddle the loop seam.
    for k in range(5):
        st = ns(rng.uniform(0, length), sr)
        kind = k % 3
        if kind == 0:
            m = ns(3.0, sr)
            ev = friction_creak(rng, 3.0, [(0, 9), (1.5, 16), (3.0, 7)], [180, 290], 14.0, sr=sr)
            ev = ev / (np.max(np.abs(ev)) + 1e-9) * hann(m) * 0.12
        elif kind == 1:
            m = ns(4.0, sr)
            tt = tvec(m, sr)
            ev = (np.sin(TAU * 880.0 * rng.uniform(0.97, 1.03) * tt) * 0.4 + np.sin(TAU * 1335.0 * tt) * 0.2) * np.exp(-tt / 1.2) * 0.05
        else:
            ev = whisper(rng, 1.4, rate=0.9, sr=sr)
            ev = lowpass(ev, 2500, 2, sr)
            ev = ev / (np.max(np.abs(ev)) + 1e-9) * 0.05
        p = rng.uniform(-0.8, 0.8)
        for ch, g in enumerate(pan(np.ones(1), p)[0]):
            place(sig[:, ch], ev * g, st, wrap=True)
    return np.stack([tile_process(sig[:, c], lambda x, c=c: reverb(x, rng_for(f"menu/rev{c}"), 3.0, wet=0.35, dry=1.0,
                                                                     bright=0.3, sr=sr, tail=False))
                     for c in range(2)], axis=1)


@sound("menu.ambience", variants=1, stereo=True, stream=True, loop=True, quality=3, peak=-4.0, rms_db=-22.0)
def s_menu_ambience(rng, v, sr):
    return menu_ambience_loop(rng, sr, 40.0)


@sound("menu.hover", variants=3, subtitle=None, peak=-4.0, fades=(0.0003, 0.01))
def s_menu_hover(rng, v, sr):
    n = ns(0.07, sr)
    t = tvec(n, sr)
    tone = np.sin(TAU * [2400, 2650, 2200][v] * t) * np.exp(-t / 0.012) * 0.5
    k = ns(0.0015, sr)
    tone[:k] += white(k, rng) * np.exp(-np.linspace(0, 4, k)) * 0.5
    return iir(tone, "lp", 6000, 0.7, sr=sr)


@sound("menu.click", variants=3, subtitle=None, peak=-3.0, fades=(0.0003, 0.02))
def s_menu_click(rng, v, sr):
    n = ns(0.2, sr)
    out = thump(rng, n, 160, 90, 0.01, 0.03, sr) * 0.8
    k = ns(0.002, sr)
    out[:k] += white(k, rng) * 0.6
    m = ns(0.035, sr)
    place(out, bitcrush(spectral(white(m, rng), r_hp(1500, 2), sr) * 0.35 * np.linspace(1, 0, m), 4, 3), ns(0.012, sr))
    return out


@sound("menu.whisper", variants=3, subtitle="Whispering", peak=-4.0, fades=(0.02, 0.1))
def s_menu_whisper(rng, v, sr):
    dur = [1.8, 2.3, 2.8][v]
    w = whisper(rng, dur, rate=rng.uniform(0.85, 1.05), scale=[1.0, 0.93, 1.06][v], sr=sr, pause_prob=0.3)
    w = spectral(w, r_mul(r_hp(250, 2), r_lp(8500, 2)), sr)
    return reverb(w, rng, 1.2, wet=0.25, dry=1.0, bright=0.4, sr=sr)


@sound("menu.waiting", variants=2, subtitle="Something has been waiting", peak=-2.0, fades=(0.4, 0.004))
def s_menu_waiting(rng, v, sr):
    dur = [3.6, 4.2][v]
    n = ns(dur, sr)
    t = tvec(n, sr)
    out = np.zeros(n)
    for f, a in [(41.2, 1.0), (41.5, 0.8), (61.7, 0.4), (82.4, 0.2)]:
        out += a * np.sin(TAU * f * t + rng.uniform(0, TAU))
    out = soft_clip(out * 0.5, 1.5) * env_points(n, [(0, 0), (dur * 0.85, 1.0), (dur, 1.0)], sr) * 0.6
    for k in range(5):
        w = whisper(rng, dur * 0.8, rate=rng.uniform(0.9, 1.2), scale=rng.uniform(0.85, 1.1), sr=sr, pause_prob=0.05)
        w = w / (rms(w) + 1e-9) * 0.06 * env_points(len(w), [(0, 0), (dur * 0.7, 1.0), (dur * 0.8, 1.0)], sr)
        place(out, w, ns(rng.uniform(0.1, 0.5), sr))
    out = reverb(out, rng, 1.5, wet=0.3, dry=1.0, bright=0.3, sr=sr, tail=False)
    k = ns(dur - 0.25, sr)
    out[k:] = 0.0
    return out


# =============================================================================================
# Rendering, encoding, verification
# =============================================================================================

def file_stem(event: str) -> tuple[str, str]:
    group, _, rest = event.partition(".")
    return group, rest.replace(".", "_")


def variant_path(event: str, index: int, count: int) -> tuple[pathlib.Path, str]:
    group, stem = file_stem(event)
    name = stem if count == 1 else f"{stem}_{index + 1}"
    return SOUND_DIR / group / f"{name}.ogg", f"{MODID}:{group}/{name}"


def encode(signal: np.ndarray, path: pathlib.Path, sr: int, channels: int, quality: int):
    path.parent.mkdir(parents=True, exist_ok=True)
    data = np.ascontiguousarray(signal, dtype="<f4").tobytes()
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
           "-f", "f32le", "-ar", str(sr), "-ac", str(channels), "-i", "pipe:0",
           "-map_metadata", "-1", "-c:a", "libvorbis", "-q:a", str(quality), "-ac", str(channels),
           "-flags:a", "+bitexact", "-fflags", "+bitexact", str(path)]
    subprocess.run(cmd, input=data, check=True)


def decode(path: pathlib.Path, sr: int, channels: int) -> np.ndarray:
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-i", str(path), "-f", "f32le",
           "-ar", str(sr), "-ac", str(channels), "pipe:1"]
    raw = subprocess.run(cmd, capture_output=True, check=True).stdout
    data = np.frombuffer(raw, dtype="<f4").astype(np.float64)
    return data.reshape(-1, channels) if channels > 1 else data


def seam_click(x: np.ndarray) -> float:
    """How click-like the loop seam is: high-frequency (second difference) energy in a 64-sample
    window straddling the wrap from the end back to the start, relative to the 99th percentile
    of the same measure over the whole file. Below 1 the seam is indistinguishable from the
    sound's own texture. Worst channel for stereo."""
    chans = [x] if x.ndim == 1 else [x[:, c] for c in range(x.shape[1])]
    worst = 0.0
    w = 64
    for ch in chans:
        d2 = np.diff(np.concatenate([ch[-2:], ch]), n=2)
        e = np.convolve(d2 * d2, np.ones(w) / w, mode="valid")
        seam = np.mean(np.concatenate([d2[-w // 2:], d2[:w // 2]]) ** 2)
        worst = max(worst, float(seam / (np.percentile(e, 99) + 1e-20)))
    return worst


def loop_rotations(sig: np.ndarray, sr: int, count: int = 8) -> list[int]:
    """Candidate start points for a periodic loop: where the waveform is near zero with a small
    slope (Vorbis reproduces the file edges least exactly, so the seam should sit somewhere quiet)."""
    x = sig if sig.ndim == 1 else sig
    step = np.abs(np.roll(x, -1, axis=0) - x)
    cost = np.abs(x) + 4.0 * step
    if cost.ndim == 2:
        cost = cost.sum(axis=1)
    order = np.argsort(cost, kind="stable")
    n = len(cost)
    spacing = ns(0.1, sr)
    picked: list[int] = []
    for idx in order[:20000]:
        idx = int(idx)
        if all(min(abs(idx - c), n - abs(idx - c)) > spacing for c in picked):
            picked.append(idx)
            if len(picked) == count:
                break
    return picked or [0]


def encode_checked(sig: np.ndarray, path: pathlib.Path, sr: int, channels: int, quality: int):
    """Encodes, decodes again and, if the codec pushed the peak above -1 dBFS, lowers the gain and
    re-encodes. Returns (decoded samples, decoded peak, final input signal)."""
    for attempt in range(4):
        encode(sig, path, sr, channels, quality)
        dec = decode(path, sr, channels)
        peak = float(np.max(np.abs(dec)))
        if db(peak) <= -1.05 or attempt == 3:
            return dec, peak, sig
        sig = sig * 10 ** ((-1.25 - db(peak)) / 20.0)
    raise AssertionError("unreachable")


def render_variant(event: str, index: int) -> dict:
    spec = SPECS[event]
    sr = spec["sr"]
    rng = rng_for(f"{event}#{index}")
    sig = np.asarray(spec["fn"](rng, index, sr), dtype=np.float64)
    if not np.all(np.isfinite(sig)):
        raise RuntimeError(f"{event} #{index}: non-finite samples")
    stereo = sig.ndim == 2
    if stereo != spec["stereo"]:
        raise RuntimeError(f"{event} #{index}: stereo mismatch")
    if stereo:
        sig = sig - sig.mean(axis=0)
        sig = np.stack([highpass(sig[:, c], 22, 2, sr, circular=spec["loop"]) for c in range(2)], axis=1)
    else:
        sig = highpass(sig - sig.mean(), 22, 2, sr, circular=spec["loop"])
    if not spec["loop"]:
        fi, fo = spec["fades"]
        if stereo:
            sig = np.stack([fade(sig[:, c], fi, fo, sr) for c in range(2)], axis=1)
        else:
            sig = fade(sig, fi, fo, sr)
    sig = normalise(sig, spec["peak"], spec["rms"])
    path, ref = variant_path(event, index, spec["variants"])
    channels = 2 if stereo else 1
    info = {"event": event, "index": index, "path": str(path.relative_to(ROOT)), "ref": ref,
            "channels": channels, "sr": sr}
    if spec["loop"]:
        # The loop is exactly periodic, so any rotation of it is the same loop: try a few quiet
        # start points and keep the one whose decoded seam is cleanest.
        best = None
        for rot in loop_rotations(sig, sr):
            rotated = np.roll(sig, -rot, axis=0)
            dec, peak, final = encode_checked(rotated, path, sr, channels, spec["quality"])
            click = seam_click(dec)
            if best is None or click < best[0]:
                best = (click, rot, final)
            if click < 0.25:
                break
        click, rot, final = best
        dec, peak, _ = encode_checked(final, path, sr, channels, spec["quality"])
        info["seam"] = seam_click(dec)
        info["length_ok"] = dec.shape[0] == final.shape[0]
    else:
        dec, peak, _ = encode_checked(sig, path, sr, channels, spec["quality"])
    info.update({"seconds": dec.shape[0] / sr, "peak_db": db(peak), "rms_db": db(rms(dec)),
                 "bytes": path.stat().st_size})
    return info


def registered_events() -> list[str]:
    src = NULL_SOUNDS_JAVA.read_text(encoding="utf-8")
    return re.findall(r'sound\("([a-z0-9_.]+)"\)', src)


SUBTITLE_KEYS = {}


def build_sounds_json(events: list[str]) -> dict:
    out = {}
    for event in events:
        spec = SPECS[event]
        entry = {}
        if spec["subtitle"]:
            key = f"subtitles.{MODID}.{event}"
            entry["subtitle"] = key
            SUBTITLE_KEYS[key] = spec["subtitle"]
        sounds = []
        for i in range(spec["variants"]):
            _, ref = variant_path(event, i, spec["variants"])
            item = {"name": ref}
            if spec["stream"]:
                item["stream"] = True
            if spec["attenuation"] and spec["attenuation"] != 16:
                item["attenuation_distance"] = spec["attenuation"]
            sounds.append(item if len(item) > 1 else ref)
        entry["sounds"] = sounds
        out[event] = entry
    return out


def spectrogram_png(path: pathlib.Path, x: np.ndarray, sr: int, title: str = ""):
    """Waveform strip plus a spectrogram (0..12 kHz, 75 dB range) for checking a sound by eye."""
    from PIL import Image, ImageDraw
    mono = x if x.ndim == 1 else x.mean(axis=1)
    n_fft, hop = 1024, 128
    if len(mono) < n_fft:
        mono = np.pad(mono, (0, n_fft - len(mono)))
    frames = np.lib.stride_tricks.sliding_window_view(mono, n_fft)[::hop] * np.hanning(n_fft)
    mag = 20 * np.log10(np.abs(np.fft.rfft(frames, axis=1)) + 1e-9)
    fmax = min(sr / 2, 12000.0)
    bins = int(fmax / (sr / n_fft))
    mag = mag[:, :bins]
    img = np.clip((mag - (mag.max() - 75.0)) / 75.0, 0, 1).T[::-1]
    width = int(np.clip(len(mono) / sr * 220, 120, 1800))
    spec = Image.fromarray((img * 255).astype(np.uint8)).resize((width, 260), Image.BILINEAR)
    lut = np.zeros((256, 3), dtype=np.uint8)
    for i in range(256):
        v = i / 255.0
        lut[i] = (min(255, int(255 * min(1, v * 1.6))), int(255 * max(0, v * 1.5 - 0.5) ** 1.2),
                  int(255 * (0.35 * np.sin(np.pi * v) + max(0, v - 0.85) * 4)))
    spec = Image.fromarray(lut[np.asarray(spec)])
    wave = Image.new("RGB", (width, 70), (10, 10, 14))
    d = ImageDraw.Draw(wave)
    cols = np.array_split(mono, width)
    for i, c in enumerate(cols):
        if len(c):
            hi, lo = float(c.max()), float(c.min())
            d.line([(i, 35 - hi * 33), (i, 35 - lo * 33)], fill=(120, 200, 255))
    sheet = Image.new("RGB", (width, 70 + 260 + 14), (0, 0, 0))
    sheet.paste(wave, (0, 14))
    sheet.paste(spec, (0, 84))
    ImageDraw.Draw(sheet).text((2, 1), f"{title}  {len(mono) / sr:.2f}s", fill=(255, 255, 0))
    sheet.save(path)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--only", nargs="*", help="only render events starting with these prefixes (sounds.json is still written)")
    parser.add_argument("--jobs", type=int, default=max(1, (os.cpu_count() or 2)))
    parser.add_argument("--spectrograms", type=pathlib.Path, help="write a spectrogram PNG per file into this directory")
    args = parser.parse_args()

    if shutil.which("ffmpeg") is None:
        print("ffmpeg is required", file=sys.stderr)
        return 1
    events = registered_events()
    missing = [e for e in events if e not in SPECS]
    extra = [e for e in SPECS if e not in events]
    if missing or extra:
        print(f"events without a recipe: {missing}; recipes without an event: {extra}", file=sys.stderr)
        return 1

    selected = [e for e in events if not args.only or any(e.startswith(p) for p in args.only)]
    # Only the folders of this script's own events are rebuilt: other areas generate their own
    # sounds into their own folders (assets-src/sounds/<area>.json fragments), which stay.
    own_groups = sorted({file_stem(e)[0] for e in events})
    if not args.only:
        for group in own_groups:
            if (SOUND_DIR / group).exists():
                shutil.rmtree(SOUND_DIR / group)
    jobs = [(e, i) for e in selected for i in range(SPECS[e]["variants"])]
    results = []
    with concurrent.futures.ProcessPoolExecutor(max_workers=args.jobs) as pool:
        futures = {pool.submit(render_variant, e, i): (e, i) for e, i in jobs}
        for fut in concurrent.futures.as_completed(futures):
            results.append(fut.result())
    results.sort(key=lambda r: (events.index(r["event"]), r["index"]))

    sounds_json = build_sounds_json(events)
    SOUNDS_JSON.write_text(json.dumps(sounds_json, indent=2) + "\n", encoding="utf-8")
    LANG_FRAGMENT.parent.mkdir(parents=True, exist_ok=True)
    LANG_FRAGMENT.write_text(json.dumps(dict(sorted(SUBTITLE_KEYS.items())), indent=2, ensure_ascii=False) + "\n",
                             encoding="utf-8")

    # Verification: every referenced file exists, every event has an entry, nothing is left over.
    problems = []
    referenced = set()
    for event, entry in sounds_json.items():
        for item in entry["sounds"]:
            ref = item if isinstance(item, str) else item["name"]
            group_name = ref.split(":", 1)[1]
            f = SOUND_DIR / f"{group_name}.ogg"
            referenced.add(f)
            if not f.exists():
                problems.append(f"{event}: missing file {f.relative_to(ROOT)}")
    for event in events:
        if event not in sounds_json:
            problems.append(f"no sounds.json entry for {event}")
    on_disk = {f for group in own_groups for f in (SOUND_DIR / group).rglob("*.ogg")}
    for f in sorted(on_disk - referenced):
        problems.append(f"unreferenced file {f.relative_to(ROOT)}")
    for r in results:
        if r["peak_db"] > -1.0:
            problems.append(f"{r['path']}: peak {r['peak_db']:.2f} dBFS")
        if r.get("seam", 0) > 1.0:
            problems.append(f"{r['path']}: loop seam jump {r['seam']:.2f}")
        if r.get("length_ok") is False:
            problems.append(f"{r['path']}: decoded loop length differs from the rendered length")

    if args.spectrograms:
        args.spectrograms.mkdir(parents=True, exist_ok=True)
        for r in results:
            f = ROOT / r["path"]
            x = decode(f, r["sr"], r["channels"])
            spectrogram_png(args.spectrograms / (f.parent.name + "_" + f.stem + ".png"), x, r["sr"], r["ref"])

    total_bytes = sum(f.stat().st_size for f in on_disk)
    for r in results:
        extra_info = f"  seam {r['seam']:.3f}" if "seam" in r else ""
        print(f"{r['ref']:<40} {r['seconds']:6.2f}s {r['channels']}ch {r['sr']:>5}Hz  peak {r['peak_db']:6.2f} dBFS  "
              f"rms {r['rms_db']:6.1f} dBFS  {r['bytes'] / 1024:7.1f} KiB{extra_info}")
    print(f"{len(on_disk)} files, {total_bytes / 1048576:.2f} MiB, {len(events)} events, {len(SUBTITLE_KEYS)} subtitles")
    if problems:
        for p in problems:
            print("ERROR:", p, file=sys.stderr)
        return 1
    # sounds.json was written from scratch: add the other areas' fragments back.
    return subprocess.run([sys.executable, str(ROOT / "tools" / "merge_sounds.py")]).returncode


if __name__ == "__main__":
    sys.exit(main())
