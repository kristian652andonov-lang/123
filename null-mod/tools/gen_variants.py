#!/usr/bin/env python3
"""Corrupted and parasite mobs: shipped textures, and the offline proof of how they look.

The client (client/variant/) builds most of the look at run time from each mob's own texture.
This script
  * writes the few textures the client ships, into
    src/main/resources/assets/null/textures/entity/variant/:
      tentacle.png            64x64 atlas: wet black skin, skin with suckers, bone hooks, mouth
                              petals with teeth, and the red throat
      eye.png                 16x16 white pinprick glow (drawn additively: the silhouette's eyes)
      fallback_corrupted.png  generic corrupted overlay for mobs whose texture cannot be read
      fallback_infested.png   generic infested overlay (veins, wounds, sores) for the same case
  * is an exact Python port of client/variant/VariantPixels.java (same integer arithmetic, same
    SplitMix64 sequence), so the overlays it shows are the ones the game makes;
  * with --preview DIR renders stand-in mob textures (original art, vanilla-like box unwraps: a
    cow, a zombie, a chicken and a ravager; no Mojang assets) with every corrupted style and every
    infestation stage, and 3D renders (z-buffered software rasteriser with Minecraft's entity
    lighting) of the box models with their overlays, the parasite tentacles (a port of
    Tentacles.java and of ModelRecorder's anchor and face picking), the black silhouette with its
    eyes, and the parasite burst effect frame by frame;
  * with --check DIR compares the overlays against PNGs written by the Java implementation
    (VariantPixels run on the stand-ins with the seeds listed in check_cases()), pixel for pixel.

usage: python3 tools/gen_variants.py [--preview DIR] [--standins DIR] [--check DIR]
"""
import argparse
import math
import pathlib

import numpy as np
from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent.parent
TEX_OUT = ROOT / "src" / "main" / "resources" / "assets" / "null" / "textures" / "entity" / "variant"
MASK64 = (1 << 64) - 1

# ====================================================================== VariantPixels (exact port)

STYLE_GLITCH, STYLE_VOID, STYLE_MISSING, STYLE_NEGATIVE, STYLE_SMEAR, STYLE_EYES = range(6)
STYLES = 6
STYLE_NAMES = ["glitch", "void", "missing", "negative", "smear", "eyes"]
INFEST_STAGES = 3

BLACK = 0xFF000000
WHITE = 0xFFFFFFFF
MISSING_MAGENTA = 0xFFF800F8
VOID_EDGE = 0xFF0C0A0E
EYE_WHITE = 0xFFE8E6E2
EYE_HALO = 0xFF3C3C3C
VEIN_CORE = 0xFF0E050C
VEIN_FRINGE = 0xFF400A16
WOUND_CORE = 0xFF050002
WOUND_RING = 0xFF3A0610
SORE_CORE = 0xFF1A0306
SORE_RING = 0xFF6E0E16
PUS = 0xFF8C7A3C

DIR_X = [16, 15, 11, 6, 0, -6, -11, -15, -16, -15, -11, -6, 0, 6, 11, 15]
DIR_Y = [0, 6, 11, 15, 16, 15, 11, 6, 0, -6, -11, -15, -16, -15, -11, -6]
MAX_BRANCHES = 256


class Rand:
    """SplitMix64, exactly as VariantPixels.Rand."""

    def __init__(self, seed):
        self.state = seed & MASK64

    def next_long(self):
        self.state = (self.state + 0x9E3779B97F4A7C15) & MASK64
        z = self.state
        z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & MASK64
        z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & MASK64
        return z ^ (z >> 31)

    def next_int(self, bound):
        return 0 if bound <= 1 else (self.next_long() >> 33) % bound

    def range(self, lo, hi):
        return lo if hi <= lo else lo + self.next_int(hi - lo + 1)

    def unit(self):
        return (self.next_long() >> 40) / 16777216.0


def clampi(v, lo, hi):
    return lo if v < lo else (hi if v > hi else v)


def rgb(r, g, b):
    return 0xFF000000 | (clampi(r, 0, 255) << 16) | (clampi(g, 0, 255) << 8) | clampi(b, 0, 255)


def mix(a, b, weight):
    inv = 256 - weight
    r = (((a >> 16) & 255) * inv + ((b >> 16) & 255) * weight) >> 8
    g = (((a >> 8) & 255) * inv + ((b >> 8) & 255) * weight) >> 8
    bl = ((a & 255) * inv + (b & 255) * weight) >> 8
    return rgb(r, g, bl)


class Canvas:
    def __init__(self, src, w, h):
        self.src, self.w, self.h = src, w, h
        self.out = [0] * (w * h)
        self.k = max(1, w // 64)
        self.counts = max(1, (w * h) // (2048 * self.k * self.k))
        self.visible_index = [i for i in range(w * h) if (src[i] >> 24) >= 16]

    def visible(self, x, y):
        return 0 <= x < self.w and 0 <= y < self.h and (self.src[y * self.w + x] >> 24) >= 16

    def pick(self, r):
        return self.visible_index[r.next_int(len(self.visible_index))]

    def src_or(self, x, y, fallback):
        return self.src[y * self.w + x] if self.visible(x, y) else fallback

    def put(self, x, y, argb):
        if self.visible(x, y):
            self.out[y * self.w + x] = argb | 0xFF000000

    def block(self, x, y, argb):
        for oy in range(self.k):
            for ox in range(self.k):
                self.put(x + ox, y + oy, argb)


def count(c, base, per, amount):
    return max(1, (base * 100 + per * amount) * c.counts // 100)


def corrupt(src, w, h, primary, secondary, seed):
    """Returns (overlay, glow or None), lists of ARGB ints."""
    c = Canvas(src, w, h)
    if not c.visible_index:
        return c.out, None
    r = Rand(seed ^ 0x4E554C4C56415249)
    glow = _apply(c, r, primary, 100)
    if secondary >= 0 and secondary != primary:
        more = _apply(c, r, secondary, 40)
        if glow is None:
            glow = more
        elif more is not None:
            for i in range(len(glow)):
                if glow[i] == 0:
                    glow[i] = more[i]
    _dead_pixels(c, r)
    return c.out, glow


def _apply(c, r, style, amount):
    if style == STYLE_GLITCH:
        _glitch(c, r, amount)
    elif style == STYLE_VOID:
        _voids(c, r, amount)
    elif style == STYLE_MISSING:
        _missing(c, r, amount)
    elif style == STYLE_NEGATIVE:
        _negative(c, r, amount)
    elif style == STYLE_SMEAR:
        _smear(c, r, amount)
    elif style == STYLE_EYES:
        return _eyes(c, r, amount)
    return None


def _glitch(c, r, amount):
    k = c.k
    for _ in range(count(c, 5, 7, amount)):
        p = c.pick(r)
        cx, cy = p % c.w, p // c.w
        bw = r.range(2 * k, 9 * k)
        bh = r.range(k, 4 * k)
        sign = -1 if r.next_int(2) == 0 else 1
        dx = sign * r.range(3 * k, 18 * k)
        dy = r.range(-4 * k, 4 * k)
        mode = r.next_int(6)
        x0, y0 = cx - bw // 2, cy - bh // 2
        for y in range(y0, y0 + bh):
            for x in range(x0, x0 + bw):
                if not c.visible(x, y):
                    continue
                sxp = (x + dx) % c.w
                syp = clampi(y + dy, 0, c.h - 1)
                s = c.src[syp * c.w + sxp]
                if (s >> 24) < 16:
                    s = c.src[y * c.w + x]
                c.put(x, y, _transform(c, mode, s, x, y))
    for _ in range(count(c, 1, 2, amount)):
        p = c.pick(r)
        x0, y0 = p % c.w, p // c.w
        length = r.range(8 * k, 28 * k)
        shift = (-1 if r.next_int(2) == 0 else 1) * r.range(2 * k, 7 * k)
        rows = r.range(1, k)
        for y in range(y0, y0 + rows):
            for x in range(x0, x0 + length):
                if not c.visible(x, y):
                    continue
                s = c.src[y * c.w + (x - shift) % c.w]
                if (s >> 24) < 16:
                    s = c.src[y * c.w + x]
                c.put(x, y, s)


def _transform(c, mode, s, x, y):
    red, green, blue = (s >> 16) & 255, (s >> 8) & 255, s & 255
    if mode == 0:
        return rgb(blue, green, red)
    if mode == 1:
        return rgb(red, blue, green)
    if mode == 2:
        return rgb(min(255, red * 3 // 2 + 40), green // 3, blue // 3)
    if mode == 3:
        return rgb(red // 4, min(255, green + 30), min(255, blue * 5 // 4 + 30))
    if mode == 4:
        return rgb(red & 0xC0, green & 0xC0, blue & 0xC0)
    left = c.src_or(x - 2 * c.k, y, s)
    right = c.src_or(x + 2 * c.k, y, s)
    return rgb((left >> 16) & 255, green, right & 255)


def _voids(c, r, amount):
    k = c.k
    for _ in range(count(c, 2, 3, amount)):
        p = c.pick(r)
        cx, cy = p % c.w, p // c.w
        for _q in range(r.range(2, 3)):
            rw = r.range(2 * k, 8 * k)
            rh = r.range(2 * k, 6 * k)
            ox = cx + r.range(-3 * k, 3 * k) - rw // 2
            oy = cy + r.range(-3 * k, 3 * k) - rh // 2
            for y in range(oy, oy + rh):
                for x in range(ox, ox + rw):
                    if not c.visible(x, y):
                        continue
                    edge = x == ox or y == oy or x == ox + rw - 1 or y == oy + rh - 1
                    if edge and c.out[y * c.w + x] != BLACK and r.next_int(3) == 0:
                        c.put(x, y, VOID_EDGE)
                    else:
                        c.put(x, y, BLACK)


def _missing(c, r, amount):
    k = c.k
    n = count(c, 2, 3, amount)
    cell = k * (1 + r.next_int(2))
    for _ in range(n):
        p = c.pick(r)
        cx, cy = p % c.w, p // c.w
        bw = r.range(4 * k, 10 * k)
        bh = r.range(3 * k, 8 * k)
        ox = ((cx - bw // 2) // cell) * cell
        oy = ((cy - bh // 2) // cell) * cell
        for y in range(oy, oy + bh):
            for x in range(ox, ox + bw):
                if c.visible(x, y):
                    c.put(x, y, MISSING_MAGENTA if ((x // cell + y // cell) & 1) == 0 else BLACK)


def _negative(c, r, amount):
    k = c.k
    for _ in range(count(c, 2, 3, amount)):
        p = c.pick(r)
        cx, cy = p % c.w, p // c.w
        bw = r.range(3 * k, 10 * k)
        bh = r.range(2 * k, 7 * k)
        rotate = r.next_int(2) == 0
        ox, oy = cx - bw // 2, cy - bh // 2
        for y in range(oy, oy + bh):
            for x in range(ox, ox + bw):
                if not c.visible(x, y):
                    continue
                s = c.src[y * c.w + x]
                red, green, blue = 255 - ((s >> 16) & 255), 255 - ((s >> 8) & 255), 255 - (s & 255)
                c.put(x, y, rgb(green, blue, red) if rotate else rgb(red, green, blue))


def _smear(c, r, amount):
    k = c.k
    for _ in range(count(c, 3, 5, amount)):
        p = c.pick(r)
        x0, y0 = p % c.w, p // c.w
        band = r.range(1, 2 * k)
        length = r.range(6 * k, 24 * k)
        direction = -1 if r.next_int(2) == 0 else 1
        for y in range(y0, y0 + band):
            if not c.visible(x0, y):
                continue
            color = c.src[y * c.w + x0]
            for s in range(1, length + 1):
                x = x0 + direction * s
                if c.visible(x, y):
                    c.put(x, y, color)
    for _ in range(count(c, 1, 2, amount)):
        p = c.pick(r)
        x0, y0 = p % c.w, p // c.w
        band = r.range(1, k)
        length = r.range(4 * k, 14 * k)
        for x in range(x0, x0 + band):
            if not c.visible(x, y0):
                continue
            color = c.src[y0 * c.w + x]
            for s in range(1, length + 1):
                if c.visible(x, y0 + s):
                    c.put(x, y0 + s, color)


def _eyes(c, r, amount):
    k = c.k
    dark = [False] * len(c.out)
    for _ in range(count(c, 3, 3, amount)):
        p = c.pick(r)
        cx, cy = p % c.w, p // c.w
        for _q in range(r.range(2, 3)):
            rw = r.range(4 * k, 12 * k)
            rh = r.range(3 * k, 9 * k)
            ox = cx + r.range(-4 * k, 4 * k) - rw // 2
            oy = cy + r.range(-3 * k, 3 * k) - rh // 2
            for y in range(oy, oy + rh):
                for x in range(ox, ox + rw):
                    if c.visible(x, y):
                        c.put(x, y, BLACK)
                        dark[y * c.w + x] = True
    glow = [0] * len(c.out)
    wanted = count(c, 3, 5, amount)
    attempts = wanted * 8
    placed = 0
    while placed < wanted:
        if attempts <= 0:
            break
        attempts -= 1
        p = c.pick(r)
        x, y = p % c.w, p // c.w
        gap = r.range(2 * k, 3 * k) + k
        if not _dark_block(c, dark, x, y) or not _dark_block(c, dark, x + gap, y):
            continue
        single = r.next_int(10) == 0
        _eye(c, glow, x, y)
        if not single:
            _eye(c, glow, x + gap, y)
        placed += 1
    return glow


def _dark_block(c, dark, x, y):
    for oy in range(c.k):
        for ox in range(c.k):
            px, py = x + ox, y + oy
            if px < 0 or py < 0 or px >= c.w or py >= c.h or not dark[py * c.w + px]:
                return False
    return True


def _eye(c, glow, x, y):
    k = c.k
    for oy in range(k):
        for ox in range(k):
            c.put(x + ox, y + oy, EYE_WHITE)
            glow[(y + oy) * c.w + x + ox] = WHITE
    for i in range(k):
        _halo(c, glow, x - 1, y + i)
        _halo(c, glow, x + k, y + i)
        _halo(c, glow, x + i, y - 1)
        _halo(c, glow, x + i, y + k)


def _halo(c, glow, x, y):
    if 0 <= x < c.w and 0 <= y < c.h and glow[y * c.w + x] == 0:
        glow[y * c.w + x] = EYE_HALO


def _dead_pixels(c, r):
    for _ in range(count(c, 4, 6, 100)):
        p = c.pick(r)
        kind = r.next_int(10)
        color = BLACK if kind < 5 else (WHITE if kind < 7 else MISSING_MAGENTA)
        c.block(p % c.w, p // c.w, color)


def _bruise(c, cx, cy, radius):
    r2 = max(1, radius * radius)
    for dy in range(-radius, radius + 1):
        for dx in range(-radius, radius + 1):
            d2 = dx * dx + dy * dy
            x, y = cx + dx, cy + dy
            if d2 > r2 or not c.visible(x, y):
                continue
            idx = y * c.w + x
            s = c.out[idx] if c.out[idx] != 0 else c.src[idx]
            f = 100 + 60 * d2 // r2
            red = ((s >> 16) & 255) * f >> 8
            green = (((s >> 8) & 255) * f * 13) >> 12
            blue = ((s & 255) * f * 14) >> 12
            c.out[idx] = rgb(red, green, blue)


def infest(src, w, h, anchors, stage, seed):
    """anchors: list of (x, y) pixel coordinates."""
    c = Canvas(src, w, h)
    if not c.visible_index:
        return c.out
    r = Rand(seed ^ 0x494E464553544544)
    k = c.k
    st = clampi(stage, 0, INFEST_STAGES - 1)
    reach = 45 if st == 0 else (75 if st == 1 else 100)
    sx, sy = [0] * 16, [0] * 16
    seeds = 0
    for ax, ay in anchors:
        if seeds >= 10:
            break
        for attempt in range(9):
            x = ax if attempt == 0 else ax + r.range(-3 * k, 3 * k)
            y = ay if attempt == 0 else ay + r.range(-3 * k, 3 * k)
            if c.visible(x, y):
                sx[seeds], sy[seeds] = x, y
                seeds += 1
                break
    anchor_seeds = seeds
    extra = c.counts + (2 - seeds if seeds < 2 else 0)
    e = 0
    while e < extra and seeds < len(sx):
        p = c.pick(r)
        sx[seeds], sy[seeds] = p % c.w, p // c.w
        seeds += 1
        e += 1

    if st >= 1:
        keep = 230 - 22 * st
        for i in range(len(c.out)):
            s = c.src[i]
            if (s >> 24) < 16:
                continue
            red, green, blue = (s >> 16) & 255, (s >> 8) & 255, s & 255
            luma = (red * 77 + green * 150 + blue * 29) >> 8
            red = ((red * 3 + luma) >> 2) * keep >> 8
            green = ((green * 3 + luma) >> 2) * (keep - 14) >> 8
            blue = ((blue * 3 + luma) >> 2) * (keep - 6) >> 8
            c.out[i] = rgb(red, green, blue)

    for i in range(seeds):
        radius = 3 * k + 4 * k * reach // 100 + r.range(0, 2 * k)
        _bruise(c, sx[i], sy[i], radius)
    for _ in range(st + 1):
        p = c.pick(r)
        _bruise(c, p % c.w, p // c.w, 2 * k + r.range(0, 2 * k) * reach // 100)

    core = [False] * len(c.out)
    stack = []
    for i in range(seeds):
        branches = 3 + r.next_int(3) + (1 if st == 2 else 0)
        for _b in range(branches):
            if len(stack) >= MAX_BRANCHES:
                break
            d = r.next_int(16)
            steps = (8 * k + r.next_int(16 * k)) * reach // 100
            stack.append([sx[i] * 16 + 8, sy[i] * 16 + 8, d, steps, 0])
    while stack:
        px, py, d, steps, depth = stack.pop()
        for s in range(steps):
            if r.next_int(100) < 35:
                d = (d + r.range(-1, 1)) & 15
            px += DIR_X[d]
            py += DIR_Y[d]
            x, y = px >> 4, py >> 4
            if not c.visible(x, y):
                break
            for oy in range(k):
                for ox in range(k):
                    if c.visible(x + ox, y + oy):
                        idx = (y + oy) * c.w + x + ox
                        c.out[idx] = VEIN_CORE
                        core[idx] = True
            if depth < 2 and len(stack) < MAX_BRANCHES and r.next_int(100) < 7:
                nd = (d + (3 if r.next_int(2) == 0 else -3)) & 15
                stack.append([px, py, nd, (steps - s) // 2, depth + 1])
    for y in range(c.h):
        for x in range(c.w):
            idx = y * c.w + x
            if core[idx] or not c.visible(x, y):
                continue
            near = (x > 0 and core[idx - 1]) or (x + 1 < c.w and core[idx + 1]) \
                or (y > 0 and core[idx - c.w]) or (y + 1 < c.h and core[idx + c.w])
            if near:
                base = c.out[idx] if c.out[idx] != 0 else c.src[idx]
                c.out[idx] = mix(base, VEIN_FRINGE, 128)

    wound = k + (k * st) // 2
    for i in range(anchor_seeds):
        r2 = wound * wound
        inner = (wound * wound) // 4
        for dy in range(-wound, wound + 1):
            for dx in range(-wound, wound + 1):
                d2 = dx * dx + dy * dy
                if d2 <= r2:
                    c.put(sx[i] + dx, sy[i] + dy, WOUND_CORE if d2 <= inner else WOUND_RING)

    if st >= 1:
        sores = (1 + st) * max(1, seeds // 2) + r.next_int(2)
        for _ in range(sores):
            s = r.next_int(seeds)
            x = sx[s] + r.range(-6 * k, 6 * k)
            y = sy[s] + r.range(-6 * k, 6 * k)
            if not c.visible(x, y):
                continue
            for oy in (-1, 0, 1):
                for ox in (-1, 0, 1):
                    if ox == 0 and oy == 0:
                        c.block(x, y, SORE_CORE)
                    elif ox == 0 or oy == 0:
                        c.block(x + ox * k, y + oy * k, SORE_RING)
                    else:
                        roll = r.next_int(10)
                        if roll < 4:
                            c.block(x + ox * k, y + oy * k, PUS)
                        elif roll < 7:
                            c.block(x + ox * k, y + oy * k, WOUND_RING)
    return c.out


# ====================================================================== image helpers

def to_argb(img):
    arr = np.array(img.convert("RGBA"), dtype=np.uint32).reshape(-1, 4)
    argb = (arr[:, 3] << 24) | (arr[:, 0] << 16) | (arr[:, 1] << 8) | arr[:, 2]
    return [int(p) for p in argb], img.width, img.height


def from_argb(pixels, w, h):
    img = Image.new("RGBA", (w, h))
    img.putdata([((p >> 16) & 255, (p >> 8) & 255, p & 255, (p >> 24) & 255) for p in pixels])
    return img


# ====================================================================== shipped textures

def periodic_noise(size, cells, rng):
    """Smooth value noise on a size x size tile that wraps in both directions."""
    grid = rng.random((cells, cells))
    out = np.zeros((size, size))
    for y in range(size):
        for x in range(size):
            gx, gy = x * cells / size, y * cells / size
            x0, y0 = int(gx) % cells, int(gy) % cells
            x1, y1 = (x0 + 1) % cells, (y0 + 1) % cells
            fx, fy = gx - int(gx), gy - int(gy)
            fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
            top = grid[y0, x0] * (1 - fx) + grid[y0, x1] * fx
            bottom = grid[y1, x0] * (1 - fx) + grid[y1, x1] * fx
            out[y, x] = top * (1 - fy) + bottom * fy
    return out


def tentacle_atlas():
    rng = np.random.default_rng(0x7E47AC1E)
    img = np.zeros((64, 64, 4), dtype=np.float64)
    img[..., 3] = 255

    def skin(suckers):
        n1 = periodic_noise(32, 8, rng)   # broad mottling
        n2 = periodic_noise(32, 4, rng)   # dark red flesh showing through
        n3 = periodic_noise(32, 16, rng)  # fine grain, breaks up the sheen
        tile = np.zeros((32, 32, 3))
        for y in range(32):
            for x in range(32):
                base = np.array([11.0, 5.0, 13.0]) * (1 - n1[y, x]) + np.array([31.0, 12.0, 26.0]) * n1[y, x]
                if n2[y, x] > 0.6:
                    t = min(1.0, (n2[y, x] - 0.6) / 0.25)
                    base = base * (1 - t) + np.array([56.0, 9.0, 18.0]) * t
                base = base * (0.85 + 0.3 * n3[y, x])
                # wavy ridges around the tentacle, like a worm's segments
                for k in range(4):
                    ridge = (8 * k + round(1.4 * math.sin(TWO_PI * x / 32 + 1.3 * k))) % 32
                    if y == ridge:
                        base = base * 0.5
                    elif y == (ridge + 1) % 32:
                        base = base * 1.18
                # a wet sheen wandering along its length, broken up
                sheen = 9 + round(1.6 * math.sin(TWO_PI * y * 2 / 32 + 0.7))
                if x == sheen and n3[y, x] > 0.3:
                    base = base + np.array([44.0, 36.0, 52.0]) * (0.5 + 0.5 * n3[y, x])
                elif x == sheen + 1 and n3[y, x] > 0.45:
                    base = base + np.array([16.0, 12.0, 20.0])
                tile[y, x] = base
        if suckers:
            srng = np.random.default_rng(0x5C)
            for j in range(4):
                for i in range(4):
                    cx = 4 + 8 * i + (4 if j % 2 else 0) + int(srng.integers(-1, 2))
                    cy = 4 + 8 * j + int(srng.integers(-1, 2))
                    radius = 1.8 + float(srng.random())
                    for y in range(cy - 4, cy + 5):
                        for x in range(cx - 4, cx + 5):
                            d = math.hypot(x - cx, (y - cy) * 0.8)
                            px, py = x % 32, y % 32
                            if d <= radius * 0.45:
                                tile[py, px] = [6, 2, 5]
                            elif d <= radius:
                                tile[py, px] = [112, 80, 92] if (x * 3 + y) % 4 else [84, 58, 68]
                            elif d <= radius + 0.6:
                                tile[py, px] = tile[py, px] * 0.55
        return tile

    img[0:32, 0:32, :3] = skin(False)
    img[32:64, 0:32, :3] = skin(True)

    # bone hooks: pale, rounded, cracked
    n = periodic_noise(32, 8, rng)
    for y in range(32):
        for x in range(16):
            shade = 0.72 + 0.28 * math.sin(math.pi * (x + 0.5) / 16)
            col = np.array([198.0, 188.0, 162.0]) * shade + (n[y, x * 2] - 0.5) * 26
            img[y, 32 + x, :3] = col
    crack_rng = np.random.default_rng(77)
    for _ in range(5):
        x, y = int(crack_rng.integers(2, 14)), int(crack_rng.integers(0, 32))
        for _s in range(int(crack_rng.integers(5, 12))):
            img[y % 32, 32 + x, :3] = [92, 82, 66]
            y += 1
            x = int(np.clip(x + crack_rng.integers(-1, 2), 1, 14))

    # mouth petals: dark red flesh at the base, teeth at the tip
    for y in range(32):
        for x in range(16):
            t = y / 31
            flesh = np.array([66.0, 7.0, 15.0]) * (1 - t) + np.array([118.0, 16.0, 26.0]) * t
            flesh += (rng.random() - 0.5) * 14
            if y >= 21:
                tooth_len = 11 - abs(x % 4 - 1.5) * 4
                if x % 4 in (1, 2) and y >= 31 - tooth_len:
                    flesh = np.array([216.0, 206.0, 184.0]) - (31 - y) * 3 + (rng.random() - 0.5) * 10
                elif x % 4 in (0, 3):
                    flesh = np.array([36.0, 3.0, 7.0])
            img[y, 48 + x, :3] = flesh

    # the throat: black at the bottom, deep red, a ring of small teeth at the rim
    for y in range(32, 64):
        for x in range(32, 64):
            d = math.hypot(x + 0.5 - 48, y + 0.5 - 48)
            angle = math.atan2(y + 0.5 - 48, x + 0.5 - 48)
            if d < 4:
                col = [4, 0, 2]
            elif d < 9:
                t = (d - 4) / 5
                col = [60 + 50 * t, 4 + 8 * t, 10 + 12 * t]
            elif d < 15.6:
                col = [128, 20, 30]
                if d > 12.5 and (math.degrees(angle) % 30) < 11:
                    col = [212, 202, 180]
            else:
                col = [40, 6, 12]
            img[y, x, :3] = np.array(col, dtype=np.float64) + (rng.random() - 0.5) * 10
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGBA")


def eye_texture():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 255))
    px = img.load()
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, y - 7.5)
            v = 255 if d < 1.3 else int(255 * max(0.0, 1 - d / 7.5) ** 2.4)
            px[x, y] = (v, v, int(v * 0.96), 255)
    return img


def neutral_source(seed):
    rng = np.random.default_rng(seed)
    vals = rng.integers(96, 144, size=64 * 64)
    return [0xFF000000 | (int(v) << 16) | (int(v) << 8) | int(v) for v in vals]


def fallback_corrupted():
    src = neutral_source(11)
    overlay, _glow = corrupt(src, 64, 64, STYLE_VOID, STYLE_MISSING, 0xFA11BAC)
    return from_argb(overlay, 64, 64)


def fallback_infested():
    src = neutral_source(12)
    overlay = infest(src, 64, 64, [], 2, 0xFA11BAD)
    fixed = {VEIN_CORE, WOUND_CORE, WOUND_RING, SORE_CORE, SORE_RING, PUS}
    kept = []
    for p in overlay:
        red, green = (p >> 16) & 255, (p >> 8) & 255
        kept.append(p if p in fixed or (p != 0 and red > green + 20) else 0)
    return from_argb(kept, 64, 64)


def write_textures():
    TEX_OUT.mkdir(parents=True, exist_ok=True)
    tentacle_atlas().save(TEX_OUT / "tentacle.png")
    eye_texture().save(TEX_OUT / "eye.png")
    fallback_corrupted().save(TEX_OUT / "fallback_corrupted.png")
    fallback_infested().save(TEX_OUT / "fallback_infested.png")
    print("wrote", sorted(p.name for p in TEX_OUT.glob("*.png")))


# ====================================================================== stand-in mobs

def paint_box(img, rng, u, v, w, h, d, base, var=18, front=None):
    px = img.load()

    def fill(x0, y0, fw, fh):
        for y in range(y0, y0 + fh):
            for x in range(x0, x0 + fw):
                if 0 <= x < img.width and 0 <= y < img.height:
                    n = int(rng.integers(-var, var + 1))
                    px[x, y] = tuple(max(0, min(255, c + n)) for c in base) + (255,)

    fill(u + d, v, w, d)
    fill(u + d + w, v, w, d)
    fill(u, v + d, d, h)
    fill(u + d, v + d, w, h)
    fill(u + d + w, v + d, d, h)
    fill(u + 2 * d + w, v + d, w, h)
    if front:
        front(px, u + d, v + d, w, h)


class Mob:
    """A stand-in: texture plus boxes (pixel units, origin at the feet, front toward +Z)."""

    def __init__(self, name, tex_size, bbox, eye_height):
        self.name = name
        self.texture = Image.new("RGBA", tex_size, (0, 0, 0, 0))
        self.boxes = []          # (x, y, z, w, h, d, u, v)
        self.bbox = bbox         # (width, height) in blocks
        self.eye_height = eye_height

    def box(self, rng, x, y, z, w, h, d, u, v, color, var=18, front=None):
        paint_box(self.texture, rng, u, v, w, h, d, color, var, front)
        self.boxes.append((x, y, z, w, h, d, u, v))


def _eyes_front(px, fx, fy, fw, fh, row, white, pupil):
    for ex in (fx + 1, fx + fw - 3):
        px[ex, row] = white
        px[ex + 1, row] = pupil


def standin_mobs():
    mobs = []
    rng = np.random.default_rng(1)
    cow = Mob("cow", (64, 32), (0.9, 1.4), 1.3)
    brown, white = (92, 64, 44), (226, 222, 214)
    cow.box(rng, -4, 13, 7, 8, 8, 6, 0, 0, brown,
            front=lambda px, x, y, w, h: _eyes_front(px, x, y, w, h, y + 3, (235, 235, 235, 255), (20, 20, 30, 255)))
    cow.box(rng, -6, 10, -9, 12, 10, 18, 18, 4, brown)
    p = cow.texture.load()
    for y in range(4, 32):
        for x in range(18, 62):
            if p[x, y][3] and ((x * 7 + y * 3) % 17 < 6 or (x // 5 + y // 6) % 3 == 0):
                p[x, y] = white + (255,)
    for lx, lz in ((-5, -8), (1, -8), (-5, 4), (1, 4)):
        cow.box(rng, lx, 0, lz, 4, 12, 4, 0, 16, brown)
    mobs.append(cow)

    rng = np.random.default_rng(2)
    zombie = Mob("zombie", (64, 64), (0.6, 1.95), 1.74)
    green, shirt, pants = (74, 110, 60), (40, 120, 120), (60, 60, 140)
    zombie.box(rng, -4, 24, -4, 8, 8, 8, 0, 0, green,
               front=lambda px, x, y, w, h: _eyes_front(px, x, y, w, h, y + 4, (20, 30, 20, 255), (10, 10, 10, 255)))
    zombie.box(rng, -4, 12, -2, 8, 12, 4, 16, 16, shirt)
    zombie.box(rng, -8, 12, -2, 4, 12, 4, 40, 16, green)
    zombie.box(rng, 4, 12, -2, 4, 12, 4, 32, 48, green)
    zombie.box(rng, -4, 0, -2, 4, 12, 4, 0, 16, pants)
    zombie.box(rng, 0, 0, -2, 4, 12, 4, 16, 48, pants)
    mobs.append(zombie)

    rng = np.random.default_rng(3)
    chicken = Mob("chicken", (64, 32), (0.4, 0.7), 0.64)
    wh = (236, 236, 230)
    chicken.box(rng, -2, 9, 3, 4, 6, 3, 0, 0, wh, var=8,
                front=lambda px, x, y, w, h: (px.__setitem__((x, y + 2), (10, 10, 10, 255)),
                                              px.__setitem__((x + 3, y + 2), (10, 10, 10, 255))))
    chicken.box(rng, -2, 11, 6, 4, 2, 2, 14, 0, (240, 190, 40), var=6)
    chicken.box(rng, -1, 9, 6, 2, 2, 2, 14, 4, (200, 30, 30), var=6)
    chicken.box(rng, -3, 5, -4, 6, 6, 8, 0, 9, wh, var=8)
    chicken.box(rng, -4, 6, -3, 1, 4, 6, 24, 13, (220, 220, 214), var=8)
    chicken.box(rng, 3, 6, -3, 1, 4, 6, 24, 13, (220, 220, 214), var=8)
    chicken.box(rng, -2, 0, -1, 1, 5, 1, 26, 0, (240, 190, 40), var=6)
    chicken.box(rng, 1, 0, -1, 1, 5, 1, 26, 0, (240, 190, 40), var=6)
    mobs.append(chicken)

    rng = np.random.default_rng(4)
    ravager = Mob("ravager", (128, 128), (1.95, 2.2), 2.1)
    hide, dark = (96, 88, 80), (60, 54, 50)
    ravager.box(rng, -8, 18, 12, 16, 20, 16, 0, 0, hide, var=14,
                front=lambda px, x, y, w, h: [px.__setitem__((xx, yy), (230, 220, 200, 255))
                                              for xx in (x + 3, x + 4, x + w - 5, x + w - 4) for yy in (y + 6, y + 7)])
    ravager.box(rng, -7, 18, -14, 14, 16, 26, 0, 55, hide, var=14)
    for lx, lz in ((-8, -12), (4, -12), (-8, 6), (4, 6)):
        ravager.box(rng, lx, 0, lz, 4, 18, 6, 96, 0, dark, var=10)
    ravager.box(rng, -10, 34, 18, 2, 8, 2, 64, 0, (210, 200, 180), var=6)
    ravager.box(rng, 8, 34, 18, 2, 8, 2, 64, 0, (210, 200, 180), var=6)
    mobs.append(ravager)
    return mobs


def box_quads(mob):
    """Every box face as a quad: (corners[4] in blocks, normal, uv corners[4] normalized)."""
    tw, th = mob.texture.size
    quads = []
    for (x, y, z, w, h, d, u, v) in mob.boxes:
        x0, y0, z0 = x / 16, y / 16, z / 16
        x1, y1, z1 = (x + w) / 16, (y + h) / 16, (z + d) / 16

        def uvr(ru, rv, rw, rh):
            return [((ru) / tw, (rv) / th), ((ru + rw) / tw, (rv) / th),
                    ((ru + rw) / tw, (rv + rh) / th), ((ru) / tw, (rv + rh) / th)]
        # front (+Z), back (-Z), right (+X), left (-X), top (+Y), bottom (-Y)
        quads.append(([(x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)], (0, 0, 1), uvr(u + d, v + d, w, h)))
        quads.append(([(x1, y1, z0), (x0, y1, z0), (x0, y0, z0), (x1, y0, z0)], (0, 0, -1), uvr(u + 2 * d + w, v + d, w, h)))
        quads.append(([(x1, y1, z1), (x1, y1, z0), (x1, y0, z0), (x1, y0, z1)], (1, 0, 0), uvr(u, v + d, d, h)))
        quads.append(([(x0, y1, z0), (x0, y1, z1), (x0, y0, z1), (x0, y0, z0)], (-1, 0, 0), uvr(u + d + w, v + d, d, h)))
        quads.append(([(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)], (0, 1, 0), uvr(u + d, v, w, d)))
        quads.append(([(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)], (0, -1, 0), uvr(u + d + w, v, w, d)))
    return quads


# ====================================================================== ModelRecorder / Tentacles ports

def quad_area(q):
    (a, b, _c, dd) = q[0]
    e1 = np.subtract(b, a)
    e2 = np.subtract(dd, a)
    return float(np.linalg.norm(np.cross(e1, e2)))


def bilinear(corners, s, t):
    a, b, c, d = (np.array(p, dtype=float) for p in corners)
    top = a + (b - a) * s
    bottom = d + (c - d) * s
    return top + (bottom - top) * t


def pick_anchors(quads, seed, wanted):
    """ModelRecorder.pickAnchors."""
    if not quads:
        return []
    ys = [p[1] for q in quads for p in q[0]]
    min_y, max_y = min(ys), max(ys)
    areas = [quad_area(q) for q in quads]
    max_area = max(areas)
    height = max(1e-4, max_y - min_y)
    weight = []
    for q, a in zip(quads, areas):
        if a < max_area * 0.04:
            weight.append(0.0)
            continue
        cy = sum(p[1] for p in q[0]) / 4
        high = 0.35 + 0.65 * (cy - min_y) / height
        down = q[1][1]
        weight.append(a * high * (0.15 if down < -0.6 else 1.0))
    r = Rand(seed ^ 0x414E43484F52532E)
    taken = [False] * len(quads)
    found = []
    for _k in range(wanted):
        total = sum(w for w, tk in zip(weight, taken) if not tk)
        if total <= 0:
            taken = [False] * len(quads)
            total = sum(weight)
            if total <= 0:
                break
        pick = r.unit() * total
        chosen = -1
        for qi, w in enumerate(weight):
            if taken[qi] or w <= 0:
                continue
            chosen = qi
            pick -= w
            if pick <= 0:
                break
        if chosen < 0:
            break
        taken[chosen] = True
        found.append((chosen, 0.2 + 0.6 * r.unit(), 0.2 + 0.6 * r.unit()))
    return found


def face_quad(quads, look, eye, max_dy):
    best, best_score = -1, -1e9
    for qi, q in enumerate(quads):
        n = np.array(q[1], dtype=float)
        facing = float(np.dot(n, look))
        if facing < 0.6:
            continue
        c = np.mean(np.array(q[0], dtype=float), axis=0)
        dy = abs(c[1] - eye[1])
        if dy > max_dy:
            continue
        forward = float(np.dot(c - eye, look))
        score = forward * 0.6 + math.sqrt(quad_area(q)) - dy
        if score > best_score:
            best, best_score = qi, score
    return best


def mix64(h):
    h &= MASK64
    h = ((h ^ (h >> 30)) * 0xBF58476D1CE4E5B9) & MASK64
    h = ((h ^ (h >> 27)) * 0x94D049BB133111EB) & MASK64
    return h ^ (h >> 31)


def unit11(bits):
    return (bits & 2047) / 2048.0


TIP_POINT, TIP_HOOK, TIP_MOUTH = 0, 1, 2
TWO_PI = math.pi * 2


class Spec:
    """Tentacles.Spec.roll."""

    def __init__(self, seed, size, index, count_):
        h = mix64(seed + 0x2545F4914F6CDD1D * (index + 1))
        a, b, c, d = unit11(h), unit11(h >> 11), unit11(h >> 22), unit11(h >> 33)
        g = mix64(h)
        e, f, q, r = unit11(g), unit11(g >> 11), unit11(g >> 22), unit11(g >> 33)
        self.length = size * (0.5 + 0.65 * a)
        self.radius = min(0.16, max(0.018, size * (0.032 + 0.022 * b)))
        roll = int(c * 10)
        self.tip = TIP_POINT if roll < 4 else (TIP_HOOK if roll < 7 else TIP_MOUTH)
        if count_ >= 4 and index == 0:
            self.tip = TIP_MOUTH
        elif count_ >= 4 and index == 1:
            self.tip = TIP_HOOK
        if self.tip == TIP_MOUTH:
            self.radius *= 1.25
        self.suckers = d < 0.35
        self.amp = 0.55 + 0.55 * e
        self.droop = 0.1 + 0.35 * f
        self.w1 = 0.06 + 0.07 * q
        self.w2 = 0.11 + 0.09 * r
        self.w3 = 0.05 + 0.08 * unit11(h >> 44)
        self.w4 = 0.13 + 0.08 * unit11(g >> 44)
        self.k1 = 2.2 + 2.6 * a * b + 0.6 * c
        self.k2 = 4.5 + 3.0 * d
        self.p1 = TWO_PI * unit11(mix64(g + 1))
        self.p2 = TWO_PI * unit11(mix64(g + 2))
        self.p3 = TWO_PI * unit11(mix64(g + 3))
        self.p4 = TWO_PI * unit11(mix64(g + 4))
        self.p5 = TWO_PI * unit11(mix64(g + 5))
        self.twist = TWO_PI * unit11(mix64(g + 6))
        self.pulse_speed = 0.035 + 0.03 * unit11(mix64(g + 7))
        self.bite_speed = 0.08 + 0.08 * unit11(mix64(g + 8))


def ring(sides):
    return [(math.cos(TWO_PI * j / sides), math.sin(TWO_PI * j / sides)) for j in range(sides + 1)]


def tentacle_quads(base, direction, spec, unit, extent, thickness, time, agitation, segments, sides, tips):
    """Tentacles.draw: returns quads (4 positions, 4 normals, 4 uvs)."""
    out = []
    length = spec.length * unit * extent
    radius = spec.radius * unit * thickness
    if length < 0.01 * unit or radius <= 0:
        return out
    n = max(2, min(14, segments))
    s = 4 if sides <= 4 else (6 if sides >= 6 else 5)
    tip = spec.tip if tips else TIP_POINT
    d = np.array(direction, dtype=float)
    d /= np.linalg.norm(d)
    u = np.array([-d[2], 0, d[0]]) if abs(d[1]) < 0.9 else np.array([0, d[2], -d[1]])
    u /= np.linalg.norm(u)
    v = np.cross(d, u)
    step = length / n
    amp = spec.amp * min(2.2, agitation)
    speed = agitation
    pts = [np.array(base, dtype=float)]
    segs = []
    for i in range(n):
        t = (i + 0.5) / n
        env = t * (1.25 - 0.25 * t)
        au = amp * env * (0.65 * math.sin(spec.w1 * speed * time - spec.k1 * t + spec.p1)
                          + 0.35 * math.sin(spec.w2 * speed * time - spec.k2 * t + spec.p2))
        av = amp * env * (0.65 * math.sin(spec.w3 * speed * time - spec.k1 * 0.8 * t + spec.p3)
                          + 0.35 * math.sin(spec.w4 * speed * time - spec.k2 * 1.3 * t + spec.p4))
        if tip == TIP_HOOK and t > 0.68:
            hh = (t - 0.68) / 0.32
            au += 2.7 * hh * math.sqrt(hh)
        e = d * math.cos(au) * math.cos(av) + u * math.sin(au) * math.cos(av) + v * math.sin(av)
        e[1] -= spec.droop * t
        e /= np.linalg.norm(e)
        segs.append(e)
        pts.append(pts[-1] + e * step)
    frames = []
    radii = []
    p = u.copy()
    for i in range(n + 1):
        if i == 0:
            tvec = segs[0]
        elif i == n:
            tvec = segs[n - 1]
        else:
            tvec = segs[i - 1] + segs[i]
            tvec = tvec / np.linalg.norm(tvec)
        q = p - np.dot(p, tvec) * tvec
        if np.linalg.norm(q) > 1e-4:
            p = q / np.linalg.norm(q)
        frames.append((tvec, p.copy(), np.cross(tvec, p)))
        t = i / n
        rr = radius * (1 - 0.45 * t if tip == TIP_MOUTH else 1 - 0.82 * t)
        if t < 0.12:
            root = 1 - t / 0.12
            rr *= 1 + 0.6 * root * root
        wave = math.sin(TWO_PI * t * 2.2 - spec.pulse_speed * speed * time + spec.p5)
        if wave > 0:
            rr *= 1 + 0.22 * wave ** 3
        if tip == TIP_MOUTH and t > 0.8:
            m = (t - 0.8) / 0.2
            rr *= 1 + 1.3 * m * m
        if i == n and tip != TIP_MOUTH:
            rr = radius * 0.03
        radii.append(rr)
    rg = ring(s)
    tc, ts = math.cos(spec.twist), math.sin(spec.twist)
    for i in range(n):
        t0 = i / n
        bone = tip == TIP_HOOK and t0 >= 0.68 - 0.001
        u0, uw = (0.5, 0.25) if bone else (0.0, 0.5)
        v0 = 0.0 if bone else (0.5 if spec.suckers else 0.0)
        va = v0 + (0.0 if i % 2 == 0 else 0.25)
        vb = va + 0.25
        for j in range(s):
            c0, s0 = rg[j][0] * tc - rg[j][1] * ts, rg[j][1] * tc + rg[j][0] * ts
            c1, s1 = rg[j + 1][0] * tc - rg[j + 1][1] * ts, rg[j + 1][1] * tc + rg[j + 1][0] * ts
            ua, ub = u0 + uw * j / s, u0 + uw * (j + 1) / s
            corners, normals = [], []
            for (ri, cc, ss) in ((i, c0, s0), (i, c1, s1), (i + 1, c1, s1), (i + 1, c0, s0)):
                nrm = frames[ri][1] * cc + frames[ri][2] * ss
                corners.append(pts[ri] + nrm * radii[ri])
                normals.append(nrm)
            out.append((corners, normals, [(ua, va), (ub, va), (ub, vb), (ua, vb)]))
    if tip == TIP_MOUTH:
        out.extend(_mouth_quads(pts[n], frames[n], radii[n], s, spec, speed, time))
    return out


def _mouth_quads(center, frame, r, s, spec, speed, time):
    out = []
    tvec, uu, vv = frame
    sunk = center - tvec * r * 0.45
    rg = ring(s)
    for j in range(s):
        a = uu * rg[j][0] + vv * rg[j][1]
        b = uu * rg[j + 1][0] + vv * rg[j + 1][1]
        out.append(([sunk, sunk, center + b * r, center + a * r], [tvec] * 4,
                    [(0.75, 0.75), (0.75, 0.75), (0.75 + rg[j + 1][0] * 0.24, 0.75 + rg[j + 1][1] * 0.24),
                     (0.75 + rg[j][0] * 0.24, 0.75 + rg[j][1] * 0.24)]))
    r4 = ring(4)
    for p in range(5):
        angle = TWO_PI * p / 5 + spec.twist
        rr = uu * math.cos(angle) + vv * math.sin(angle)
        opening = 0.35 + 0.8 * (0.5 + 0.5 * math.sin(spec.bite_speed * speed * time + spec.p4 + p * 0.45))
        e = tvec * math.cos(opening) + rr * math.sin(opening)
        w = np.cross(tvec, rr)
        m = np.cross(e, w)
        q = [center + rr * r * 0.85]
        seg = r * 0.95
        for _k in range(3):
            e = e - rr * 0.42 + tvec * 0.08
            e = e / np.linalg.norm(e)
            q.append(q[-1] + e * seg)
        for k in range(3):
            w0 = r * 0.42 * (1 - k / 3) + r * 0.03
            w1 = r * 0.42 * (1 - (k + 1) / 3) + r * 0.03
            va, vb = 0.5 * k / 3, 0.5 * (k + 1) / 3
            for j in range(4):
                corners, normals, uvs = [], [], []
                for (kk, (cc, ss), wd, vv_) in ((k, r4[j], w0, va), (k, r4[j + 1], w0, va),
                                                (k + 1, r4[j + 1], w1, vb), (k + 1, r4[j], w1, vb)):
                    nrm = m * cc + w * ss
                    off = m * cc * 0.5 + w * ss
                    corners.append(q[kk] + off * wd)
                    normals.append(nrm)
                uvs = [(0.75 + 0.25 * j / 4, va), (0.75 + 0.25 * (j + 1) / 4, va),
                       (0.75 + 0.25 * (j + 1) / 4, vb), (0.75 + 0.25 * j / 4, vb)]
                out.append((corners, normals, uvs))
    return out


# ====================================================================== software rasteriser

LIGHT0 = np.array([0.2, 1.0, -0.7]) / np.linalg.norm([0.2, 1.0, -0.7])
LIGHT1 = np.array([-0.2, 1.0, 0.7]) / np.linalg.norm([-0.2, 1.0, 0.7])


class Raster:
    def __init__(self, w, h, eye, target, fov=50.0, sky=(26, 28, 36), ambient=1.0):
        self.w, self.h = w, h
        self.color = np.zeros((h, w, 3))
        self.color[:] = sky
        self.depth = np.full((h, w), np.inf)
        self.eye = np.array(eye, dtype=float)
        f = np.array(target, dtype=float) - self.eye
        self.fwd = f / np.linalg.norm(f)
        self.right = np.cross(self.fwd, [0, 1, 0])
        self.right /= np.linalg.norm(self.right)
        self.up = np.cross(self.right, self.fwd)
        self.focal = (h / 2) / math.tan(math.radians(fov) / 2)
        self.ambient = ambient

    def project(self, p):
        d = np.array(p, dtype=float) - self.eye
        z = float(np.dot(d, self.fwd))
        x = float(np.dot(d, self.right))
        y = float(np.dot(d, self.up))
        return self.w / 2 + x * self.focal / z, self.h / 2 - y * self.focal / z, z

    def quad(self, corners, normals, uvs, tex, additive=False, tint=(1.0, 1.0, 1.0), lit=True):
        for tri in ((0, 1, 2), (0, 2, 3)):
            self.triangle([corners[i] for i in tri], [normals[i] for i in tri], [uvs[i] for i in tri], tex,
                          additive, tint, lit)

    def triangle(self, pts, normals, uvs, tex, additive, tint, lit):
        proj = [self.project(p) for p in pts]
        if any(z <= 0.05 for (_x, _y, z) in proj):
            return
        xs = [p[0] for p in proj]
        ys = [p[1] for p in proj]
        x0, x1 = max(0, int(math.floor(min(xs)))), min(self.w - 1, int(math.ceil(max(xs))))
        y0, y1 = max(0, int(math.floor(min(ys)))), min(self.h - 1, int(math.ceil(max(ys))))
        if x0 > x1 or y0 > y1:
            return
        (ax, ay, az), (bx, by, bz), (cx, cy, cz) = proj
        den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
        if abs(den) < 1e-9:
            return
        gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
        l0 = ((by - cy) * (gx - cx) + (cx - bx) * (gy - cy)) / den
        l1 = ((cy - ay) * (gx - cx) + (ax - cx) * (gy - cy)) / den
        l2 = 1 - l0 - l1
        inside = (l0 >= -1e-6) & (l1 >= -1e-6) & (l2 >= -1e-6)
        if not inside.any():
            return
        iz = l0 / az + l1 / bz + l2 / cz
        z = 1 / np.maximum(iz, 1e-9)
        u = (l0 * uvs[0][0] / az + l1 * uvs[1][0] / bz + l2 * uvs[2][0] / cz) * z
        v = (l0 * uvs[0][1] / az + l1 * uvs[1][1] / bz + l2 * uvs[2][1] / cz) * z
        th, tw = tex.shape[:2]
        tx = np.clip((u * tw).astype(int), 0, tw - 1)
        ty = np.clip((v * th).astype(int), 0, th - 1)
        texel = tex[ty, tx]
        mask = inside & (texel[..., 3] >= 26)
        region = self.depth[y0:y1 + 1, x0:x1 + 1]
        mask &= z <= region + 1e-6
        if not mask.any():
            return
        if lit:
            n = np.mean(np.array(normals, dtype=float), axis=0)
            n /= max(1e-9, np.linalg.norm(n))
            light = min(1.0, (max(0.0, float(np.dot(LIGHT0, n))) + max(0.0, float(np.dot(LIGHT1, n)))) * 0.6 + 0.4)
            light *= self.ambient
        else:
            light = 1.0
        rgb_ = texel[..., :3] * light * np.array(tint)
        target = self.color[y0:y1 + 1, x0:x1 + 1]
        if additive:
            target[mask] = np.minimum(255, target[mask] + rgb_[mask])
        else:
            target[mask] = rgb_[mask]
            region[mask] = z[mask]

    def image(self):
        return Image.fromarray(np.clip(self.color, 0, 255).astype(np.uint8), "RGB")


def tex_array(img):
    return np.array(img.convert("RGBA"), dtype=np.float64)


# ====================================================================== preview scenes

def mob_scene_quads(mob, origin, yaw_deg):
    """Box faces of a mob placed in the world: (corners, normals, uvs)."""
    yaw = math.radians(yaw_deg)
    cy, sy = math.cos(yaw), math.sin(yaw)

    def tr(p):
        return np.array([origin[0] + p[0] * cy + p[2] * sy, origin[1] + p[1], origin[2] - p[0] * sy + p[2] * cy])

    def rn(n):
        return np.array([n[0] * cy + n[2] * sy, n[1], -n[0] * sy + n[2] * cy])
    out = []
    for (corners, normal, uvs) in box_quads(mob):
        out.append(([tr(p) for p in corners], [rn(normal)] * 4, uvs, rn(normal)))
    return out


def host_size(mob):
    """VariantRendering.parasite: sqrt(width * height) * 1.2."""
    w, h = mob.bbox
    return math.sqrt(max(0.01, w * h)) * 1.2


def tentacle_count(mob, seed):
    """MobVisual.ensureTentacles."""
    h = mix64(seed ^ 0x54454E5441434C45)
    return max(3, min(8, int(math.floor(2 + 2.2 * host_size(mob) + 0.5)) + int(h & 1)))


def layout_of(seed):
    """MobVisual.reset: the layout (0..7) that decides where the tentacles leave the body."""
    return (mix64(seed ^ 0x434F525255505445) >> 40) % 8


def anchors_for(mob, placed, seed):
    quads = [(q[0], tuple(q[3]), q[2]) for q in placed]
    count_ = tentacle_count(mob, seed)
    picks = pick_anchors(quads, mix64(0x4C41594F55545300 + layout_of(seed)), count_)
    center = np.mean([p for q in placed for p in q[0]], axis=0)
    result = []
    for (qi, s, t) in picks:
        corners = placed[qi][0]
        pos = bilinear(corners, s, t)
        n = np.array(placed[qi][3], dtype=float)
        out_dir = pos - center
        out_dir /= max(1e-6, np.linalg.norm(out_dir))
        d = n * 1.0 + np.array([0, 1.0, 0]) * 0.45 + out_dir * 0.35
        d /= np.linalg.norm(d)
        if d[1] < -0.2:
            hl = math.hypot(d[0], d[2])
            scale = math.sqrt(1 - 0.04) / hl if hl > 1e-5 else 0.0
            d = np.array([d[0] * scale, -0.2, d[2] * scale])
        uv = bilinear(placed[qi][2], s, t)
        result.append((pos, d, uv))
    return result, count_


def render_mob(ras, mob, origin, yaw, look, overlay=None, glow=None, parasite=None, silhouette=False,
               growth=1.0, time=0.0, tentacle_tex=None, eye_tex=None, detail=(10, 6, True)):
    base = tex_array(mob.texture)
    placed = mob_scene_quads(mob, origin, yaw)
    for (corners, normals, uvs, _n) in placed:
        ras.quad(corners, normals, uvs, base)
    if overlay is not None:
        for (corners, normals, uvs, _n) in placed:
            ras.quad(corners, normals, uvs, overlay)
    if glow is not None:
        for (corners, normals, uvs, _n) in placed:
            ras.quad(corners, normals, uvs, glow, additive=True, lit=False)
    if silhouette:
        black = base.copy()
        black[..., :3] = 0
        for (corners, normals, uvs, _n) in placed:
            ras.quad(corners, normals, uvs, black)
        eye_pos = np.array([origin[0], origin[1] + mob.eye_height, origin[2]])
        quads = [(q[0], tuple(q[3]), q[2]) for q in placed]
        fq = face_quad(quads, np.array(look, dtype=float), eye_pos, 0.45 * mob.bbox[1])
        if fq >= 0:
            corners = [np.array(p, dtype=float) for p in placed[fq][0]]
            n = np.array(placed[fq][3], dtype=float)
            a = corners[1] - corners[0]
            b = corners[3] - corners[0]
            horiz, vert = (a, b) if abs(a[1]) < abs(b[1]) else (b, a)
            top_t = 0.38 if vert[1] < 0 else 0.62
            size = max(0.02, min(0.08, 0.11 * np.linalg.norm(horiz)))
            hu = horiz / np.linalg.norm(horiz)
            vu = vert / np.linalg.norm(vert)
            for s_ in (0.27, 0.73):
                c = corners[0] + horiz * s_ + vert * top_t + n * 0.003
                for scale in (2.6,):
                    hs = size * scale
                    quad = [c - hu * hs + vu * hs, c + hu * hs + vu * hs, c + hu * hs - vu * hs, c - hu * hs - vu * hs]
                    ras.quad(quad, [n] * 4, [(0, 0), (1, 0), (1, 1), (0, 1)], eye_tex, additive=True, lit=False)
    if parasite is not None:
        anchors, count_ = anchors_for(mob, placed, parasite)
        size = max(0.8, host_size(mob))
        segments, sides, tips = detail
        for k, (pos, d, _uv) in enumerate(anchors):
            spec = Spec(parasite, size, k, count_)
            emerge = min(1.0, max(0.0, (growth - 0.6 * k / count_) / 0.25))
            extent = smooth(emerge) * (0.25 + 0.75 * smooth(growth))
            if extent < 0.02:
                continue
            thick = 0.5 + 0.5 * smooth(growth)
            for (corners, normals, uvs) in tentacle_quads(pos, d, spec, 1.0, extent, thick, time, 1.0,
                                                           segments, sides, tips):
                ras.quad(corners, normals, uvs, tentacle_tex)


def smooth(x):
    t = min(1.0, max(0.0, x))
    return t * t * (3 - 2 * t)


def mob_overlays(mob, style, secondary, seed):
    argb, w, h = to_argb(mob.texture)
    overlay, glow = corrupt(argb, w, h, style, secondary, seed)
    return from_argb(overlay, w, h), (from_argb(glow, w, h) if glow is not None else None)


def infested_overlay(mob, seed, stage, anchor_uvs):
    argb, w, h = to_argb(mob.texture)
    anchors = [(int(u * w), int(v * h)) for (u, v) in anchor_uvs]
    return from_argb(infest(argb, w, h, anchors, stage, seed), w, h)


def preview(out_dir, mobs):
    out_dir.mkdir(parents=True, exist_ok=True)
    tentacle = tex_array(tentacle_atlas())
    eye = tex_array(eye_texture())

    # 1. every style and stage on every stand-in, flat
    cell = 260
    cols = ["original"] + STYLE_NAMES + ["infested 0", "infested 1", "infested 2"]
    sheet = Image.new("RGB", (cell * len(cols), (cell + 16) * len(mobs)), (40, 40, 48))
    draw = ImageDraw.Draw(sheet)
    for row, mob in enumerate(mobs):
        argb, w, h = to_argb(mob.texture)
        anchors = [(w // 8, h // 4), (w // 2, h // 2), ((3 * w) // 4, (5 * h) // 8)]
        for col, name in enumerate(cols):
            img = mob.texture.copy()
            if name in STYLE_NAMES:
                style = STYLE_NAMES.index(name)
                ov, _g = corrupt(argb, w, h, style, (style + 1 + style % 3) % STYLES, 1000 + style)
                img.alpha_composite(from_argb(ov, w, h))
            elif name.startswith("infested"):
                stage = int(name[-1])
                img.alpha_composite(from_argb(infest(argb, w, h, anchors, stage, 2000 + stage), w, h))
            bg = Image.new("RGBA", img.size, (70, 70, 80, 255))
            bg.alpha_composite(img)
            scale = cell // max(img.width, img.height)
            big = bg.resize((img.width * scale, img.height * scale), Image.NEAREST)
            sheet.paste(big, (col * cell, row * (cell + 16) + 16))
            draw.text((col * cell + 4, row * (cell + 16) + 2), f"{mob.name}: {name}", fill=(255, 255, 255))
    sheet.save(out_dir / "textures.png")

    def scene(path, items, eye_pos, target, ambient=1.0, size=(1100, 520)):
        ras = Raster(size[0], size[1], eye_pos, target, ambient=ambient)
        look = np.array(eye_pos, dtype=float) - np.array(target, dtype=float)
        look[1] = 0
        look /= np.linalg.norm(look)
        for item in items:
            render_mob(ras, look=look, tentacle_tex=tentacle, eye_tex=eye, **item)
        ras.image().save(path)

    xs = [-3.3, -1.3, 0.9, 3.4]
    by_name = {m.name: m for m in mobs}
    order = [by_name["chicken"], by_name["cow"], by_name["zombie"], by_name["ravager"]]

    # 2. parasites, full grown, two moments to show the writhing; and a young one
    for label, time_, growth in (("parasites_t0", 0.0, 1.0), ("parasites_t40", 40.0, 1.0), ("parasites_young", 0.0, 0.35)):
        items = []
        for i, mob in enumerate(order):
            seed = 0x5EED0 + i * 977
            placed = mob_scene_quads(mob, (xs[i], 0, 0), 0)
            anchors, _c = anchors_for(mob, placed, seed)
            stage = 2 if growth > 0.7 else 0
            ov = tex_array(infested_overlay(mob, seed, stage, [a[2] for a in anchors]))
            items.append(dict(mob=mob, origin=(xs[i], 0, 0), yaw=0, overlay=ov, parasite=seed, growth=growth, time=time_))
        scene(out_dir / f"{label}.png", items, (0.2, 2.2, 7.5), (0.2, 1.0, 0))
    # far level of detail
    items = []
    for i, mob in enumerate(order):
        seed = 0x5EED0 + i * 977
        items.append(dict(mob=mob, origin=(xs[i], 0, 0), yaw=0, parasite=seed, growth=1.0, time=0.0, detail=(4, 4, False)))
    scene(out_dir / "parasites_far_lod.png", items, (0.2, 2.2, 7.5), (0.2, 1.0, 0))
    # from the side and behind
    items = []
    for i, mob in enumerate(order):
        seed = 0x5EED0 + i * 977
        items.append(dict(mob=mob, origin=(xs[i], 0, 0), yaw=65, parasite=seed, growth=1.0, time=12.0))
    scene(out_dir / "parasites_side.png", items, (0.2, 3.2, 6.5), (0.2, 1.0, 0))

    # 3. corrupted: four styles, and the silhouette with its eyes (lit and in the dark)
    items = []
    for i, (mob, style) in enumerate(zip(order, (STYLE_GLITCH, STYLE_MISSING, STYLE_EYES, STYLE_VOID))):
        ov, gl = mob_overlays(mob, style, (style + 2) % STYLES, 0xC0 + i)
        items.append(dict(mob=mob, origin=(xs[i], 0, 0), yaw=0, overlay=tex_array(ov),
                          glow=tex_array(gl) if gl is not None and style == STYLE_EYES else None))
    scene(out_dir / "corrupted.png", items, (0.2, 2.0, 7.0), (0.2, 1.0, 0))
    items = [dict(mob=mob, origin=(xs[i], 0, 0), yaw=0, silhouette=True) for i, mob in enumerate(order)]
    scene(out_dir / "silhouettes.png", items, (0.2, 1.6, 7.0), (0.2, 1.0, 0))
    scene(out_dir / "silhouettes_night.png", items, (0.2, 1.6, 7.0), (0.2, 1.0, 0), ambient=0.12)

    # 4. the parasite burst, frame by frame
    frames = []
    for tick in (1, 3, 6, 10, 16, 22, 27):
        ras = Raster(360, 300, (0.0, 1.6, 4.2), (0.0, 0.6, 0))
        burst_quads(ras, tentacle, 0x6B5A, tick, 1.0)
        img = ras.image()
        ImageDraw.Draw(img).text((6, 6), f"tick {tick}", fill=(255, 255, 255))
        frames.append(img)
    strip = Image.new("RGB", (360 * len(frames), 300))
    for i, f in enumerate(frames):
        strip.paste(f, (i * 360, 0))
    strip.save(out_dir / "burst.png")
    print("previews in", out_dir)


def burst_quads(ras, tentacle_tex, seed, tick, intensity):
    """ParasiteBursts: the same envelope and specs as the client."""
    count_ = 6 + int(mix64(seed) & 3)
    host = 1.4
    for k in range(count_):
        h = mix64(seed + k * 0x9E3779B97F4A7C15)
        yaw = TWO_PI * (k + unit11(h)) / count_
        pitch = 0.25 + 0.95 * unit11(h >> 11)
        d = np.array([math.cos(pitch) * math.sin(yaw), math.sin(pitch), math.cos(pitch) * math.cos(yaw)])
        spec = Spec(seed, max(0.8, host) * (0.75 + 0.25 * intensity), k, count_)
        spec.droop *= 0.5
        if k == 0:
            spec.tip = TIP_MOUTH
        elif spec.tip == TIP_MOUTH:
            spec.tip = TIP_POINT
        extent = burst_extent(tick, k)
        if extent <= 0.01:
            continue
        agitation = 2.2 - 1.2 * min(1.0, tick / 30.0)
        for (corners, normals, uvs) in tentacle_quads((0.0, 0.05, 0.0), d, spec, 1.0, extent, 0.7 + 0.3 * extent,
                                                       tick, agitation, 10, 6, True):
            ras.quad(corners, normals, uvs, tentacle_tex)


def burst_extent(tick, k):
    """How far tentacle k is out at a tick of the burst (30 ticks)."""
    t = tick - (k % 3) * 0.6
    if t <= 0:
        return 0.0
    if t < 5:
        x = t / 5
        c1, c3 = 1.70158, 2.70158
        return 1 + c3 * (x - 1) ** 3 + c1 * (x - 1) ** 2
    if t < 14:
        return 1.0
    if t < 30:
        x = (t - 14) / 16
        return max(0.0, 1 - x * x * (3 - 2 * x))
    return 0.0


# ====================================================================== checks against the Java implementation

def check_cases(mobs):
    """(file name, function) pairs for VariantPixels; the Java runner writes the same names."""
    cases = []
    for idx, mob in enumerate(mobs):
        argb, w, h = to_argb(mob.texture)
        for style in range(STYLES):
            secondary = (style + 1 + style % 3) % STYLES
            seed = 1000 + style * 7 + idx
            cases.append((f"{mob.name}_c{style}.png", lambda a=argb, w=w, h=h, st=style, se=secondary, sd=seed:
                          corrupt(a, w, h, st, se, sd)[0], w, h))
            cases.append((f"{mob.name}_c{style}_glow.png", lambda a=argb, w=w, h=h, st=style, se=secondary, sd=seed:
                          corrupt(a, w, h, st, se, sd)[1], w, h))
        anchors = [(w // 8, h // 4), (w // 2, h // 2), ((3 * w) // 4, (5 * h) // 8)]
        for stage in range(INFEST_STAGES):
            seed = 2000 + stage + idx * 3
            cases.append((f"{mob.name}_i{stage}.png", lambda a=argb, w=w, h=h, an=anchors, st=stage, sd=seed:
                          infest(a, w, h, an, st, sd), w, h))
    return cases


def check(java_dir, mobs):
    bad = 0
    total = 0
    for name, fn, w, h in check_cases(mobs):
        path = java_dir / name
        result = fn()
        if result is None:
            if path.exists():
                print("MISMATCH (Java has a glow, Python none):", name)
                bad += 1
            continue
        if not path.exists():
            print("missing Java output", name)
            bad += 1
            continue
        java = Image.open(path).convert("RGBA")
        mine = from_argb(result, w, h)
        total += 1
        if not np.array_equal(np.array(java), np.array(mine)):
            print("MISMATCH", name)
            bad += 1
    print(f"checked {total} overlays against Java: {bad} mismatches")
    return bad == 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview", type=pathlib.Path, help="directory for preview renders")
    parser.add_argument("--standins", type=pathlib.Path, help="write the stand-in mob textures here")
    parser.add_argument("--check", type=pathlib.Path, help="directory of overlays written by the Java code")
    args = parser.parse_args()
    write_textures()
    mobs = standin_mobs()
    if args.standins:
        args.standins.mkdir(parents=True, exist_ok=True)
        for mob in mobs:
            mob.texture.save(args.standins / f"{mob.name}.png")
    if args.check:
        if not check(args.check, mobs):
            raise SystemExit(1)
    if args.preview:
        preview(args.preview, mobs)


if __name__ == "__main__":
    main()
