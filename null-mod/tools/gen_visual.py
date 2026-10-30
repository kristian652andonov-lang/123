#!/usr/bin/env python3
"""Generates the textures of NULL's visual distortion system.

Everything is drawn procedurally and deterministically (fixed seeds), so running the script
twice produces identical files. Output goes to
src/main/resources/assets/null/textures/gui/visual/:

  capture_tendrils.png   6 frames (512x288 each, stacked vertically) of black tendrils
                         creeping in from the screen edges, each frame further grown than the
                         last; the overlay cross-fades between them by capture progress.
  silhouette.png         4 dark, featureless humanoid figures (64x128 each, side by side),
                         subtly wrong in their proportions.
  noise.png              128x128 grey static, used by the overlay when shaders are off.
  vignette.png           256x256 soft black vignette (alpha only), for the shader-less fallback.
  missing_pattern.png    32x32 magenta/black checker, one texel per half block, for the
                         chunk flicker cuboid.

Usage: python3 tools/gen_visual.py [--preview <png>]
"""
import argparse
import math
import pathlib

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "src" / "main" / "resources" / "assets" / "null" / "textures" / "gui" / "visual"

TENDRIL_FRAMES = 6
FRAME_W, FRAME_H = 512, 288
SS = 2  # supersampling factor for anti-aliased edges


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


# --------------------------------------------------------------------------- tendrils

class Tendril:
    """A tapering curved arm that starts outside a screen edge and reaches inward."""

    def __init__(self, rng, base, heading, length, width, rate, delay, depth=0):
        self.points = []
        self.widths = []
        self.rate = rate
        self.delay = delay
        self.children = []
        step = 2.5 * SS
        n = max(4, int(length / step))
        x, y = base
        wiggle_a = rng.uniform(0.8, 2.2)
        wiggle_b = rng.uniform(2.5, 5.5)
        phase_a = rng.uniform(0, math.tau)
        phase_b = rng.uniform(0, math.tau)
        curl = rng.uniform(-0.9, 0.9)
        h = heading
        for i in range(n):
            s = i / (n - 1)
            self.points.append((x, y))
            # Thick at the root, thinning to a needle; a slight swelling a third of the way in
            # makes it read as something alive rather than a cone.
            swell = 1.0 + 0.1 * math.exp(-((s - 0.32) ** 2) / 0.01)
            self.widths.append(max(0.7 * SS, width * (1.0 - s) ** 0.85 * swell))
            turn = (math.sin(s * wiggle_a * math.tau + phase_a) * 0.05
                    + math.sin(s * wiggle_b * math.tau + phase_b) * 0.028
                    + curl * 0.03 * s * s)
            h += turn
            x += math.cos(h) * step
            y += math.sin(h) * step
        if depth == 0:
            for _ in range(rng.integers(0, 3)):
                at = rng.uniform(0.25, 0.65)
                idx = int(at * (n - 1))
                bx, by = self.points[idx]
                side = 1 if rng.random() < 0.5 else -1
                bh = heading + side * rng.uniform(0.45, 0.95)
                child = Tendril(rng, (bx, by), bh, length * rng.uniform(0.28, 0.5),
                                self.widths[idx] * rng.uniform(0.45, 0.65), rate, delay, depth + 1)
                child.start_fraction = at
                self.children.append(child)

    def grown(self, frame_t):
        """Fraction of this tendril visible at frame progress frame_t (0..1)."""
        return float(np.clip((frame_t * self.rate - self.delay) / max(0.05, 1.0 - self.delay), 0.0, 1.0))

    def draw(self, body, sheen, frame_t, parent_grown=1.0):
        g = self.grown(frame_t)
        if hasattr(self, "start_fraction"):
            # A branch only starts once its parent has grown past the branching point.
            g = float(np.clip((parent_grown - self.start_fraction) / max(0.05, 1.0 - self.start_fraction), 0.0, 1.0))
        if g <= 0.0:
            return
        count = max(2, int(len(self.points) * g))
        db = ImageDraw.Draw(body)
        ds = ImageDraw.Draw(sheen)
        for i in range(count):
            x, y = self.points[i]
            r = self.widths[i] * 0.5
            # The visible tip always tapers, even while still growing.
            remaining = (count - i) / max(1, count)
            if remaining < 0.12:
                r *= max(0.15, remaining / 0.12)
            db.ellipse((x - r, y - r, x + r, y + r), fill=255)
            if r > 1.6 * SS:
                # Glossy ridge offset toward one side: gives the black mass some volume.
                hx = x - r * 0.38
                hy = y - r * 0.38
                hr = max(0.6 * SS, r * 0.16)
                ds.ellipse((hx - hr, hy - hr, hx + hr, hy + hr), fill=70)
        for child in self.children:
            child.draw(body, sheen, frame_t, g)


def edge_points(rng, w, h, per_long, per_short):
    """Roots along all four edges; the short edges get nearly as many as the long ones."""
    pts = []
    margin = 18 * SS
    for i in range(per_long):
        u = (i + rng.uniform(0.1, 0.9)) / per_long
        pts.append((u * w, -margin, math.pi / 2))
        u = (i + rng.uniform(0.1, 0.9)) / per_long
        pts.append((u * w, h + margin, -math.pi / 2))
    for i in range(per_short):
        u = (i + rng.uniform(0.1, 0.9)) / per_short
        pts.append((-margin, u * h, 0.0))
        u = (i + rng.uniform(0.1, 0.9)) / per_short
        pts.append((w + margin, u * h, math.pi))
    return pts


def build_tendrils():
    rng = np.random.default_rng(0x4E554C4C)
    w, h = FRAME_W * SS, FRAME_H * SS
    cx, cy = w / 2, h / 2
    tendrils = []
    for (x, y, inward) in edge_points(rng, w, h, 11, 9):
        to_center = math.atan2(cy - y, cx - x)
        # Blend between "straight in from the edge" and "toward the centre".
        heading = 0.55 * inward + 0.45 * to_center
        if abs(inward - to_center) > math.pi:
            heading = to_center
        heading += rng.uniform(-0.35, 0.35)
        dist = math.hypot(cx - x, cy - y)
        # Push the root back along its heading so only the tapering part enters the frame.
        back = rng.uniform(10, 34) * SS
        x -= math.cos(heading) * back
        y -= math.sin(heading) * back
        thick = rng.random() < 0.35
        length = dist * (rng.uniform(0.3, 0.55) if thick else rng.uniform(0.45, 0.85))
        width = (rng.uniform(30, 52) if thick else rng.uniform(10, 26)) * SS
        rate = rng.uniform(1.0, 1.7)
        delay = rng.uniform(0.0, 0.4)
        tendrils.append(Tendril(rng, (x, y), heading, length, width, rate, delay))
    return tendrils


def tendril_frame(tendrils, k):
    w, h = FRAME_W * SS, FRAME_H * SS
    frame_t = (k + 1) / TENDRIL_FRAMES
    body = Image.new("L", (w, h), 0)
    sheen = Image.new("L", (w, h), 0)
    for t in tendrils:
        t.draw(body, sheen, frame_t)
    body = body.filter(ImageFilter.GaussianBlur(0.8 * SS))
    sheen = sheen.filter(ImageFilter.GaussianBlur(2.2 * SS))
    a = np.asarray(body, dtype=np.float32) / 255.0
    s = np.asarray(sheen, dtype=np.float32) / 255.0

    # Darkness pooling at the edges, deeper in later frames.
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    nx = (xx - w / 2) / (w / 2)
    ny = (yy - h / 2) / (h / 2)
    # Superellipse distance: rounded corners, darkness pooling deepest in the corners.
    r = (np.abs(nx) ** 3.2 + np.abs(ny) ** 3.2) ** (1.0 / 3.2)
    ang = np.arctan2(ny, nx)
    wobble = (0.035 * np.sin(ang * 5.0 + 1.3) + 0.025 * np.sin(ang * 11.0 + 4.1)
              + 0.015 * np.sin(ang * 23.0 + 0.7))
    inner = 1.02 - 0.2 * frame_t + wobble
    vignette = smoothstep(inner, inner + 0.22, r) * (0.5 + 0.45 * frame_t)
    alpha = np.clip(np.maximum(a, vignette), 0.0, 1.0)

    # Near-black with a cold violet sheen on the ridges.
    base = np.array([5.0, 2.0, 8.0], dtype=np.float32)
    glint = np.array([46.0, 16.0, 58.0], dtype=np.float32)
    sheen_amount = np.clip(s * a * 1.4, 0.0, 1.0)[..., None]
    rgb = base[None, None, :] * (1.0 - sheen_amount) + glint[None, None, :] * sheen_amount
    img = np.dstack([rgb, alpha[..., None] * 255.0]).clip(0, 255).astype(np.uint8)
    return Image.fromarray(img, "RGBA").resize((FRAME_W, FRAME_H), Image.LANCZOS)


def make_tendrils():
    tendrils = build_tendrils()
    sheet = Image.new("RGBA", (FRAME_W, FRAME_H * TENDRIL_FRAMES), (0, 0, 0, 0))
    for k in range(TENDRIL_FRAMES):
        sheet.paste(tendril_frame(tendrils, k), (0, k * FRAME_H))
    return sheet


# --------------------------------------------------------------------------- silhouettes

SIL_W, SIL_H = 64, 128
SIL_SS = 4


def draw_figure(d, s, variant):
    """Draws one figure in a 64x128 unit box scaled by s. Feet touch the bottom edge."""
    def P(x, y):
        return (x * s, y * s)

    def poly(points):
        d.polygon([P(x, y) for x, y in points], fill=255)

    def ellipse(x0, y0, x1, y1):
        d.ellipse((x0 * s, y0 * s, x1 * s, y1 * s), fill=255)

    if variant == 0:
        # Upright, sloping shoulders, arms hanging a little too low.
        ellipse(27.0, 5.0, 37.0, 18.5)
        poly([(30.0, 16), (34.0, 16), (34.6, 26), (29.4, 26)])
        poly([(29, 24), (35, 24), (41, 25.5), (44, 29), (42, 46), (41, 62), (23, 62), (22, 46),
              (20, 29), (23, 25.5)])
        poly([(20.2, 28), (24.2, 29), (23.4, 52), (22.4, 81), (19.2, 82), (18.6, 52)])
        poly([(39.8, 29), (43.8, 28), (45.4, 52), (44.8, 82), (41.6, 81), (40.6, 52)])
        poly([(23.0, 60), (31.4, 60), (30.4, 127.9), (25.4, 127.9), (24.0, 95)])
        poly([(32.6, 60), (41.0, 60), (40.0, 95), (38.6, 127.9), (33.6, 127.9)])
    elif variant == 1:
        # Too tall and too thin, long neck, arms down past the knees.
        ellipse(28.4, 0.5, 35.6, 12.5)
        poly([(30.6, 11), (33.4, 11), (34.0, 22), (30.0, 22)])
        poly([(30, 20.5), (34, 20.5), (38.5, 22), (41, 25), (39.5, 44), (38.5, 62), (25.5, 62),
              (24.5, 44), (23, 25), (25.5, 22)])
        poly([(23.2, 24), (26.0, 25), (25.2, 60), (24.4, 97), (21.8, 97.5), (21.0, 60)])
        poly([(38.0, 25), (40.8, 24), (43.0, 60), (42.2, 97.5), (39.6, 97), (38.8, 60)])
        poly([(25.6, 60), (31.4, 60), (30.8, 127.9), (27.0, 127.9), (26.2, 96)])
        poly([(32.6, 60), (38.4, 60), (37.8, 96), (37.0, 127.9), (33.2, 127.9)])
    elif variant == 2:
        # Head tilted hard to one side on a bent neck, one shoulder dropped.
        ellipse(30.0, 8.0, 40.0, 21.5)
        poly([(31.5, 18.5), (35.5, 19.5), (34.6, 28), (29.8, 28)])
        poly([(29, 26.5), (35, 25.5), (41, 26), (44, 29.5), (42, 47), (41, 63), (23, 63), (22, 47),
              (19.5, 33), (22.5, 29)])
        poly([(19.6, 32), (23.6, 32.5), (22.8, 55), (21.8, 84), (18.6, 85), (18.0, 55)])
        poly([(39.8, 29.5), (43.8, 28.5), (45.4, 51), (44.8, 79), (41.6, 78), (40.6, 51)])
        poly([(23.0, 61), (31.4, 61), (30.4, 127.9), (25.4, 127.9), (24.0, 96)])
        poly([(32.6, 61), (41.0, 61), (40.0, 96), (38.6, 127.9), (33.6, 127.9)])
    else:
        # Broad, arms held slightly away from the body, legs apart.
        ellipse(26.0, 5.5, 38.0, 19.5)
        poly([(29.5, 17), (34.5, 17), (35.2, 27), (28.8, 27)])
        poly([(28, 25), (36, 25), (43, 26.5), (47, 30.5), (44, 47), (42.5, 63), (21.5, 63),
              (20, 47), (17, 30.5), (21, 26.5)])
        poly([(17.2, 29), (21.6, 30.5), (18.2, 54), (14.6, 78), (11.2, 77), (13.0, 52)])
        poly([(42.4, 30.5), (46.8, 29), (51.0, 52), (52.8, 77), (49.4, 78), (45.8, 54)])
        poly([(21.8, 61), (31.0, 61), (28.6, 95), (26.8, 127.9), (21.6, 127.9), (22.4, 95)])
        poly([(33.0, 61), (42.2, 61), (41.6, 95), (42.4, 127.9), (37.2, 127.9), (35.4, 95)])


def make_silhouettes():
    sheet = Image.new("RGBA", (SIL_W * 4, SIL_H), (0, 0, 0, 0))
    for v in range(4):
        big = Image.new("L", (SIL_W * SIL_SS, SIL_H * SIL_SS), 0)
        draw_figure(ImageDraw.Draw(big), SIL_SS, v)
        big = big.filter(ImageFilter.GaussianBlur(0.6 * SIL_SS))
        mask = big.resize((SIL_W, SIL_H), Image.LANCZOS)
        a = np.asarray(mask, dtype=np.float32)
        # Featureless: almost black, with a hint of cold blue so it is not a hole in the image.
        rgb = np.zeros((SIL_H, SIL_W, 3), dtype=np.float32)
        rgb[..., 0] = 3.0
        rgb[..., 1] = 3.0
        rgb[..., 2] = 6.0
        img = np.dstack([rgb, a]).clip(0, 255).astype(np.uint8)
        sheet.paste(Image.fromarray(img, "RGBA"), (v * SIL_W, 0))
    return sheet


# --------------------------------------------------------------------------- small textures

def make_noise():
    rng = np.random.default_rng(0x5717C)
    n = rng.random((128, 128)).astype(np.float32)
    # A few horizontal dropout lines so it reads as signal noise, not sand.
    for row in rng.choice(128, 9, replace=False):
        n[row, :] = n[row, :] * 0.25 + 0.75 * rng.random()
    v = (n * 255.0).astype(np.uint8)
    return Image.fromarray(np.dstack([v, v, v, np.full_like(v, 255)]), "RGBA")


def make_vignette():
    size = 256
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    c = (size - 1) / 2.0
    r = np.hypot((xx - c) / c, (yy - c) / c) / math.sqrt(2.0)
    a = smoothstep(0.32, 1.0, r) ** 1.3
    img = np.zeros((size, size, 4), dtype=np.uint8)
    img[..., 3] = (a * 255.0).astype(np.uint8)
    return Image.fromarray(img, "RGBA")


def make_missing():
    size = 32
    yy, xx = np.mgrid[0:size, 0:size]
    checker = (xx + yy) % 2 == 0
    img = np.zeros((size, size, 4), dtype=np.uint8)
    img[checker] = (248, 0, 248, 255)
    img[~checker] = (0, 0, 0, 255)
    return Image.fromarray(img, "RGBA")


# --------------------------------------------------------------------------- preview

def preview(path, tendrils, silhouettes, noise, vignette, missing):
    cols = 3
    rows = 2
    tile_w, tile_h = FRAME_W, FRAME_H
    canvas = Image.new("RGB", (tile_w * cols, tile_h * rows + 300), (40, 40, 44))
    # Tendril frames over a fake daytime scene so the alpha reads correctly.
    scene = Image.new("RGB", (tile_w, tile_h))
    sd = ImageDraw.Draw(scene)
    for y in range(tile_h):
        t = y / tile_h
        sd.line([(0, y), (tile_w, y)], fill=(int(120 + 60 * t), int(170 + 30 * t), int(230 - 60 * t)) if t < 0.55
                else (int(70 + 30 * t), int(120 - 20 * t), int(60)))
    for k in range(TENDRIL_FRAMES):
        frame = tendrils.crop((0, k * FRAME_H, FRAME_W, (k + 1) * FRAME_H))
        tile = scene.copy()
        tile.paste(frame, (0, 0), frame)
        canvas.paste(tile, ((k % cols) * tile_w, (k // cols) * tile_h))
    y0 = tile_h * rows + 10
    sky = Image.new("RGB", (silhouettes.width * 2, silhouettes.height * 2), (150, 170, 190))
    big = silhouettes.resize((silhouettes.width * 2, silhouettes.height * 2), Image.NEAREST)
    sky.paste(big, (0, 0), big)
    canvas.paste(sky, (10, y0))
    canvas.paste(noise.convert("RGB"), (560, y0))
    vg = Image.new("RGB", (256, 256), (200, 200, 200))
    vg.paste(vignette, (0, 0), vignette)
    canvas.paste(vg, (700, y0))
    canvas.paste(missing.resize((128, 128), Image.NEAREST).convert("RGB"), (970, y0))
    canvas.save(path)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview", type=pathlib.Path, default=None)
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)

    tendrils = make_tendrils()
    silhouettes = make_silhouettes()
    noise = make_noise()
    vignette = make_vignette()
    missing = make_missing()

    tendrils.save(OUT / "capture_tendrils.png", optimize=True)
    silhouettes.save(OUT / "silhouette.png", optimize=True)
    noise.save(OUT / "noise.png", optimize=True)
    vignette.save(OUT / "vignette.png", optimize=True)
    missing.save(OUT / "missing_pattern.png", optimize=True)
    # Smooth scaling and no edge bleeding for the full-screen overlays.
    blur_clamp = '{\n  "texture": {\n    "blur": true,\n    "clamp": true\n  }\n}\n'
    (OUT / "capture_tendrils.png.mcmeta").write_text(blur_clamp, encoding="utf-8")
    (OUT / "vignette.png.mcmeta").write_text(blur_clamp, encoding="utf-8")

    for name in ("capture_tendrils.png", "silhouette.png", "noise.png", "vignette.png", "missing_pattern.png"):
        p = OUT / name
        print(f"{p.relative_to(ROOT)}  {Image.open(p).size}  {p.stat().st_size} bytes")
    if args.preview:
        preview(args.preview, tendrils, silhouettes, noise, vignette, missing)
        print(f"preview -> {args.preview}")


if __name__ == "__main__":
    main()
