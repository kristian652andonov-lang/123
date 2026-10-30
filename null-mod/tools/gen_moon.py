#!/usr/bin/env python3
"""Generates the sky textures of the Shattering Moon (deterministic, original art).

  assets/null/textures/environment/moon_debris.png  4x4 atlas of small rock shards (16x16 each)
  assets/null/textures/environment/moon_glow.png    soft glow with faint rays (flashes, glints)
  assets/null/textures/environment/moon_ring.png    thin shockwave ring (the final disappearance)

The moon itself is never shipped: the client draws its cracks into a copy of whatever moon the
active resource pack provides. These sprites are drawn with additive blending like the vanilla
sun and moon, so black/transparent means "nothing".

Usage: python3 tools/gen_moon.py [--preview out.png]
"""
import math
import pathlib
import sys

import numpy as np
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "src" / "main" / "resources" / "assets" / "null" / "textures" / "environment"
SEED = 0x4E554C4C


def rock_sprite(rng: np.random.Generator, size: int = 16):
    """One jagged moon-rock shard, pixel-art shaded (light from the top left), or None if too thin."""
    img = np.zeros((size, size, 4), np.uint8)
    cx = size / 2 + rng.uniform(-1.0, 1.0)
    cy = size / 2 + rng.uniform(-1.0, 1.0)
    corners = int(rng.integers(5, 9))
    base_r = rng.uniform(4.0, 7.0)
    angles = np.sort(rng.uniform(0, 2 * math.pi, corners))
    radii = base_r * rng.uniform(0.62, 1.0, corners)
    poly = [(cx + r * math.cos(a), cy + r * math.sin(a)) for a, r in zip(angles, radii)]

    def inside(px: float, py: float) -> bool:
        hit = False
        j = len(poly) - 1
        for i in range(len(poly)):
            xi, yi = poly[i]
            xj, yj = poly[j]
            if (yi > py) != (yj > py):
                cross = (xj - xi) * (py - yi) / (yj - yi) + xi
                if px < cross:
                    hit = not hit
            j = i
        return hit

    mask = np.zeros((size, size), bool)
    for y in range(size):
        for x in range(size):
            mask[y, x] = inside(x + 0.5, y + 0.5)
    if mask.sum() < 18:
        return None

    tone = rng.uniform(150, 205)
    crater = (rng.uniform(2, size - 2), rng.uniform(2, size - 2), rng.uniform(1.0, 2.2))
    for y in range(size):
        for x in range(size):
            if not mask[y, x]:
                continue
            # Edge pixels: lit on the top/left, dark on the bottom/right.
            up = y == 0 or not mask[y - 1, x]
            left = x == 0 or not mask[y, x - 1]
            down = y == size - 1 or not mask[y + 1, x]
            right = x == size - 1 or not mask[y, x + 1]
            v = tone + rng.uniform(-9, 9)
            if up or left:
                v += 34
            if down or right:
                v -= 58
            if (x - crater[0]) ** 2 + (y - crater[1]) ** 2 < crater[2] ** 2:
                v -= 30
            v = int(max(40, min(245, v)))
            img[y, x] = (v, v, min(255, v + 8), 255)
    return img


def debris_atlas() -> Image.Image:
    rng = np.random.default_rng(SEED)
    atlas = np.zeros((64, 64, 4), np.uint8)
    for i in range(16):
        sprite = rock_sprite(rng)
        while sprite is None:
            sprite = rock_sprite(rng)
        x, y = (i % 4) * 16, (i // 4) * 16
        atlas[y:y + 16, x:x + 16] = sprite
    return Image.fromarray(atlas, "RGBA")


def glow() -> Image.Image:
    size = 64
    y, x = np.mgrid[0:size, 0:size].astype(np.float64)
    dx = (x + 0.5 - size / 2) / (size / 2)
    dy = (y + 0.5 - size / 2) / (size / 2)
    r = np.sqrt(dx * dx + dy * dy)
    core = np.exp(-(r / 0.16) ** 2)
    halo = np.exp(-(r / 0.45) ** 2) * 0.55
    angle = np.arctan2(dy, dx)
    rays = (np.abs(np.cos(angle * 3.0 + 0.4)) ** 24 + np.abs(np.cos(angle * 2.0 - 0.9)) ** 30 * 0.7)
    rays *= np.exp(-(r / 0.75) ** 2) * 0.45
    value = np.clip(core + halo + rays, 0.0, 1.0)
    value *= np.clip((1.0 - r) / 0.12, 0.0, 1.0)  # reach zero before the sprite border
    img = np.zeros((size, size, 4), np.uint8)
    img[..., 0] = 255
    img[..., 1] = 247
    img[..., 2] = 235
    img[..., 3] = np.round(value * 255).astype(np.uint8)
    return Image.fromarray(img, "RGBA")


def ring() -> Image.Image:
    size = 128
    y, x = np.mgrid[0:size, 0:size].astype(np.float64)
    dx = (x + 0.5 - size / 2) / (size / 2)
    dy = (y + 0.5 - size / 2) / (size / 2)
    r = np.sqrt(dx * dx + dy * dy)
    angle = np.arctan2(dy, dx)
    # A slightly broken ring: brightness varies around it like an uneven shock front.
    variation = 0.7 + 0.3 * np.cos(angle * 5.0 + 1.1) * np.cos(angle * 3.0 - 0.3)
    band = np.exp(-((r - 0.82) / 0.05) ** 2) * variation
    inner = np.exp(-((r - 0.74) / 0.12) ** 2) * 0.25
    value = np.clip(band + inner, 0.0, 1.0) * np.clip((1.0 - r) / 0.05, 0.0, 1.0)
    img = np.zeros((size, size, 4), np.uint8)
    img[..., 0] = 236
    img[..., 1] = 240
    img[..., 2] = 255
    img[..., 3] = np.round(value * 255).astype(np.uint8)
    return Image.fromarray(img, "RGBA")


def preview(images: dict, path: pathlib.Path) -> None:
    """Additive composite of every texture over a night sky, enlarged, for checking by eye."""
    sky = (10, 12, 28)
    tiles = []
    for name, im in images.items():
        scale = 4 if im.width <= 64 else 2
        big = im.resize((im.width * scale, im.height * scale), Image.NEAREST)
        a = np.asarray(big).astype(np.float64)
        rgb = np.array(sky, np.float64) + a[..., :3] * (a[..., 3:4] / 255.0)
        tiles.append(np.clip(rgb, 0, 255).astype(np.uint8))
    h = max(t.shape[0] for t in tiles)
    w = sum(t.shape[1] for t in tiles) + 16 * (len(tiles) - 1)
    canvas = np.zeros((h, w, 3), np.uint8)
    canvas[:] = sky
    x = 0
    for t in tiles:
        canvas[:t.shape[0], x:x + t.shape[1]] = t
        x += t.shape[1] + 16
    Image.fromarray(canvas, "RGB").save(path)


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    images = {
        "moon_debris.png": debris_atlas(),
        "moon_glow.png": glow(),
        "moon_ring.png": ring(),
    }
    for name, im in images.items():
        im.save(OUT / name, optimize=True)
        print(f"wrote {OUT.relative_to(ROOT) / name} ({im.width}x{im.height})")
    if len(sys.argv) > 2 and sys.argv[1] == "--preview":
        preview(images, pathlib.Path(sys.argv[2]))
        print(f"preview -> {sys.argv[2]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
