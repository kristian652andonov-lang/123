#!/usr/bin/env python3
"""Generates the NULLROOT entity textures (deterministic, original art).

Writes 128x128 PNGs into src/main/resources/assets/null/textures/entity/nullroot/:

  nullroot_black.png    near-black organic skin with faint purple-grey veins (the usual look)
  nullroot_missing.png  the purple-and-black "missing texture" checkerboard
  nullroot_error.png    a distorted error texture: torn bands of checker, noise and colour fringes
  nullroot_static.png   a second error look: purple-tinted television static with tears
  nullroot_veins.png    emissive overlay (transparent except the veins), drawn additively while it pulses

Every texture tiles seamlessly, because the models map boxes of many sizes onto them.

usage: python3 tools/gen_nullroot.py [--preview <png>]
"""
import argparse
import pathlib

import numpy as np
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "src" / "main" / "resources" / "assets" / "null" / "textures" / "entity" / "nullroot"
SIZE = 128
SEED = 0x4E554C4C


def periodic_noise(rng, size, cells):
    """Smooth value noise that wraps around: a random lattice of cells x cells, cubic-interpolated."""
    lattice = rng.random((cells, cells))
    coords = np.arange(size) * cells / size
    i0 = np.floor(coords).astype(int)
    t = coords - i0
    t = t * t * (3.0 - 2.0 * t)
    i1 = (i0 + 1) % cells
    i0 %= cells
    a = lattice[np.ix_(i0, i0)]
    b = lattice[np.ix_(i0, i1)]
    c = lattice[np.ix_(i1, i0)]
    d = lattice[np.ix_(i1, i1)]
    ty = t[:, None]
    tx = t[None, :]
    return (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty


def fractal(rng, size, base_cells, octaves, falloff=0.5):
    total = np.zeros((size, size))
    amp = 1.0
    norm = 0.0
    cells = base_cells
    for _ in range(octaves):
        total += amp * periodic_noise(rng, size, cells)
        norm += amp
        amp *= falloff
        cells *= 2
    return total / norm


def veins(rng):
    """0..1 strength of a branching vein network (ridges of two noise fields), tileable."""
    n1 = fractal(rng, SIZE, 4, 4)
    n2 = fractal(rng, SIZE, 8, 3)
    r1 = 1.0 - np.abs(n1 - 0.5) * 2.0
    r2 = 1.0 - np.abs(n2 - 0.5) * 2.0
    ridge = np.maximum(r1 ** 14, 0.75 * r2 ** 18)
    return np.clip(ridge, 0.0, 1.0)


def save(name, rgba):
    OUT.mkdir(parents=True, exist_ok=True)
    img = Image.fromarray(np.clip(rgba, 0, 255).astype(np.uint8), "RGBA")
    img.save(OUT / name, optimize=True)
    return img


def black_skin(rng, vein):
    base = fractal(rng, SIZE, 8, 4)
    pores = fractal(rng, SIZE, 32, 2)
    specks = rng.random((SIZE, SIZE))
    v = 5.0 + 7.0 * base
    v -= np.where(pores < 0.25, 3.0, 0.0)
    r = v + 14.0 * vein
    g = v * 0.75 + 9.0 * vein
    b = v * 1.15 + 20.0 * vein
    bright = specks > 0.996
    r = np.where(bright, r + 10, r)
    g = np.where(bright, g + 6, g)
    b = np.where(bright, b + 14, b)
    alpha = np.full((SIZE, SIZE), 255.0)
    return np.stack([r, g, b, alpha], axis=-1)


def missing(rng):
    y, x = np.mgrid[0:SIZE, 0:SIZE]
    check = ((x // 8) + (y // 8)) % 2 == 0
    r = np.where(check, 248.0, 0.0)
    g = np.zeros((SIZE, SIZE))
    b = np.where(check, 248.0, 0.0)
    alpha = np.full((SIZE, SIZE), 255.0)
    return np.stack([r, g, b, alpha], axis=-1)


def error(rng):
    y, x = np.mgrid[0:SIZE, 0:SIZE]
    out = np.zeros((SIZE, SIZE, 4))
    out[..., 3] = 255.0
    noise = rng.random((SIZE, SIZE))
    # Dark violet ground with a fine grain.
    out[..., 0] = 18 + 30 * noise
    out[..., 1] = 4 + 8 * noise
    out[..., 2] = 26 + 40 * noise
    row = 0
    while row < SIZE:
        height = int(rng.integers(2, 12))
        kind = rng.random()
        shift = int(rng.integers(-24, 25))
        rows = slice(row, min(SIZE, row + height))
        xs = (x[rows] + shift) % SIZE
        if kind < 0.35:
            # A torn strip of the missing texture, shifted sideways.
            check = ((xs // 8) + (y[rows] // 8)) % 2 == 0
            out[rows, :, 0] = np.where(check, 236, 10)
            out[rows, :, 1] = np.where(check, 0, 0)
            out[rows, :, 2] = np.where(check, 236, 14)
        elif kind < 0.55:
            # Hard vertical stripes.
            stripe = (xs // int(rng.integers(2, 6))) % 2 == 0
            out[rows, :, 0] = np.where(stripe, 120, 6)
            out[rows, :, 1] = np.where(stripe, 20, 0)
            out[rows, :, 2] = np.where(stripe, 200, 10)
        elif kind < 0.68:
            # A smeared run of bright pixels.
            smear = rng.random(SIZE)
            smear = np.convolve(np.concatenate([smear, smear[:7]]), np.ones(8) / 8, mode="valid")[:SIZE]
            out[rows, :, 0] = 40 + 200 * smear[xs]
            out[rows, :, 1] = 30 + 60 * smear[xs]
            out[rows, :, 2] = 60 + 190 * smear[xs]
        elif kind < 0.8:
            # Nearly black.
            out[rows, :, 0:3] *= 0.25
        row += height
    # Colour fringes: red pushed right, blue pushed left, like a broken signal.
    red = np.roll(out[..., 0], 2, axis=1)
    blue = np.roll(out[..., 2], -2, axis=1)
    out[..., 0] = np.maximum(out[..., 0] * 0.6, red)
    out[..., 2] = np.maximum(out[..., 2] * 0.6, blue)
    return out


def static(rng):
    grain = rng.random((SIZE, SIZE))
    coarse = np.kron(rng.random((SIZE // 2, SIZE // 2)), np.ones((2, 2)))
    v = 0.55 * grain + 0.45 * coarse
    v = np.where(v > 0.5, v, v * 0.35)
    out = np.zeros((SIZE, SIZE, 4))
    out[..., 0] = 30 + 150 * v
    out[..., 1] = 18 + 110 * v
    out[..., 2] = 48 + 190 * v
    out[..., 3] = 255.0
    # A few torn rows shifted sideways.
    for _ in range(9):
        r0 = int(rng.integers(0, SIZE))
        h = int(rng.integers(1, 4))
        shift = int(rng.integers(5, 40))
        for r in range(r0, min(SIZE, r0 + h)):
            out[r] = np.roll(out[r], shift, axis=0)
            out[r, :, 0:3] *= 0.5
    return out


def vein_glow(vein, rng):
    nodes = fractal(rng, SIZE, 16, 2)
    # Only the cores of the veins glow, brighter where they cross the occasional node.
    core = np.clip((vein - 0.42) * 2.4, 0.0, 1.0)
    strength = np.clip(core * (0.75 + np.where(nodes > 0.78, (nodes - 0.78) * 4.0, 0.0)), 0.0, 1.0)
    strength = np.where(strength > 0.08, strength, 0.0)
    out = np.zeros((SIZE, SIZE, 4))
    out[..., 0] = 150 * strength
    out[..., 1] = 40 * strength
    out[..., 2] = 255 * strength
    out[..., 3] = 255 * strength
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview", help="also write an enlarged contact sheet here")
    args = parser.parse_args()
    rng = np.random.default_rng(SEED)
    vein = veins(rng)
    images = [
        save("nullroot_black.png", black_skin(rng, vein)),
        save("nullroot_missing.png", missing(rng)),
        save("nullroot_error.png", error(rng)),
        save("nullroot_static.png", static(rng)),
        save("nullroot_veins.png", vein_glow(vein, rng)),
    ]
    print(f"wrote {len(images)} textures to {OUT.relative_to(ROOT)}")
    if args.preview:
        scale = 3
        sheet = Image.new("RGBA", (len(images) * (SIZE * scale + 8), SIZE * scale * 2 + 8), (40, 40, 40, 255))
        for i, img in enumerate(images):
            big = img.resize((SIZE * scale, SIZE * scale), Image.NEAREST)
            bg = Image.new("RGBA", big.size, (90, 90, 90, 255))
            sheet.paste(Image.alpha_composite(bg, big), (i * (SIZE * scale + 8), 0))
            # Tiled 2x2 at normal scale to show the seams.
            tile = Image.new("RGBA", (SIZE * 2, SIZE * 2))
            for tx in range(2):
                for ty in range(2):
                    tile.paste(img, (tx * SIZE, ty * SIZE))
            tile = Image.alpha_composite(Image.new("RGBA", tile.size, (90, 90, 90, 255)), tile)
            sheet.paste(tile.resize((SIZE * scale, SIZE * scale), Image.NEAREST), (i * (SIZE * scale + 8), SIZE * scale + 8))
        sheet.save(args.preview)
        print(f"preview -> {args.preview}")


if __name__ == "__main__":
    main()
