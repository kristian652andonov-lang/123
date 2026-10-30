#!/usr/bin/env python3
"""Generates every block asset NULL ships.

For each block in registry/NullBlocks.java this writes
  src/main/resources/assets/null/textures/block/<name>.png (+ .png.mcmeta when animated)
  src/main/resources/assets/null/models/block/<name>.json
  src/main/resources/assets/null/blockstates/<name>.json
plus the block loot tables (data/null/loot_table/blocks/) and the vanilla mineable tool tags
(data/minecraft/tags/block/mineable/).

All art is original and procedural (16x16 pixel art drawn from noise, cells and patterns; no
Mojang texture is read or copied). Every texture is drawn from its own fixed seed, so running
the script again produces byte-identical files. Blocks with several faces keep the vanilla
naming: <name>.png is the side (and the particle texture), <name>_top.png / <name>_bottom.png
the ends, so every block has a <name>.png that other code may reference.

Usage:
  python3 tools/gen_block_assets.py               # regenerate everything
  python3 tools/gen_block_assets.py --sheet X.png # also write an enlarged contact sheet
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "src" / "main" / "resources"
ASSETS = RES / "assets" / "null"
TEX_DIR = ASSETS / "textures" / "block"
MODELS = ASSETS / "models" / "block"
BLOCKSTATES = ASSETS / "blockstates"
LOOT = RES / "data" / "null" / "loot_table" / "blocks"
MINEABLE = RES / "data" / "minecraft" / "tags" / "block" / "mineable"
NULL_BLOCKS_JAVA = ROOT / "src" / "main" / "java" / "dev" / "kristian" / "nullmod" / "registry" / "NullBlocks.java"

MODID = "null"
S = 16
YY, XX = np.mgrid[0:S, 0:S]


# ---------------------------------------------------------------------------------------------
# Small pixel-art toolkit
# ---------------------------------------------------------------------------------------------

def rgb(hex_color: str) -> np.ndarray:
    h = hex_color.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], dtype=np.float64)


def rng_for(key: str) -> np.random.Generator:
    """A generator seeded from the texture name (stable across runs and Python versions)."""
    seed = int.from_bytes(hashlib.sha256(("null-block:" + key).encode()).digest()[:8], "little")
    return np.random.default_rng(seed)


def solid(color: str, h: int = S, w: int = S) -> np.ndarray:
    img = np.zeros((h, w, 3))
    img[...] = rgb(color)
    return img


def opaque(h: int = S, w: int = S) -> np.ndarray:
    return np.full((h, w), 255.0)


def tile_noise(rng: np.random.Generator, cells: int, size: int = S) -> np.ndarray:
    """Tileable smooth value noise in 0..1 (bilinear with smoothstep between random lattice values)."""
    grid = rng.random((cells, cells))
    t = (np.arange(size) + 0.5) * cells / size - 0.5
    i0 = np.floor(t).astype(int)
    f = t - i0
    f = f * f * (3.0 - 2.0 * f)
    i0 %= cells
    i1 = (i0 + 1) % cells
    a = grid[i0][:, i0]
    b = grid[i0][:, i1]
    c = grid[i1][:, i0]
    d = grid[i1][:, i1]
    fy = f[:, None]
    fx = f[None, :]
    return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy


def coarse(rng: np.random.Generator, block: int = 2, size: int = S) -> np.ndarray:
    """Random values in blocks of block x block pixels (the chunky noise of old textures)."""
    small = rng.random((size // block, size // block))
    return np.kron(small, np.ones((block, block)))


def ranked(v: np.ndarray) -> np.ndarray:
    """Maps values to 0..1 by rank, so palette proportions are exact whatever the noise does."""
    flat = v.ravel()
    order = np.argsort(flat, kind="stable")
    ranks = np.empty(flat.size, dtype=np.float64)
    ranks[order] = np.arange(flat.size)
    return (ranks / flat.size).reshape(v.shape)


def paint(v: np.ndarray, palette: list[str], weights: list[float]) -> np.ndarray:
    """Quantises a value field onto a dark-to-light palette with the given proportions."""
    u = ranked(v)
    cw = np.cumsum(weights) / float(sum(weights))
    idx = np.clip(np.searchsorted(cw, u, side="right"), 0, len(palette) - 1)
    colours = np.array([rgb(p) for p in palette])
    return colours[idx]


def mix(a: np.ndarray, b: np.ndarray, t) -> np.ndarray:
    return a * (1.0 - t) + b * t


def to_rgba(img: np.ndarray, alpha: np.ndarray | None = None) -> np.ndarray:
    h, w = img.shape[:2]
    out = np.zeros((h, w, 4), dtype=np.uint8)
    out[..., :3] = np.clip(np.rint(img), 0, 255).astype(np.uint8)
    out[..., 3] = 255 if alpha is None else np.clip(np.rint(alpha), 0, 255).astype(np.uint8)
    return out


def torus_dist(ax, ay, bx, by, size=S):
    dx = np.abs(ax - bx)
    dy = np.abs(ay - by)
    dx = np.minimum(dx, size - dx)
    dy = np.minimum(dy, size - dy)
    return dx, dy


def cells(rng: np.random.Generator, count: int, min_dist: float, squash: float = 1.0):
    """Tileable Voronoi cells: returns (label map, number of cells)."""
    pts = []
    tries = 0
    while len(pts) < count and tries < 20000:
        tries += 1
        p = rng.random(2) * S
        ok = True
        for q in pts:
            dx, dy = torus_dist(p[0], p[1], q[0], q[1])
            if (dx * dx + dy * dy) ** 0.5 < min_dist:
                ok = False
                break
        if ok:
            pts.append(p)
    best = np.full((S, S), 1e9)
    label = np.zeros((S, S), dtype=int)
    for i, (px, py) in enumerate(pts):
        dx, dy = torus_dist(XX + 0.5, YY + 0.5, px, py)
        d = np.sqrt(dx * dx + (dy * squash) ** 2)
        m = d < best
        best[m] = d[m]
        label[m] = i
    return label, len(pts)


def cell_edges(label: np.ndarray) -> np.ndarray:
    """1-pixel outlines: a pixel is an edge if its right or lower neighbour is another cell."""
    return (label != np.roll(label, -1, axis=1)) | (label != np.roll(label, -1, axis=0))


# ---------------------------------------------------------------------------------------------
# Natural blocks (NULL's own versions, used as the base for the corrupted ones)
# ---------------------------------------------------------------------------------------------

def nat_grass_top(rng):
    v = 0.62 * rng.random((S, S)) + 0.38 * tile_noise(rng, 4)
    return paint(v, ["#2c561d", "#386a25", "#447c2d", "#518d34", "#5f9e3d", "#71b04a"], [6, 15, 25, 27, 19, 8])


def nat_dirt(rng):
    v = 0.6 * rng.random((S, S)) + 0.4 * tile_noise(rng, 4)
    img = paint(v, ["#47301f", "#583c27", "#6a4930", "#7b5739", "#8b6544"], [9, 22, 32, 25, 12])
    for _ in range(6):
        x, y = rng.integers(0, S, 2)
        img[y, x] = rgb(str(rng.choice(["#9b7b59", "#3a271a", "#94714f"])))
    return img


def nat_stone(rng):
    v = 0.5 * rng.random((S, S)) + 0.5 * tile_noise(rng, 5)
    img = paint(v, ["#59595b", "#656567", "#717173", "#7c7c7e", "#89898b"], [10, 22, 32, 24, 12])
    for _ in range(4):
        y = int(rng.integers(0, S))
        x = int(rng.integers(0, S))
        for k in range(int(rng.integers(2, 5))):
            img[y, (x + k) % S] *= 0.86
    return img


def nat_sand(rng):
    v = 0.7 * rng.random((S, S)) + 0.3 * tile_noise(rng, 4)
    img = paint(v, ["#c2b385", "#cdbf90", "#d7ca9b", "#e1d6a8"], [14, 32, 36, 18])
    for _ in range(7):
        x, y = rng.integers(0, S, 2)
        img[y, x] = rgb(str(rng.choice(["#ad9c6d", "#b8a878", "#e9e0b8"])))
    return img


def nat_log_side(rng):
    streak = np.tile(rng.random(S)[None, :], (S, 1))
    v = 0.4 * rng.random((S, S)) + 0.6 * streak
    v = 0.5 * v + 0.25 * np.roll(v, 1, axis=0) + 0.25 * np.roll(v, -1, axis=0)
    img = paint(v, ["#3b2a18", "#4c3820", "#5d4629", "#6c5331", "#7a5f39"], [12, 20, 30, 26, 12])
    for gx in rng.choice(S, 3, replace=False):
        x = int(gx)
        for y in range(S):
            if rng.random() < 0.12:
                x = (x + int(rng.choice([-1, 1]))) % S
            if rng.random() < 0.85:
                img[y, x] = rgb("#2c2012")
    return img


def nat_log_top(rng):
    cx = cy = 7.5
    eu = np.sqrt((XX - cx) ** 2 + (YY - cy) ** 2)
    ch = np.maximum(np.abs(XX - cx), np.abs(YY - cy))
    d = 0.55 * eu + 0.45 * ch + 0.5 * rng.random((S, S))
    ring = (np.floor(d * 0.85) % 2)[..., None]
    img = mix(rgb("#a7814c"), rgb("#8f6b3c"), ring)
    img[(d < 1.2)] = rgb("#b48c55")
    border = (XX == 0) | (XX == S - 1) | (YY == 0) | (YY == S - 1)
    bark = np.where(rng.random((S, S)) < 0.5, 0, 1)[..., None]
    img = np.where(border[..., None], mix(rgb("#4c3820"), rgb("#3b2a18"), bark), img)
    return img


def nat_leaves(rng):
    v = 0.6 * rng.random((S, S)) + 0.4 * tile_noise(rng, 4)
    img = paint(v, ["#214b18", "#2c5d20", "#376f28", "#438131", "#52943b"], [10, 22, 30, 26, 12])
    h = 0.7 * rng.random((S, S)) + 0.3 * tile_noise(rng, 3)
    alpha = np.where(ranked(h) < 0.2, 0.0, 255.0)
    return img, alpha


def nat_grass_side(rng, dirt, top):
    img = dirt.copy()
    depth = 3 + (rng.random(S) < 0.45).astype(int) + (rng.random(S) < 0.18).astype(int)
    for x in range(S):
        d = int(depth[x])
        img[:d, x] = top[:d, x]
        img[d, x] = img[d, x] * 0.8
    return img


# ---------------------------------------------------------------------------------------------
# Corruption: natural blocks eaten by black and magenta-black glitch blocks, with shifted rows
# ---------------------------------------------------------------------------------------------

BLACK = rgb("#000000")
MAGENTA = rgb("#f800f8")
PURPLE = rgb("#6b14a6")
DARK_PURPLE = rgb("#2a0742")


def corrupt(img, alpha, rng, *, blacks=3, checkers=1, tears=2, smears=1, fray=0.4, specks=4):
    """Eats into a texture: black holes with frayed edges, magenta/black checker fragments (the
    missing texture showing through), torn row segments that slid sideways and left a black or
    smeared gap behind, and smeared pixels."""
    img = img.copy()
    alpha = alpha.copy()
    eaten = np.zeros((S, S), dtype=bool)

    def rect(x, y, w, h):
        ys = (np.arange(h) + y) % S
        xs = (np.arange(w) + x) % S
        return np.ix_(ys, xs)

    for _ in range(blacks):
        w = int(rng.choice([2, 2, 3, 3, 4, 5]))
        h = int(rng.choice([1, 2, 2, 3, 3, 4]))
        x = int(rng.integers(0, S // 2)) * 2
        y = int(rng.integers(0, S))
        r = rect(x, y, w, h)
        img[r] = BLACK
        alpha[r] = 255
        eaten[r] = True
    if fray > 0:
        nb = (np.roll(eaten, 1, 0) | np.roll(eaten, -1, 0) | np.roll(eaten, 1, 1) | np.roll(eaten, -1, 1)) & ~eaten
        pick = nb & (rng.random((S, S)) < fray)
        burnt = rng.random((S, S)) < 0.5
        img[pick & burnt] = BLACK
        img[pick & ~burnt] = img[pick & ~burnt] * 0.25
        alpha[pick] = 255
    for _ in range(checkers):
        w = int(rng.choice([4, 4, 6, 8]))
        h = int(rng.choice([2, 4, 4]))
        x = int(rng.integers(0, S // 2)) * 2
        y = int(rng.integers(0, S // 2)) * 2
        cell = int(rng.choice([1, 2, 2]))
        ys = (np.arange(h) + y) % S
        xs = (np.arange(w) + x) % S
        for j, yy in enumerate(ys):
            for i, xx in enumerate(xs):
                img[yy, xx] = MAGENTA if ((i // cell + j // cell) % 2 == 0) else BLACK
                alpha[yy, xx] = 255
    for _ in range(specks):
        x, y = rng.integers(0, S, 2)
        img[y, x] = BLACK if rng.random() < 0.85 else MAGENTA
        alpha[y, x] = 255
    for _ in range(tears):
        # A segment of 1-2 rows slides sideways; the gap it leaves is black or smeared.
        y = int(rng.integers(0, S))
        h = int(rng.choice([1, 1, 2]))
        x0 = int(rng.integers(0, S))
        length = int(rng.integers(5, 11))
        dx = int(rng.choice([-4, -3, -2, 2, 3, 4]))
        smear_gap = rng.random() < 0.4
        for yy in (np.arange(h) + y) % S:
            seg = [(x0 + k) % S for k in range(length)]
            moved_rgb = img[yy, seg].copy()
            moved_a = alpha[yy, seg].copy()
            for k, x in enumerate(seg):
                img[yy, x] = moved_rgb[0] if smear_gap else BLACK
                alpha[yy, x] = moved_a[0] if smear_gap else 255
            for k in range(length):
                x = (x0 + k + dx) % S
                img[yy, x] = moved_rgb[k]
                alpha[yy, x] = moved_a[k]
    for _ in range(smears):
        y = int(rng.integers(0, S))
        x0 = int(rng.integers(0, S))
        length = int(rng.integers(3, 7))
        for k in range(1, length + 1):
            img[y, (x0 + k) % S] = img[y, x0]
            alpha[y, (x0 + k) % S] = alpha[y, x0]
    return img, alpha


# ---------------------------------------------------------------------------------------------
# Texture catalogue. Each entry returns a list of frames (RGBA uint8 arrays); one frame = static.
# ---------------------------------------------------------------------------------------------

TEXTURES: dict[str, dict] = {}


def texture(name, animation=None):
    def wrap(fn):
        TEXTURES[name] = {"fn": fn, "animation": animation}
        return fn
    return wrap


def corrupted_variant(base_key: str, variant: str, *, fn_base, **kwargs):
    rng = rng_for(base_key + "/" + variant)
    out = fn_base(rng_for(base_key))
    if isinstance(out, tuple):
        img, alpha = out
    else:
        img, alpha = out, opaque()
    img, alpha = corrupt(img, alpha, rng, **kwargs)
    return [to_rgba(img, alpha)]


# ---- Corruption blocks ---------------------------------------------------------------------

@texture("black_block")
def tex_black_block():
    return [to_rgba(solid("#000000"))]


@texture("missing_block")
def tex_missing_block():
    # The classic placeholder: magenta where exactly one of (x < 8, y < 8) holds.
    img = solid("#000000")
    img[(YY < 8) ^ (XX < 8)] = MAGENTA
    return [to_rgba(img)]


@texture("error_block")
def tex_error_block():
    rng = rng_for("error_block")
    # A purple/black checker of 4px cells that failed to load in pieces.
    img = np.where((((YY // 4) + (XX // 4)) % 2 == 0)[..., None], PURPLE, BLACK)
    # Fragment 1: an 8x8 magenta checker chunk pasted 3px off the grid.
    for y in range(1, 9):
        for x in range(3, 11):
            if ((y - 1) // 4 + (x - 3) // 4) % 2 == 0:
                img[y, x] = MAGENTA
            else:
                img[y, x] = BLACK
    # Fragment 2: one cell re-drawn as a fine 1px checker (wrong mip level).
    for y in range(12, 16):
        for x in range(0, 4):
            img[y, x] = MAGENTA if (x + y) % 2 == 0 else DARK_PURPLE
    # Fragment 3: a cell that never loaded (dark placeholder with a lighter rim).
    img[8:12, 12:16] = rgb("#151018")
    img[8, 12:16] = rgb("#3a2a44")
    img[8:12, 12] = rgb("#3a2a44")
    # A torn band.
    img[10:12] = np.roll(img[10:12], 3, axis=1)
    img[14] = np.roll(img[14], -2, axis=0)
    # Stray pixels.
    for _ in range(7):
        x, y = rng.integers(0, S, 2)
        img[y, x] = [MAGENTA, BLACK, rgb("#a040e0")][int(rng.integers(0, 3))]
    return [to_rgba(img)]


@texture("error_stripes")
def tex_error_stripes():
    rng = rng_for("error_stripes")
    # Diagonal magenta/black stripes, period 6.
    phase = (XX + YY) % 6
    img = np.where((phase < 3)[..., None], rgb("#d000d0"), BLACK)
    img[(phase == 3)] = rgb("#3b0a52")
    # Misaligned bands: rows that slipped sideways.
    img[3:6] = np.roll(img[3:6], 2, axis=1)
    img[9:11] = np.roll(img[9:11], -3, axis=1)
    # A band drawn with the stripes running the other way.
    band = (np.arange(S)[None, :] - np.arange(6, 8)[:, None]) % 6
    img[6:8] = np.where((band < 3)[..., None], PURPLE, BLACK)
    # A partially missing band.
    img[12:14, 2:13] = rgb("#08020c")
    img[12, 2:13] = rgb("#1d0629")
    # A dead vertical column.
    img[:, 14] = np.where((YY[:, 14] % 3 == 0)[:, None], MAGENTA, BLACK)
    for _ in range(4):
        x, y = rng.integers(0, S, 2)
        img[y, x] = rgb("#ff7aff")
    return [to_rgba(img)]


def _error_noise_cells(rng):
    palette = np.array([BLACK, BLACK, BLACK, BLACK, DARK_PURPLE, PURPLE, MAGENTA,
                        rgb("#5a5a5a"), rgb("#1a1a1a"), rgb("#d8d8d8")])
    weights = np.array([30, 0, 0, 0, 14, 12, 14, 8, 16, 2], dtype=float)
    weights /= weights.sum()
    # Cells are 2 wide and 1 tall: blocky like broken compression.
    idx = rng.choice(len(palette), size=(S, S // 2), p=weights)
    return palette, idx


@texture("error_noise", animation="error_noise")
def tex_error_noise():
    rng = rng_for("error_noise")
    palette, idx = _error_noise_cells(rng)
    weights = np.array([30, 0, 0, 0, 14, 12, 14, 8, 16, 2], dtype=float)
    weights /= weights.sum()
    frames = []
    for i in range(12):
        if i > 0:
            if i in (2, 5, 6, 9):
                change = rng.random(idx.shape) < 0.05   # near-duplicate frame: the flicker "sticks"
            else:
                change = rng.random(idx.shape) < 0.7
            idx = np.where(change, rng.choice(len(palette), size=idx.shape, p=weights), idx)
        img = palette[np.repeat(idx, 2, axis=1)]
        if i in (3, 8):   # a magenta scan line tears through
            y = int(rng.integers(0, S))
            img[y] = MAGENTA
        if i in (4, 10):  # a dead band
            y = int(rng.integers(0, S - 2))
            img[y:y + 2] = BLACK
        frames.append(to_rgba(img))
    return frames


@texture("error_shift", animation="error_shift")
def tex_error_shift():
    rng = rng_for("error_shift")
    base = np.where(((YY < 8) ^ (XX < 8))[..., None], MAGENTA, BLACK)
    base[(XX % 8 == 7) | (YY % 8 == 7)] = PURPLE
    frames = []
    for i in range(12):
        img = base.copy()
        near_dup = i in (3, 7, 10)
        local = rng_for(f"error_shift/{i - 1 if near_dup else i}")
        # Horizontal tears: a few bands slide sideways by different amounts.
        for _ in range(int(local.integers(2, 5))):
            y = int(local.integers(0, S))
            h = int(local.integers(1, 5))
            dx = int(local.integers(-7, 8))
            rows = (np.arange(h) + y) % S
            img[rows] = np.roll(img[rows], dx, axis=1)
        # Smears: a pixel stretched to the right edge of the row.
        for _ in range(int(local.integers(1, 4))):
            y = int(local.integers(0, S))
            x0 = int(local.integers(0, S - 3))
            img[y, x0:] = img[y, x0]
        # A missing band.
        if local.random() < 0.6:
            y = int(local.integers(0, S - 1))
            img[y:y + int(local.integers(1, 3))] = rgb("#060008")
        if near_dup:
            y = int(rng.integers(0, S))
            img[y] = np.roll(img[y], 1, axis=0)
        frames.append(to_rgba(img))
    return frames


@texture("static_block", animation="static_block")
def tex_static_block():
    rng = rng_for("static_block")
    frames = []
    for i in range(20):
        lum = rng.random((S, S)) * 0.8 + 0.12
        row_gain = 1.0 + 0.22 * rng.standard_normal(S)[:, None]
        lum = lum * row_gain
        band = (i * 5) % (S + 6) - 3   # a brighter band rolls down the screen
        for y in range(S):
            if 0 <= y - band < 3:
                lum[y] = lum[y] * 0.55 + 0.45
        if i % 7 == 3:                 # an occasional dark sync line
            lum[int(rng.integers(0, S))] *= 0.25
        grey = np.clip(lum, 0, 1) * 235 + 10
        img = np.repeat(grey[..., None], 3, axis=2)
        frames.append(to_rgba(img))
    return frames


@texture("corrupted_grass_block_top")
def tex_corrupted_grass_top():
    return corrupted_variant("grass_top", "a", fn_base=nat_grass_top, blacks=3, checkers=1, tears=2, smears=1)


@texture("corrupted_grass_block_top_alt")
def tex_corrupted_grass_top_alt():
    return corrupted_variant("grass_top", "b", fn_base=nat_grass_top, blacks=2, checkers=2, tears=2, smears=1)


def _grass_side(rng_key):
    rng = rng_for(rng_key)
    return nat_grass_side(rng, nat_dirt(rng_for("dirt")), nat_grass_top(rng_for("grass_top")))


@texture("corrupted_grass_block")
def tex_corrupted_grass_side():
    return corrupted_variant("grass_side", "a", fn_base=lambda r: _grass_side("grass_side"),
                             blacks=2, checkers=1, tears=3, smears=1)


@texture("corrupted_grass_block_alt")
def tex_corrupted_grass_side_alt():
    return corrupted_variant("grass_side", "b", fn_base=lambda r: _grass_side("grass_side"),
                             blacks=3, checkers=1, tears=2, smears=2)


@texture("corrupted_grass_block_bottom")
def tex_corrupted_grass_bottom():
    return corrupted_variant("dirt", "bottom", fn_base=nat_dirt, blacks=2, checkers=1, tears=1, smears=1)


@texture("corrupted_dirt")
def tex_corrupted_dirt():
    return corrupted_variant("dirt", "a", fn_base=nat_dirt, blacks=3, checkers=1, tears=2, smears=1)


@texture("corrupted_dirt_alt")
def tex_corrupted_dirt_alt():
    return corrupted_variant("dirt", "b", fn_base=nat_dirt, blacks=2, checkers=2, tears=2, smears=1)


@texture("corrupted_stone")
def tex_corrupted_stone():
    return corrupted_variant("stone", "a", fn_base=nat_stone, blacks=3, checkers=1, tears=2, smears=1)


@texture("corrupted_stone_alt")
def tex_corrupted_stone_alt():
    return corrupted_variant("stone", "b", fn_base=nat_stone, blacks=2, checkers=2, tears=3, smears=1)


@texture("corrupted_sand")
def tex_corrupted_sand():
    return corrupted_variant("sand", "a", fn_base=nat_sand, blacks=3, checkers=1, tears=2, smears=1)


@texture("corrupted_sand_alt")
def tex_corrupted_sand_alt():
    return corrupted_variant("sand", "b", fn_base=nat_sand, blacks=2, checkers=2, tears=2, smears=2)


@texture("corrupted_log")
def tex_corrupted_log():
    return corrupted_variant("log_side", "a", fn_base=nat_log_side, blacks=3, checkers=1, tears=3, smears=1)


@texture("corrupted_log_top")
def tex_corrupted_log_top():
    return corrupted_variant("log_top", "a", fn_base=nat_log_top, blacks=2, checkers=1, tears=2, smears=1)


@texture("corrupted_leaves")
def tex_corrupted_leaves():
    return corrupted_variant("leaves", "a", fn_base=nat_leaves, blacks=3, checkers=1, tears=2, smears=1, fray=0.3)


@texture("corrupted_leaves_alt")
def tex_corrupted_leaves_alt():
    return corrupted_variant("leaves", "b", fn_base=nat_leaves, blacks=2, checkers=2, tears=2, smears=1, fray=0.3)


@texture("residue")
def tex_residue():
    rng = rng_for("residue")
    v = 0.55 * rng.random((S, S)) + 0.45 * tile_noise(rng, 4)
    img = paint(v, ["#050307", "#09060b", "#0d0a10", "#121016"], [20, 38, 30, 12])
    vein_a = rgb("#321443")
    vein_b = rgb("#47205e")
    # Veins: wandering, branching random walks that wrap around the tile edges.
    for start in range(3):
        x, y = rng.integers(0, S, 2).astype(float)
        heading = rng.random() * 2 * np.pi
        for step in range(int(rng.integers(14, 22))):
            heading += rng.normal(0, 0.55)
            x = (x + np.cos(heading)) % S
            y = (y + np.sin(heading)) % S
            px, py = int(x), int(y)
            img[py, px] = vein_b if step % 5 == 0 else vein_a
            if rng.random() < 0.12:   # a short side branch
                bh = heading + rng.choice([-1.2, 1.2])
                bx, by = x, y
                for _ in range(int(rng.integers(2, 5))):
                    bx = (bx + np.cos(bh)) % S
                    by = (by + np.sin(bh)) % S
                    img[int(by), int(bx)] = mix(vein_a, rgb("#120a18"), 0.5)
    for _ in range(5):   # faint wet sheen
        x, y = rng.integers(0, S, 2)
        img[y, x] = rgb("#1f1a26")
    return [to_rgba(img)]


# ---- Retro terrain: an outdated look - fewer colours, more saturation, chunkier noise -------

def retro_dirt_img(rng):
    v = 0.55 * coarse(rng, 2) + 0.45 * rng.random((S, S))
    img = paint(v, ["#6a4119", "#7e4f22", "#925e2c", "#a46d36"], [18, 34, 32, 16])
    for _ in range(9):
        x, y = rng.integers(0, S, 2)
        img[y, x] = rgb("#4e2f12")
    return img


def retro_grass_top_img(rng):
    v = 0.55 * coarse(rng, 2) + 0.45 * rng.random((S, S))
    return paint(v, ["#3a8f1c", "#48a523", "#57bb2c", "#6cd13b"], [18, 34, 32, 16])


@texture("retro_grass_block_top")
def tex_retro_grass_top():
    return [to_rgba(retro_grass_top_img(rng_for("retro_grass_top")))]


@texture("retro_grass_block")
def tex_retro_grass_side():
    rng = rng_for("retro_grass_side")
    img = retro_dirt_img(rng_for("retro_dirt"))
    top = retro_grass_top_img(rng_for("retro_grass_top"))
    for x in range(S):
        d = 3 if x % 4 in (0, 3) else 4
        if rng.random() < 0.3:
            d += 1
        img[:d, x] = top[:d, x]
    return [to_rgba(img)]


@texture("retro_grass_block_bottom")
def tex_retro_grass_bottom():
    return [to_rgba(retro_dirt_img(rng_for("retro_dirt")))]


@texture("retro_dirt")
def tex_retro_dirt():
    return [to_rgba(retro_dirt_img(rng_for("retro_dirt")))]


@texture("retro_stone")
def tex_retro_stone():
    rng = rng_for("retro_stone")
    v = 0.6 * coarse(rng, 2) + 0.4 * rng.random((S, S))
    img = paint(v, ["#646464", "#727272", "#808080", "#8d8d8d"], [16, 32, 34, 18])
    for _ in range(5):
        y = int(rng.integers(0, S))
        x = int(rng.integers(0, S))
        img[y, x:x + int(rng.integers(2, 4))] = rgb("#575757")
    return [to_rgba(img)]


def cobble_img(rng, moss_rng=None):
    label, n = cells(rng, 9, 4.2, squash=1.2)
    edges = cell_edges(label)
    shades = [rgb(c) for c in ["#6c6c6c", "#7b7b7b", "#888888", "#959595", "#727272"]]
    img = np.zeros((S, S, 3))
    for i in range(n):
        img[label == i] = shades[int(rng.integers(0, len(shades)))]
    img *= (0.94 + 0.12 * rng.random((S, S)))[..., None]
    up = np.roll(edges, 1, axis=0)
    left = np.roll(edges, 1, axis=1)
    img[(up | left) & ~edges] *= 1.16
    down = np.roll(edges, -1, axis=0)
    img[down & ~edges & ~up] *= 0.86
    img[edges] = rgb("#3c3c3c")
    if moss_rng is not None:
        m = 0.6 * tile_noise(moss_rng, 3) + 0.4 * moss_rng.random((S, S))
        moss = ranked(m) > 0.58
        moss |= edges & (ranked(m) > 0.4)
        green = paint(moss_rng.random((S, S)), ["#356f22", "#43862a", "#53a033"], [30, 40, 30])
        img[moss] = green[moss]
    return np.clip(img, 0, 255)


@texture("retro_cobblestone")
def tex_retro_cobblestone():
    return [to_rgba(cobble_img(rng_for("retro_cobble")))]


@texture("retro_mossy_cobblestone")
def tex_retro_mossy_cobblestone():
    return [to_rgba(cobble_img(rng_for("retro_cobble"), rng_for("retro_moss")))]


@texture("retro_gravel")
def tex_retro_gravel():
    rng = rng_for("retro_gravel")
    v = 0.6 * coarse(rng, 2) + 0.4 * rng.random((S, S))
    img = paint(v, ["#5d5654", "#706866", "#827a77", "#7a6c5f", "#958d8a", "#aaa4a0"], [10, 20, 24, 14, 20, 12])
    # Each 2x2 pebble gets a lit top-left and a shadowed bottom-right pixel.
    lit = (YY % 2 == 0) & (XX % 2 == 0) & (np.kron(rng.random((S // 2, S // 2)), np.ones((2, 2))) < 0.55)
    shade = (YY % 2 == 1) & (XX % 2 == 1) & (np.kron(rng.random((S // 2, S // 2)), np.ones((2, 2))) < 0.7)
    img[lit] *= 1.14
    img[shade] *= 0.7
    for _ in range(6):
        x, y = rng.integers(0, S, 2)
        img[y, x] = rgb("#4a4442")
    return [to_rgba(np.clip(img, 0, 255))]


@texture("retro_sand")
def tex_retro_sand():
    rng = rng_for("retro_sand")
    v = 0.5 * coarse(rng, 2) + 0.5 * rng.random((S, S))
    img = paint(v, ["#d0bd6c", "#dbc979", "#e5d588", "#efe19a"], [16, 34, 34, 16])
    for _ in range(8):
        x, y = rng.integers(0, S, 2)
        img[y, x] = rgb("#bda559")
    return [to_rgba(img)]


@texture("retro_log")
def tex_retro_log():
    rng = rng_for("retro_log")
    streak = np.tile(rng.random(S // 2)[None, :], (S, 1)).repeat(2, axis=1)
    v = 0.65 * streak + 0.35 * rng.random((S, S))
    img = paint(v, ["#4f3416", "#62421d", "#755025", "#86602e"], [16, 32, 34, 18])
    for gx in (1, 6, 10, 13):
        x = gx
        for y in range(S):
            if rng.random() < 0.15:
                x = (x + int(rng.choice([-1, 1]))) % S
            if rng.random() < 0.8:
                img[y, x] = rgb("#3a250e")
    return [to_rgba(img)]


@texture("retro_log_top")
def tex_retro_log_top():
    rng = rng_for("retro_log_top")
    ch = np.maximum(np.abs(XX - 7.5), np.abs(YY - 7.5))
    ring = (np.floor(ch) % 2)[..., None]
    img = mix(rgb("#bf914e"), rgb("#a37940"), ring)
    img *= (0.95 + 0.1 * rng.random((S, S)))[..., None]
    border = (XX == 0) | (XX == S - 1) | (YY == 0) | (YY == S - 1)
    img[border] = rgb("#5a3c19")
    img[(XX >= 7) & (XX <= 8) & (YY >= 7) & (YY <= 8)] = rgb("#c99b58")
    return [to_rgba(np.clip(img, 0, 255))]


@texture("retro_leaves")
def tex_retro_leaves():
    rng = rng_for("retro_leaves")
    v = 0.5 * coarse(rng, 2) + 0.5 * rng.random((S, S))
    img = paint(v, ["#25751a", "#2f8a20", "#3aa027", "#4bb632"], [18, 34, 32, 16])
    holes = ranked(0.6 * coarse(rng, 2) + 0.4 * rng.random((S, S))) < 0.24
    alpha = np.where(holes, 0.0, 255.0)
    img[holes] = rgb("#1d5a14")   # hidden colour under the holes keeps mipmaps dark
    return [to_rgba(img, alpha)]


@texture("retro_planks")
def tex_retro_planks():
    rng = rng_for("retro_planks")
    img = np.zeros((S, S, 3))
    base = [rgb("#a8743a"), rgb("#b8813f"), rgb("#9e6c34"), rgb("#b27b3c")]
    seams = [3, 11, 7, 14]
    for p in range(4):
        y0 = p * 4
        grain = np.repeat(rng.random((4, S // 4)), 4, axis=1)
        grain = 0.6 * grain + 0.4 * rng.random((4, S))
        shade = (0.9 + 0.2 * grain)[..., None]
        img[y0:y0 + 4] = base[p] * shade
        img[y0 + 3] = rgb("#5f3c17")          # gap under each plank
        img[y0:y0 + 3, seams[p]] = rgb("#6e4519")   # butt joint
        img[y0, :] = img[y0, :] * 1.07        # lit top edge
    return [to_rgba(np.clip(img, 0, 255))]


@texture("retro_bricks")
def tex_retro_bricks():
    rng = rng_for("retro_bricks")
    img = np.zeros((S, S, 3))
    mortar = rgb("#b5ada3")
    reds = [rgb(c) for c in ["#9d3a29", "#ae4430", "#8b3224", "#b84d36"]]
    for row in range(4):
        y0 = row * 4
        offset = 0 if row % 2 == 0 else 4
        for b in range(-1, 3):
            x0 = b * 8 + offset
            colour = reds[int(rng.integers(0, len(reds)))]
            for y in range(y0, y0 + 3):
                for x in range(x0, x0 + 7):
                    img[y, x % S] = colour * (0.9 + 0.2 * rng.random())
            for y in range(y0, y0 + 4):
                img[y, (x0 + 7) % S] = mortar
        img[y0 + 3, :] = mortar
    img[(img.sum(axis=2) == 0)] = mortar
    return [to_rgba(np.clip(img, 0, 255))]


@texture("retro_glass")
def tex_retro_glass():
    img = solid("#cfeaf4")
    alpha = np.zeros((S, S))
    frame = (XX == 0) | (XX == S - 1) | (YY == 0) | (YY == S - 1)
    alpha[frame] = 255
    img[(XX == S - 1) | (YY == S - 1)] = rgb("#9fc2d0")
    img[(XX == 0) | (YY == 0)] = rgb("#e6f6fb")
    for x, y in [(3, 2), (2, 3), (4, 2), (2, 4), (5, 5), (11, 12), (12, 11)]:
        alpha[y, x] = 255
        img[y, x] = rgb("#f4fbfd")
    return [to_rgba(img, alpha)]


# ---- The NULL dimension -------------------------------------------------------------------

@texture("void_panel")
def tex_void_panel():
    rng = rng_for("void_panel")
    img = solid("#07070a")
    img += (rng.random((S, S)) * 2.0 - 1.0)[..., None] * 1.2
    img[(XX == 1) | (YY == 1)] = rgb("#0a0a0e")
    img[(XX == S - 2) | (YY == S - 2)] = rgb("#040406")
    img[(XX == 0) | (YY == 0) | (XX == S - 1) | (YY == S - 1)] = rgb("#020203")
    return [to_rgba(np.clip(img, 0, 255))]


@texture("grid_panel")
def tex_grid_panel():
    img = solid("#020203")
    img[(XX == 8) | (YY == 8)] = rgb("#10151c")
    img[(XX == 0) | (YY == 0)] = rgb("#1b232e")
    img[(XX == 0) & (YY == 0)] = rgb("#2c3846")
    img[(XX == 8) & (YY == 8)] = rgb("#18202a")
    return [to_rgba(img)]


@texture("frame_block")
def tex_frame_block():
    edge = np.minimum(np.minimum(XX, S - 1 - XX), np.minimum(YY, S - 1 - YY))
    img = solid("#03060a")
    img[edge == 0] = rgb("#c4faff")
    img[edge == 1] = rgb("#36d2ea")
    img[edge == 2] = rgb("#0f4654")
    img[edge == 3] = rgb("#08232b")
    img[edge == 4] = rgb("#05121a")
    corner = ((XX <= 1) | (XX >= S - 2)) & ((YY <= 1) | (YY >= S - 2))
    img[corner & (edge == 0)] = rgb("#ffffff")
    return [to_rgba(img)]


GLYPHS = {
    "0": ["###", "#.#", "#.#", "#.#", "###"],
    "1": [".#.", "##.", ".#.", ".#.", "###"],
}


@texture("data_block", animation="data_block")
def tex_data_block():
    rng = rng_for("data_block")
    frames_n = 24
    speeds = [1, 2, 1, 2]
    tapes = []
    for col in range(4):
        length = frames_n * speeds[col]   # the tape scrolls exactly once per animation cycle
        tape = np.zeros((length, 3, 3))
        for slot in range(length // 6):
            if rng.random() < 0.18:
                continue  # an empty slot: gaps make the columns read as a stream
            glyph = GLYPHS[str(rng.choice(["0", "1"]))]
            level = rng.choice([0.35, 0.6, 0.85, 1.0], p=[0.25, 0.35, 0.28, 0.12])
            colour = mix(rgb("#00a8bb"), rgb("#b8ffff"), float(level) ** 2) * (0.45 + 0.55 * float(level))
            for gy, line in enumerate(glyph):
                for gx, ch in enumerate(line):
                    if ch == "#":
                        tape[slot * 6 + gy, gx] = colour
        tapes.append(tape)
    frames = []
    for f in range(frames_n):
        img = solid("#000304")
        for col in range(4):
            tape = tapes[col]
            length = tape.shape[0]
            for y in range(S):
                row = tape[(y - f * speeds[col]) % length]
                img[y, col * 4:col * 4 + 3] = np.maximum(img[y, col * 4:col * 4 + 3], row)
        flicker = rng_for(f"data_block/{f}")
        for _ in range(3):
            x, y = flicker.integers(0, S, 2)
            img[y, x] = np.maximum(img[y, x], rgb("#004b55"))
        frames.append(to_rgba(img))
    return frames


@texture("null_glass")
def tex_null_glass():
    img = solid("#0a0e18")
    alpha = np.full((S, S), 118.0)
    mid = (XX == 8) | (YY == 8)
    img[mid] = rgb("#1d3a4c")
    alpha[mid] = 150
    edge = (XX == 0) | (YY == 0)
    img[edge] = rgb("#3a7088")
    alpha[edge] = 196
    img[(XX == 0) & (YY == 0)] = rgb("#6fb6d0")
    alpha[(XX == 0) & (YY == 0)] = 220
    sheen = (XX - YY == 6) & (XX > 8) & (XX < 14)
    img[sheen] = rgb("#1a2a3a")
    alpha[sheen] = 140
    return [to_rgba(img, alpha)]


GLOW_COLOURS = {
    "glow_red": ("#ff2a2a", 3),
    "glow_green": ("#2aff52", 4),
    "glow_blue": ("#2f5bff", 5),
    "glow_cyan": ("#1ef2ff", 6),
    "glow_magenta": ("#ff2ae6", 7),
    "glow_yellow": ("#ffe82a", 8),
    "glow_white": ("#f2f6ff", 9),
}


def glow_frames(name):
    colour_hex, _ = GLOW_COLOURS[name]
    colour = rgb(colour_hex)
    white = rgb("#ffffff")
    edge = np.minimum(np.minimum(XX, S - 1 - XX), np.minimum(YY, S - 1 - YY))
    r = np.sqrt((XX - 7.5) ** 2 + (YY - 7.5) ** 2) / 7.5
    rng = rng_for(name)
    dither = 1.0 + (rng.random((S, S)) - 0.5) * 0.05
    frames = []
    n = 12
    for i in range(n):
        pulse = 0.5 + 0.5 * np.cos(2 * np.pi * i / n)       # 1 at frame 0, 0 at the middle
        bright = 0.8 + 0.2 * pulse
        core = mix(colour, white, (0.32 * (1 - np.clip(r, 0, 1)) * (0.6 + 0.4 * pulse))[..., None])
        img = core * dither[..., None] * bright
        img[edge == 0] = colour * 0.32
        img[edge == 1] = mix(colour, white, 0.62) * (0.88 + 0.12 * pulse)
        img[edge == 2] = colour * 0.62 * bright
        frames.append(to_rgba(np.clip(img, 0, 255)))
    return frames


for _glow in GLOW_COLOURS:
    TEXTURES[_glow] = {"fn": (lambda name=_glow: glow_frames(name)), "animation": _glow}


# ---------------------------------------------------------------------------------------------
# Animation metadata
# ---------------------------------------------------------------------------------------------

def animation_meta(key: str, frame_count: int) -> dict:
    if key == "error_noise":
        # Irregular: some frames hold for a while, others flash past.
        times = [3, 2, 6, 2, 3, 7, 2, 3, 2, 5, 3, 4]
        return {"animation": {"frametime": 3, "frames": [{"index": i, "time": t} for i, t in enumerate(times)]}}
    if key == "error_shift":
        order = [0, 1, 2, 3, 2, 4, 5, 6, 7, 6, 8, 9, 10, 11, 10]
        times = [4, 2, 3, 2, 2, 4, 3, 2, 3, 2, 4, 2, 3, 2, 6]
        return {"animation": {"frametime": 3, "frames": [{"index": i, "time": t} for i, t in zip(order, times)]}}
    if key == "static_block":
        return {"animation": {"frametime": 1}}
    if key == "data_block":
        return {"animation": {"frametime": 2}}
    if key in GLOW_COLOURS:
        return {"animation": {"frametime": GLOW_COLOURS[key][1], "interpolate": True}}
    raise KeyError(key)


# ---------------------------------------------------------------------------------------------
# Models and blockstates
# ---------------------------------------------------------------------------------------------

def tex_ref(name: str) -> str:
    return f"{MODID}:block/{name}"


def model_ref(name: str) -> str:
    return f"{MODID}:block/{name}"


MODEL_FILES: dict[str, dict] = {}
STATE_FILES: dict[str, dict] = {}


def cube_all(model: str, texture_name: str, render_type: str | None = None):
    data = {"parent": "minecraft:block/cube_all", "textures": {"all": tex_ref(texture_name)}}
    if render_type:
        data["render_type"] = render_type
    MODEL_FILES[model] = data


def bottom_top(model: str, side: str, top: str, bottom: str):
    MODEL_FILES[model] = {
        "parent": "minecraft:block/cube_bottom_top",
        "textures": {"bottom": tex_ref(bottom), "side": tex_ref(side), "top": tex_ref(top)},
    }


def column(model: str, side: str, end: str):
    MODEL_FILES[model] = {"parent": "minecraft:block/cube_column",
                          "textures": {"end": tex_ref(end), "side": tex_ref(side)}}
    MODEL_FILES[model + "_horizontal"] = {"parent": "minecraft:block/cube_column_horizontal",
                                          "textures": {"end": tex_ref(end), "side": tex_ref(side)}}


def single_state(block: str, model: str | None = None):
    STATE_FILES[block] = {"variants": {"": {"model": model_ref(model or block)}}}


def rotated_state(block: str, models: list[str]):
    """Random y rotations (and texture variants) so patches of a block do not visibly repeat."""
    variants = []
    for m in models:
        for y in (0, 90, 180, 270):
            entry = {"model": model_ref(m)}
            if y:
                entry["y"] = y
            variants.append(entry)
    STATE_FILES[block] = {"variants": {"": variants}}


def axis_state(block: str):
    STATE_FILES[block] = {"variants": {
        "axis=x": {"model": model_ref(block + "_horizontal"), "x": 90, "y": 90},
        "axis=y": {"model": model_ref(block)},
        "axis=z": {"model": model_ref(block + "_horizontal"), "x": 90},
    }}


def build_models():
    for name in ["black_block", "missing_block", "error_block", "error_stripes", "error_noise",
                 "error_shift", "static_block"]:
        cube_all(name, name)
        single_state(name)

    bottom_top("corrupted_grass_block", "corrupted_grass_block", "corrupted_grass_block_top",
               "corrupted_grass_block_bottom")
    bottom_top("corrupted_grass_block_alt", "corrupted_grass_block_alt", "corrupted_grass_block_top_alt",
               "corrupted_grass_block_bottom")
    rotated_state("corrupted_grass_block", ["corrupted_grass_block", "corrupted_grass_block_alt"])

    for name in ["corrupted_dirt", "corrupted_stone", "corrupted_sand"]:
        cube_all(name, name)
        cube_all(name + "_alt", name + "_alt")
        rotated_state(name, [name, name + "_alt"])

    column("corrupted_log", "corrupted_log", "corrupted_log_top")
    axis_state("corrupted_log")

    cube_all("corrupted_leaves", "corrupted_leaves", "minecraft:cutout_mipped")
    cube_all("corrupted_leaves_alt", "corrupted_leaves_alt", "minecraft:cutout_mipped")
    rotated_state("corrupted_leaves", ["corrupted_leaves", "corrupted_leaves_alt"])

    MODEL_FILES["residue"] = {"parent": "minecraft:block/carpet", "textures": {"wool": tex_ref("residue")}}
    rotated_state("residue", ["residue"])

    bottom_top("retro_grass_block", "retro_grass_block", "retro_grass_block_top", "retro_grass_block_bottom")
    single_state("retro_grass_block")
    for name in ["retro_dirt", "retro_stone", "retro_cobblestone", "retro_mossy_cobblestone",
                 "retro_gravel", "retro_sand", "retro_planks", "retro_bricks"]:
        cube_all(name, name)
        single_state(name)
    column("retro_log", "retro_log", "retro_log_top")
    axis_state("retro_log")
    cube_all("retro_leaves", "retro_leaves", "minecraft:cutout_mipped")
    single_state("retro_leaves")
    cube_all("retro_glass", "retro_glass", "minecraft:cutout")
    single_state("retro_glass")

    for name in ["void_panel", "grid_panel", "frame_block", "data_block"] + list(GLOW_COLOURS):
        cube_all(name, name)
        single_state(name)
    cube_all("null_glass", "null_glass", "minecraft:translucent")
    single_state("null_glass")


# ---------------------------------------------------------------------------------------------
# Loot tables and tool tags
# ---------------------------------------------------------------------------------------------

RETRO_DROPS = {
    "retro_grass_block": "minecraft:dirt",
    "retro_dirt": "minecraft:dirt",
    "retro_stone": "minecraft:cobblestone",
    "retro_cobblestone": "minecraft:cobblestone",
    "retro_mossy_cobblestone": "minecraft:mossy_cobblestone",
    "retro_gravel": "minecraft:gravel",
    "retro_sand": "minecraft:sand",
    "retro_log": "minecraft:oak_log",
    "retro_planks": "minecraft:oak_planks",
    "retro_bricks": "minecraft:bricks",
}
RETRO_NO_DROPS = ["retro_leaves", "retro_glass"]

ERROR_BLOCKS = ["black_block", "missing_block", "error_block", "error_stripes", "error_noise",
                "error_shift", "static_block"]
MINEABLE_TAGS = {
    # The error blocks are "unknown" blocks that replaced dirt and stone alike: both tools work.
    "pickaxe": ERROR_BLOCKS + ["corrupted_stone", "retro_stone", "retro_cobblestone",
                               "retro_mossy_cobblestone", "retro_bricks"],
    "shovel": ERROR_BLOCKS + ["corrupted_grass_block", "corrupted_dirt", "corrupted_sand",
                              "retro_grass_block", "retro_dirt", "retro_gravel", "retro_sand"],
    "axe": ["corrupted_log", "retro_log", "retro_planks"],
    "hoe": ["corrupted_leaves", "residue", "retro_leaves"],
}


def loot_table(block: str, item: str | None) -> dict:
    table = {"type": "minecraft:block", "pools": []}
    if item is not None:
        table["pools"].append({
            "bonus_rolls": 0.0,
            "conditions": [{"condition": "minecraft:survives_explosion"}],
            "entries": [{"type": "minecraft:item", "name": item}],
            "rolls": 1.0,
        })
    table["random_sequence"] = f"{MODID}:blocks/{block}"
    return table


# ---------------------------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------------------------

def write_json(path: pathlib.Path, data: dict):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def save_png(path: pathlib.Path, frames: list[np.ndarray]):
    path.parent.mkdir(parents=True, exist_ok=True)
    data = np.concatenate(frames, axis=0) if len(frames) > 1 else frames[0]
    Image.fromarray(data, "RGBA").save(path, format="PNG", optimize=True)


def clean(directory: pathlib.Path, patterns: list[str]):
    if not directory.exists():
        return
    for pattern in patterns:
        for f in directory.glob(pattern):
            f.unlink()


def registered_blocks() -> list[str]:
    import re
    src = NULL_BLOCKS_JAVA.read_text(encoding="utf-8")
    names = re.findall(r'register(?:Simple)?Block\("([a-z0-9_]+)"', src)
    names += re.findall(r'glow\("([a-z0-9_]+)"', src)
    return sorted(set(names))


def contact_sheet(path: pathlib.Path, rendered: dict[str, list[np.ndarray]]):
    """Every texture enlarged 6x on a checker background (animated ones show their first frames)."""
    scale = 6
    cell = S * scale
    label_h = 14
    cols = 8
    pad = 6
    entries = [(name, rendered[name][:4]) for name in sorted(rendered)]
    width = cols * (cell + pad) + pad
    # Animated entries take as many cells as frames shown.
    layout = []
    x = pad
    y = pad
    for name, shown in entries:
        w = len(shown) * cell + (len(shown) - 1) * 2
        if x + w + pad > width:
            x = pad
            y += cell + label_h + pad
        layout.append((name, shown, x, y))
        x += w + pad
    height = y + cell + label_h + pad
    sheet = Image.new("RGBA", (width, height), (40, 40, 44, 255))
    checker = Image.new("RGBA", (cell, cell))
    cd = ImageDraw.Draw(checker)
    for cy in range(0, cell, 8):
        for cx in range(0, cell, 8):
            shade = 90 if (cx // 8 + cy // 8) % 2 else 120
            cd.rectangle([cx, cy, cx + 7, cy + 7], fill=(shade, shade, shade, 255))
    draw = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 10)
    except OSError:
        font = ImageFont.load_default()
    for name, shown, x, y in layout:
        for k, frame in enumerate(shown):
            img = Image.fromarray(frame, "RGBA").resize((cell, cell), Image.NEAREST)
            fx = x + k * (cell + 2)
            sheet.alpha_composite(checker, (fx, y))
            sheet.alpha_composite(img, (fx, y))
        draw.text((x, y + cell + 1), name, fill=(230, 230, 230, 255), font=font)
    sheet.convert("RGB").save(path)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--sheet", type=pathlib.Path, help="also write an enlarged contact sheet PNG here")
    args = parser.parse_args()

    clean(TEX_DIR, ["*.png", "*.png.mcmeta"])
    clean(MODELS, ["*.json"])
    clean(BLOCKSTATES, ["*.json"])
    clean(LOOT, ["*.json"])

    rendered: dict[str, list[np.ndarray]] = {}
    for name, entry in TEXTURES.items():
        frames = entry["fn"]()
        for f in frames:
            assert f.shape == (S, S, 4) and f.dtype == np.uint8, name
        rendered[name] = frames
        save_png(TEX_DIR / f"{name}.png", frames)
        if entry["animation"] is not None:
            assert len(frames) > 1, name
            write_json(TEX_DIR / f"{name}.png.mcmeta", animation_meta(entry["animation"], len(frames)))

    build_models()
    for name, data in MODEL_FILES.items():
        write_json(MODELS / f"{name}.json", data)
    for name, data in STATE_FILES.items():
        write_json(BLOCKSTATES / f"{name}.json", data)

    for block, item in RETRO_DROPS.items():
        write_json(LOOT / f"{block}.json", loot_table(block, item))
    for block in RETRO_NO_DROPS:
        write_json(LOOT / f"{block}.json", loot_table(block, None))

    for tool, blocks in MINEABLE_TAGS.items():
        write_json(MINEABLE / f"{tool}.json", {"replace": False, "values": [f"{MODID}:{b}" for b in blocks]})

    # Consistency checks: every registered block has a blockstate, every referenced model and
    # texture exists, and no block is left without art.
    problems = []
    blocks = registered_blocks()
    for block in blocks:
        if block not in STATE_FILES:
            problems.append(f"no blockstate for {block}")
        if block not in TEXTURES:
            problems.append(f"no {block}.png")
    for block in STATE_FILES:
        if block not in blocks:
            problems.append(f"blockstate for unknown block {block}")
    for state in STATE_FILES.values():
        for variant in state["variants"].values():
            for v in (variant if isinstance(variant, list) else [variant]):
                model = v["model"].split(":block/")[1]
                if model not in MODEL_FILES:
                    problems.append(f"missing model {model}")
    for name, model in MODEL_FILES.items():
        for ref in model["textures"].values():
            tex = ref.split(":block/")[1]
            if tex not in TEXTURES:
                problems.append(f"model {name} uses missing texture {tex}")
    tagged = {b for values in MINEABLE_TAGS.values() for b in values}
    for b in tagged:
        if b not in blocks:
            problems.append(f"tag entry for unknown block {b}")
    for b in list(RETRO_DROPS) + RETRO_NO_DROPS:
        if b not in blocks:
            problems.append(f"loot table for unknown block {b}")
    if problems:
        for p in problems:
            print("ERROR:", p, file=sys.stderr)
        return 1

    if args.sheet:
        contact_sheet(args.sheet, rendered)
        print(f"contact sheet -> {args.sheet}")
    print(f"{len(TEXTURES)} textures, {len(MODEL_FILES)} models, {len(STATE_FILES)} blockstates, "
          f"{len(RETRO_DROPS) + len(RETRO_NO_DROPS)} loot tables, {len(MINEABLE_TAGS)} tool tags "
          f"for {len(blocks)} blocks")
    return 0


if __name__ == "__main__":
    sys.exit(main())
