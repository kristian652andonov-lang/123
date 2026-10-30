#!/usr/bin/env python3
"""Generates the art for NULL's corrupted main menu (deterministic, fixed seeds).

Everything is synthesised with numpy + PIL; no external images are used.

Outputs (under src/main/resources/assets/null/):
  textures/font/horror.png (+.mcmeta)       distressed serif glyph sheet (A-Z, 0-9, punctuation)
  font/horror.json                          bitmap font "null:horror" built on that sheet
  textures/gui/menu/apparitions.png (+.mcmeta)
                                            atlas: faces, eye pairs, eye glints, mouths, silhouettes
  textures/gui/menu/grain.png               tileable film grain / static
  textures/gui/menu/fog.png (+.mcmeta)      tileable drifting darkness
  textures/gui/menu/vignette.png (+.mcmeta) edge darkening
  textures/gui/menu/art.json                atlas regions, eye anchors and glyph metrics for the menu code

All PNGs are written as RGBA (Minecraft uploads grey/grey-alpha PNGs with the wrong channel layout).

Usage: python3 tools/gen_menu.py [--preview DIR]
  --preview DIR  also writes contact sheets and a full menu mock-up into DIR for visual checks.
"""
import argparse
import json
import pathlib

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src" / "main" / "resources" / "assets" / "null"
MENU_DIR = ASSETS / "textures" / "gui" / "menu"
FONT_TEX_DIR = ASSETS / "textures" / "font"
FONT_DEF_DIR = ASSETS / "font"

SERIF_FONTS = [
    "/usr/share/fonts/truetype/freefont/FreeSerifBold.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSerif-Bold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf",
]


# --------------------------------------------------------------------------------------------
# numpy helpers
# --------------------------------------------------------------------------------------------

def _kernel(sigma):
    r = int(max(1, round(sigma * 3)))
    x = np.arange(-r, r + 1, dtype=np.float32)
    k = np.exp(-(x * x) / (2 * sigma * sigma))
    return k / k.sum(), r


def blur(a, sigma, wrap=False):
    """Separable gaussian blur; wrap=True keeps tileable images tileable."""
    if sigma <= 0:
        return a.copy()
    k, r = _kernel(sigma)
    mode = "wrap" if wrap else "edge"
    p = np.pad(a, ((r, r), (0, 0)), mode=mode)
    out = np.zeros_like(a)
    for i, w in enumerate(k):
        out += w * p[i:i + a.shape[0], :]
    p = np.pad(out, ((0, 0), (r, r)), mode=mode)
    out2 = np.zeros_like(a)
    for i, w in enumerate(k):
        out2 += w * p[:, i:i + a.shape[1]]
    return out2


def vblur(a, sigma):
    """Vertical-only blur (smear)."""
    k, r = _kernel(sigma)
    p = np.pad(a, ((r, r), (0, 0)), mode="edge")
    out = np.zeros_like(a)
    for i, w in enumerate(k):
        out += w * p[i:i + a.shape[0], :]
    return out


def fnoise(h, w, rng, beta=2.0):
    """Periodic (tileable) fractal noise, zero mean, unit deviation. Higher beta = smoother."""
    white = rng.standard_normal((h, w))
    spec = np.fft.fft2(white)
    fy = np.fft.fftfreq(h)[:, None]
    fx = np.fft.fftfreq(w)[None, :]
    f = np.sqrt(fx * fx + fy * fy)
    f[0, 0] = 1.0
    spec = spec / f ** (beta / 2.0)
    spec[0, 0] = 0
    n = np.real(np.fft.ifft2(spec))
    return ((n - n.mean()) / (n.std() + 1e-9)).astype(np.float32)


def sample(a, xs, ys):
    """Bilinear lookup with clamped coordinates."""
    h, w = a.shape
    xs = np.clip(xs, 0, w - 1.001)
    ys = np.clip(ys, 0, h - 1.001)
    x0 = np.floor(xs).astype(np.int32)
    y0 = np.floor(ys).astype(np.int32)
    fx = xs - x0
    fy = ys - y0
    top = a[y0, x0] * (1 - fx) + a[y0, x0 + 1] * fx
    bottom = a[y0 + 1, x0] * (1 - fx) + a[y0 + 1, x0 + 1] * fx
    return top * (1 - fy) + bottom * fy


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def gauss(X, Y, cx, cy, sx, sy, rot=0.0):
    c, s = np.cos(rot), np.sin(rot)
    dx = X - cx
    dy = Y - cy
    u = c * dx + s * dy
    v = -s * dx + c * dy
    return np.exp(-0.5 * ((u / sx) ** 2 + (v / sy) ** 2))


def ellipse_d(X, Y, cx, cy, rx, ry, rot=0.0):
    """Normalised ellipse distance: 1.0 on the outline."""
    c, s = np.cos(rot), np.sin(rot)
    dx = X - cx
    dy = Y - cy
    u = c * dx + s * dy
    v = -s * dx + c * dy
    return np.sqrt((u / rx) ** 2 + (v / ry) ** 2)


def smin(a, b, k=8.0):
    """Exponential smooth minimum for normalised distances (k = sharpness)."""
    return -np.log(np.exp(-k * a) + np.exp(-k * b)) / k


def smin_px(a, b, r):
    """Polynomial smooth minimum for pixel distances (r = blend radius in pixels)."""
    hh = np.clip(0.5 + 0.5 * (b - a) / r, 0, 1)
    return b * (1 - hh) + a * hh - r * hh * (1 - hh)


def border_window(h, w, margin):
    """1 inside, fading to 0 within `margin` pixels of the sprite edge."""
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    dist = np.minimum(np.minimum(xs, w - 1 - xs), np.minimum(ys, h - 1 - ys))
    return smoothstep(0.0, margin, dist)


def downsample(a, ss):
    if ss == 1:
        return a
    h, w = a.shape[:2]
    if a.ndim == 2:
        return a.reshape(h // ss, ss, w // ss, ss).mean(axis=(1, 3))
    return a.reshape(h // ss, ss, w // ss, ss, a.shape[2]).mean(axis=(1, 3))


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


def save_png(img, path, mcmeta=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    img.convert("RGBA").save(path, optimize=True)
    meta = path.with_name(path.name + ".mcmeta")
    if mcmeta is not None:
        meta.write_text(json.dumps(mcmeta, indent=2) + "\n", encoding="utf-8")
    elif meta.exists():
        meta.unlink()


def bezier(p0, p1, p2, n=10):
    t = np.linspace(0, 1, n)[:, None]
    return (1 - t) ** 2 * np.array(p0) + 2 * (1 - t) * t * np.array(p1) + t ** 2 * np.array(p2)


# --------------------------------------------------------------------------------------------
# faces
# --------------------------------------------------------------------------------------------

def needle_teeth(w, h, cx, cy, rx, ry, rot, rng, count, scale_px, rows=(-1, 1), length=(0.05, 0.15)):
    """Thin, uneven, slightly curved needle teeth on the rim of an ellipse, pointing inwards."""
    img = Image.new("L", (w, h), 0)
    dr = ImageDraw.Draw(img)
    c, s = np.cos(rot), np.sin(rot)
    for row in rows:
        n = max(3, count + int(rng.integers(-2, 3)))
        for i in range(n):
            if rng.random() < 0.18:
                continue
            t = (i + 0.5 + rng.uniform(-0.3, 0.3)) / n
            ang = np.pi * (0.12 + 0.76 * t)
            ex = np.cos(ang) * rx
            ey = -row * np.sin(ang) * ry
            px = cx + c * ex - s * ey
            py = cy + s * ex + c * ey
            vx, vy = (cx - px) * 0.35, (cy - py)
            nrm = np.hypot(vx, vy) + 1e-6
            vx, vy = vx / nrm, vy / nrm
            ln = scale_px * rng.uniform(*length) * (0.4 + 0.6 * np.sin(ang))
            if rng.random() < 0.2:
                ln *= 0.45
            base = scale_px * rng.uniform(0.009, 0.016)
            bend = rng.uniform(-0.35, 0.35) * ln
            perp = (-vy, vx)
            tip = (px + vx * ln + perp[0] * bend * 0.4, py + vy * ln + perp[1] * bend * 0.4)
            mid = (px + vx * ln * 0.5 + perp[0] * bend, py + vy * ln * 0.5 + perp[1] * bend)
            left = bezier((px + perp[0] * base, py + perp[1] * base),
                          (mid[0] + perp[0] * base * 0.5, mid[1] + perp[1] * base * 0.5), tip)
            right = bezier(tip, (mid[0] - perp[0] * base * 0.5, mid[1] - perp[1] * base * 0.5),
                           (px - perp[0] * base, py - perp[1] * base))
            pts = [tuple(q) for q in np.concatenate([left, right])]
            dr.polygon(pts, fill=int(rng.uniform(130, 220)))
    return np.asarray(img).astype(np.float32) / 255.0


def make_face(seed, p, W=256, H=320, ss=2):
    """A soft, skull-like face lit from above, dissolving into darkness.

    Eye sockets are empty black pits (the menu draws glowing pupils on top at the returned
    anchors); the mouth is a stretched black hole with faint needle teeth.
    Returns (lum, alpha, eye_anchors_px, eye_radius_px).
    """
    rng = np.random.default_rng(seed)
    w, h = W * ss, H * ss
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    X = (xs + 0.5) / w * 2 - 1
    Y = ((ys + 0.5) / h * 2 - 1) * (H / W)
    warp = p.get("warp", 0.03)
    wx = fnoise(h, w, rng, 3.6) * warp
    wy = fnoise(h, w, rng, 3.6) * warp
    tilt = p.get("tilt", 0.0)
    c, s = np.cos(tilt), np.sin(tilt)
    st = p.get("stretch", 1.0)
    Xr = c * X + s * Y + wx
    Yr = (-s * X + c * Y + wy) / st
    jaw = p.get("jaw", 1.0)
    d1 = ellipse_d(Xr, Yr, 0, -0.30, 0.70, 0.76)
    d2 = ellipse_d(Xr, Yr, 0, 0.30, 0.50 * jaw, 0.84)
    d = smin(d1, d2, 6.0)
    head = smoothstep(1.05, 0.78, d)
    dome = np.sqrt(np.clip(1 - np.minimum(d, 1) ** 2, 0, 1))
    ex, ey, es = p.get("eye_x", 0.28), p.get("eye_y", -0.12), p.get("eye_size", 1.0)
    asym = p.get("asym", 0.0)

    hgt = dome * 0.9
    hgt += 0.10 * gauss(Xr, Yr, 0, -0.33, 0.48, 0.10)       # brow
    hgt += 0.16 * gauss(Xr, Yr, -0.40, 0.06, 0.14, 0.08)    # cheekbones
    hgt += 0.16 * gauss(Xr, Yr, 0.40, 0.06, 0.14, 0.08)
    hgt -= 0.26 * gauss(Xr, Yr, -0.32, 0.34, 0.11, 0.19)    # hollow cheeks
    hgt -= 0.26 * gauss(Xr, Yr, 0.32, 0.34, 0.11, 0.19)
    hgt -= 0.16 * gauss(Xr, Yr, -0.60, -0.18, 0.11, 0.18)   # temples
    hgt -= 0.16 * gauss(Xr, Yr, 0.60, -0.18, 0.11, 0.18)
    hgt += 0.10 * gauss(Xr, Yr, 0, -0.04, 0.05, 0.14)       # nose bridge
    hgt += 0.10 * gauss(Xr, Yr, 0, 1.0, 0.18, 0.10)         # chin
    hgt -= 0.26 * gauss(Xr, Yr, -ex, ey, 0.17 * es, 0.15 * es)
    hgt -= 0.26 * gauss(Xr, Yr, ex, ey + asym, 0.17 * es * (1 + 2 * asym), 0.15 * es * (1 + 3 * asym))
    hgt += 0.012 * fnoise(h, w, rng, 4.0)
    hgt = blur(hgt, 2.5 * ss)

    gy, gx = np.gradient(hgt)
    rel = p.get("relief", 0.6) * (w / 2)
    gx, gy = gx * rel, gy * rel
    inv = 1.0 / np.sqrt(1 + gx * gx + gy * gy)
    nx, ny, nz = -gx * inv, -gy * inv, inv
    light = np.array([p.get("lx", 0.10), -0.45, 0.89])
    light /= np.linalg.norm(light)
    lam = np.clip(nx * light[0] + ny * light[1] + nz * light[2], 0, 1)
    ao = np.clip(1 - 2.6 * (blur(hgt, 16 * ss) - hgt), 0.2, 1.0)
    lum = (0.02 + 0.78 * lam ** 2.6 + 0.22 * lam ** 18) * ao * (0.35 + 0.65 * dome)
    # a dim light on the middle of the face; the skull top and the edges sink into the dark
    lum *= 0.22 + 0.78 * gauss(X, Y, 0.0, p.get("spot_y", 0.08), 0.60, 0.62)

    sock = np.maximum(
        smoothstep(1.45, 0.45, ellipse_d(Xr, Yr, -ex, ey, 0.15 * es, 0.13 * es, 0.35)),
        smoothstep(1.45, 0.45, ellipse_d(Xr, Yr, ex, ey + asym, 0.15 * es * (1 + 2 * asym),
                                         0.13 * es * (1 + 3 * asym), -0.35)))
    nose = smoothstep(1.25, 0.30, ellipse_d(Xr, Yr, 0, 0.17, 0.045, 0.08))
    mx, my = 0.0, p.get("mouth_y", 0.64)
    mrx, mry, mrot = p.get("mouth_w", 0.18), p.get("mouth_h", 0.40), p.get("mouth_rot", 0.0)
    md = ellipse_d(Xr, Yr, mx, my, mrx, mry, mrot)
    mouth = smoothstep(1.06, 0.72, md + 0.05 * fnoise(h, w, rng, 3.0))
    lip = smoothstep(1.5, 1.0, md) * (1 - mouth)
    lum = lum * (1 - 0.985 * sock) * (1 - 0.9 * nose) * (1 - 0.55 * lip) * (1 - mouth)

    px = (mx + 1) / 2 * w
    py = (my * st / (H / W) + 1) / 2 * h
    teeth = needle_teeth(w, h, px, py, mrx * w / 2, mry * st * w / 2, mrot, rng, p.get("teeth", 8), w / 2)
    teeth = blur(teeth, 0.6 * ss)
    inner = smoothstep(1.06, 0.92, md)
    lum = np.maximum(lum, teeth * inner * p.get("teeth_lum", 0.6) * (0.6 + 0.4 * smoothstep(1.4, 0.4, Y)))

    hide = p.get("hide", 0.0)
    if hide:
        side = smoothstep(-0.35, 0.65, X * np.sign(hide))
        lum *= 1 - abs(hide) * side
        head = head * (1 - 0.8 * abs(hide) * side)
    low = smoothstep(1.50, 0.80, Y)
    top = smoothstep(-1.30, -0.40, Y)
    lum *= low * top ** 1.3
    alpha = head * (0.30 + 0.70 * low) * (0.20 + 0.80 * top)
    alpha = np.maximum(alpha, mouth * head * 0.95)
    lum *= 0.94 + 0.06 * np.clip(fnoise(h, w, rng, 0.2), -2, 2) / 2
    sm = p.get("smear", 0.18)
    if sm > 0:
        lum = lum * (1 - sm) + vblur(lum, 11 * ss) * sm
    gh = p.get("ghost", 0.0)
    if gh:
        sh = int(p.get("ghost_dx", 18) * ss)
        lum = np.maximum(lum, np.roll(lum, sh, axis=1) * gh)
        alpha = np.maximum(alpha, np.roll(alpha, sh, axis=1) * gh)
    lum = blur(lum, p.get("blur", 1.2) * ss)
    alpha = blur(alpha, 1.6 * ss)
    lum = downsample(lum, ss)
    alpha = downsample(alpha, ss)
    # keep the outermost pixels transparent so linear filtering never bleeds neighbours in
    alpha[:2, :] = 0
    alpha[-2:, :] = 0
    alpha[:, :2] = 0
    alpha[:, -2:] = 0

    eyes = []
    for sx_, dy_ in ((-ex, 0.0), (ex, asym)):
        yy = (ey + dy_) * st
        xr = c * sx_ - s * yy
        yr = s * sx_ + c * yy
        eyes.append([round(float((xr + 1) / 2 * W), 1), round(float((yr / (H / W) + 1) / 2 * H), 1)])
    return lum, alpha, eyes, 0.055 * W * es


FACE_VARIANTS = [
    dict(),
    dict(tilt=0.22, asym=0.05, mouth_rot=0.20, mouth_h=0.46, lx=-0.35),
    dict(stretch=1.10, jaw=0.80, mouth_w=0.10, mouth_h=0.56, mouth_y=0.70, teeth=10),
    dict(hide=0.9, lx=-0.7, smear=0.35),
    dict(mouth_w=0.40, mouth_h=0.14, mouth_y=0.52, teeth=7, eye_size=0.8),
    dict(eye_size=1.25, mouth_h=0.30, smear=0.45, blur=1.8, warp=0.05, ghost=0.5, ghost_dx=22),
]


# --------------------------------------------------------------------------------------------
# eyes, glints, mouths, silhouettes
# --------------------------------------------------------------------------------------------

def make_glint(kind, S=32, ss=4):
    """A glowing pupil: tiny hot core, soft halo; drawn additively by the menu."""
    w = S * ss
    ys, xs = np.mgrid[0:w, 0:w].astype(np.float32)
    X = (xs + 0.5) / w * 2 - 1
    Y = (ys + 0.5) / w * 2 - 1
    if kind == 0:
        core = np.exp(-(X ** 2 + Y ** 2) / (2 * 0.07 ** 2))
    elif kind == 1:  # vertical slit
        core = np.exp(-((X / 0.045) ** 2 + (Y / 0.20) ** 2) / 2)
    else:  # ring reflection
        r = np.sqrt(X ** 2 + Y ** 2)
        core = np.exp(-(r / 0.06) ** 2 / 2) + 0.45 * np.exp(-((r - 0.24) / 0.035) ** 2 / 2)
    halo = np.exp(-(X ** 2 + Y ** 2) / (2 * 0.30 ** 2)) * 0.30
    v = np.clip(core + halo, 0, 1) * smoothstep(1.0, 0.82, np.sqrt(X ** 2 + Y ** 2))
    v = downsample(v, ss)
    return np.ones_like(v), v


def make_eye_pair(seed, p, W=160, H=64, ss=2):
    """Two (or one, or three) reflective eyes in the dark, with a faint shine ring and lids."""
    rng = np.random.default_rng(seed)
    w, h = W * ss, H * ss
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    X = (xs + 0.5) / w * 2 - 1
    Y = ((ys + 0.5) / h * 2 - 1) * (H / W)
    glow = np.zeros_like(X)
    for (cx, cy, r, kind) in p["eyes"]:
        d = np.sqrt(((X - cx) / 1.0) ** 2 + ((Y - cy) / 0.62) ** 2)
        if kind == "slit":
            core = np.exp(-(((X - cx) / (0.012 * r / 0.08)) ** 2 + ((Y - cy) / (0.05 * r / 0.08)) ** 2) / 2)
        else:
            core = np.exp(-(((X - cx) ** 2 + (Y - cy) ** 2) / (2 * (0.016 * r / 0.08) ** 2)))
        iris = 0.30 * np.exp(-((d - r * 0.55) / (r * 0.12)) ** 2 / 2)
        eyeball = 0.17 * smoothstep(r * 1.15, r * 0.6, d)
        lid = smoothstep(r * 0.42 * p.get("open", 1.0), r * 0.30 * p.get("open", 1.0), np.abs(Y - cy))
        halo = 0.18 * np.exp(-(((X - cx) ** 2 + (Y - cy) ** 2) / (2 * (r * 0.9) ** 2)))
        glow = np.maximum(glow, (core + iris + eyeball) * lid + halo * 0.6)
    glow *= 0.9 + 0.1 * np.clip(fnoise(h, w, rng, 1.0), -2, 2) / 2
    glow = blur(glow, 0.8 * ss)
    v = downsample(np.clip(glow, 0, 1), ss)
    v[:1, :] = 0
    v[-1:, :] = 0
    v[:, :1] = 0
    v[:, -1:] = 0
    return np.ones_like(v), v


EYE_VARIANTS = [
    dict(eyes=[(-0.22, 0.0, 0.10, "round"), (0.22, 0.0, 0.10, "round")]),
    dict(eyes=[(-0.55, 0.0, 0.09, "round"), (0.55, 0.02, 0.09, "round")]),
    dict(eyes=[(-0.25, -0.03, 0.08, "round"), (0.22, 0.04, 0.13, "round")]),
    dict(eyes=[(-0.24, 0.0, 0.11, "slit"), (0.24, 0.0, 0.11, "slit")]),
    dict(eyes=[(0.0, 0.0, 0.16, "round")]),
    dict(eyes=[(-0.30, 0.06, 0.08, "round"), (0.30, 0.06, 0.08, "round"), (0.0, -0.10, 0.07, "round")]),
    dict(eyes=[(-0.10, 0.0, 0.06, "round"), (0.10, 0.0, 0.06, "round")]),
    dict(eyes=[(-0.28, 0.0, 0.12, "round"), (0.28, 0.0, 0.12, "round")], open=0.45),
]


def mouth_sdf(X, Y, p):
    """Signed distance (sprite pixels, negative inside) of a mouth opening centred on 0,0."""
    kind = p["kind"]
    if kind in ("grin", "slit"):
        hw = p["w"]
        u = np.clip(X / hw, -1.5, 1.5)
        mid = -p["curve"] * u * u
        thick = p["h"] * np.clip(1 - u * u, 0, 1) ** 0.75
        return np.maximum(np.abs(Y - mid) - thick, np.abs(X) - hw)
    rx, ry, rot = p["w"], p["h"], p.get("rot", 0.0)
    d = (ellipse_d(X, Y, 0, 0, rx, ry, rot) - 1) * min(rx, ry)
    if kind == "torn":
        for side in (-1, 1):
            d = smin_px(d, sd_capsule(X, Y, side * rx * 0.85, 0.0, side * rx * 1.9, -ry * 0.75, ry * 0.10, ry * 0.03), 3.0)
    return d


def make_mouth(seed, p, W=256, H=160, ss=2):
    """A mouth appearing out of nothing: a dim patch of skin lit from above, a black opening,
    a pale lower lip, a shadowed upper lip and faint needle teeth."""
    rng = np.random.default_rng(seed)
    w, h = W * ss, H * ss
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    X = (xs + 0.5) / ss - W / 2
    Y = (ys + 0.5) / ss - H / 2
    Xw = X + fnoise(h, w, rng, 3.4) * 2.2
    Yw = Y + fnoise(h, w, rng, 3.4) * 1.6
    d = mouth_sdf(Xw, Yw, p) + fnoise(h, w, rng, 3.0) * 0.9
    hole = smoothstep(1.2, -1.2, d)
    gy, gx = np.gradient(blur(d, 2.0 * ss))
    ny = gy / (np.sqrt(gx * gx + gy * gy) + 1e-6)
    lip = smoothstep(9.0, 1.0, d) * (1 - hole)
    lower = np.clip(ny, 0, 1)
    upper = np.clip(-ny, 0, 1)
    size = max(p["w"], p["h"])
    patch = gauss(X, Y, 0, p["h"] * 0.25, size * 1.15 + 18, p["h"] * 1.3 + 22)
    patch = patch * smoothstep(1.0, 0.55, ellipse_d(X, Y, 0, 0, W * 0.49, H * 0.47))
    lum = 0.16 * patch
    lum = lum + 0.34 * lip * lower * smoothstep(0.0, 4.0, d)
    lum = lum + 0.12 * lip * upper * smoothstep(3.0, 7.0, d)
    lum = lum * (1 - 0.75 * lip * upper * smoothstep(5.0, 0.5, d))
    lum = lum * (1 - hole)
    lum = lum * (0.9 + 0.1 * np.clip(fnoise(h, w, rng, 0.3), -2, 2) / 2)
    timg = Image.new("L", (w, h), 0)
    td = ImageDraw.Draw(timg)
    n = p.get("teeth", 10)
    for row in (-1, 1):
        for i in range(n):
            if rng.random() < 0.2:
                continue
            u = -0.88 + 1.76 * (i + 0.5 + rng.uniform(-0.3, 0.3)) / n
            if p["kind"] in ("grin", "slit"):
                x = u * p["w"]
                th = p["h"] * max(0.0, 1 - u * u) ** 0.75
                y0 = -p["curve"] * u * u + row * th
                ln = th * rng.uniform(0.45, 0.95)
            else:
                ang = np.pi * (0.5 + 0.40 * u)
                rx, ry, rot = p["w"], p["h"], p.get("rot", 0.0)
                ex, ey = -np.cos(ang) * rx, row * np.sin(ang) * ry
                c, s = np.cos(rot), np.sin(rot)
                x = c * ex - s * ey
                y0 = s * ex + c * ey
                ln = ry * rng.uniform(0.18, 0.45) * (0.4 + 0.6 * np.sin(ang))
            if rng.random() < 0.2:
                ln *= 0.5
            base = rng.uniform(0.9, 1.8)
            tipx = x + rng.uniform(-1.2, 1.2)
            tipy = y0 - row * ln
            pts = [((x - base + W / 2) * ss, (y0 + H / 2) * ss), ((x + base + W / 2) * ss, (y0 + H / 2) * ss),
                   ((tipx + W / 2) * ss, (tipy + H / 2) * ss)]
            td.polygon(pts, fill=int(rng.uniform(120, 215)))
    teeth = blur(np.asarray(timg).astype(np.float32) / 255.0, 0.55 * ss)
    lum = np.maximum(lum, teeth * smoothstep(1.5, -1.0, d) * 0.62)
    alpha = np.clip(np.maximum(patch * 0.80, hole * np.clip(patch * 1.6, 0, 1)), 0, 1)
    win = border_window(h, w, 26 * ss)
    alpha = alpha * win
    lum = lum * win
    lum = blur(lum, 0.9 * ss)
    alpha = blur(alpha, 1.2 * ss)
    lum = downsample(lum, ss)
    alpha = downsample(alpha, ss)
    alpha[:2, :] = 0
    alpha[-2:, :] = 0
    alpha[:, :2] = 0
    alpha[:, -2:] = 0
    return lum, alpha


MOUTH_VARIANTS = [
    dict(kind="gape", w=22, h=50, teeth=7),
    dict(kind="grin", w=104, h=13, curve=26, teeth=16),
    dict(kind="crooked", w=40, h=22, rot=0.35, teeth=8),
    dict(kind="slit", w=110, h=4.5, curve=20, teeth=18),
    dict(kind="torn", w=34, h=26, teeth=9),
]


def sd_capsule(X, Y, ax, ay, bx, by, r0, r1=None):
    r1 = r0 if r1 is None else r1
    pax, pay = X - ax, Y - ay
    bax, bay = bx - ax, by - ay
    t = np.clip((pax * bax + pay * bay) / (bax * bax + bay * bay), 0, 1)
    return np.sqrt((pax - bax * t) ** 2 + (pay - bay * t) ** 2) - (r0 + (r1 - r0) * t)


def sd_ellipse(X, Y, cx, cy, rx, ry, rot=0.0):
    return (ellipse_d(X, Y, cx, cy, rx, ry, rot) - 1) * min(rx, ry)


def make_silhouette(seed, kind, ss=2):
    """A dark figure standing in faint mist, dissolving into the dark at the bottom.
    Returns (lum, alpha, eye anchors, eye radius)."""
    rng = np.random.default_rng(seed)
    W, H = (256, 320) if kind.startswith("bust") else (160, 448)
    w, h = W * ss, H * ss
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    X = (xs + 0.5) / ss - W / 2 + fnoise(h, w, rng, 3.8) * 1.6
    Y = (ys + 0.5) / ss
    if kind.startswith("bust"):
        tilt = 0.0 if kind == "bust" else 0.20
        hx, hy = (6.0 if tilt else 0.0), 112.0
        hx, hy = (8.0 if tilt else 0.0), 104.0
        head = sd_ellipse(X, Y, hx, hy, 37, 49, tilt)
        neck = sd_capsule(X, Y, hx * 0.6, hy + 36, 0, 196, 15, 17)
        shoulders = sd_capsule(X, Y, -74, 226, 74, 226, 27, 27)
        chest = np.maximum(np.abs(X) - (100 + (Y - 226) * 0.06), 226 - Y)
        body = smin_px(shoulders, chest, 10.0)
        d = smin_px(smin_px(head, neck, 7.0), body, 22.0)
        eyes = [[W / 2 + hx - 14, hy - 2], [W / 2 + hx + 14, hy + 1]]
        er = 3.4
        fade = smoothstep(H * 1.02, H * 0.70, Y)
    else:
        lean = 0.0 if kind == "tall" else 7.0
        hx, hy = lean, 50.0
        head = sd_ellipse(X, Y, hx, hy, 13, 18, 0.12 if lean else 0.0)
        neck = sd_capsule(X, Y, hx, hy + 14, lean * 0.4, 92, 4.5, 6)
        torso = sd_capsule(X, Y, lean * 0.3, 96, 0, 230, 15, 9)
        shoulders = sd_capsule(X, Y, -21 + lean * 0.3, 98, 21 + lean * 0.3, 98, 6.5, 6.5)
        d = smin_px(smin_px(head, neck, 2.5), smin_px(torso, shoulders, 3.5), 3.5)
        for side in (-1, 1):
            sx = side * 21 + lean * 0.3
            ex, ey = sx + side * 8, 205
            arm = smin_px(sd_capsule(X, Y, sx, 100, ex, ey, 4.2, 3.2), sd_capsule(X, Y, ex, ey, ex - side * 1, 318, 3.2, 2.4), 1.5)
            for f in range(3):
                fx = ex - side + (f - 1) * 2.6
                arm = smin_px(arm, sd_capsule(X, Y, fx, 316, fx + side * (f - 1) * 1.5, 352 + f * 3, 1.1, 0.6), 1.0)
            leg = sd_capsule(X, Y, side * 7, 224, side * 10, 448, 6.5, 4.0)
            d = smin_px(d, smin_px(arm, leg, 2.0), 2.5)
        eyes = [[W / 2 + hx - 5, hy - 1], [W / 2 + hx + 5, hy]]
        er = 1.6
        fade = smoothstep(H * 0.98, H * 0.62, Y)
    m = blur(smoothstep(1.4, -1.4, d), 0.9 * ss)
    Xn = ((xs + 0.5) / w) * 2 - 1
    Yn = ((ys + 0.5) / h) * 2 - 1
    mist = smoothstep(1.0, 0.0, np.sqrt((Xn / 0.98) ** 2 + ((Yn + 0.12) / 0.92) ** 2)) ** 1.3
    mist = mist * (0.70 + 0.30 * np.clip(fnoise(h, w, rng, 3.0), -2, 2) / 2)
    rim = np.clip(blur(m, 2.2 * ss) - m, 0, 1) * 1.4
    lum = ((mist * 0.30 + rim * 0.22) * (1 - m) + m * 0.006) * fade
    alpha = np.clip(np.maximum(mist * 0.55, m * fade), 0, 1) * (0.25 + 0.75 * fade)
    lum = downsample(lum, ss)
    alpha = downsample(alpha, ss)
    alpha[:2, :] = 0
    alpha[-2:, :] = 0
    alpha[:, :2] = 0
    alpha[:, -2:] = 0
    eyes = [[round(float(e[0]), 1), round(float(e[1]), 1)] for e in eyes]
    return lum, alpha, eyes, float(er)


SILHOUETTE_KINDS = ["bust", "bust_tilt", "tall", "tall_lean"]


# --------------------------------------------------------------------------------------------
# tileable textures
# --------------------------------------------------------------------------------------------

def make_grain(S=256):
    rng = np.random.default_rng(404)
    n = rng.standard_normal((S, S)).astype(np.float32)
    n = 0.7 * n + 0.3 * blur(n, 0.7, wrap=True) / 0.35
    v = np.clip(0.36 + 0.22 * n, 0, 1)
    specks = rng.random((S, S)) < 0.004
    v[specks] = np.clip(v[specks] + 0.45, 0, 1)
    # a few faint horizontal scan streaks
    for y in rng.integers(0, S, 6):
        v[y, :] = np.clip(v[y, :] * 1.18, 0, 1)
    return v, np.ones_like(v)


def make_fog(S=256):
    rng = np.random.default_rng(1313)
    n = fnoise(S, S, rng, 2.8) * 0.65 + fnoise(S, S, rng, 2.2) * 0.35
    n = (n - n.min()) / (n.max() - n.min())
    a = smoothstep(0.35, 0.95, n) ** 1.4
    return np.ones_like(a), a


def make_vignette(S=256):
    ys, xs = np.mgrid[0:S, 0:S].astype(np.float32)
    X = (xs + 0.5) / S * 2 - 1
    Y = (ys + 0.5) / S * 2 - 1
    r = np.sqrt((X * 0.92) ** 2 + (Y * 1.0) ** 2)
    a = smoothstep(0.30, 1.30, r) ** 1.2 * 0.95
    return np.zeros_like(a), a


# --------------------------------------------------------------------------------------------
# glyphs
# --------------------------------------------------------------------------------------------

GLYPH_ROWS = [
    "ABCDEFGHIJKLMNOP",
    "QRSTUVWXYZ012345",
    "6789.,:;!?-'\"()/",
    "#%&+=_*<>[]@",
]
CELL_W, CELL_H = 128, 160
BASELINE = 124
CAP = 88
GLYPH_PAD = 6
GLYPH_SS = 4


def find_font():
    for f in SERIF_FONTS:
        if pathlib.Path(f).is_file():
            return f
    raise SystemExit("no serif font found in /usr/share/fonts/truetype")


def glyph_mask(font_path, ch, condense=0.86):
    size = 100
    for _ in range(6):
        f = ImageFont.truetype(font_path, size)
        bb = f.getbbox("H")
        size = int(round(size * CAP * GLYPH_SS / (bb[3] - bb[1])))
    f = ImageFont.truetype(font_path, size)
    W, H = CELL_W * GLYPH_SS * 2, CELL_H * GLYPH_SS
    img = Image.new("L", (W, H), 0)
    ImageDraw.Draw(img).text((8 * GLYPH_SS, BASELINE * GLYPH_SS), ch, font=f, fill=255, anchor="ls")
    a = np.asarray(img).astype(np.float32) / 255.0
    cols = np.where(a.max(axis=0) > 0.01)[0]
    out = np.zeros((CELL_H * GLYPH_SS, CELL_W * GLYPH_SS), np.float32)
    if len(cols) == 0:
        return out
    x0, x1 = cols[0], cols[-1] + 1
    glyph = Image.fromarray((a[:, x0:x1] * 255).astype(np.uint8))
    glyph = glyph.resize((max(1, int(round((x1 - x0) * condense))), H), Image.LANCZOS)
    g = np.asarray(glyph).astype(np.float32) / 255.0
    pad = GLYPH_PAD * GLYPH_SS
    room = CELL_W * GLYPH_SS - pad - 4 * GLYPH_SS
    if g.shape[1] > room:
        raise SystemExit(f"glyph {ch!r} is too wide for its cell ({g.shape[1]} > {room})")
    out[:, pad:pad + g.shape[1]] = g
    return out


def distress(mask, rng, scratches=True):
    """Old, worn print: rough eroded edges, pits, specks, a scratch or two, ink bleed, grain."""
    h, w = mask.shape
    ss = GLYPH_SS
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    amp = 0.9 * ss
    m = sample(mask, xs + fnoise(h, w, rng, 2.9) * amp, ys + fnoise(h, w, rng, 2.9) * amp)
    fine = blur(fnoise(h, w, rng, 1.0), 0.8 * ss)
    fine = fine / (fine.std() + 1e-9)
    m = smoothstep(0.40, 0.60, m + fine * 0.07)
    edge = np.clip(1 - np.abs(blur(m, 3 * ss) - 0.5) * 2, 0, 1)
    m = m * (1 - smoothstep(1.7, 2.1, fnoise(h, w, rng, 2.4) + edge * 0.5) * 0.95)
    m = m * (1 - smoothstep(2.6, 3.0, fnoise(h, w, rng, 0.8)) * 0.9)
    sc = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(sc)
    for _ in range(int(rng.integers(0, 3)) if scratches else 0):
        x = rng.uniform(0, w)
        y = rng.uniform(0, h)
        ang = rng.uniform(-1.3, 1.3) + (np.pi / 2 if rng.random() < 0.6 else 0)
        ln = rng.uniform(0.3, 1.0) * h
        d.line([(x - np.cos(ang) * ln / 2, y - np.sin(ang) * ln / 2), (x + np.cos(ang) * ln / 2, y + np.sin(ang) * ln / 2)],
               fill=int(rng.uniform(150, 255)), width=int(rng.integers(2, 4)))
    m = m * (1 - np.asarray(sc).astype(np.float32) / 255.0 * 0.92)
    bleed = blur(m, 2.2 * ss) * 0.22
    grain = 0.80 + 0.20 * np.clip(blur(fnoise(h, w, rng, 1.4), 0.6 * ss) * 0.5 + 0.5, 0, 1)
    streak = 1 - 0.08 * np.clip(fnoise(1, w, rng, 1.0), 0, 3)[0][None, :]
    lum = np.clip(m * grain * streak + bleed * 0.6, 0, 1)
    alpha = np.clip(np.maximum(m, bleed), 0, 1)
    lum = downsample(lum, ss)
    alpha = downsample(alpha, ss)
    alpha[alpha < 4 / 255] = 0
    alpha[:, :2] = 0
    alpha[:, -2:] = 0
    alpha[:1, :] = 0
    alpha[-1:, :] = 0
    return lum, alpha


def build_glyphs():
    font_path = find_font()
    rng = np.random.default_rng(1999)
    cols = 16
    rows = len(GLYPH_ROWS)
    lum = np.zeros((rows * CELL_H, cols * CELL_W), np.float32)
    alpha = np.zeros_like(lum)
    chars, advance, left = [], [], []
    for r, row in enumerate(GLYPH_ROWS):
        for c, ch in enumerate(row):
            clean = glyph_mask(font_path, ch)
            small = downsample(clean, GLYPH_SS) > 0.5
            clean_ink = float(small.sum())
            ink_cols = np.where(small.any(axis=0))[0]
            narrow = len(ink_cols) < 40
            for _attempt in range(10):
                gl, ga = distress(clean, rng, scratches=not narrow)
                if clean_ink == 0 or float((ga > 0.5).sum()) / clean_ink >= (0.9 if narrow else 0.85):
                    break
            lum[r * CELL_H:(r + 1) * CELL_H, c * CELL_W:(c + 1) * CELL_W] = gl
            alpha[r * CELL_H:(r + 1) * CELL_H, c * CELL_W:(c + 1) * CELL_W] = ga
            ink = np.where(ga.max(axis=0) > 0.02)[0]
            lft = int(ink[0]) if len(ink) else 0
            rgt = int(ink[-1]) + 1 if len(ink) else 0
            chars.append(ch)
            left.append(lft)
            advance.append(rgt - lft)
    meta = {
        "texture": "null:textures/font/horror.png",
        "width": cols * CELL_W,
        "height": rows * CELL_H,
        "cell_w": CELL_W,
        "cell_h": CELL_H,
        "columns": cols,
        "baseline": BASELINE,
        "cap_top": BASELINE - CAP,
        "space": int(CAP * 0.42),
        "chars": "".join(chars),
        "left": left,
        "advance": advance,
    }
    return lum, alpha, meta


def font_definition():
    grid = [row + "\u0000" * (16 - len(row)) for row in GLYPH_ROWS]
    height = 13
    return {
        "providers": [
            {"type": "space", "advances": {" ": 4}},
            {
                "type": "bitmap",
                "file": "null:font/horror.png",
                "height": height,
                "ascent": int(round(BASELINE / CELL_H * height)),
                "chars": grid,
            },
            {"type": "reference", "id": "minecraft:default"},
        ]
    }


# --------------------------------------------------------------------------------------------
# atlas packing
# --------------------------------------------------------------------------------------------

def pack(items, width):
    """Shelf packer: items = list of (key, w, h). Returns {key: (x, y)}, total height."""
    order = sorted(items, key=lambda it: -it[2])
    pos = {}
    x = y = shelf = 0
    for key, w, h in order:
        if x + w > width:
            y += shelf
            x = shelf = 0
        pos[key] = (x, y)
        x += w
        shelf = max(shelf, h)
    total = y + shelf
    return pos, ((total + 63) // 64) * 64


def build_atlas():
    sprites = {}
    faces = []
    for i, p in enumerate(FACE_VARIANTS):
        lum, alpha, eyes, er = make_face(100 + i, p)
        sprites[("face", i)] = (lum, alpha)
        faces.append({"eyes": eyes, "eye_size": round(float(er), 2)})
    for i, p in enumerate(EYE_VARIANTS):
        sprites[("eyes", i)] = make_eye_pair(300 + i, p)
    for i in range(3):
        sprites[("glint", i)] = make_glint(i)
    for i, p in enumerate(MOUTH_VARIANTS):
        sprites[("mouth", i)] = make_mouth(500 + i, p)
    silhouettes = []
    for i, kind in enumerate(SILHOUETTE_KINDS):
        lum, alpha, eyes, er = make_silhouette(700 + i, kind)
        sprites[("silhouette", i)] = (lum, alpha)
        silhouettes.append({"eyes": eyes, "eye_size": round(er, 2)})
    width = 2048
    pos, height = pack([(k, v[0].shape[1], v[0].shape[0]) for k, v in sprites.items()], width)
    lum = np.zeros((height, width), np.float32)
    alpha = np.zeros_like(lum)
    meta = {"faces": [], "eyes": [], "glints": [], "mouths": [], "silhouettes": []}
    groups = {"face": "faces", "eyes": "eyes", "glint": "glints", "mouth": "mouths", "silhouette": "silhouettes"}
    for (kind, i), (l, a) in sorted(sprites.items(), key=lambda kv: (kv[0][0], kv[0][1])):
        x, y = pos[(kind, i)]
        h, w = l.shape
        lum[y:y + h, x:x + w] = l
        alpha[y:y + h, x:x + w] = a
        entry = {"x": x, "y": y, "w": w, "h": h}
        if kind == "face":
            entry.update(faces[i])
        elif kind == "silhouette":
            entry.update(silhouettes[i])
        meta[groups[kind]].append(entry)
    return lum, alpha, width, height, meta


# --------------------------------------------------------------------------------------------
# preview (approximates the in-game composition)
# --------------------------------------------------------------------------------------------

def composite(dst, lum, alpha, x, y, scale=1.0, opacity=1.0, additive=False):
    src_l = Image.fromarray((np.clip(lum, 0, 1) * 255).astype(np.uint8))
    src_a = Image.fromarray((np.clip(alpha, 0, 1) * 255).astype(np.uint8))
    if scale != 1.0:
        size = (max(1, int(lum.shape[1] * scale)), max(1, int(lum.shape[0] * scale)))
        src_l = src_l.resize(size, Image.BILINEAR)
        src_a = src_a.resize(size, Image.BILINEAR)
    l = np.asarray(src_l).astype(np.float32) / 255.0
    a = np.asarray(src_a).astype(np.float32) / 255.0 * opacity
    h, w = l.shape
    x, y = int(x), int(y)
    x0, y0 = max(0, x), max(0, y)
    x1, y1 = min(dst.shape[1], x + w), min(dst.shape[0], y + h)
    if x0 >= x1 or y0 >= y1:
        return
    ls = l[y0 - y:y1 - y, x0 - x:x1 - x]
    as_ = a[y0 - y:y1 - y, x0 - x:x1 - x]
    if additive:
        dst[y0:y1, x0:x1] = np.clip(dst[y0:y1, x0:x1] + ls * as_, 0, 1)
    else:
        dst[y0:y1, x0:x1] = dst[y0:y1, x0:x1] * (1 - as_) + ls * as_


def preview(out_dir, atlas, glyphs):
    out_dir.mkdir(parents=True, exist_ok=True)
    a_lum, a_alpha, aw, ah, ameta = atlas
    g_lum, g_alpha, gmeta = glyphs
    # contact sheets on dark grey, brightened copy for judging shapes
    bg = np.full(a_lum.shape, 0.04, np.float32)
    composite(bg, a_lum, a_alpha, 0, 0)
    Image.fromarray((bg * 255).astype(np.uint8)).save(out_dir / "atlas_sheet.png")
    Image.fromarray((np.clip(bg * 2.5, 0, 1) * 255).astype(np.uint8)).save(out_dir / "atlas_sheet_bright.png")
    gbg = np.full(g_lum.shape, 0.03, np.float32)
    composite(gbg, g_lum, g_alpha, 0, 0)
    Image.fromarray((gbg * 255).astype(np.uint8)).save(out_dir / "glyph_sheet.png")

    # full-screen mock-up at 1920x1080 (GUI scale 2 -> 960x540 GUI units)
    W, H = 1920, 1080
    scr = np.full((H, W), 0.022, np.float32)
    fog_l, fog_a = make_fog()
    fog_big_l = np.asarray(Image.fromarray((fog_a * 255).astype(np.uint8)).resize((W, H), Image.BILINEAR)).astype(np.float32) / 255.0
    scr = scr * (1 - fog_big_l * 0.07) + fog_big_l * 0.07 * 0.55

    def sprite(group, idx):
        e = ameta[group][idx]
        return (a_lum[e["y"]:e["y"] + e["h"], e["x"]:e["x"] + e["w"]],
                a_alpha[e["y"]:e["y"] + e["h"], e["x"]:e["x"] + e["w"]], e)

    rng = np.random.default_rng(5)
    placements = [("faces", 0, 1180, 120, 1.55, 0.30), ("faces", 2, 1560, 420, 1.2, 0.22),
                  ("faces", 3, 760, 520, 1.3, 0.16), ("faces", 5, 1450, -60, 0.9, 0.12),
                  ("silhouettes", 2, 1000, 330, 1.6, 0.55), ("mouths", 1, 1300, 820, 1.0, 0.30)]
    for group, idx, x, y, sc, op in placements:
        l, a, e = sprite(group, idx)
        composite(scr, l, a, x, y, sc, op)
        if "eyes" in e and group == "faces":
            gl, ga, ge = sprite("glints", 0)
            for ex, ey in e["eyes"]:
                gs = e["eye_size"] * sc * 2.4 / ge["w"]
                composite(scr, gl, ga, x + ex * sc - ge["w"] * gs / 2, y + ey * sc - ge["h"] * gs / 2, gs, 0.85, True)
    for idx, (x, y, sc) in enumerate([(1700, 260, 0.9), (820, 900, 0.7), (1840, 760, 0.6)]):
        l, a, e = sprite("eyes", idx)
        composite(scr, l, a, x, y, sc, 0.8, True)
    vig_l, vig_a = make_vignette()
    vig = np.asarray(Image.fromarray((vig_a * 255).astype(np.uint8)).resize((W, H), Image.BILINEAR)).astype(np.float32) / 255.0
    scr = scr * (1 - vig)

    # logo: THE / CORRUPTED / DIRECTORY, left aligned
    def run_width(text, size, tracking):
        wsum = 0.0
        for ch in text:
            if ch == " ":
                wsum += gmeta["space"] * size / CELL_H
                continue
            i = gmeta["chars"].index(ch)
            wsum += gmeta["advance"][i] * size / CELL_H
        return wsum + tracking * size * (len(text) - 1)

    def draw_run(text, x, y_cap_top, size, tracking, opacity, red_index=-1):
        pen = x
        scale = size / CELL_H
        for k, ch in enumerate(text):
            if ch == " ":
                pen += gmeta["space"] * scale + tracking * size
                continue
            i = gmeta["chars"].index(ch)
            col, row = i % gmeta["columns"], i // gmeta["columns"]
            cl = g_lum[row * CELL_H:(row + 1) * CELL_H, col * CELL_W:(col + 1) * CELL_W]
            ca = g_alpha[row * CELL_H:(row + 1) * CELL_H, col * CELL_W:(col + 1) * CELL_W]
            if k == red_index:
                cl = cl * 0.55
            composite(scr, cl * 0.92, ca, pen - gmeta["left"][i] * scale, y_cap_top - gmeta["cap_top"] * scale, scale, opacity)
            pen += gmeta["advance"][i] * scale + tracking * size

    mx, my = 120, 100
    big = 132  # cell height in px for CORRUPTED
    cap = CAP / CELL_H
    w_c = run_width("CORRUPTED", big, 0.02)
    small = big * 0.40
    draw_run("THE", mx, my, small, 0.10, 0.78)
    y = my + small * cap + big * 0.10
    draw_run("CORRUPTED", mx, y, big, 0.02, 0.95, red_index=4)
    y += big * cap + big * 0.13
    dsize = big * 0.42
    natural = run_width("DIRECTORY", dsize, 0.0)
    tr = max(0.15, (w_c - natural) / (8 * dsize))
    draw_run("DIRECTORY", mx, y, dsize, tr, 0.80)
    y += dsize * cap
    # buttons
    by = int(y + 70)
    img = Image.fromarray((np.clip(scr, 0, 1) * 255).astype(np.uint8)).convert("RGB")
    d = ImageDraw.Draw(img)
    try:
        ui = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf", 18)
    except OSError:
        ui = ImageFont.load_default()
    for k, label in enumerate(["Singleplayer", "Multiplayer", "Options", "Quit Game"]):
        y0 = by + k * 48
        hover = k == 0
        d.rectangle([mx, y0, mx + 400, y0 + 40], outline=(235, 235, 235) if hover else (130, 130, 130), width=2,
                    fill=(22, 22, 22) if hover else (6, 6, 6))
        d.text((mx + 16 + (6 if hover else 0), y0 + 10), label, fill=(255, 255, 255) if hover else (200, 200, 200), font=ui)
    d.text((W - 150, 30), "Mods   NULL", fill=(110, 110, 110), font=ui)
    d.text((8, H - 50), "Minecraft 1.21.1\nNeoForge 21.1.265 (40 mods)", fill=(80, 80, 80), font=ui)
    d.text((W - 470, H - 26), "Copyright Mojang AB. Do not distribute!", fill=(80, 80, 80), font=ui)
    img.save(out_dir / "menu_mockup.png")


# --------------------------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", type=pathlib.Path, default=None)
    args = ap.parse_args()

    g_lum, g_alpha, gmeta = build_glyphs()
    save_png(to_rgba(g_lum, g_alpha), FONT_TEX_DIR / "horror.png", {"texture": {"blur": True, "clamp": True}})
    FONT_DEF_DIR.mkdir(parents=True, exist_ok=True)
    (FONT_DEF_DIR / "horror.json").write_text(json.dumps(font_definition(), indent=2, ensure_ascii=True) + "\n", encoding="utf-8")

    a_lum, a_alpha, aw, ah, ameta = build_atlas()
    save_png(to_rgba(a_lum, a_alpha), MENU_DIR / "apparitions.png", {"texture": {"blur": True, "clamp": True}})
    gl, ga = make_grain()
    save_png(to_rgba(gl, ga), MENU_DIR / "grain.png", None)
    fl, fa = make_fog()
    save_png(to_rgba(fl, fa), MENU_DIR / "fog.png", {"texture": {"blur": True, "clamp": False}})
    vl, va = make_vignette()
    save_png(to_rgba(vl, va), MENU_DIR / "vignette.png", {"texture": {"blur": True, "clamp": True}})

    art = {
        "version": 1,
        "atlas": {"texture": "null:textures/gui/menu/apparitions.png", "width": aw, "height": ah},
        "grain": {"texture": "null:textures/gui/menu/grain.png", "size": 256},
        "fog": {"texture": "null:textures/gui/menu/fog.png", "size": 256},
        "vignette": {"texture": "null:textures/gui/menu/vignette.png"},
        **ameta,
        "glyphs": gmeta,
    }
    (MENU_DIR / "art.json").write_text(json.dumps(art, indent=1) + "\n", encoding="utf-8")
    print(f"glyphs {gmeta['width']}x{gmeta['height']}, atlas {aw}x{ah}: "
          f"{len(ameta['faces'])} faces, {len(ameta['eyes'])} eye pairs, {len(ameta['glints'])} glints, "
          f"{len(ameta['mouths'])} mouths, {len(ameta['silhouettes'])} silhouettes")
    if args.preview:
        preview(args.preview, (a_lum, a_alpha, aw, ah, ameta), (g_lum, g_alpha, gmeta))
        print(f"previews -> {args.preview}")


if __name__ == "__main__":
    main()
