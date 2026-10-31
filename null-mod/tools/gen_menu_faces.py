#!/usr/bin/env python3
"""Turns the four faces the user supplied for the main menu into menu textures (deterministic).

Sources (kept untouched):  assets-src/menu-faces/face_1.png .. face_4.png
Outputs (under src/main/resources/assets/null/textures/gui/menu/):
  faces.png    RGBA atlas: every face at full size (max 512 px on the long side) and at half size,
               the movable eye patches of faces whose pupils follow the cursor, and a soft glint
  faces.json   sprite regions, eye anchors, how far each eye may look around, patch regions

Processing per face:
  1. composite on black, convert to grayscale (luminance) - the menu is dark and grayscale
  2. crop to the face
  3. normalise exposure: the face's own background goes to black, its brightest features (eyes,
     teeth) to near white, the mid-tones are pushed down so the face looms out of the dark
  4. darken towards the edge of the face and fade the alpha softly to zero inside the sprite, so no
     rectangle or hard cut-out border can ever show
  5. resize (never upscale), add a light film grain matching the menu's own
  6. eyes: faces with pale eyes (1, 3) get their pupils cut out into separate soft-edged "patch"
     sprites and the hole in the face is filled (harmonic inpainting); the menu draws the patch on
     top, shifted a little towards the mouse cursor. Faces with dark eyes (2, 4) get a moving
     glint instead.

Usage: python3 tools/gen_menu_faces.py [--preview DIR]
  --preview DIR  also writes contact sheets and mock-ups for every stage, composed the way the menu
                 code draws them: the main menu (854x480 at GUI scale 2, 1920x1080 at GUI scale 4,
                 still composition, close-ups), the Select World screen on NULL's backdrop and a
                 pause menu inside a world.
"""
import argparse
import json
import pathlib

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCE_DIR = ROOT / "assets-src" / "menu-faces"
ASSETS = ROOT / "src" / "main" / "resources" / "assets" / "null"
MENU_DIR = ASSETS / "textures" / "gui" / "menu"

MAX_SIDE = 512
PAD = 8
ATLAS_WIDTH = 1536
SEED = 0x4E554C4C

# Per face, in the source image's pixel coordinates:
#   crop                 the face (with room for the alpha fall-off)
#   black/white/gamma    exposure: lum -> clip((lum - black) / (white - black)) ** gamma
#   ellipse              (cx, cy, rx, ry) of the face inside the crop, as fractions of the crop size;
#                        alpha fades out between `fade` and 1.0 of it, the colour darkens from `dark`
#   eyes                 (x, y) of each pupil
#   mode                 "patch": the pupil (radius `pupil`) moves inside the eye by up to `travel`
#                        "glint": a highlight moves by up to `travel`; `glint` is its diameter
#   grain                film grain strength (face_2 is already grainy VHS footage)
FACES = [
    {
        "name": "face_1", "crop": (56, 140, 696, 1224),
        "black": 0.012, "white": 0.335, "gamma": 1.18,
        "ellipse": (0.50, 0.50, 0.50, 0.49), "fade": 0.80, "dark": 0.52,
        "eyes": [(189.0, 508.0), (548.0, 571.0)], "mode": "patch", "pupil": 14.0, "travel": 15.0,
        "glint": 9.0, "grain": 0.016,
    },
    {
        "name": "face_2", "crop": (40, 0, 344, 448),
        "black": 0.030, "white": 0.390, "gamma": 1.32,
        "ellipse": (0.50, 0.50, 0.50, 0.52), "fade": 0.72, "dark": 0.50,
        "eyes": [(122.0, 157.0), (263.0, 160.0)], "mode": "glint", "pupil": 0.0, "travel": 7.0,
        "glint": 11.0, "grain": 0.0,
    },
    {
        "name": "face_3", "crop": (58, 36, 462, 540),
        "black": 0.138, "white": 0.610, "gamma": 1.22,
        "ellipse": (0.50, 0.53, 0.50, 0.50), "fade": 0.74, "dark": 0.48,
        "eyes": [(140.0, 222.0), (345.0, 200.0)], "mode": "patch", "pupil": 33.0, "travel": 7.0,
        "glint": 12.0, "grain": 0.014,
    },
    {
        "name": "face_4", "crop": (48, 26, 372, 410),
        "black": 0.022, "white": 0.550, "gamma": 1.20,
        "ellipse": (0.50, 0.50, 0.50, 0.50), "fade": 0.72, "dark": 0.48,
        "eyes": [(156.0, 136.0), (266.0, 136.0)], "mode": "glint", "pupil": 0.0, "travel": 5.0,
        "glint": 8.0, "grain": 0.014,
    },
]


# --------------------------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------------------------

def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def blur(a, sigma):
    """Separable gaussian blur with edge padding."""
    if sigma <= 0:
        return a.copy()
    r = int(max(1, round(sigma * 3)))
    x = np.arange(-r, r + 1, dtype=np.float32)
    k = np.exp(-(x * x) / (2 * sigma * sigma))
    k /= k.sum()
    p = np.pad(a, ((r, r), (0, 0)), mode="edge")
    out = np.zeros_like(a)
    for i, w in enumerate(k):
        out += w * p[i:i + a.shape[0], :]
    p = np.pad(out, ((0, 0), (r, r)), mode="edge")
    out2 = np.zeros_like(a)
    for i, w in enumerate(k):
        out2 += w * p[:, i:i + a.shape[1]]
    return out2


def border_window(h, w, margin):
    """1 inside, falling to exactly 0 at the sprite's edge within `margin` pixels."""
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    dist = np.minimum(np.minimum(xs, w - 1 - xs), np.minimum(ys, h - 1 - ys))
    return smoothstep(0.0, margin, dist)


def resize(a, size):
    """High quality resize of a float image (Lanczos on 32-bit float data)."""
    img = Image.fromarray(a.astype(np.float32), mode="F")
    return np.asarray(img.resize(size, Image.LANCZOS)).astype(np.float32)


def luminance(path):
    img = Image.open(path).convert("RGBA")
    a = np.asarray(img).astype(np.float32) / 255.0
    rgb = a[..., :3] * a[..., 3:4]  # composite on black
    return 0.2126 * rgb[..., 0] + 0.7152 * rgb[..., 1] + 0.0722 * rgb[..., 2]


def inpaint(lum, mask, iterations=400):
    """Fills mask > 0.5 smoothly from its surroundings (iterated diffusion = harmonic fill)."""
    hole = mask > 0.5
    if not hole.any():
        return lum
    ys, xs = np.nonzero(hole)
    y0, y1 = max(0, ys.min() - 6), min(lum.shape[0], ys.max() + 7)
    x0, x1 = max(0, xs.min() - 6), min(lum.shape[1], xs.max() + 7)
    sub = lum[y0:y1, x0:x1].copy()
    h = hole[y0:y1, x0:x1]
    known = sub.copy()
    # start from the mean of the ring around the hole
    ring = (blur(h.astype(np.float32), 2.0) > 0.02) & ~h
    sub[h] = known[ring].mean() if ring.any() else known.mean()
    for _ in range(iterations):
        p = np.pad(sub, 1, mode="edge")
        avg = (p[:-2, 1:-1] + p[2:, 1:-1] + p[1:-1, :-2] + p[1:-1, 2:]) * 0.25
        sub = np.where(h, avg, known)
    out = lum.copy()
    out[y0:y1, x0:x1] = sub
    return out


def disc(h, w, cx, cy, r0, r1):
    """Soft disc: 1 inside r0, 0 outside r1."""
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    d = np.hypot(xs + 0.5 - cx, ys + 0.5 - cy)
    return 1.0 - smoothstep(r0, r1, d)


def to_rgba(lum, alpha):
    lum = np.clip(lum, 0, 1)
    alpha = np.clip(alpha, 0, 1)
    out = np.zeros(lum.shape + (4,), np.uint8)
    v = (lum * 255 + 0.5).astype(np.uint8)
    out[..., 0] = v
    out[..., 1] = v
    out[..., 2] = v
    out[..., 3] = (alpha * 255 + 0.5).astype(np.uint8)
    out[out[..., 3] == 0, :3] = 0
    return Image.fromarray(out, "RGBA")


# --------------------------------------------------------------------------------------------
# processing
# --------------------------------------------------------------------------------------------

def process(spec, rng):
    src = luminance(SOURCE_DIR / (spec["name"] + ".png"))
    x0, y0, x1, y1 = spec["crop"]
    lum = src[y0:y1, x0:x1].copy()
    ch, cw = lum.shape

    # exposure
    v = np.clip((lum - spec["black"]) / (spec["white"] - spec["black"]), 0.0, 1.0) ** spec["gamma"]

    # darkness towards the edges of the face, soft alpha inside the sprite
    ys, xs = np.mgrid[0:ch, 0:cw].astype(np.float32)
    ex, ey, rx, ry = spec["ellipse"]
    d = np.sqrt((((xs + 0.5) / cw - ex) / rx) ** 2 + (((ys + 0.5) / ch - ey) / ry) ** 2)
    v *= 1.0 - 0.72 * smoothstep(spec["dark"], 1.0, d)
    alpha = 1.0 - smoothstep(spec["fade"], 1.0, d)

    # final size (never upscale)
    scale = min(1.0, MAX_SIDE / max(cw, ch))
    w, h = int(round(cw * scale)), int(round(ch * scale))
    if scale < 1.0:
        v = np.clip(resize(v, (w, h)), 0.0, 1.0)
        alpha = np.clip(resize(alpha, (w, h)), 0.0, 1.0)
    alpha *= border_window(h, w, 6.0)
    eyes = [((ex_ - x0) * scale, (ey_ - y0) * scale) for ex_, ey_ in spec["eyes"]]

    # film grain (slightly soft so it does not shimmer when the face is drawn smaller)
    if spec["grain"] > 0.0:
        g = blur(rng.standard_normal((h, w)).astype(np.float32), 0.65)
        g /= g.std() + 1e-6
        v = np.clip(v + g * spec["grain"] * (0.35 + 0.65 * np.sqrt(v)), 0.0, 1.0)

    # eyes
    pupil = spec["pupil"] * scale
    travel = spec["travel"] * scale
    glint = spec["glint"] * scale
    patches = []
    if spec["mode"] == "patch":
        for (px, py) in eyes:
            r_core = pupil * 1.06
            r_edge = pupil + max(3.0, pupil * 0.28)
            half = int(np.ceil(r_edge + 2))
            size = half * 2
            # the patch: the processed face around the pupil, alpha soft at its rim
            cx, cy = int(round(px)), int(round(py))
            sub = np.zeros((size, size), np.float32)
            sy0, sx0 = cy - half, cx - half
            yy0, yy1 = max(0, sy0), min(h, sy0 + size)
            xx0, xx1 = max(0, sx0), min(w, sx0 + size)
            sub[yy0 - sy0:yy1 - sy0, xx0 - sx0:xx1 - sx0] = v[yy0:yy1, xx0:xx1]
            pa = disc(size, size, px - sx0, py - sy0, r_core, r_edge)
            pa *= border_window(size, size, 1.5)
            patches.append((sub, pa, (px - sx0, py - sy0)))
            # remove the pupil from the face itself
            hole = disc(h, w, px, py, r_core + 0.5, r_core + 1.5)
            v = inpaint(v, hole)
    return {
        "name": spec["name"], "lum": v.astype(np.float32), "alpha": alpha.astype(np.float32),
        "eyes": eyes, "mode": spec["mode"], "travel": travel, "glint": glint, "pupil": pupil,
        "patches": patches,
    }


def half_size(lum, alpha):
    h, w = lum.shape
    size = (max(1, (w + 1) // 2), max(1, (h + 1) // 2))
    # premultiply for the downscale so dark transparent pixels do not bleed into the edge
    pre = resize(lum * alpha, size)
    a = np.clip(resize(alpha, size), 0.0, 1.0)
    l = np.where(a > 1e-3, np.clip(pre / np.maximum(a, 1e-3), 0.0, 1.0), 0.0)
    return l.astype(np.float32), a.astype(np.float32)


def make_glint(size=32):
    ys, xs = np.mgrid[0:size, 0:size].astype(np.float32)
    r = np.hypot(xs + 0.5 - size / 2, ys + 0.5 - size / 2)
    a = 0.62 * np.exp(-(r * r) / (2 * 2.3 ** 2)) + 0.38 * np.exp(-(r * r) / (2 * 6.5 ** 2))
    a *= border_window(size, size, 3.0)
    return np.ones_like(a), np.clip(a / a.max(), 0, 1)


def pack(items, width):
    """Shelf packer: items = [(key, w, h)] -> {key: (x, y)}, height."""
    order = sorted(items, key=lambda it: (-it[2], -it[1]))
    pos = {}
    x = y = shelf = 0
    for key, w, h in order:
        if x + w + PAD > width:
            y += shelf + PAD
            x = shelf = 0
        pos[key] = (x, y)
        x += w + PAD
        shelf = max(shelf, h)
    total = y + shelf
    return pos, ((total + 15) // 16) * 16


def build():
    rng = np.random.default_rng(SEED)
    faces = [process(spec, rng) for spec in FACES]
    sprites = {}
    for i, f in enumerate(faces):
        sprites[("face", i)] = (f["lum"], f["alpha"])
        sprites[("half", i)] = half_size(f["lum"], f["alpha"])
        for k, (pl, pa, _) in enumerate(f["patches"]):
            sprites[("patch", i, k)] = (pl, pa)
            sprites[("half_patch", i, k)] = half_size(pl, pa)
    sprites[("glint",)] = make_glint()
    pos, height = pack([(key, v[0].shape[1], v[0].shape[0]) for key, v in sprites.items()], ATLAS_WIDTH)
    lum = np.zeros((height, ATLAS_WIDTH), np.float32)
    alpha = np.zeros_like(lum)
    for key, (l, a) in sprites.items():
        x, y = pos[key]
        h, w = l.shape
        lum[y:y + h, x:x + w] = l
        alpha[y:y + h, x:x + w] = a

    def region(key):
        x, y = pos[key]
        h, w = sprites[key][0].shape
        return {"x": x, "y": y, "w": w, "h": h}

    meta = {
        "version": 1,
        "texture": "null:textures/gui/menu/faces.png",
        "width": ATLAS_WIDTH,
        "height": height,
        "glint": region(("glint",)),
        "faces": [],
    }
    for i, f in enumerate(faces):
        entry = {"name": f["name"], **region(("face", i)), "half": region(("half", i)), "eyes": []}
        for k, (ex, ey) in enumerate(f["eyes"]):
            eye = {"x": round(ex, 2), "y": round(ey, 2), "travel": round(f["travel"], 2), "glint": round(f["glint"], 2)}
            if f["mode"] == "patch":
                pl, pa, (cx, cy) = f["patches"][k]
                patch = region(("patch", i, k))
                patch["cx"] = round(cx, 2)
                patch["cy"] = round(cy, 2)
                eye["patch"] = patch
                eye["half_patch"] = region(("half_patch", i, k))
            entry["eyes"].append(eye)
        meta["faces"].append(entry)
    return lum, alpha, meta, faces


# --------------------------------------------------------------------------------------------
# preview: contact sheets and mock-ups composed like the menu code draws them
# --------------------------------------------------------------------------------------------

# Mirrors of the Java tables (MenuMood, index = stage - 1); keep them in step with the code.
FACE_ALPHA = [0.15, 0.22, 0.30, 0.38, 0.46, 0.54]
DOMINANT_ALPHA = [0.0, 0.0, 0.0, 0.0, 0.22, 0.30]
DOMINANT_SIZE = [0.0, 0.0, 0.0, 0.0, 1.30, 1.42]
CLOSE_UP_ALPHA = [0.0, 0.0, 0.40, 0.48, 0.56, 0.64]
FOLLOW = [0.0, 0.45, 0.65, 0.80, 1.0, 1.0]
GLINT = [0.55, 0.65, 0.75, 0.85, 0.95, 1.0]
SCRIM = [0.50, 0.52, 0.55, 0.60, 0.66, 0.70]
BACKDROP_FACES = [1, 1, 1, 2, 2, 2]
BACKDROP_EYES = [0, 1, 2, 3, 4, 4]
EYES = [1, 3, 5, 7, 8, 8]
MOUTHS = [0, 1, 2, 3, 4, 4]
SILHOUETTES = [0, 0, 1, 3, 4, 4]
EYE_ALPHA = [0.45, 0.60, 0.72, 0.85, 0.95, 1.0]
MOUTH_ALPHA = [0.0, 0.16, 0.28, 0.36, 0.45, 0.50]
SILHOUETTE_ALPHA = [0.30, 0.38, 0.50, 0.65, 0.80, 0.88]
GRAIN = [0.045, 0.050, 0.060, 0.070, 0.085, 0.100]
FOG = [0.060, 0.065, 0.070, 0.075, 0.060, 0.055]
BASE = [0x07, 0x06, 0x06, 0x05, 0x03, 0x02]
VIGNETTE = [0.80, 0.84, 0.88, 0.93, 1.0, 1.0]
# the default settings (BACKGROUND_ANIMATION_INTENSITY, MENU_GLITCH_INTENSITY)
MOTION = 0.8
GLITCH = 0.7
# MainMenuAnimationManager: the still composition (portrait, x, y, size, alpha factor)
STILL_FACES = [
    [(2, 0.76, 0.50, 0.50, 1.0)],
    [(0, 0.66, 0.46, 0.56, 1.0), (1, 0.90, 0.62, 0.50, 0.85)],
    [(2, 0.62, 0.42, 0.62, 1.0), (1, 0.88, 0.60, 0.58, 0.9)],
    [(0, 0.60, 0.50, 0.74, 1.0), (2, 0.86, 0.36, 0.64, 0.95), (1, 0.94, 0.80, 0.40, 0.7)],
    [(2, 0.66, 0.38, 0.80, 1.0), (1, 0.90, 0.64, 0.72, 0.95), (0, 0.48, 0.84, 0.46, 0.7)],
    [(3, 0.70, 0.40, 0.92, 1.0), (2, 0.92, 0.66, 0.80, 0.95), (1, 0.50, 0.80, 0.52, 0.7)],
]
STILL_DOMINANT = [(3, 0.52, 0.52), (0, 0.56, 0.52)]
STILL_BACKDROP = [(2, 0.93, 0.52, 0.60, 1.0), (0, 0.06, 0.58, 0.56, 0.9)]
STILL_EYES = [(0.86, 0.18, 0.035), (0.56, 0.56, 0.03), (0.95, 0.48, 0.028)]
STILL_BACKDROP_EYES = [(0.95, 0.16, 0.03), (0.05, 0.86, 0.028)]
BACKDROP_ALPHA = 0.62
PATCH_K = 3.5          # MainMenuBackgroundRenderer.PATCH_OPACITY
HALF_SIZE_BELOW = 0.62
# NullScreenLook: menus inside a world
IN_WORLD_DARKEN = [0.08, 0.12, 0.16, 0.20, 0.24, 0.28]
IN_WORLD_VIGNETTE = [0.45, 0.55, 0.65, 0.75, 0.85, 0.95]
IN_WORLD_GRAIN = [0.030, 0.035, 0.040, 0.050, 0.060, 0.070]


def load_rgba(path):
    a = np.asarray(Image.open(path).convert("RGBA")).astype(np.float32) / 255.0
    return a[..., 0], a[..., 3]


def blit(dst, lum, alpha, x0, y0, w, h, opacity, additive=False, color=1.0):
    """Draws a sprite into the float screen like a textured quad (bilinear, normal or additive blend)."""
    if w < 1 or h < 1 or opacity <= 0:
        return
    sl = Image.fromarray(lum.astype(np.float32), mode="F").resize((max(1, int(round(w))), max(1, int(round(h)))), Image.BILINEAR)
    sa = Image.fromarray(alpha.astype(np.float32), mode="F").resize(sl.size, Image.BILINEAR)
    l = np.asarray(sl) * color
    a = np.clip(np.asarray(sa) * opacity, 0, 1)
    x0, y0 = int(round(x0)), int(round(y0))
    H, W = dst.shape
    dx0, dy0 = max(0, x0), max(0, y0)
    dx1, dy1 = min(W, x0 + l.shape[1]), min(H, y0 + l.shape[0])
    if dx0 >= dx1 or dy0 >= dy1:
        return
    ls = l[dy0 - y0:dy1 - y0, dx0 - x0:dx1 - x0]
    as_ = a[dy0 - y0:dy1 - y0, dx0 - x0:dx1 - x0]
    if additive:
        dst[dy0:dy1, dx0:dx1] = np.clip(dst[dy0:dy1, dx0:dx1] + ls * as_, 0, 1)
    else:
        dst[dy0:dy1, dx0:dx1] = dst[dy0:dy1, dx0:dx1] * (1 - as_) + ls * as_


def sub(atlas, r):
    l, a = atlas
    return l[r["y"]:r["y"] + r["h"], r["x"]:r["x"] + r["w"]], a[r["y"]:r["y"] + r["h"], r["x"]:r["x"] + r["w"]]


def draw_face(scr, atlas, meta, index, cx, cy, size, alpha, follow, glint, gaze, mirror=False, glints=None):
    """One face like MainMenuBackgroundRenderer.drawPortrait: sprite, then the eye patches shifted
    towards the gaze; its glints are collected (drawn later, additive, like drawGlints)."""
    H, W = scr.shape
    f = meta["faces"][index % len(meta["faces"])]
    h = size * H
    w = h * f["w"] / f["h"]
    use_half = h < f["h"] * HALF_SIZE_BELOW
    region = f["half"] if use_half else f
    l, a = sub(atlas, region)
    if mirror:
        l, a = l[:, ::-1], a[:, ::-1]
    x0, y0 = cx * W - w / 2, cy * H - h / 2
    blit(scr, l, a, x0, y0, w, h, alpha)
    vis = min(1.0, alpha * PATCH_K)
    for eye in f["eyes"]:
        ex = eye["x"] / f["w"]
        if mirror:
            ex = 1.0 - ex
        px, py = x0 + ex * w, y0 + eye["y"] / f["h"] * h
        dx, dy = gaze[0] * W - px, gaze[1] * H - py
        dist = max(1e-3, np.hypot(dx, dy))
        reach = eye["travel"] / f["w"] * w * follow * min(1.0, dist / (0.3 * H))
        ox, oy = dx / dist * reach, dy / dist * reach
        if "patch" in eye and vis > 0.003:
            p = eye["half_patch"] if use_half else eye["patch"]
            pl, pa = sub(atlas, p)
            if mirror:
                pl, pa = pl[:, ::-1], pa[:, ::-1]
            ps = eye["patch"]["w"] / f["w"] * w
            pcx = eye["patch"]["cx"] / eye["patch"]["w"]
            pcy = eye["patch"]["cy"] / eye["patch"]["h"]
            if mirror:
                pcx = 1.0 - pcx
            blit(scr, pl, pa, px + ox - pcx * ps, py + oy - pcy * ps, ps, ps, vis, color=alpha / max(vis, 1e-3))
        if glints is not None:
            gs = max(1.5, eye["glint"] / f["w"] * w)
            glints.append((px + ox * 1.15, py + oy * 1.15, gs, min(1.0, alpha * glint * 1.6)))


def draw_glints(scr, atlas, meta, glints):
    gl, ga = sub(atlas, meta["glint"])
    for gx, gy, gs, strength in glints:
        blit(scr, gl, ga, gx - gs * 0.62, gy - gs * 0.62, gs, gs, strength, additive=True)


def apparition(scr, art, kind, index, cx, cy, size, alpha, additive=False):
    """A generated apparition (eye pair, mouth, silhouette) from the old atlas."""
    H, W = scr.shape
    al, aa = art["atlas"]
    s = art["meta"][kind][index % len(art["meta"][kind])]
    l, a = al[s["y"]:s["y"] + s["h"], s["x"]:s["x"] + s["w"]], aa[s["y"]:s["y"] + s["h"], s["x"]:s["x"] + s["w"]]
    h = size * H
    w = h * s["w"] / s["h"]
    blit(scr, l, a, cx * W - w / 2, cy * H - h / 2, w, h, alpha, additive=additive)


def fog_layer(scr, art, stage, gw, gh):
    H, W = scr.shape
    fog_l, fog_a = art["fog"]
    for (tile, rgb, k) in ((0.9, 0xA8, 1.0), (1.6, 0x8C, 0.8)):
        reps_x, reps_y = gw / (gh * tile), 1.0 / tile
        fy = np.tile(fog_a, (int(np.ceil(reps_y)) + 1, int(np.ceil(reps_x)) + 1))
        fh, fw = fog_a.shape
        crop = fy[:max(1, int(fh * reps_y)), :max(1, int(fw * reps_x))]
        big = np.asarray(Image.fromarray(crop.astype(np.float32), mode="F").resize((W, H), Image.BILINEAR))
        a = big * FOG[stage - 1] * k
        scr[:] = scr * (1 - a) + (rgb / 255.0) * a


def vignette_layer(scr, art, strength):
    H, W = scr.shape
    vl, va = art["vignette"]
    vig = np.asarray(Image.fromarray(va.astype(np.float32), mode="F").resize((W, H), Image.BILINEAR)) * strength
    scr[:] = scr * (1 - vig)


def grain_layer(img_rgb, art, alpha):
    H, W = img_rgb.shape[:2]
    gl, ga = art["grain"]
    reps = (int(np.ceil(H / (256 * 1.5))) + 1, int(np.ceil(W / (256 * 1.5))) + 1)
    gt = np.tile(gl, reps)
    gt = np.asarray(Image.fromarray(gt.astype(np.float32), mode="F").resize(
        (int(gt.shape[1] * 1.5), int(gt.shape[0] * 1.5)), Image.BILINEAR))[:H, :W]
    return img_rgb * (1 - alpha) + gt[..., None] * alpha


def ui_font(scale):
    try:
        return ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf", int(8 * scale))
    except OSError:
        return ImageFont.load_default()


def mock(stage, W, H, scale, atlas, meta, art, closeup=False, still=False):
    """The main menu at this stage. still=True: exactly the still composition (animation off);
    otherwise a typical moment of the animation at the same places (full alpha, eyes on the cursor)."""
    i = stage - 1
    gw, gh = W / scale, H / scale
    scr = np.full((H, W), BASE[i] / 255.0, np.float32)
    fog_layer(scr, art, stage, gw, gh)
    gaze = (0.22, 0.45)  # the cursor near the buttons
    follow = 0.0 if still else FOLLOW[i] * (0.4 + 0.6 * MOTION)
    k_alpha = 0.85 if still else 1.0
    # silhouettes, then the faces (dominant first), then mouths - like MainMenuBackgroundRenderer.render
    if SILHOUETTES[i] > 0:
        apparition(scr, art, "silhouettes", 2, 0.86, 0.62, 0.80, SILHOUETTE_ALPHA[i] * 0.7)
    glints = []
    if DOMINANT_ALPHA[i] > 0:
        p = STILL_DOMINANT[1 if stage >= 6 else 0]
        draw_face(scr, atlas, meta, p[0], p[1], p[2], DOMINANT_SIZE[i], DOMINANT_ALPHA[i] * k_alpha, follow, GLINT[i],
                  gaze, glints=glints)
    for n, (index, cx, cy, size, k) in enumerate(STILL_FACES[i]):
        draw_face(scr, atlas, meta, index, cx, cy, size, FACE_ALPHA[i] * k_alpha * k, follow, GLINT[i], gaze,
                  mirror=(n == 1 and stage % 2 == 0), glints=glints)
    if closeup:
        draw_face(scr, atlas, meta, 2, 0.64, 0.51, 1.55, CLOSE_UP_ALPHA[i], follow, GLINT[i], gaze, glints=glints)
    if MOUTHS[i] > 0:
        apparition(scr, art, "mouths", 1, 0.74, 0.86, 0.09, MOUTH_ALPHA[i] * 0.75)
    for n in range(min(3, EYES[i])):
        ex, ey, es = STILL_EYES[n]
        apparition(scr, art, "eyes", n, ex, ey, es, EYE_ALPHA[i] * 0.7, additive=True)
    draw_glints(scr, atlas, meta, glints)

    # layout of CorruptedMainMenu.buildLayout (GUI units -> pixels)
    margin_x = min(64, max(6, round(gw * 0.065)))
    margin_top = min(56, max(4, round(gh * 0.085)))
    logo_gap = min(34, max(6, round(gh * 0.06)))
    gap = min(5, max(2, round(gh * 0.012)))
    button_w = min(max(90, round(gw * 0.24)), 200)
    stack = 4 * 20 + 3 * gap
    footer = 2 * 10 + 6
    available = gh - margin_top - logo_gap - stack - footer
    logo_h = max(0.0, min(available, gh * 0.40))
    logo_w = min(gw * 0.62, gw - margin_x * 2 - 80)
    glyphs = art["glyphs"]
    cap = glyphs["meta"]["baseline"] - glyphs["meta"]["cap_top"]
    run = run_width(glyphs, "CORRUPTED", cap * 0.035)
    s_logo = min(logo_w / max(1.0, run), logo_h / (cap * (0.38 + 0.18 + 1.0 + 0.22 + 0.40)))
    buttons_y = margin_top + cap * s_logo * (0.38 + 0.18 + 1.0 + 0.22 + 0.40) + logo_gap
    # CorruptedMainMenu.scrimRight: max(buttons right, 0.55 x logo right) + 0.16 of the width
    logo_right = (margin_x + run * s_logo) / gw
    scrim_right = min(1.0, max((margin_x + button_w) / gw, logo_right * 0.55) + 0.16) * gw
    xs = (np.arange(W) + 0.5) / scale
    ramp = SCRIM[i] * (1 - smoothstep(scrim_right * 0.35, scrim_right, xs))
    scr = scr * (1 - ramp[None, :])
    vignette_layer(scr, art, VIGNETTE[i])

    draw_logo(scr, glyphs, margin_x * scale, margin_top * scale, s_logo * scale, cap)
    img = Image.fromarray((np.clip(scr, 0, 1) * 255).astype(np.uint8)).convert("RGB")
    d = ImageDraw.Draw(img)
    ui = ui_font(scale)
    for k, label in enumerate(["Singleplayer", "Multiplayer", "Options...", "Quit Game"]):
        y0 = (buttons_y + k * (20 + gap)) * scale
        x0 = margin_x * scale
        d.rectangle([x0, y0, x0 + button_w * scale, y0 + 20 * scale], outline=(140, 140, 140), width=max(1, scale // 2),
                    fill=(9, 9, 9))
        d.text((x0 + 8 * scale, y0 + 6 * scale), label, fill=(196, 196, 196), font=ui)
    d.text((W - 60 * scale, 4 * scale), "Mods  NULL", fill=(112, 112, 112), font=ui)
    d.text((2 * scale, H - 20 * scale), "Minecraft 1.21.1\nNeoForge 21.1.265", fill=(80, 80, 80), font=ui)
    out = grain_layer(np.asarray(img).astype(np.float32) / 255.0, art, GRAIN[i] * (0.55 + 0.45 * GLITCH))
    return Image.fromarray((np.clip(out, 0, 1) * 255).astype(np.uint8))


def mock_select_world(stage, W, H, scale, atlas, meta, art):
    """The Select World screen on NULL's backdrop (MenuBackdrop, still composition at its edges),
    with the list's own translucent dark background and the vanilla widgets drawn on top."""
    i = stage - 1
    gw, gh = W / scale, H / scale
    scr = np.full((H, W), BASE[i] / 255.0, np.float32)
    fog_layer(scr, art, stage, gw, gh)
    glints = []
    for n in range(BACKDROP_FACES[i]):
        index, cx, cy, size, k = STILL_BACKDROP[n]
        draw_face(scr, atlas, meta, index, cx, cy, size, FACE_ALPHA[i] * BACKDROP_ALPHA * k, FOLLOW[i] * 0.88,
                  GLINT[i], (0.5, 0.3), mirror=(n == 1), glints=glints)
    for n in range(min(len(STILL_BACKDROP_EYES), BACKDROP_EYES[i])):
        ex, ey, es = STILL_BACKDROP_EYES[n]
        apparition(scr, art, "eyes", n + 2, ex, ey, es, EYE_ALPHA[i] * 0.6, additive=True)
    draw_glints(scr, atlas, meta, glints)
    vignette_layer(scr, art, VIGNETTE[i])
    img = Image.fromarray((np.clip(scr, 0, 1) * 255).astype(np.uint8)).convert("RGBA")
    # vanilla widgets: title, search box, list (menu_list_background is ~44% black), buttons
    over = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(over)
    ui = ui_font(scale)
    d.text((W / 2 - 34 * scale, 8 * scale), "Select World", fill=(255, 255, 255, 255), font=ui)
    d.rectangle([W / 2 - 100 * scale, 22 * scale, W / 2 + 100 * scale, 42 * scale], fill=(0, 0, 0, 255),
                outline=(160, 160, 160, 255))
    top, bottom = 48 * scale, H - 64 * scale
    d.rectangle([0, top, W, bottom], fill=(0, 0, 0, 112))
    d.line([0, top, W, top], fill=(70, 70, 70, 255))
    d.line([0, bottom, W, bottom], fill=(70, 70, 70, 255))
    for n, (name, info) in enumerate((("New World", "null-autotest (10/31/26 12:00)"),
                                       ("My House", "Survival Mode, Version: 1.21.1"),
                                       ("Test", "Creative Mode, Cheats"))):
        y = top + (4 + n * 36) * scale
        x = W / 2 - 110 * scale
        if n == 0:
            d.rectangle([x - 2 * scale, y - 2 * scale, x + 222 * scale, y + 34 * scale], outline=(255, 255, 255, 255))
        d.rectangle([x, y, x + 32 * scale, y + 32 * scale], fill=(60, 90, 50, 255))
        d.text((x + 36 * scale, y + 2 * scale), name, fill=(255, 255, 255, 255), font=ui)
        d.text((x + 36 * scale, y + 13 * scale), info, fill=(128, 128, 128, 255), font=ui)
    for row, labels in enumerate((("Play Selected World", "Create New World"), ("Edit", "Delete", "Re-Create", "Back"))):
        y = H - (54 - row * 24) * scale
        widths = 150 if len(labels) == 2 else 72
        total = len(labels) * widths + (len(labels) - 1) * 4
        x = W / 2 - total * scale / 2
        for label in labels:
            d.rectangle([x, y, x + widths * scale, y + 20 * scale], fill=(110, 110, 110, 255), outline=(0, 0, 0, 255))
            d.text((x + 6 * scale, y + 6 * scale), label, fill=(255, 255, 255, 255), font=ui)
            x += (widths + 4) * scale
    img.alpha_composite(over)
    out = grain_layer(np.asarray(img.convert("RGB")).astype(np.float32) / 255.0, art,
                      GRAIN[i] * (0.55 + 0.45 * GLITCH) * 0.8)
    return Image.fromarray((np.clip(out, 0, 1) * 255).astype(np.uint8))


def mock_pause(stage, W, H, scale, art):
    """A pause menu inside a world: the blurred game stays, NULL adds darkening, vignette and grain."""
    i = stage - 1
    rng = np.random.default_rng(3)
    ys = np.linspace(0, 1, H)[:, None]
    sky = np.concatenate([np.broadcast_to(0.45 + 0.25 * (1 - ys), (H, W))[..., None] * c for c in (0.55, 0.70, 0.95)], axis=2)
    ground = ys > 0.55
    sky[np.broadcast_to(ground, (H, W))] = np.array([0.25, 0.40, 0.18])
    for _ in range(14):  # trees
        x = rng.integers(0, W)
        w = rng.integers(20, 60) * scale // 2
        y = int(0.55 * H)
        sky[max(0, y - 3 * w):y, max(0, x - w // 2):x + w // 2] = np.array([0.12, 0.25, 0.10])
    world = Image.fromarray((sky * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(6 * scale))
    scr = np.asarray(world).astype(np.float32) / 255.0
    scr = scr * 0.75  # vanilla in-world menu background
    scr = scr * (1 - IN_WORLD_DARKEN[i])
    vl, va = art["vignette"]
    vig = np.asarray(Image.fromarray(va.astype(np.float32), mode="F").resize((W, H), Image.BILINEAR)) * IN_WORLD_VIGNETTE[i]
    scr = scr * (1 - vig[..., None])
    img = Image.fromarray((np.clip(scr, 0, 1) * 255).astype(np.uint8)).convert("RGB")
    d = ImageDraw.Draw(img)
    ui = ui_font(scale)
    d.text((W / 2 - 30 * scale, 40 * scale), "Game Menu", fill=(255, 255, 255), font=ui)
    for row, labels in enumerate((("Back to Game",), ("Advancements", "Statistics"), ("Give Feedback", "Report Bugs"),
                                  ("Options...", "Open to LAN"), ("Mods",), ("Save and Quit to Title",))):
        y = (64 + row * 24) * scale
        widths = 204 if len(labels) == 1 else 100
        x = W / 2 - (len(labels) * widths + (len(labels) - 1) * 4) * scale / 2
        for label in labels:
            d.rectangle([x, y, x + widths * scale, y + 20 * scale], fill=(110, 110, 110), outline=(0, 0, 0))
            d.text((x + 6 * scale, y + 6 * scale), label, fill=(255, 255, 255), font=ui)
            x += (widths + 4) * scale
    out = grain_layer(np.asarray(img).astype(np.float32) / 255.0, art, IN_WORLD_GRAIN[i] * (0.55 + 0.45 * GLITCH))
    return Image.fromarray((np.clip(out, 0, 1) * 255).astype(np.uint8))


def run_width(glyphs, text, tracking):
    m = glyphs["meta"]
    total = 0.0
    for ch in text:
        if ch == " ":
            total += m["space"] + tracking
            continue
        total += m["advance"][m["chars"].index(ch)] + tracking
    return total - tracking


def draw_logo(scr, glyphs, x, y, s, cap):
    m = glyphs["meta"]
    gl, ga = glyphs["tex"]
    cw, chh = m["cell_w"], m["cell_h"]

    def run(text, px, cap_top, scale, tracking, rgb, opacity):
        pen = 0.0
        for ch in text:
            if ch == " ":
                pen += m["space"] + tracking
                continue
            i = m["chars"].index(ch)
            col, row = i % m["columns"], i // m["columns"]
            l = gl[row * chh:(row + 1) * chh, col * cw:(col + 1) * cw]
            a = ga[row * chh:(row + 1) * chh, col * cw:(col + 1) * cw]
            blit(scr, l, a, px + (pen - m["left"][i]) * scale, cap_top - m["cap_top"] * scale, cw * scale, chh * scale,
                 opacity, color=rgb)
            pen += m["advance"][i] + tracking

    the_s, dir_s = 0.38, 0.40
    yy = y
    run("THE", x, yy, s * the_s, cap * 0.16, 0.72, 0.92)
    yy += cap * s * (the_s + 0.18)
    run("CORRUPTED", x, yy, s, cap * 0.035, 0.91, 1.0)
    yy += cap * s * (1.0 + 0.22)
    natural = run_width(glyphs, "DIRECTORY", 0.0)
    tr = max(cap * 0.12, (run_width(glyphs, "CORRUPTED", cap * 0.035) / dir_s - natural) / 8)
    run("DIRECTORY", x, yy, s * dir_s, tr, 0.66, 0.9)


def preview(out_dir, lum, alpha, meta, faces):
    out_dir.mkdir(parents=True, exist_ok=True)
    # contact sheets: the atlas on the menu's background, plain and brightened
    bg = np.full(lum.shape, 0.03, np.float32)
    sheet = bg * (1 - alpha) + lum * alpha
    Image.fromarray((sheet * 255).astype(np.uint8)).save(out_dir / "faces_atlas.png")
    Image.fromarray((np.clip(sheet * 1.6, 0, 1) * 255).astype(np.uint8)).save(out_dir / "faces_atlas_bright.png")
    # alpha only (no rectangles may show)
    Image.fromarray((alpha * 255).astype(np.uint8)).save(out_dir / "faces_alpha.png")
    # each face with its eyes marked
    tiles = []
    for i, f in enumerate(meta["faces"]):
        l, a = lum[f["y"]:f["y"] + f["h"], f["x"]:f["x"] + f["w"]], alpha[f["y"]:f["y"] + f["h"], f["x"]:f["x"] + f["w"]]
        t = Image.fromarray(((0.03 * (1 - a) + l * a) * 255).astype(np.uint8)).convert("RGB")
        d = ImageDraw.Draw(t)
        for eye in f["eyes"]:
            r = eye["travel"]
            d.ellipse([eye["x"] - r, eye["y"] - r, eye["x"] + r, eye["y"] + r], outline=(255, 40, 40))
        tiles.append(t)
    wsum = sum(t.width for t in tiles) + 10 * (len(tiles) + 1)
    hmax = max(t.height for t in tiles) + 20
    board = Image.new("RGB", (wsum, hmax), (20, 20, 40))
    x = 10
    for t in tiles:
        board.paste(t, (x, 10))
        x += t.width + 10
    board.save(out_dir / "faces_eyes.png")

    atlas = (lum, alpha)
    art = load_menu_art()
    for stage in range(1, 7):
        for (W, H, scale) in ((854, 480, 2), (1920, 1080, 4)):
            mock(stage, W, H, scale, atlas, meta, art).save(out_dir / f"menu_stage{stage}_{W}x{H}.png")
        mock(stage, 854, 480, 2, atlas, meta, art, still=True).save(out_dir / f"menu_stage{stage}_still_854x480.png")
        if CLOSE_UP_ALPHA[stage - 1] > 0:
            mock(stage, 854, 480, 2, atlas, meta, art, closeup=True).save(out_dir / f"menu_stage{stage}_closeup_854x480.png")
        mock_select_world(stage, 854, 480, 2, atlas, meta, art).save(out_dir / f"select_world_stage{stage}_854x480.png")
        mock_pause(stage, 854, 480, 2, art).save(out_dir / f"pause_stage{stage}_854x480.png")
    mock_select_world(6, 1920, 1080, 4, atlas, meta, art).save(out_dir / "select_world_stage6_1920x1080.png")
    # small and odd windows: GUI scale 1 and a 4:3 window
    mock(5, 640, 480, 1, atlas, meta, art).save(out_dir / "menu_stage5_640x480_gui1.png")
    mock(6, 1024, 768, 2, atlas, meta, art).save(out_dir / "menu_stage6_1024x768.png")
    # gaze test: the same face with the cursor in four places
    tiles = []
    for gaze in ((0.05, 0.5), (0.95, 0.5), (0.5, 0.02), (0.5, 0.98)):
        for index in (0, 2, 1, 3):
            scr = np.full((300, 300), 0.02, np.float32)
            glints = []
            draw_face(scr, atlas, meta, index, 0.5, 0.5, 0.95, 0.9, 1.0, 1.0, gaze, glints=glints)
            draw_glints(scr, atlas, meta, glints)
            tiles.append(Image.fromarray((np.clip(scr, 0, 1) * 255).astype(np.uint8)))
    board = Image.new("L", (4 * 300, 4 * 300))
    for k, t in enumerate(tiles):
        board.paste(t, ((k % 4) * 300, (k // 4) * 300))
    board.save(out_dir / "faces_gaze.png")


def load_menu_art():
    art = json.loads((MENU_DIR / "art.json").read_text(encoding="utf-8"))
    font_tex = ASSETS / "textures" / "font" / "horror.png"
    return {
        "fog": load_rgba(MENU_DIR / "fog.png"),
        "vignette": load_rgba(MENU_DIR / "vignette.png"),
        "grain": load_rgba(MENU_DIR / "grain.png"),
        "atlas": load_rgba(MENU_DIR / "apparitions.png"),
        "meta": art,
        "glyphs": {"tex": load_rgba(font_tex), "meta": art["glyphs"]},
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", type=pathlib.Path, default=None)
    args = ap.parse_args()
    lum, alpha, meta, faces = build()
    MENU_DIR.mkdir(parents=True, exist_ok=True)
    to_rgba(lum, alpha).save(MENU_DIR / "faces.png", optimize=True)
    (MENU_DIR / "faces.json").write_text(json.dumps(meta, indent=1) + "\n", encoding="utf-8")
    print(f"faces.png {meta['width']}x{meta['height']}: " + ", ".join(
        f"{f['name']} {f['w']}x{f['h']}" for f in meta["faces"]))
    if args.preview:
        preview(args.preview, lum, alpha, meta, faces)
        print(f"previews -> {args.preview}")


if __name__ == "__main__":
    main()
