#!/usr/bin/env python3
"""Generates the blood graffiti "Leave while you can" for NULL's container screens (deterministic).

The words are not set in a font: every letter is a hand-designed skeleton of strokes (the way
someone would scrawl it with a finger), smoothed into curves and painted with a wet brush whose
width, pressure and wobble change along the stroke. Paint pools where strokes start and turn, runs
down in drips that end in drops, and splatters a little around the words.

Output (under src/main/resources/assets/null/textures/gui/menu/):
  graffiti.png   512x512 RGBA, two versions of the same scrawl:
                   top half    (v 0.0-0.5)  old and dried: brown-red, cracked and patchy, short drips
                   bottom half (v 0.5-1.0)  fresh: deep wet red, glossy edge, long running drips
                 The menu code draws the dried one faintly in the first stages and fades over to
                 the fresh one as the corruption grows (see InventoryGraffiti).

Usage: python3 tools/gen_graffiti.py [--preview DIR]
  --preview DIR  also composites both versions at every stage onto mock-ups of the vanilla
                 inventory, a chest and a furnace (GUI scale 3) the way the menu code draws them.
"""
import argparse
import math
import pathlib

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "src" / "main" / "resources" / "assets" / "null" / "textures" / "gui" / "menu" / "graffiti.png"

W, H = 512, 256          # one version
SS = 2                   # supersampling while painting
SEED = 0x6C656176        # "leav"

# --------------------------------------------------------------------------------------------
# letters: strokes of (x, y) points in x-height units (baseline y = 0, x-height y = 1, up is +)
# --------------------------------------------------------------------------------------------
GLYPHS = {
    "L": (1.02, [[(0.16, 1.78), (0.10, 1.0), (0.04, 0.06)], [(0.04, 0.04), (0.50, 0.08), (0.98, 0.02)]]),
    "e": (0.86, [[(0.06, 0.52), (0.42, 0.56), (0.74, 0.62), (0.70, 0.88), (0.42, 1.02), (0.12, 0.84),
                  (0.03, 0.42), (0.18, 0.06), (0.48, -0.02), (0.80, 0.14)]]),
    "a": (0.98, [[(0.74, 0.80), (0.52, 1.0), (0.18, 0.88), (0.04, 0.45), (0.16, 0.06), (0.42, 0.0),
                  (0.68, 0.24), (0.76, 0.66)],
                 [(0.80, 1.04), (0.76, 0.44), (0.78, 0.10), (0.94, 0.0)]]),
    "v": (0.92, [[(0.0, 1.02), (0.22, 0.48), (0.42, 0.0), (0.64, 0.56), (0.90, 1.08)]]),
    "w": (1.30, [[(0.0, 1.02), (0.14, 0.45), (0.30, 0.0), (0.46, 0.44), (0.62, 0.80), (0.78, 0.40),
                  (0.94, 0.0), (1.10, 0.50), (1.28, 1.06)]]),
    "h": (0.92, [[(0.10, 1.80), (0.06, 0.9), (0.02, 0.0)],
                 [(0.04, 0.52), (0.26, 0.92), (0.56, 0.98), (0.74, 0.64), (0.78, 0.0)]]),
    "i": (0.44, [[(0.18, 1.0), (0.14, 0.42), (0.12, 0.0)], "dot", (0.22, 1.46)]),
    "l": (0.46, [[(0.20, 1.80), (0.14, 0.9), (0.10, 0.10), (0.30, 0.0)]]),
    "y": (0.92, [[(0.0, 1.02), (0.18, 0.52), (0.40, 0.04)],
                 [(0.88, 1.08), (0.60, 0.44), (0.40, -0.10), (0.24, -0.56), (0.06, -0.72)]]),
    "o": (0.92, [[(0.50, 1.0), (0.18, 0.88), (0.04, 0.44), (0.18, 0.04), (0.48, -0.02), (0.74, 0.22),
                  (0.80, 0.62), (0.62, 0.98), (0.36, 0.98)]]),
    "u": (0.98, [[(0.0, 1.02), (0.04, 0.40), (0.20, 0.04), (0.46, 0.0), (0.68, 0.28), (0.78, 0.70)],
                 [(0.82, 1.06), (0.78, 0.44), (0.80, 0.10), (0.96, 0.0)]]),
    "c": (0.84, [[(0.78, 0.84), (0.50, 1.02), (0.16, 0.86), (0.03, 0.44), (0.18, 0.04), (0.48, -0.02),
                  (0.80, 0.16)]]),
    "n": (0.92, [[(0.06, 1.04), (0.04, 0.5), (0.02, 0.0)],
                 [(0.04, 0.58), (0.28, 0.98), (0.58, 0.98), (0.74, 0.62), (0.78, 0.0)]]),
}

LINES = [
    # text, x of the first letter, baseline y (in output pixels), x-height (px), slant (shear), rotation (deg)
    ("Leave while", 40.0, 112.0, 42.0, 0.20, -5.5),
    ("you can", 148.0, 210.0, 44.0, 0.22, -4.0),
]
LETTER_GAP = 0.20   # extra space between letters, in x-height units
WORD_GAP = 0.62
# the words (with the width of their strokes) must stay inside this box of each half, so nothing
# is cut off at the texture's edges or bleeds into the other half when the game filters it;
# the space below is for the drips, which stop before the bottom edge
FIT_LEFT, FIT_RIGHT, FIT_TOP, FIT_BOTTOM = 12.0, W - 12.0, 10.0, H - 34.0
EDGE = 6            # every pixel this close to an edge of a half fades out


def catmull_rom(points, samples_per_unit):
    """Smooth curve through the points (centripetal-ish Catmull-Rom), returned as an (n, 2) array."""
    pts = np.asarray(points, dtype=np.float64)
    if len(pts) == 1:
        return pts
    ext = np.vstack([2 * pts[0] - pts[1], pts, 2 * pts[-1] - pts[-2]])
    out = []
    for i in range(1, len(ext) - 2):
        p0, p1, p2, p3 = ext[i - 1], ext[i], ext[i + 1], ext[i + 2]
        seg = np.linalg.norm(p2 - p1)
        n = max(2, int(seg * samples_per_unit))
        for t in np.linspace(0.0, 1.0, n, endpoint=False):
            t2, t3 = t * t, t * t * t
            out.append(0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2
                              + (-p0 + 3 * p1 - 3 * p2 + p3) * t3))
    out.append(pts[-1])
    return np.asarray(out)


def value_noise(h, w, cell, rng):
    """Smooth value noise in 0..1 (bilinear interpolation of a random lattice)."""
    gh, gw = h // cell + 2, w // cell + 2
    grid = rng.random((gh, gw))
    ys = np.arange(h) / cell
    xs = np.arange(w) / cell
    y0 = np.floor(ys).astype(int)
    x0 = np.floor(xs).astype(int)
    fy = (ys - y0)[:, None]
    fx = (xs - x0)[None, :]
    fy = fy * fy * (3 - 2 * fy)
    fx = fx * fx * (3 - 2 * fx)
    a = grid[y0][:, x0]
    b = grid[y0][:, x0 + 1]
    c = grid[y0 + 1][:, x0]
    d = grid[y0 + 1][:, x0 + 1]
    return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy


def blur(a, sigma):
    if sigma <= 0:
        return a.copy()
    r = int(max(1, round(sigma * 3)))
    x = np.arange(-r, r + 1, dtype=np.float32)
    k = np.exp(-(x * x) / (2 * sigma * sigma))
    k /= k.sum()
    p = np.pad(a, ((r, r), (0, 0)), mode="edge")
    out = np.zeros_like(a)
    for i, wgt in enumerate(k):
        out += wgt * p[i:i + a.shape[0], :]
    p = np.pad(out, ((0, 0), (r, r)), mode="edge")
    out2 = np.zeros_like(a)
    for i, wgt in enumerate(k):
        out2 += wgt * p[:, i:i + a.shape[1]]
    return out2


class Canvas:
    """Paint coverage at SS x resolution; stamps soft discs."""

    def __init__(self):
        self.h, self.w = H * SS, W * SS
        self.paint = np.zeros((self.h, self.w), np.float32)
        self.wet = np.zeros((self.h, self.w), np.float32)     # where paint pooled (thicker, darker)
        self.streak = np.zeros((self.h, self.w), np.float32)  # finger ridges dragged along the stroke

    def _window(self, cx, cy, r):
        x0, x1 = int(max(0, cx - r - 2)), int(min(self.w, cx + r + 3))
        y0, y1 = int(max(0, cy - r - 2)), int(min(self.h, cy + r + 3))
        if x0 >= x1 or y0 >= y1:
            return None
        ys, xs = np.mgrid[y0:y1, x0:x1].astype(np.float32)
        return x0, x1, y0, y1, np.hypot(xs + 0.5 - cx, ys + 0.5 - cy)

    def disc(self, cx, cy, r, amount=1.0, pool=0.0):
        cx, cy, r = cx * SS, cy * SS, max(0.6, r * SS)
        win = self._window(cx, cy, r)
        if win is None:
            return
        x0, x1, y0, y1, d = win
        cov = np.clip(r + 0.5 - d, 0.0, 1.0) * amount
        np.maximum(self.paint[y0:y1, x0:x1], cov, out=self.paint[y0:y1, x0:x1])
        if pool > 0:
            np.maximum(self.wet[y0:y1, x0:x1], cov * pool, out=self.wet[y0:y1, x0:x1])

    def ridge(self, cx, cy, r, amount):
        cx, cy, r = cx * SS, cy * SS, max(0.5, r * SS)
        win = self._window(cx, cy, r)
        if win is None:
            return
        x0, x1, y0, y1, d = win
        cov = np.clip(r + 0.5 - d, 0.0, 1.0) * amount
        np.maximum(self.streak[y0:y1, x0:x1], cov, out=self.streak[y0:y1, x0:x1])


def layout_strokes(rng):
    """All strokes of both lines in output pixels: list of (points (n,2), width, is_dot)."""
    strokes = []
    for text, x0, base, xh, shear, rot in LINES:
        pen = 0.0
        cos_r, sin_r = math.cos(math.radians(rot)), math.sin(math.radians(rot))
        pivot = (x0, base)
        for ch in text:
            if ch == " ":
                pen += WORD_GAP
                continue
            advance, parts = GLYPHS[ch]
            # every letter a little different: size, lean, height wobble
            k = 1.0 + rng.normal(0.0, 0.05)
            lean = shear + rng.normal(0.0, 0.05)
            lift = rng.normal(0.0, 0.05)
            i = 0
            while i < len(parts):
                part = parts[i]
                if part == "dot":
                    dx, dy = parts[i + 1]
                    pts = [(dx, dy)]
                    i += 2
                    is_dot = True
                else:
                    pts = part
                    i += 1
                    is_dot = False
                out = []
                for (u, v) in pts:
                    # small hand tremor on every control point
                    u += rng.normal(0.0, 0.025)
                    v += rng.normal(0.0, 0.03)
                    lx = (pen + u * k + v * lean) * xh
                    ly = -(v * k + lift) * xh
                    gx = pivot[0] + lx * cos_r - ly * sin_r
                    gy = pivot[1] + lx * sin_r + ly * cos_r
                    out.append((gx, gy))
                strokes.append((out, xh, is_dot))
            pen += advance * k + LETTER_GAP
    return fit(strokes)


def fit(strokes):
    """Scales and moves the whole scrawl (uniformly) into the FIT box, stroke widths included."""
    xs, ys = [], []
    for pts, xh, _ in strokes:
        pad = xh * 0.16
        for (x, y) in pts:
            xs += [x - pad, x + pad]
            ys += [y - pad, y + pad]
    x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
    k = min(1.0, (FIT_RIGHT - FIT_LEFT) / (x1 - x0), (FIT_BOTTOM - FIT_TOP) / (y1 - y0))
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    tx = (FIT_LEFT + FIT_RIGHT) / 2
    ty = min(max(cy, FIT_TOP + (y1 - y0) * k / 2), FIT_BOTTOM - (y1 - y0) * k / 2)
    return [([(tx + (x - cx) * k, ty + (y - cy) * k) for (x, y) in pts], xh * k, dot) for pts, xh, dot in strokes]


def paint_version(fresh, seed):
    rng = np.random.default_rng(seed)
    canvas = Canvas()
    drips = []
    strokes = layout_strokes(np.random.default_rng(SEED))  # same scrawl in both versions
    previous_end = None
    for pts, xh, is_dot in strokes:
        base_r = xh * 0.105
        if is_dot:
            x, y = pts[0]
            canvas.disc(x, y, base_r * 1.25, 1.0, pool=1.0)
            if rng.random() < (0.9 if fresh else 0.5):
                drips.append((x, y + base_r, base_r * 0.6))
            continue
        curve = catmull_rom(pts, samples_per_unit=1.4)
        seglen = np.r_[0.0, np.cumsum(np.hypot(np.diff(curve[:, 0]), np.diff(curve[:, 1])))]
        total = max(1e-3, seglen[-1])
        phase = rng.random() * 10
        lanes = rng.uniform(0.25, 1.0, size=3)          # ridges of the fingertip, each its own strength
        retrace = rng.random() < 0.35                    # went over it a second time
        offset = rng.normal(0.0, base_r * 0.35, size=2)
        for j, (x, y) in enumerate(curve):
            s = seglen[j] / total
            # finger pressure: heavier where it lands, thinner as the paint runs out
            pressure = 1.0 + 0.22 * math.exp(-s * 6.0) - 0.32 * s + 0.10 * math.sin(phase + seglen[j] * 0.21)
            r = base_r * pressure * (1.0 + rng.normal(0.0, 0.04))
            canvas.disc(x, y, r, 1.0, pool=max(0.0, (pressure - 1.1) * 2.5))
            # direction of travel, for the ridges across the stroke
            a = curve[min(j + 1, len(curve) - 1)] - curve[max(j - 1, 0)]
            n = math.hypot(a[0], a[1])
            if n > 1e-6:
                nx, ny = -a[1] / n, a[0] / n
                for lane, k in zip((-0.55, 0.0, 0.55), lanes):
                    canvas.ridge(x + nx * r * lane, y + ny * r * lane, r * 0.16, k)
            # ragged edge where the finger was drier
            if rng.random() < 0.22:
                ang = rng.random() * 2 * math.pi
                canvas.disc(x + math.cos(ang) * r * 0.85, y + math.sin(ang) * r * 0.85, r * 0.32, 0.7)
            if retrace and 0.08 < s < 0.92:
                canvas.disc(x + offset[0], y + offset[1], r * 0.62, 0.95)
        # paint runs down from the lowest points and from where strokes start or end (not from a
        # stroke that starts where the previous one ended: two runs from one corner read as a leg)
        joined = previous_end is not None and math.hypot(curve[0][0] - previous_end[0], curve[0][1] - previous_end[1]) < base_r * 3
        previous_end = (curve[-1][0], curve[-1][1])
        candidates = ([] if joined else [0]) + [len(curve) - 1]
        ys = curve[:, 1]
        for j in range(2, len(curve) - 2, 3):
            if ys[j] >= ys[j - 2] and ys[j] >= ys[j + 2]:
                candidates.append(j)
        for j in candidates:
            chance = 0.62 if fresh else 0.40
            if rng.random() < chance:
                x, y = curve[j]
                if any(abs(x - dx) < base_r * 2.2 and abs(y - dy) < base_r * 4 for dx, dy, _ in drips):
                    continue  # runs never bunch up
                drips.append((x + rng.normal(0, base_r * 0.3), y + base_r * 0.6, base_r * rng.uniform(0.45, 0.75)))

    # drips: thin runs straight down, wobbling a little, ending in a drop above the bottom edge
    for (x, y, r) in drips:
        length = rng.uniform(10, 46) if fresh else rng.uniform(5, 20)
        if rng.random() < (0.22 if fresh else 0.08):
            length *= rng.uniform(1.6, 2.4)
        drop = r * (1.05 if fresh else 0.8)
        length = min(length, H - EDGE - 3 - y - drop * 1.4)
        # a run stops before it reaches other paint below it (one running through the opening of
        # a "c" would close it into an "o")
        below = canvas.paint[:, int(min(W * SS - 1, max(0, x * SS)))]
        for yy in np.arange(y + r * 2.4, y + length + drop * 2.0, 0.5):
            if yy * SS >= H * SS or below[int(yy * SS)] > 0.4:
                length = min(length, yy - y - drop * 2.2 - 2.0)
                break
        if length < 3:
            continue
        steps = int(length * 2)
        slant = rng.uniform(-0.035, 0.035)   # gravity: runs go straight down, barely wandering
        cx = x
        for k in range(steps):
            t = k / max(1, steps - 1)
            cx += slant * 0.5 + rng.normal(0, 0.025)
            rr = r * (1.0 - 0.55 * t) * (1.0 + 0.08 * math.sin(k * 0.7))
            canvas.disc(cx, y + t * length, rr, 1.0, pool=0.3)
        canvas.disc(cx, y + length + drop * 0.4, drop, 1.0, pool=1.0)

    # splatter flicked off the finger
    for _ in range(40 if fresh else 22):
        x = rng.uniform(30, W - 30)
        y = rng.uniform(30, H - 20)
        s = rng.uniform(0.6, 2.4)
        canvas.disc(x, y, s, rng.uniform(0.6, 1.0), pool=0.5)

    paint = canvas.paint
    wet = blur(canvas.wet, 1.5 * SS)
    streak = blur(canvas.streak, 0.6 * SS) * paint
    # downsample the supersampled coverage
    cov = paint.reshape(H, SS, W, SS).mean(axis=(1, 3))
    wet = wet.reshape(H, SS, W, SS).mean(axis=(1, 3))
    streak = streak.reshape(H, SS, W, SS).mean(axis=(1, 3))
    return colour(cov, wet, streak, fresh, np.random.default_rng(seed + 1))


def colour(cov, wet, streak, fresh, rng):
    """Turns paint coverage into RGBA: dark rims, uneven body, finger ridges, gloss (fresh) or a
    thin, faded, hairline-cracked crust (dried)."""
    edge = np.clip(cov - blur(cov, 1.2), 0.0, 1.0)            # rim of every stroke
    inner = blur(cov, 2.0)                                     # how deep inside the paint a pixel is
    mottling = value_noise(H, W, 9, rng) * 0.65 + value_noise(H, W, 3, rng) * 0.35
    if fresh:
        base = np.array([0.50, 0.02, 0.02])
        body = base[None, None, :] * (0.82 + 0.26 * mottling[..., None])
        body -= 0.12 * wet[..., None] * np.array([1.0, 0.2, 0.2])[None, None, :]
        body += np.array([0.10, 0.02, 0.02])[None, None, :] * streak[..., None]
        # wet gloss along the upper-left rim of the strokes
        soft = blur(cov, 1.0)
        light = np.clip(soft - np.roll(np.roll(soft, 2, axis=0), 2, axis=1), 0.0, 1.0)
        body += np.array([0.20, 0.05, 0.05])[None, None, :] * light[..., None]
        body -= np.array([0.12, 0.0, 0.0])[None, None, :] * edge[..., None]
        alpha = np.clip(cov * (0.88 + 0.12 * np.maximum(wet, streak * 0.5)), 0.0, 1.0)
    else:
        base = np.array([0.30, 0.07, 0.05])
        body = base[None, None, :] * (0.78 + 0.34 * mottling[..., None])
        body -= np.array([0.08, 0.025, 0.015])[None, None, :] * edge[..., None]
        body -= np.array([0.05, 0.012, 0.008])[None, None, :] * wet[..., None]
        body += np.array([0.03, 0.006, 0.004])[None, None, :] * streak[..., None]
        # old paint: thinner in places (smooth), a few hairline cracks, nothing that looks like a pattern
        thin = value_noise(H, W, 14, rng)
        cracks = np.abs(value_noise(H, W, 7, rng) - 0.5) < 0.018
        keep = (0.62 + 0.38 * np.clip(thin * 1.4, 0.0, 1.0)) * np.where(cracks, 0.55, 1.0)
        alpha = np.clip(cov * keep * (0.80 + 0.20 * inner), 0.0, 1.0)
    body = np.clip(body, 0.0, 1.0)
    # nothing at the very edges of a half (the game filters the texture linearly)
    ys, xs = np.mgrid[0:H, 0:W].astype(np.float32)
    dist = np.minimum(np.minimum(xs, W - 1 - xs), np.minimum(ys, H - 1 - ys))
    alpha = alpha * np.clip((dist - 1.0) / (EDGE - 1.0), 0.0, 1.0)
    out = np.zeros((H, W, 4), np.uint8)
    out[..., :3] = (body * 255 + 0.5).astype(np.uint8)
    out[..., 3] = (alpha * 255 + 0.5).astype(np.uint8)
    out[out[..., 3] == 0, :3] = 0
    return out


# --------------------------------------------------------------------------------------------
# preview: mock container screens composited like InventoryGraffiti draws the graffiti
# --------------------------------------------------------------------------------------------

# mirrors of InventoryGraffiti (index = stage - 1); keep them in step with the Java
DRIED = [0.34, 0.46, 0.40, 0.22, 0.12, 0.08]
FRESH = [0.0, 0.0, 0.32, 0.62, 0.76, 0.86]
WIDTH_SHARE = 0.92      # of the panel width
MAX_WIDTH = 300.0       # GUI units


def slot(d, x, y, s):
    d.rectangle([x, y, x + 18 * s - 1, y + 18 * s - 1], fill=(139, 139, 139))
    d.rectangle([x, y, x + 18 * s - 1, y + s - 1], fill=(55, 55, 55))
    d.rectangle([x, y, x + s - 1, y + 18 * s - 1], fill=(55, 55, 55))
    d.rectangle([x, y + 17 * s, x + 18 * s - 1, y + 18 * s - 1], fill=(255, 255, 255))
    d.rectangle([x + 17 * s, y, x + 18 * s - 1, y + 18 * s - 1], fill=(255, 255, 255))


def panel(kind, s):
    """A vanilla-looking container panel (GUI scale s) and where its items sit."""
    w, h = {"inventory": (176, 166), "chest": (176, 166), "furnace": (176, 166)}[kind]
    img = Image.new("RGBA", (w * s, h * s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([s, s, (w - 1) * s - 1, (h - 1) * s - 1], fill=(198, 198, 198))
    d.rectangle([0, s * 2, s - 1, (h - 2) * s - 1], fill=(0, 0, 0))
    d.rectangle([(w - 1) * s, s * 2, w * s - 1, (h - 2) * s - 1], fill=(0, 0, 0))
    d.rectangle([s * 2, 0, (w - 2) * s - 1, s - 1], fill=(0, 0, 0))
    d.rectangle([s * 2, (h - 1) * s, (w - 2) * s - 1, h * s - 1], fill=(0, 0, 0))
    d.rectangle([s, s, (w - 2) * s - 1, s * 3 - 1], fill=(255, 255, 255))
    d.rectangle([s, s, s * 3 - 1, (h - 2) * s - 1], fill=(255, 255, 255))
    d.rectangle([s * 3, (h - 3) * s, (w - 1) * s - 1, (h - 1) * s - 1], fill=(85, 85, 85))
    d.rectangle([(w - 3) * s, s * 3, (w - 1) * s - 1, (h - 1) * s - 1], fill=(85, 85, 85))
    slots = []
    for row in range(3):
        for col in range(9):
            slots.append((7 + col * 18, 83 + row * 18))
    for col in range(9):
        slots.append((7 + col * 18, 141))
    if kind == "inventory":
        for row in range(4):
            slots.append((7, 7 + row * 18))
        slots.append((76, 61))
        for row in range(2):
            for col in range(2):
                slots.append((97 + col * 18, 17 + row * 18))
        slots.append((153, 27))
        d.rectangle([25 * s, 7 * s, 76 * s - 1, 78 * s - 1], fill=(0, 0, 0))
    elif kind == "chest":
        for row in range(3):
            for col in range(9):
                slots.append((7 + col * 18, 17 + row * 18))
    else:
        slots += [(55, 16), (55, 52), (111, 34)]
    for (x, y) in slots:
        slot(d, x * s, y * s, s)
    return img, slots


def player_doll(img, s):
    d = ImageDraw.Draw(img)
    # a stand-in for the 3D player (drawn above the graffiti by the depth test in game)
    cx = 51 * s
    d.rectangle([cx - 4 * s, 14 * s, cx + 4 * s, 22 * s], fill=(160, 110, 80))
    d.rectangle([cx - 5 * s, 22 * s, cx + 5 * s, 46 * s], fill=(40, 160, 170))
    d.rectangle([cx - 5 * s, 46 * s, cx + 5 * s, 70 * s], fill=(60, 60, 150))


def items(img, slots, s, rng):
    d = ImageDraw.Draw(img)
    colours = [(150, 110, 70), (90, 90, 90), (230, 200, 60), (60, 140, 60), (200, 200, 210), (140, 70, 40)]
    for (x, y) in slots:
        if rng.random() < 0.28:
            c = colours[rng.integers(len(colours))]
            d.rectangle([(x + 2) * s, (y + 2) * s, (x + 15) * s, (y + 15) * s], fill=c)
            d.rectangle([(x + 2) * s, (y + 2) * s, (x + 15) * s, (y + 4) * s], fill=tuple(min(255, v + 40) for v in c))


def composite_graffiti(img, tex, stage, s, panel_w, panel_h, dark_bg):
    """Like InventoryGraffiti: centred on the panel, two layers with the stage's strengths."""
    gw = min(panel_w * WIDTH_SHARE, MAX_WIDTH)
    gh = gw * 0.5
    x0 = (panel_w - gw) * 0.5
    y0 = (panel_h - gh) * 0.5 - panel_h * 0.02
    size = (int(round(gw * s)), int(round(gh * s)))
    out = img.copy()
    for layer, strength in ((0, DRIED[stage - 1]), (1, FRESH[stage - 1])):
        if strength <= 0:
            continue
        part = tex.crop((0, layer * H, W, (layer + 1) * H)).resize(size, Image.BILINEAR)
        arr = np.asarray(part).astype(np.float32)
        arr[..., 3] *= strength
        layer_img = Image.fromarray(arr.clip(0, 255).astype(np.uint8), "RGBA")
        out.alpha_composite(layer_img, (int(round(x0 * s)), int(round(y0 * s))))
    return out


def preview(out_dir, tex):
    out_dir.mkdir(parents=True, exist_ok=True)
    s = 3
    rng = np.random.default_rng(7)
    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 8 * s)
    except OSError:
        font = ImageFont.load_default()
    # the raw texture on grey (both halves)
    sheet = Image.new("RGBA", tex.size, (198, 198, 198, 255))
    sheet.alpha_composite(tex)
    sheet.save(out_dir / "graffiti_sheet.png")
    boards = []
    for kind in ("inventory", "chest", "furnace"):
        row = []
        for stage in range(1, 7):
            base, slots = panel(kind, s)
            if kind == "inventory":
                pass
            img = composite_graffiti(base, tex, stage, s, 176, 166, True)
            if kind == "inventory":
                player_doll(img, s)
            items(img, slots, s, np.random.default_rng(stage * 11 + len(kind)))
            d = ImageDraw.Draw(img)
            title = {"inventory": "Crafting", "chest": "Chest", "furnace": "Furnace"}[kind]
            d.text(((97 if kind == "inventory" else 8) * s, 6 * s - 2), title, fill=(64, 64, 64), font=font)
            d.text((8 * s, 72 * s), "Inventory", fill=(64, 64, 64), font=font)
            frame = Image.new("RGBA", (img.width + 24, img.height + 40), (16, 18, 22, 255))
            frame.alpha_composite(img, (12, 30))
            ImageDraw.Draw(frame).text((12, 6), f"{kind} - stage {stage}", fill=(220, 220, 220), font=font)
            row.append(frame)
        boards.append(row)
    for kind_rows, name in zip(boards, ("inventory", "chest", "furnace")):
        wsum = sum(f.width for f in kind_rows[:3])
        board = Image.new("RGBA", (wsum, kind_rows[0].height * 2), (16, 18, 22, 255))
        for k, f in enumerate(kind_rows):
            board.alpha_composite(f, ((k % 3) * f.width, (k // 3) * f.height))
        board.convert("RGB").save(out_dir / f"graffiti_{name}.png")
    # one full-size inventory at stage 4 on a darkened world, like the game shows it
    world = Image.new("RGBA", (1280, 720), (40, 52, 34, 255))
    dw = ImageDraw.Draw(world)
    for y in range(720):
        dw.line([(0, y), (1280, y)], fill=(int(70 - y * 0.03), int(90 - y * 0.05), int(120 - y * 0.09), 255))
    world.alpha_composite(Image.new("RGBA", world.size, (16, 16, 16, 192)))
    base, slots = panel("inventory", s)
    img = composite_graffiti(base, tex, 4, s, 176, 166, True)
    player_doll(img, s)
    items(img, slots, s, rng)
    world.alpha_composite(img, ((1280 - img.width) // 2, (720 - img.height) // 2))
    world.convert("RGB").save(out_dir / "graffiti_ingame_stage4.png")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", type=pathlib.Path, default=None)
    args = ap.parse_args()
    dried = paint_version(False, SEED + 1)
    fresh = paint_version(True, SEED + 2)
    tex = Image.fromarray(np.concatenate([dried, fresh], axis=0), "RGBA")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    tex.save(OUT, optimize=True)
    print(f"graffiti.png {tex.size[0]}x{tex.size[1]} -> {OUT.relative_to(ROOT)}")
    if args.preview:
        preview(args.preview, tex)
        print(f"previews -> {args.preview}")


if __name__ == "__main__":
    main()
