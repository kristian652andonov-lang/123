#!/usr/bin/env python3
"""Generates the field's textures (Stage 6, HARVEST) into
src/main/resources/assets/null/textures/environment/field/:

  sun.png         the static sun's disc: pale, almost white, faintly warm, with a mottled,
                  sickly surface (slow blotches and fine granulation) and a darkened rim. Transparent
                  outside the disc with a soft edge, so the shards cut from it by the sky renderer
                  (along the disc's own geometry) have clean borders.
  sun_glow.png    a soft radial glow (white, the colour is applied in the renderer), drawn
                  additively around the sun, as the core light when it breaks and as the light
                  leaking through its cracks.
  crack_line.png  a cross-section of a line: opaque in the middle, fading to nothing at both
                  edges (V), constant along it (U). Cracks, the light in them and the rays escaping
                  at the rim are ribbons with this texture, so they stay smooth at any distance.
  countdown.png   the backdrop of the countdown at the top of the screen: a smear of dried,
                  near-black blood, ragged at the edges, with a few drips running down.

Every texture has a .png.mcmeta with blur and clamp (smooth filtering, no wrap at the edges).
Deterministic: fixed seeds, so running it again gives identical files. Original art, nothing taken
from Minecraft.

Usage: python3 tools/gen_field.py [--preview DIR]
"""
from __future__ import annotations

import argparse
import json
import pathlib

import numpy as np
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "src" / "main" / "resources" / "assets" / "null" / "textures" / "environment" / "field"


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def value_noise(rng: np.random.Generator, size: int, cells: int) -> np.ndarray:
    """Smooth value noise in [0, 1] on a size x size grid with `cells` features across (periodic)."""
    grid = rng.random((cells + 1, cells + 1))
    grid[-1, :] = grid[0, :]
    grid[:, -1] = grid[:, 0]
    coords = np.linspace(0.0, cells, size, endpoint=False)
    i = np.floor(coords).astype(int)
    f = coords - i
    f = f * f * (3.0 - 2.0 * f)
    a = grid[np.ix_(i, i)]
    b = grid[np.ix_(i, i + 1)]
    c = grid[np.ix_(i + 1, i)]
    d = grid[np.ix_(i + 1, i + 1)]
    fy = f[:, None]
    fx = f[None, :]
    return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy


def fbm(rng, size, octaves):
    out = np.zeros((size, size))
    total = 0.0
    for cells, weight in octaves:
        out += weight * value_noise(rng, size, cells)
        total += weight
    return out / total


def save(img: np.ndarray, name: str):
    OUT.mkdir(parents=True, exist_ok=True)
    data = np.clip(np.round(img * 255.0), 0, 255).astype(np.uint8)
    Image.fromarray(data, "RGBA").save(OUT / name, optimize=True)
    meta = {"texture": {"blur": True, "clamp": True}}
    (OUT / (name + ".mcmeta")).write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")


def sun(size=256):
    rng = np.random.default_rng(0x5A11)
    y, x = np.mgrid[0:size, 0:size]
    c = (size - 1) / 2.0
    r = np.hypot(x - c, y - c) / (size / 2.0 - 1.5)
    inside = 1.0 - smoothstep(0.985, 1.0, r)
    # Pale, nearly white, faintly warm; the rim darkens and yellows (limb darkening).
    limb = np.sqrt(np.clip(1.0 - r * r, 0.0, 1.0))
    base = np.stack([np.full_like(r, 0.985), np.full_like(r, 0.962), np.full_like(r, 0.905)], axis=-1)
    edge = np.array([0.86, 0.80, 0.66])
    k = (1.0 - limb)[..., None] ** 1.6
    color = base * (1.0 - 0.55 * k) + edge * (0.55 * k)
    # A sick surface: slow blotches and fine granulation, never strong enough to read as a face.
    blotch = fbm(rng, size, [(3, 1.0), (6, 0.6), (11, 0.35)])
    grain = fbm(rng, size, [(28, 1.0), (55, 0.7)])
    shade = 1.0 - 0.07 * smoothstep(0.45, 0.85, blotch) - 0.035 * (grain - 0.5)
    color = color * shade[..., None]
    # A faint grey-green bruise in the blotches, the only colour beyond cream.
    bruise = smoothstep(0.62, 0.9, blotch)[..., None] * np.array([-0.03, -0.01, -0.035])
    color = np.clip(color + bruise, 0.0, 1.0)
    alpha = inside
    return np.dstack([color, alpha])


def glow(size=128):
    y, x = np.mgrid[0:size, 0:size]
    c = (size - 1) / 2.0
    r = np.hypot(x - c, y - c) / (size / 2.0)
    a = np.exp(-(r / 0.30) ** 2) * 0.75 + np.exp(-(r / 0.62) ** 1.6) * 0.32
    a *= 1.0 - smoothstep(0.88, 1.0, r)
    a = np.clip(a / a.max(), 0.0, 1.0)
    rgb = np.ones((size, size, 3))
    return np.dstack([rgb, a])


def crack_line(width=16, height=32):
    v = (np.arange(height) + 0.5) / height
    profile = np.exp(-((v - 0.5) / 0.2) ** 2)
    profile = (profile - profile.min()) / (profile.max() - profile.min())
    a = np.tile(profile[:, None], (1, width))
    rgb = np.ones((height, width, 3))
    return np.dstack([rgb, a])


def countdown(w=512, h=128):
    """A smear of dark, dried blood with ragged edges and a few drips."""
    rng = np.random.default_rng(0xB100D)
    y, x = np.mgrid[0:h, 0:w]
    u = x / (w - 1)
    v = y / (h - 1)
    # The band: thickest in the middle, frayed towards the ends, wavering edges.
    centre = 0.36 + 0.03 * np.sin(u * 9.0 + 1.3) + 0.02 * np.sin(u * 23.0)
    noise = fbm(rng, max(w, h), [(8, 1.0), (19, 0.5), (41, 0.3)])[:h, :w]
    half = (0.17 + 0.05 * (noise - 0.5)) * (1.0 - 0.75 * smoothstep(0.62, 1.0, np.abs(u - 0.5) * 2.0))
    d = np.abs(v - centre) - half
    band = 1.0 - smoothstep(-0.02, 0.025, d + 0.03 * (noise - 0.5))
    # Brush strokes along the band.
    streak = fbm(np.random.default_rng(7), max(w, h), [(3, 1.0), (60, 0.5)])[:h, :w]
    band *= 0.72 + 0.28 * streak
    # Drips running down from the lower edge.
    drips = np.zeros_like(band)
    for _ in range(9):
        cx = rng.uniform(0.18, 0.82)
        length = rng.uniform(0.12, 0.48)
        width = rng.uniform(0.0025, 0.006)
        top = 0.45
        dx = np.abs(u - cx) / width
        along = (v - top) / length
        body = (1.0 - smoothstep(0.6, 1.0, dx)) * (along > 0) * (1.0 - smoothstep(0.75, 1.0, along))
        bead = np.exp(-(((u - cx) / (width * 1.7)) ** 2 + ((v - top - length * 0.92) / (width * 4.5)) ** 2))
        drips = np.maximum(drips, np.maximum(body * (0.85 - 0.3 * along), bead * 0.9))
    alpha = np.clip(np.maximum(band, drips * 0.95), 0.0, 1.0)
    # Near black where it is thick (so the red letters stand out on it), a deep dried red where it
    # thins out at the edges and in the drips.
    thin = (1.0 - alpha) ** 0.7
    rgb = np.stack([0.05 + 0.34 * thin + 0.04 * streak, 0.006 + 0.02 * thin, 0.008 + 0.02 * thin], axis=-1)
    return np.dstack([np.clip(rgb, 0.0, 1.0), alpha])


def preview(directory: pathlib.Path):
    directory.mkdir(parents=True, exist_ok=True)
    sky = np.ones((300, 1000, 3)) * np.array([0.735, 0.722, 0.655])
    canvas = Image.fromarray((sky * 255).astype(np.uint8), "RGB").convert("RGBA")
    for i, name in enumerate(["sun.png", "sun_glow.png"]):
        img = Image.open(OUT / name).resize((256, 256))
        canvas.alpha_composite(img, (20 + i * 280, 20))
    line = Image.open(OUT / "crack_line.png").resize((64, 128), Image.NEAREST)
    canvas.alpha_composite(line, (580, 20))
    dark = Image.new("RGBA", (360, 160), (12, 12, 14, 255))
    cd = Image.open(OUT / "countdown.png").resize((340, 85))
    dark.alpha_composite(cd, (10, 30))
    canvas.alpha_composite(dark, (640, 20))
    canvas.convert("RGB").save(directory / "field_textures.png")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview", type=pathlib.Path)
    args = parser.parse_args()
    save(sun(), "sun.png")
    save(glow(), "sun_glow.png")
    save(crack_line(), "crack_line.png")
    save(countdown(), "countdown.png")
    if args.preview:
        preview(args.preview)
    print(f"field textures -> {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
