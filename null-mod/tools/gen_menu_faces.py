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
  --preview DIR  also writes contact sheets and full main-menu mock-ups (854x480 at GUI scale 2,
                 1920x1080 at GUI scale 4) for every stage, composed the way the menu code draws.
"""
import argparse
import json
import pathlib

import numpy as np
from PIL import Image, ImageDraw, ImageFont

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
# preview: contact sheets and main-menu mock-ups composed like MainMenuBackgroundRenderer
# --------------------------------------------------------------------------------------------

# Mirrors of the Java tables (MenuMood, index = stage - 1); keep them in step with the code.
FACE_ALPHA = [0.17, 0.24, 0.32, 0.40, 0.48, 0.56]
DOMINANT_ALPHA = [0.0, 0.0, 0.0, 0.0, 0.24, 0.32]
CLOSE_UP_ALPHA = [0.0, 0.0, 0.42, 0.50, 0.58, 0.66]
GRAIN = [0.045, 0.050, 0.060, 0.070, 0.085, 0.100]
FOG = [0.060, 0.065, 0.070, 0.075, 0.060, 0.055]
BASE = [0x07, 0x06, 0x06, 0x05, 0x03, 0x02]
VIGNETTE = [0.80, 0.84, 0.88, 0.93, 1.0, 1.0]
SCRIM = [0.50, 0.52, 0.55, 0.60, 0.66, 0.70]
EYE_ALPHA = [0.35, 0.50, 0.62, 0.75, 0.85, 0.95]
SILHOUETTE_ALPHA = [0.30, 0.38, 0.50, 0.65, 0.80, 0.88]
FOLLOW = [0.0, 0.45, 0.65, 0.80, 1.0, 1.0]
GLINT = [0.55, 0.65, 0.75, 0.85, 0.95, 1.0]
PATCH_K = 3.5


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


def draw_face(scr, atlas, meta, index, cx, cy, size, alpha, stage, gaze, mirror=False):
    """One face like MainMenuBackgroundRenderer.drawFace: sprite, eye patches (towards the gaze), glints."""
    H, W = scr.shape
    f = meta["faces"][index]
    h = size * H
    w = h * f["w"] / f["h"]
    use_half = h < f["h"] * 0.62
    region = f["half"] if use_half else f
    l, a = sub(atlas, region)
    if mirror:
        l, a = l[:, ::-1], a[:, ::-1]
    x0, y0 = cx * W - w / 2, cy * H - h / 2
    blit(scr, l, a, x0, y0, w, h, alpha)
    follow = FOLLOW[stage - 1]
    glints = []
    for eye in f["eyes"]:
        ex = eye["x"] / f["w"]
        if mirror:
            ex = 1.0 - ex
        px, py = x0 + ex * w, y0 + eye["y"] / f["h"] * h
        dx, dy = gaze[0] * W - px, gaze[1] * H - py
        dist = max(1e-3, np.hypot(dx, dy))
        reach = eye["travel"] / f["w"] * w * follow * min(1.0, dist / (0.3 * H))
        ox, oy = dx / dist * reach, dy / dist * reach
        if "patch" in eye:
            p = eye["half_patch"] if use_half else eye["patch"]
            pl, pa = sub(atlas, p)
            if mirror:
                pl, pa = pl[:, ::-1], pa[:, ::-1]
            ps = eye["patch"]["w"] / f["w"] * w
            pcx = eye["patch"]["cx"] / eye["patch"]["w"]
            pcy = eye["patch"]["cy"] / eye["patch"]["h"]
            if mirror:
                pcx = 1.0 - pcx
            vis = min(1.0, alpha * PATCH_K)
            blit(scr, pl, pa, px + ox - pcx * ps, py + oy - pcy * ps, ps, ps, vis, color=alpha / max(vis, 1e-3))
        glints.append((px + ox * 1.15, py + oy * 1.15, eye["glint"] / f["w"] * w))
    gl, ga = sub(atlas, meta["glint"])
    for gx, gy, gs in glints:
        gs = max(1.5, gs)
        blit(scr, gl, ga, gx - gs * 0.62, gy - gs * 0.62, gs, gs, alpha * GLINT[stage - 1] * 1.6, additive=True)


def mock(stage, W, H, scale, atlas, meta, art, closeup=False):
    """A still of the main menu at this stage: what the menu shows at a typical moment."""
    gw, gh = W / scale, H / scale
    scr = np.full((H, W), BASE[stage - 1] / 255.0, np.float32)
    fog_l, fog_a = art["fog"]
    for (u, v, tile, rgb, k) in ((0.0, 0.0, 0.9, 0xA8, 1.0), (0.37, 0.21, 1.6, 0x8C, 0.8)):
        reps_x, reps_y = gw / (gh * tile), 1.0 / tile
        fy = np.tile(fog_a, (int(np.ceil(reps_y)) + 1, int(np.ceil(reps_x)) + 1))
        fh, fw = fog_a.shape
        crop = fy[:int(fh * reps_y), :int(fw * reps_x)]
        big = np.asarray(Image.fromarray(crop.astype(np.float32), mode="F").resize((W, H), Image.BILINEAR))
        a = big * FOG[stage - 1] * k
        scr = scr * (1 - a) + (rgb / 255.0) * a
    # apparitions of the old atlas: a silhouette from stage 3, a few eye pairs
    al, aa = art["atlas"]
    ameta = art["meta"]
    if stage >= 3:
        s = ameta["silhouettes"][2]
        l, a = al[s["y"]:s["y"] + s["h"], s["x"]:s["x"] + s["w"]], aa[s["y"]:s["y"] + s["h"], s["x"]:s["x"] + s["w"]]
        sh = 0.8 * H
        blit(scr, l, a, 0.86 * W - sh * s["w"] / s["h"] / 2, H - sh * 0.95, sh * s["w"] / s["h"], sh, SILHOUETTE_ALPHA[stage - 1] * 0.8)
    gaze = (0.22, 0.45)  # the cursor near the buttons
    if stage >= 5:
        dom = 0.52 if stage == 5 else 0.56
        draw_face(scr, atlas, meta, 3 if stage == 5 else 0, dom, 0.52, 1.30 if stage == 5 else 1.42,
                  DOMINANT_ALPHA[stage - 1], stage, gaze)
    placements = {
        1: [(2, 0.76, 0.50, 0.50, 1.0)],
        2: [(0, 0.66, 0.46, 0.56, 1.0), (1, 0.90, 0.62, 0.50, 0.85)],
        3: [(2, 0.62, 0.42, 0.62, 1.0), (1, 0.88, 0.60, 0.58, 0.9)],
        4: [(0, 0.60, 0.50, 0.74, 1.0), (2, 0.86, 0.36, 0.64, 0.95), (1, 0.94, 0.80, 0.40, 0.7)],
        5: [(2, 0.66, 0.38, 0.80, 1.0), (1, 0.90, 0.64, 0.72, 0.95), (0, 0.48, 0.84, 0.46, 0.7)],
        6: [(3, 0.70, 0.40, 0.92, 1.0), (2, 0.92, 0.66, 0.80, 0.95), (1, 0.50, 0.80, 0.52, 0.7)],
    }[stage]
    for index, cx, cy, size, k in placements:
        draw_face(scr, atlas, meta, index, cx, cy, size, FACE_ALPHA[stage - 1] * k, stage, gaze, mirror=(index == 1 and stage % 2 == 0))
    if closeup:
        draw_face(scr, atlas, meta, 2, 0.58, 0.52, 1.55, CLOSE_UP_ALPHA[stage - 1], stage, gaze)
    # generated eye pairs, additive
    for k in range(min(stage, 4)):
        e = ameta["eyes"][k]
        l, a = al[e["y"]:e["y"] + e["h"], e["x"]:e["x"] + e["w"]], aa[e["y"]:e["y"] + e["h"], e["x"]:e["x"] + e["w"]]
        eh = 0.04 * H
        px, py = [(0.95, 0.14), (0.47, 0.90), (0.98, 0.42), (0.40, 0.08)][k]
        blit(scr, l, a, px * W - eh * e["w"] / e["h"] / 2, py * H - eh / 2, eh * e["w"] / e["h"], eh,
             EYE_ALPHA[stage - 1] * 0.8, additive=True)
    # vignette
    vl, va = art["vignette"]
    vig = np.asarray(Image.fromarray(va.astype(np.float32), mode="F").resize((W, H), Image.BILINEAR)) * VIGNETTE[stage - 1]
    scr = scr * (1 - vig)

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
    s_logo = min(logo_w / max(1.0, run_width(glyphs, "CORRUPTED", cap * 0.035)), logo_h / (cap * (0.38 + 0.18 + 1.0 + 0.22 + 0.40)))
    buttons_y = margin_top + cap * s_logo * (0.38 + 0.18 + 1.0 + 0.22 + 0.40) + logo_gap
    buttons_bottom = buttons_y + 3 * (20 + gap) + 20
    # scrim behind the logo and the buttons (left side)
    scrim_right = min(gw, margin_x + button_w + gw * 0.16)
    xs = (np.arange(W) + 0.5) / scale
    ramp = SCRIM[stage - 1] * (1 - smoothstep(scrim_right * 0.35, scrim_right, xs))
    scr = scr * (1 - ramp[None, :])

    # logo
    draw_logo(scr, glyphs, margin_x * scale, margin_top * scale, s_logo * scale, cap)
    # buttons
    img = Image.fromarray((np.clip(scr, 0, 1) * 255).astype(np.uint8)).convert("RGB")
    d = ImageDraw.Draw(img)
    try:
        ui = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf", int(8 * scale))
    except OSError:
        ui = ImageFont.load_default()
    for k, label in enumerate(["Singleplayer", "Multiplayer", "Options...", "Quit Game"]):
        y0 = (buttons_y + k * (20 + gap)) * scale
        x0 = margin_x * scale
        d.rectangle([x0, y0, x0 + button_w * scale, y0 + 20 * scale], outline=(140, 140, 140), width=max(1, scale // 2),
                    fill=None)
        d.text((x0 + 8 * scale, y0 + 6 * scale), label, fill=(196, 196, 196), font=ui)
    d.text((W - 60 * scale, 4 * scale), "Mods  NULL", fill=(112, 112, 112), font=ui)
    d.text((2 * scale, H - 20 * scale), "Minecraft 1.21.1\nNeoForge 21.1.265", fill=(80, 80, 80), font=ui)
    out = np.asarray(img).astype(np.float32) / 255.0
    # grain over everything
    gl, ga = art["grain"]
    reps = (int(np.ceil(H / (256 * 1.5))) + 1, int(np.ceil(W / (256 * 1.5))) + 1)
    gt = np.tile(gl, reps)
    gt = np.asarray(Image.fromarray(gt.astype(np.float32), mode="F").resize((int(gt.shape[1] * 1.5), int(gt.shape[0] * 1.5)), Image.BILINEAR))[:H, :W]
    g = GRAIN[stage - 1]
    out = out * (1 - g) + gt[..., None] * g
    return Image.fromarray((np.clip(out, 0, 1) * 255).astype(np.uint8)), buttons_bottom


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
            img, _ = mock(stage, W, H, scale, atlas, meta, art)
            img.save(out_dir / f"menu_stage{stage}_{W}x{H}.png")
        if CLOSE_UP_ALPHA[stage - 1] > 0:
            img, _ = mock(stage, 854, 480, 2, atlas, meta, art, closeup=True)
            img.save(out_dir / f"menu_stage{stage}_closeup_854x480.png")
    # gaze test: the same face with the cursor in four places
    tiles = []
    for gaze in ((0.05, 0.5), (0.95, 0.5), (0.5, 0.02), (0.5, 0.98)):
        for index in (0, 2, 1, 3):
            scr = np.full((300, 300), 0.02, np.float32)
            draw_face(scr, atlas, meta, index, 0.5, 0.5, 0.95, 0.9, 6, gaze)
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
