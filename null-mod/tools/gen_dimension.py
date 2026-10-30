#!/usr/bin/env python3
"""Generates the sky textures of the NULL dimension (deterministic, original art).

    python3 tools/gen_dimension.py [--preview out.png]

Writes into src/main/resources/assets/null/textures/environment/:
  null_line.png   64x32  soft glowing line profile (v runs across the line); tinted by vertex colour
  null_flare.png  64x64  point of light with a halo and a faint four-way glint
  null_frame.png  128x128 rectangular frame of light with nested inner frames (recursive glow)
Each gets a .mcmeta enabling linear filtering and clamping, so the glows stay smooth when
stretched across the sky. All textures are white with alpha; colour comes from the renderer.
"""
import json
import math
import pathlib
import sys

from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "src" / "main" / "resources" / "assets" / "null" / "textures" / "environment"


def clamp01(v):
    return 0.0 if v < 0.0 else 1.0 if v > 1.0 else v


def write(name, img):
    OUT.mkdir(parents=True, exist_ok=True)
    img.save(OUT / name, optimize=True)
    meta = {"texture": {"blur": True, "clamp": True}}
    (OUT / (name + ".mcmeta")).write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")


def line_texture():
    w, h = 64, 32
    img = Image.new("RGBA", (w, h))
    px = img.load()
    for y in range(h):
        v = (y + 0.5) / h - 0.5
        core = math.exp(-(v / 0.075) ** 2)
        halo = 0.42 * math.exp(-(v / 0.21) ** 2)
        a = clamp01(max(core, halo) * 1.02)
        # the core is pure white; the halo keeps a faint cool edge so additive overlaps read as light
        for x in range(w):
            # a very slight digital texture along the line: every 8th texel is 6% dimmer
            scan = 0.94 if x % 8 == 7 else 1.0
            px[x, y] = (255, 255, 255, int(round(255 * a * scan)))
    return img


def flare_texture():
    n = 64
    img = Image.new("RGBA", (n, n))
    px = img.load()
    for y in range(n):
        for x in range(n):
            dx = (x + 0.5) / n - 0.5
            dy = (y + 0.5) / n - 0.5
            r = math.hypot(dx, dy)
            core = math.exp(-(r / 0.045) ** 2)
            halo = 0.55 * math.exp(-(r / 0.16) ** 2)
            glint = 0.35 * (math.exp(-(dx / 0.012) ** 2) + math.exp(-(dy / 0.012) ** 2)) * math.exp(-(r / 0.3) ** 2)
            edge = clamp01((0.5 - r) / 0.08)
            a = clamp01(max(core, halo) + glint) * edge
            px[x, y] = (255, 255, 255, int(round(255 * a)))
    return img


def frame_texture():
    n = 128
    img = Image.new("RGBA", (n, n))
    px = img.load()
    for y in range(n):
        for x in range(n):
            u = (x + 0.5) / n
            v = (y + 0.5) / n
            # distance to the outer border of the square, 0 at the edge, 0.5 at the centre
            d = min(u, 1.0 - u, v, 1.0 - v)
            a = 0.0
            # outer frame: bright core with glow on both sides
            a = max(a, math.exp(-((d - 0.045) / 0.016) ** 2))
            a = max(a, 0.35 * math.exp(-((d - 0.045) / 0.05) ** 2))
            # nested frames, each fainter: the recursion fades into the middle
            for k, depth in enumerate((0.16, 0.255, 0.33, 0.39)):
                strength = 0.62 * (0.72 ** k)
                a = max(a, strength * math.exp(-((d - depth) / 0.009) ** 2))
            px[x, y] = (255, 255, 255, int(round(255 * clamp01(a))))
    return img


def preview(path):
    """Composite of the three textures over black, tinted, for a quick visual check."""
    line = line_texture().resize((256, 64), Image.BILINEAR)
    flare = flare_texture().resize((128, 128), Image.BILINEAR)
    frame = frame_texture().resize((256, 256), Image.BILINEAR)
    sheet = Image.new("RGB", (660, 300), (0, 0, 0))

    def tint(img, rgb):
        out = Image.new("RGBA", img.size)
        src = img.load()
        dst = out.load()
        for y in range(img.size[1]):
            for x in range(img.size[0]):
                a = src[x, y][3]
                dst[x, y] = (rgb[0], rgb[1], rgb[2], a)
        return out

    sheet.paste(tint(line, (60, 220, 255)), (10, 20), tint(line, (60, 220, 255)))
    sheet.paste(tint(line, (255, 60, 200)), (10, 100), tint(line, (255, 60, 200)))
    sheet.paste(tint(flare, (255, 255, 255)), (60, 160), tint(flare, (255, 255, 255)))
    sheet.paste(tint(frame, (90, 160, 255)), (390, 20), tint(frame, (90, 160, 255)))
    sheet.save(path)


def main():
    write("null_line.png", line_texture())
    write("null_flare.png", flare_texture())
    write("null_frame.png", frame_texture())
    if len(sys.argv) > 2 and sys.argv[1] == "--preview":
        preview(sys.argv[2])
    print("wrote null_line.png, null_flare.png, null_frame.png to", OUT.relative_to(ROOT))


if __name__ == "__main__":
    main()
