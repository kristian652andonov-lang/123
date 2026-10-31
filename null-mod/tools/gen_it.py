#!/usr/bin/env python3
"""IT: the model, its textures and the offline previews (deterministic, original art).

This script is the single source of truth for IT's geometry. It
  * describes every part of the model (pivots, rest rotations, boxes, materials),
  * packs the boxes onto a 256x256 texture and paints them,
  * writes src/main/java/dev/kristian/nullmod/client/it/ItModelLayer.java (the LayerDefinition,
    generated: never edit it by hand),
  * writes the textures into src/main/resources/assets/null/textures/:
      entity/it/it.png        wet greys, black, bone and sores (cutout: torn holes and jagged teeth)
      entity/it/it_glow.png   emissive layer: the eyes and the dim red inside of the chest
      gui/it/kill_vignette.png  the dark red vignette the victim sees
  * and with --preview DIR renders what it made: the unwrapped texture with every box outlined, and
    the model itself (software rasteriser, Minecraft's entity lighting, cutout, emissive layer) in
    its rest pose and in the poses ItModel animates, lit and in near-total darkness.

usage: python3 tools/gen_it.py [--preview DIR]
"""
import argparse
import math
import pathlib

import numpy as np
from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent.parent
TEX_OUT = ROOT / "src" / "main" / "resources" / "assets" / "null" / "textures" / "entity" / "it"
GUI_OUT = ROOT / "src" / "main" / "resources" / "assets" / "null" / "textures" / "gui" / "it"
JAVA_OUT = ROOT / "src" / "main" / "java" / "dev" / "kristian" / "nullmod" / "client" / "it" / "ItModelLayer.java"
TEX_W = 256
TEX_H = 256
SEED = 0x49545F48  # "IT_H"


# ====================================================================== the model

class Box:
    def __init__(self, x, y, z, w, h, d, material):
        for value in (w, h, d):
            assert float(value).is_integer() and value >= 1, "box sizes must be whole pixels"
        self.x, self.y, self.z = float(x), float(y), float(z)
        self.w, self.h, self.d = int(w), int(h), int(d)
        self.material = material
        self.u = self.v = None


class Part:
    def __init__(self, name, parent, pivot, rot, boxes, java_name=None):
        self.name = name                # unique id in this script
        self.parent = parent            # Part or None (root)
        self.java_name = java_name or name.split("/")[-1]
        self.pivot = tuple(float(c) for c in pivot)
        self.rot = tuple(float(c) for c in rot)
        self.boxes = [Box(*b) for b in boxes]
        self.children = []
        if parent is not None:
            assert all(c.java_name != self.java_name for c in parent.children), f"duplicate child {self.java_name}"
            parent.children.append(self)


PARTS = []
BY_NAME = {}


def part(name, parent, pivot, rot, boxes, java_name=None):
    p = Part(name, BY_NAME[parent] if parent else None, pivot, rot, boxes, java_name)
    PARTS.append(p)
    BY_NAME[name] = p
    return p


def chain(name, parent, pivot, rot, lengths, thickness, curl, material, axis="z"):
    """A tentacle: segments each the child of the one before (child name "s"), so bending each a
    little curls the whole chain. axis z: the chain grows along +Z; y: along +Y (hanging)."""
    prev = parent
    for k, (length, t) in enumerate(zip(lengths, thickness)):
        half = t / 2.0
        if axis == "z":
            box = (-half, -half, -0.5, t, t, length + 1, material)
            seg_pivot = pivot if k == 0 else (0.0, 0.0, lengths[k - 1] - 0.5)
        else:
            box = (-half, -0.5, -half, t, length + 1, t, material)
            seg_pivot = pivot if k == 0 else (0.0, lengths[k - 1] - 0.5, 0.0)
        seg_rot = rot if k == 0 else curl
        seg_name = name if k == 0 else f"{name}/s{k}"
        part(seg_name, prev, seg_pivot, seg_rot, [box], java_name=None if k == 0 else "s")
        prev = seg_name


def build_model():
    # Model space (pixels): +X is IT's left, +Y is down, -Z is its front; the ground is y = 24.
    part("hips", None, (0, -38, 0), (0, 0, 0), [
        (-7, -4, -4.5, 14, 7, 9, "flesh"),
        (-9.5, -2.5, -2, 3, 4, 4, "bone"),
        (6.5, -2, -2, 3, 4, 4, "bone"),
        (-2, -3, 4, 4, 3, 2, "bone"),
    ])
    # Legs: long, digitigrade, one joint too many. The right one is broken at the shin.
    for side, sx in (("l", 1), ("r", -1)):
        part(f"thigh_{side}", "hips", (6 * sx, 1, 0.5), (-0.45, 0, -0.08 * sx), [
            (-2.5, -1, -2.5, 5, 25, 5, "flesh"),
            (-1.5, 19, -4, 3, 4, 2, "bone"),
            (-3 if sx > 0 else 1, 4, -1.5, 2, 12, 3, "sinew"),
        ])
        part(f"shin_{side}", f"thigh_{side}", (0, 24, 0), (1.05, 0, 0), [
            (-2, -1, -2, 4, 27, 4, "sinew"),
            (-1, -2, 1.5, 2, 6, 3, "bone"),
        ] + ([(-2.5, 11, -2.5, 5, 3, 5, "bone")] if sx < 0 else []))
        part(f"cannon_{side}", f"shin_{side}", (0, 26, 0), (-0.75, 0, 0), [
            (-1.5, -1, -1.5, 3, 19, 3, "bone"),
        ])
        part(f"foot_{side}", f"cannon_{side}", (0, 18, 0), (0.15, 0, 0), [
            (-2.5, -1, -7, 5, 3, 9, "black"),
            (-2.5, 0, -11, 1, 2, 4, "blade"),
            (1.5, 0, -11, 1, 2, 4, "blade"),
            (-0.5, 0, -12, 1, 2, 5, "blade"),
            (-0.5, -2, 1, 1, 3, 3, "bone"),
        ])
    # Spine: bare vertebrae and a few strands of what used to be a belly.
    part("spine", "hips", (0, -4, 1.5), (0.35, 0, 0), [
        (-2, -17, -2, 4, 17, 4, "bone"),
        (-0.5, -16, 2, 1, 2, 3, "bone"),
        (-0.5, -11, 2, 1, 2, 3, "bone"),
        (-0.5, -6, 2, 1, 2, 3, "bone"),
        (-5, -13, -1.5, 2, 9, 2, "sinew"),
        (3, -15, -1, 2, 7, 2, "sinew"),
        (-1, -9, -4, 2, 6, 1, "sinew"),
    ])
    # The chest: caved in and open at the front, a back plate, broken side walls, the collar bar
    # the arms hang from, and the dark wet inside.
    part("chest", "spine", (0, -16, 0), (0.25, 0, 0), [
        (-11, -21, 2, 22, 21, 5, "back"),
        (8, -20, -4, 3, 15, 6, "flesh"),
        (-11, -17, -3, 3, 10, 5, "flesh"),
        (-15, -24, -2, 30, 3, 6, "bone"),
        (-6, -17, -1, 12, 13, 3, "cavity"),
        (-2, -4, -3, 4, 4, 5, "flesh"),
    ])
    part("sternum", "chest", (0, -21, -4), (0.6, 0, 0.35), [(-1, 0, -1, 2, 8, 2, "bone")])
    # Ribs splayed open like a burst cage; two segments each so they curve. Three on the right,
    # one of them snapped short.
    rib_rows = [(-19, 0.0), (-15, 0.12), (-11, 0.24), (-7, 0.36)]
    for k, (y, drop) in enumerate(rib_rows):
        part(f"rib_l{k}", "chest", (7.5, y, -3), (drop - 0.1, -0.55 - 0.1 * k, 0.15), [(-1, -1, -10, 2, 2, 10, "bone")])
        part(f"rib_l{k}/tip", f"rib_l{k}", (0, 0, -9.5), (0.2, -0.65, 0), [(-0.5, -0.5, -6, 1, 1, 6, "bone")], "tip")
    for k, (y, drop) in enumerate(rib_rows[:3]):
        part(f"rib_r{k}", "chest", (-7.5, y, -3), (drop - 0.05, 0.6 + 0.12 * k, -0.15), [(-1, -1, -10, 2, 2, 10, "bone")])
        if k != 1:
            part(f"rib_r{k}/tip", f"rib_r{k}", (0, 0, -9.5), (0.25, 0.6, 0), [(-0.5, -0.5, -6, 1, 1, 6, "bone")], "tip")
    # Tentacles out of the open chest (forward, drooping) and out of the back (rising, arching).
    chest_t = [((-3, -12, -1), (0.35, -0.35, 0)), ((3, -9, -1), (0.45, 0.4, 0)), ((0, -15, -1), (0.1, 0.05, 0))]
    for i, (pivot, rot) in enumerate(chest_t):
        chain(f"ct{i}", "chest", pivot, (-rot[0], math.pi + rot[1], rot[2]), [6, 6, 6, 6, 5, 5], [3, 3, 2, 2, 2, 1],
              (-0.15, 0.06 * (1 if i % 2 else -1), 0), "tentacle")
    back_t = [((-6, -19, 6), (0.85, -0.3, 0.1)), ((6, -17, 6), (0.95, 0.35, -0.1)),
              ((-4, -9, 6), (0.55, -0.55, 0.0)), ((7, -8, 6), (0.6, 0.6, 0.0))]
    for i, (pivot, rot) in enumerate(back_t):
        chain(f"bt{i}", "chest", pivot, rot, [8, 8, 8, 8, 7, 7, 6], [4, 4, 3, 3, 3, 2, 1],
              (-0.16, 0.04 * (1 if i % 2 else -1), 0), "tentacle")
    # Neck and the small head with its hanging jaw.
    part("neck", "chest", (0, -22, -4), (0.55, 0, 0), [
        (-2, -11, -2, 4, 11, 4, "sinew"),
        (-1, -10, 2, 2, 2, 2, "bone"),
        (-1, -5, 2, 2, 2, 2, "bone"),
    ])
    part("head", "neck", (0, -10, -1), (-0.85, 0, 0), [
        (-4, -8, -6, 8, 8, 8, "head"),
        (-4.5, -9, -7, 9, 2, 3, "brow"),
        (-3, 0, -6, 6, 2, 1, "teeth"),
        (2.5, -6, -2, 2, 3, 3, "flesh"),
    ])
    part("jaw", "head", (0, 0, 1), (0.35, 0, 0), [
        (-3.5, 0, -7, 7, 2, 7, "jaw"),
        (-3, -2, -7, 6, 2, 1, "teeth"),
    ])
    # Arms: far too long, hanging past the knees; bone blades for fingers.
    for side, sx in (("l", 1), ("r", -1)):
        part(f"shoulder_{side}", "chest", (14 * sx, -21, 0), (0, 0, 0), [(-3 if sx > 0 else -2, -3, -3, 5, 5, 6, "bone")])
        part(f"arm_{side}", f"shoulder_{side}", (1.5 * sx, 0, 0), (-0.15, 0, -0.12 * sx), [
            (-2.5, -2, -2.5, 5, 33, 5, "flesh"),
            (-1, 26, 2, 2, 3, 4, "bone"),
        ])
        part(f"fore_{side}", f"arm_{side}", (0, 31, 0), (-0.3, 0, 0), [
            (-2, -1, -2, 4, 31, 4, "sinew"),
            (-1, 9, -3, 2, 7, 1, "bone"),
        ])
        part(f"wrist_{side}", f"fore_{side}", (0, 30, 0), (-0.1, 0, 0), [(-1.5, -1, -1.5, 3, 11, 3, "bone")])
        part(f"hand_{side}", f"wrist_{side}", (0, 10, 0), (0, 0, 0), [(-3, -1, -2, 6, 7, 4, "black")])
        for k in range(4):
            name = f"finger_{side}{k}"
            part(name, f"hand_{side}", (-2.25 + 1.5 * k, 5.5, -0.5), (-0.25, 0, (k - 1.5) * 0.1 * sx), [(-0.5, 0, -0.5, 1, 10, 1, "blade")],
                 f"finger{k}")
            part(f"{name}/tip", name, (0, 9.5, 0), (-0.35, 0, 0), [(-0.5, 0, -0.5, 1, 8, 1, "blade")], "tip")


build_model()


# ====================================================================== UV packing

def pack():
    boxes = [(p, b) for p in PARTS for b in p.boxes]
    order = sorted(boxes, key=lambda pb: (-(pb[1].h + pb[1].d), -(pb[1].w + pb[1].d)))
    x = y = row = 0
    for p, b in order:
        bw = 2 * (b.w + b.d)
        bh = b.h + b.d
        if x + bw > TEX_W:
            x = 0
            y += row + 1
            row = 0
        b.u, b.v = x, y
        x += bw + 1
        row = max(row, bh)
        assert y + bh <= TEX_H, "the model does not fit on the texture"
    # No two boxes share a texel.
    used = np.zeros((TEX_H, TEX_W), dtype=bool)
    for p, b in boxes:
        for (u0, v0, uw, vh) in face_rects(b).values():
            region = used[v0:v0 + vh, u0:u0 + uw]
            assert not region.any(), f"overlapping UVs at {p.name}"
            region[:] = True


def face_rects(b):
    """The six faces' texture rectangles (u, v, width, height), as ModelPart.Cube lays them out."""
    u, v, w, h, d = b.u, b.v, b.w, b.h, b.d
    return {
        "top": (u + d, v, w, d),
        "bottom": (u + d + w, v, w, d),
        "west": (u, v + d, d, h),       # minX: IT's right side
        "north": (u + d, v + d, w, h),  # minZ: the front
        "east": (u + d + w, v + d, d, h),  # maxX: IT's left side
        "south": (u + 2 * d + w, v + d, w, h),  # maxZ: the back
    }


def face_points(b, face):
    """Box-local 3D point (pixels) of every texel centre of a face, and the face normal."""
    u0, v0, uw, vh = face_rects(b)[face]
    i, j = np.meshgrid(np.arange(uw) + 0.5, np.arange(vh) + 0.5)
    x0, y0, z0, w, h, d = b.x, b.y, b.z, b.w, b.h, b.d
    if face == "top":
        return np.stack([x0 + i, np.full_like(i, y0), z0 + d - j], -1), (0, -1, 0)
    if face == "bottom":
        return np.stack([x0 + i, np.full_like(i, y0 + h), z0 + d - j], -1), (0, 1, 0)
    if face == "west":
        return np.stack([np.full_like(i, x0), y0 + j, z0 + d - i], -1), (-1, 0, 0)
    if face == "north":
        return np.stack([x0 + i, y0 + j, np.full_like(i, z0)], -1), (0, 0, -1)
    if face == "east":
        return np.stack([np.full_like(i, x0 + w), y0 + j, z0 + i], -1), (1, 0, 0)
    return np.stack([x0 + w - i, y0 + j, np.full_like(i, z0 + d)], -1), (0, 0, 1)


# ====================================================================== noise

def hash3(ix, iy, iz, seed):
    h = (ix.astype(np.int64) * 374761393 + iy.astype(np.int64) * 668265263 + iz.astype(np.int64) * 2147483647 + seed * 144665) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    h = h ^ (h >> 16)
    return (h & 0xFFFF) / 65535.0


def value3(p, scale, seed):
    q = p / scale
    i = np.floor(q)
    f = q - i
    f = f * f * (3 - 2 * f)
    ix, iy, iz = i[..., 0], i[..., 1], i[..., 2]
    fx, fy, fz = f[..., 0], f[..., 1], f[..., 2]
    out = 0
    for dx in (0, 1):
        for dy in (0, 1):
            for dz in (0, 1):
                wgt = (fx if dx else 1 - fx) * (fy if dy else 1 - fy) * (fz if dz else 1 - fz)
                out = out + wgt * hash3(ix + dx, iy + dy, iz + dz, seed)
    return out


def fbm(p, scale, seed, octaves=3):
    total = 0
    amp = 1.0
    norm = 0
    for o in range(octaves):
        total = total + amp * value3(p, scale / (2 ** o), seed + 17 * o)
        norm += amp
        amp *= 0.5
    return total / norm


def cells(p, scale, seed):
    """Distance to the nearest random feature point (Worley noise), in units of the cell size."""
    q = p / scale
    i = np.floor(q)
    best = np.full(q.shape[:-1], 9.0)
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            for dz in (-1, 0, 1):
                c = i + np.array([dx, dy, dz])
                fp = np.stack([hash3(c[..., 0], c[..., 1], c[..., 2], seed + k) for k in range(3)], -1)
                dist = np.linalg.norm(c + fp - q, axis=-1)
                best = np.minimum(best, dist)
    return best


# ====================================================================== painting

def mix(a, b, t):
    t = np.clip(t, 0, 1)[..., None]
    return a * (1 - t) + b * t


def col(r, g, b):
    return np.array([r, g, b], dtype=float)


def paint_material(material, pts, normal, seed, length_axis):
    """RGB (0..255) and alpha for a face of a box, from box-local texel points."""
    n = fbm(pts, 6.0, seed)
    fine = fbm(pts, 1.6, seed + 3, 2)
    shape = pts.shape[:-1]
    alpha = np.full(shape, 255.0)
    along = pts[..., length_axis]
    if material in ("flesh", "back"):
        base = mix(col(38, 38, 41), col(84, 82, 82), n * 1.3 - 0.15)
        base = mix(base, col(22, 21, 23), np.clip((0.42 - fbm(pts, 3.0, seed + 5)) * 4, 0, 1))
        # Sores: dark red pits with a raw rim.
        c = cells(pts, 5.0, seed + 11)
        sore = np.clip((0.33 - c) * 9, 0, 1) * (fbm(pts, 9.0, seed + 13) > 0.48)
        rim = np.clip((0.45 - c) * 9, 0, 1) * (1 - sore) * (fbm(pts, 9.0, seed + 13) > 0.48)
        base = mix(base, col(104, 62, 58), rim * 0.8)
        base = mix(base, col(70, 12, 10), sore)
        base = mix(base, col(24, 3, 3), np.clip(sore * 2 - 1, 0, 1))
        # Wet glints.
        glint = (fine > 0.74) * (n > 0.45)
        base = mix(base, col(150, 148, 150), glint * 0.55)
        if material == "back":
            # Ridges where the spine pushes through.
            ridge = np.clip(1 - np.abs(pts[..., 0]) / 1.6, 0, 1) * (np.mod(pts[..., 1], 4) < 2)
            base = mix(base, col(160, 150, 132), ridge * 0.8)
        # Torn through in a few places (cutout): you can see into it.
        hole = (fbm(pts, 4.0, seed + 29) > 0.73) & (n < 0.5)
        alpha = np.where(hole, 0.0, alpha)
    elif material == "sinew":
        stripe = 0.5 + 0.5 * np.sin(pts[..., 0] * 2.7 + pts[..., 2] * 2.3 + fbm(pts, 3.0, seed) * 6)
        base = mix(col(44, 30, 31), col(108, 82, 80), stripe * 0.7 + fine * 0.3)
        base = mix(base, col(30, 8, 7), np.clip((0.36 - n) * 5, 0, 1))
        base = mix(base, col(150, 140, 136), (fine > 0.8) * 0.4)
    elif material in ("bone", "brow"):
        base = mix(col(128, 120, 104), col(196, 188, 168), n * 1.2 - 0.1)
        crack = np.clip(1 - np.abs(fbm(pts, 2.5, seed + 7) - 0.5) * 14, 0, 1)
        base = mix(base, col(52, 46, 40), crack * 0.9)
        grime = np.clip((fbm(pts, 5.0, seed + 9) - 0.55) * 4, 0, 1)
        base = mix(base, col(70, 30, 26), grime * 0.6)
        if material == "brow":
            base = mix(base, col(40, 36, 34), np.clip((0.5 - n) * 3, 0, 1))
    elif material == "blade":
        base = mix(col(150, 144, 128), col(214, 208, 190), n)
        stain = np.clip((fbm(pts, 3.0, seed + 21) - 0.45) * 3, 0, 1) * np.clip(1 - along / 12.0, 0, 1)
        base = mix(base, col(62, 12, 10), stain * 0.85)
        base = mix(base, col(40, 34, 30), (fine < 0.22) * 0.7)
    elif material == "black":
        base = mix(col(10, 10, 12), col(36, 35, 38), n)
        base = mix(base, col(92, 92, 96), (fine > 0.78) * 0.6)
        base = mix(base, col(48, 8, 8), np.clip((fbm(pts, 4.0, seed + 31) - 0.62) * 5, 0, 1))
    elif material == "tentacle":
        ring = 0.5 + 0.5 * np.cos(along * 1.9)
        base = mix(col(16, 15, 17), col(52, 48, 52), ring * 0.45 + n * 0.55)
        base = mix(base, col(96, 92, 98), (fine > 0.8) * 0.55)
        tip = np.clip((along - 3.0) / 6.0, 0, 1) * (fbm(pts, 3.0, seed + 41) > 0.52)
        base = mix(base, col(66, 10, 9), tip * 0.7)
        # Pale pits along the underside.
        if normal[1] > 0.5:
            pit = (np.mod(along, 3) < 1) & (np.abs(pts[..., 0]) < 0.6)
            base = mix(base, col(120, 96, 92), pit * 0.8)
    elif material == "cavity":
        base = mix(col(10, 2, 2), col(62, 9, 8), n * 1.3 - 0.2)
        fold = np.clip(1 - np.abs(fbm(pts, 3.0, seed + 51) - 0.5) * 10, 0, 1)
        base = mix(base, col(98, 24, 20), fold * 0.6)
        base = mix(base, col(160, 120, 118), (fine > 0.82) * 0.5)
    elif material == "head":
        base = mix(col(58, 56, 56), col(104, 100, 98), n * 1.2 - 0.1)
        base = mix(base, col(28, 26, 27), np.clip((0.4 - fbm(pts, 2.5, seed + 61)) * 4, 0, 1))
        tear = np.clip(1 - np.abs(fbm(pts, 2.0, seed + 63) - 0.5) * 12, 0, 1) * (n > 0.5)
        base = mix(base, col(84, 16, 14), tear * 0.8)
    elif material == "jaw":
        base = mix(col(52, 50, 50), col(96, 92, 90), n)
        if normal[1] < -0.5:  # top of the jaw: gums and the inside of the mouth
            base = mix(col(38, 6, 6), col(88, 22, 20), n)
    elif material == "teeth":
        base = mix(col(170, 162, 140), col(214, 206, 184), n)
        base = mix(base, col(80, 54, 40), np.clip((fbm(pts, 2.0, seed + 71) - 0.6) * 4, 0, 1))
    else:
        raise ValueError(material)
    return base, alpha


def length_axis(b):
    dims = (b.w, b.h, b.d)
    return int(np.argmax(dims))


def paint():
    tex = np.zeros((TEX_H, TEX_W, 4))
    glow = np.zeros((TEX_H, TEX_W, 4))
    for pi, p in enumerate(PARTS):
        for bi, b in enumerate(p.boxes):
            seed = SEED + pi * 101 + bi * 7
            axis = length_axis(b)
            for face, (u0, v0, uw, vh) in face_rects(b).items():
                pts, normal = face_points(b, face)
                # Offsets per part so neighbouring parts do not share the same blotches.
                offset = np.array([pi * 13.7, pi * 7.1, pi * 3.3])
                rgb, alpha = paint_material(b.material, pts + offset, normal, seed, axis)
                # Shade towards the edges a little: boxes read as rounded, wet forms.
                if uw > 2 and vh > 2:
                    ii, jj = np.meshgrid(np.linspace(-1, 1, uw), np.linspace(-1, 1, vh))
                    edge = np.clip(np.maximum(np.abs(ii), np.abs(jj)) - 0.55, 0, 1) / 0.45
                    rgb = rgb * (1 - 0.35 * edge[..., None])
                if b.material == "teeth" and face in ("north", "south", "west", "east"):
                    # Jagged: every other column is a gap, and the points are cut.
                    ii, jj = np.meshgrid(np.arange(uw), np.arange(vh))
                    gap = (ii % 2 == 1) | ((jj == (0 if p.java_name == "jaw" else vh - 1)) & (ii % 4 == 2))
                    alpha = np.where(gap, 0.0, alpha)
                tex[v0:v0 + vh, u0:u0 + uw, :3] = rgb
                tex[v0:v0 + vh, u0:u0 + uw, 3] = alpha
                g = glow_for(p, b, face, pts, uw, vh, seed)
                if g is not None:
                    glow[v0:v0 + vh, u0:u0 + uw] = g
                    if p.name == "head" and face == "north" and b.material == "head":
                        # Under the glow the eye sockets are black.
                        lit = g[..., 3] > 0
                        tex[v0:v0 + vh, u0:u0 + uw, :3][lit] = [14, 4, 4]
                        tex[v0:v0 + vh, u0:u0 + uw, 3][lit] = 255
    return tex, glow


def glow_for(p, b, face, pts, uw, vh, seed):
    out = np.zeros((vh, uw, 4))
    if p.name == "head" and b.material == "head" and face == "north":
        # Two eyes and two smaller wrong ones, on an 8x8 face.
        eyes = [((1, 3), (2, 2), 1.0), ((5, 3), (2, 2), 1.0), ((4, 1), (1, 1), 0.7), ((6, 5), (1, 1), 0.55)]
        for (x, y), (w, h), strength in eyes:
            for yy in range(y, y + h):
                for xx in range(x, x + w):
                    out[yy, xx] = [255 * strength, 238 * strength, 214 * strength, 255]
            # A red bleed around each eye.
            for yy in range(max(0, y - 1), min(vh, y + h + 1)):
                for xx in range(max(0, x - 1), min(uw, x + w + 1)):
                    if out[yy, xx, 3] == 0:
                        out[yy, xx] = [120 * strength, 12 * strength, 8 * strength, 255]
        return out
    if b.material == "cavity" and face == "north":
        n = fbm(pts, 3.0, seed + 91)
        v = np.clip(n * 1.6 - 0.45, 0, 1)
        out[..., 0] = 26 + 70 * v
        out[..., 1] = 2 + 6 * v
        out[..., 2] = 2 + 4 * v
        out[..., 3] = 255
        return out
    if p.name == "chest" and b.material == "back" and face == "north":
        # The inside of the back plate, seen through the open chest: the faintest red.
        n = fbm(pts, 4.0, seed + 93)
        v = np.clip(n * 1.4 - 0.5, 0, 1)
        out[..., 0] = 10 + 34 * v
        out[..., 1] = 1 + 2 * v
        out[..., 2] = 1 + 2 * v
        out[..., 3] = 255
        return out
    return None


def kill_vignette():
    """256x256: transparent in the middle, dark red at the edges, with darker veins creeping in."""
    size = 256
    y, x = np.mgrid[0:size, 0:size]
    nx = (x + 0.5) / size * 2 - 1
    ny = (y + 0.5) / size * 2 - 1
    r = np.sqrt(nx * nx * 0.85 + ny * ny)
    pts = np.stack([nx * 40, ny * 40, np.zeros_like(nx)], -1)
    wob = fbm(pts, 9.0, SEED + 300)
    edge = np.clip((r - 0.42 - 0.18 * wob) / 0.75, 0, 1) ** 1.6
    veins = np.clip(1 - np.abs(fbm(pts, 5.0, SEED + 301) - 0.5) * 16, 0, 1) * np.clip((r - 0.55) / 0.4, 0, 1)
    alpha = np.clip(edge * 235 + veins * 70, 0, 245)
    out = np.zeros((size, size, 4))
    out[..., 0] = 60 + 40 * (1 - edge) - 40 * veins
    out[..., 1] = 2
    out[..., 2] = 2
    out[..., 3] = alpha
    return out


# ====================================================================== Java

def fmt(v):
    v = float(v)
    if abs(v) < 5e-7:
        v = 0.0
    text = f"{v:.4f}".rstrip("0")
    if text.endswith("."):
        text += "0"
    return text + "F"


def write_java():
    lines = []
    counter = [0]

    def emit(p, parent_var):
        var = None
        if p.children:
            counter[0] += 1
            var = f"p{counter[0]}"
        cubes = "CubeListBuilder.create()"
        for b in p.boxes:
            cubes += (f"\n                .texOffs({b.u}, {b.v}).addBox({fmt(b.x)}, {fmt(b.y)}, {fmt(b.z)}, "
                      f"{fmt(b.w)}, {fmt(b.h)}, {fmt(b.d)})")
        pose = f"PartPose.offsetAndRotation({', '.join(fmt(c) for c in p.pivot)}, {', '.join(fmt(c) for c in p.rot)})"
        call = f'{parent_var}.addOrReplaceChild("{p.java_name}", {cubes},\n                {pose});'
        lines.append(f"        // {p.name}")
        lines.append(f"        {'PartDefinition ' + var + ' = ' if var else ''}{call}")
        for c in p.children:
            emit(c, var)

    for p in PARTS:
        if p.parent is None:
            emit(p, "root")
    body = "\n".join(lines)
    boxes = sum(len(p.boxes) for p in PARTS)
    java = f'''package dev.kristian.nullmod.client.it;

import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

/**
 * IT's geometry: {len(PARTS)} parts, {boxes} boxes on a {TEX_W}x{TEX_H} texture.
 * <p>
 * GENERATED by {{@code tools/gen_it.py}}, which also paints the textures for exactly these boxes:
 * change the model there and run the script again, never edit this file by hand.
 * <p>
 * Model space is in pixels (1/16 block): +X is IT's left, +Y is down, -Z is its front, the ground is
 * at y = 24. Chains of tentacle segments are nested parts named {{@code "s"}}.
 */
final class ItModelLayer {{
    static final int TEXTURE_WIDTH = {TEX_W};
    static final int TEXTURE_HEIGHT = {TEX_H};

    private ItModelLayer() {{
    }}

    static LayerDefinition create() {{
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
{body}
        return LayerDefinition.create(mesh, TEXTURE_WIDTH, TEXTURE_HEIGHT);
    }}
}}
'''
    JAVA_OUT.parent.mkdir(parents=True, exist_ok=True)
    JAVA_OUT.write_text(java, encoding="utf-8")


# ====================================================================== posing (mirror of ItModel's maths)

def rot_zyx(x, y, z):
    cx, sx = math.cos(x), math.sin(x)
    cy, sy = math.cos(y), math.sin(y)
    cz, sz = math.cos(z), math.sin(z)
    rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return rz @ ry @ rx


def world_matrices(pose):
    """Model-space 4x4 matrix of every part for a pose {name: (dx, dy, dz, drx, dry, drz)} added to the rest pose."""
    mats = {}
    for p in PARTS:
        dx, dy, dz, rx, ry, rz = pose.get(p.name, (0, 0, 0, 0, 0, 0))
        m = np.eye(4)
        m[:3, 3] = [p.pivot[0] + dx, p.pivot[1] + dy, p.pivot[2] + dz]
        m[:3, :3] = rot_zyx(p.rot[0] + rx, p.rot[1] + ry, p.rot[2] + rz)
        mats[p.name] = (mats[p.parent.name] if p.parent else np.eye(4)) @ m
    return mats


def preview_pose(kind, phase=0.0, t=0.0):
    """A few of the poses ItModel produces, with the same formulas (kept in step by hand)."""
    pose = {}

    def add(name, dx=0, dy=0, dz=0, rx=0, ry=0, rz=0):
        a = pose.get(name, (0, 0, 0, 0, 0, 0))
        pose[name] = (a[0] + dx, a[1] + dy, a[2] + dz, a[3] + rx, a[4] + ry, a[5] + rz)

    run = 1.0 if kind == "run" else 0.0
    peer = 1.0 if kind == "peer" else 0.0
    two_pi = 2 * math.pi
    # Stance.
    add("hips", dy=11 * run + 13 * peer + 2.2 * run * math.sin(2 * two_pi * phase), rx=0.22 * run + 0.18 * peer)
    add("spine", rx=0.42 * run + 0.5 * peer)
    add("chest", rx=0.36 * run + 0.42 * peer)
    add("neck", rx=-0.6 * run - 0.3 * peer)
    add("head", rx=-0.45 * run - 0.6 * peer)
    for side, sx, off in (("l", 1, 0.0), ("r", -1, 0.12)):
        s = math.sin(two_pi * (phase + off))
        c = math.cos(two_pi * (phase + off))
        lift = max(0.0, c)
        add(f"thigh_{side}", rx=-0.25 * peer + run * (0.75 * s - 0.25) - 0.35 * peer)
        add(f"shin_{side}", rx=0.3 * peer + run * (0.35 + 0.45 * lift))
        add(f"cannon_{side}", rx=-0.15 * peer + run * (-0.3 * lift))
        add(f"foot_{side}", rx=run * (0.4 * s))
        sa = math.sin(two_pi * (phase + 0.5 + off * 1.5))
        ca = math.cos(two_pi * (phase + 0.5 + off * 1.5))
        add(f"arm_{side}", rx=-run * (1.15 + 0.7 * sa) - peer * 0.9, rz=0.08 * sx * run)
        add(f"fore_{side}", rx=run * (0.25 + 0.55 * max(0.0, ca)) - peer * 0.4)
        add(f"wrist_{side}", rx=run * 0.5 * max(0.0, -ca))
    if kind == "scream":
        add("jaw", rx=1.25)
        add("head", rx=-0.25)
        add("neck", rx=-0.2)
        add("arm_l", rz=-0.5)
        add("arm_r", rz=0.5)
    if kind == "kill":
        add("spine", rx=-0.1)
        add("arm_r", rx=-2.1, rz=0.25)
        add("fore_r", rx=-0.55)
        add("arm_l", rx=-1.4, rz=-0.3)
        add("fore_l", rx=-1.2)
        add("jaw", rx=1.2)
        add("neck", rx=0.25)
        for k in range(4):
            add(f"finger_r{k}", rx=-0.9)
            add(f"finger_r{k}/tip", rx=-0.8)
    # Tentacles: layered waves, never in step; dragged back when it runs.
    for p in PARTS:
        base = p.name.split("/")[0]
        if base[:2] in ("ct", "bt") and base[2:].isdigit():
            i = int(base[2:])
            k = 0 if "/" not in p.name else int(p.name.split("/s")[-1])
            along = (k + 1) / 7.0
            amp = (0.08 + 0.16 * along) * (1.5 if base.startswith("ct") else 1.0)
            w1 = 0.11 + 0.023 * i
            add(p.name, rx=amp * math.sin(t * w1 + i * 2.1 + k * 0.7) + (0.12 * run * along if base.startswith("bt") else 0),
                ry=amp * math.cos(t * w1 * 0.83 + i * 1.3 + k * 0.55))
    return pose


# ====================================================================== preview renderer

LIGHT0 = np.array([0.2, 1.0, -0.7])
LIGHT1 = np.array([-0.2, 1.0, 0.7])
LIGHT0 /= np.linalg.norm(LIGHT0)
LIGHT1 /= np.linalg.norm(LIGHT1)


def render(tex, glow, pose, yaw_deg, pitch_deg, size=720, dark=False, distance=11.0, extra=None):
    """Perspective view of the posed model. World: blocks, y up, IT standing at the origin facing +Z."""
    mats = world_matrices(pose)
    img = np.zeros((size, size, 3))
    img[:] = (6, 6, 8) if dark else (122, 128, 138)
    if not dark:
        grad = np.linspace(0, 1, size)[:, None]
        img[:] = img * (1.0 - 0.25 * grad[..., None])
    zbuf = np.full((size, size), np.inf)
    yaw = math.radians(yaw_deg)
    pitch = math.radians(pitch_deg)
    target = np.array([0.0, 3.1, 0.0])
    eye = target + distance * np.array([math.sin(yaw) * math.cos(pitch), math.sin(pitch), math.cos(yaw) * math.cos(pitch)])
    fwd = target - eye
    fwd /= np.linalg.norm(fwd)
    right = np.cross(fwd, [0, 1, 0])
    right /= np.linalg.norm(right)
    up = np.cross(right, fwd)
    focal = size * 1.25

    def to_world(m_pts):
        # Model pixels -> blocks, entity facing +Z: (x/16, -y/16 + 1.501, -z/16).
        return np.stack([m_pts[..., 0] / 16.0, -m_pts[..., 1] / 16.0 + 1.501, -m_pts[..., 2] / 16.0], -1)

    def project(w):
        rel = w - eye
        cz = rel @ fwd
        cx = rel @ right
        cy = rel @ up
        return np.stack([size / 2 + focal * cx / cz, size / 2 - focal * cy / cz, cz], -1)

    quads = []
    for p in PARTS:
        m = mats[p.name]
        for b in p.boxes:
            x0, y0, z0 = b.x, b.y, b.z
            x1, y1, z1 = x0 + b.w, y0 + b.h, z0 + b.d
            corners = {
                "top": [(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)],
                "bottom": [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)],
                "west": [(x0, y0, z1), (x0, y0, z0), (x0, y1, z0), (x0, y1, z1)],
                "north": [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0)],
                "east": [(x1, y0, z0), (x1, y0, z1), (x1, y1, z1), (x1, y1, z0)],
                "south": [(x1, y0, z1), (x0, y0, z1), (x0, y1, z1), (x1, y1, z1)],
            }
            rects = face_rects(b)
            for face, pts in corners.items():
                local = np.array(pts, dtype=float)
                homo = np.concatenate([local, np.ones((4, 1))], 1) @ m.T
                quads.append((to_world(homo[:, :3]), rects[face]))
    # The corners are listed so their texture coordinates run (u0,v0) (u1,v0) (u1,v1) (u0,v1).
    for world, (u0, v0, uw, vh) in quads:
        scr = project(world)
        if np.any(scr[:, 2] < 0.1):
            continue
        normal = np.cross(world[1] - world[0], world[3] - world[0])
        nl = np.linalg.norm(normal)
        if nl < 1e-9:
            continue
        normal /= nl
        # Both sides are drawn (no culling); light the side we see.
        if np.dot(normal, eye - world[0]) < 0:
            normal = -normal
        shade = min(1.0, 0.4 + 0.6 * (max(0.0, normal @ LIGHT0) + max(0.0, normal @ LIGHT1)))
        uv = np.array([[u0, v0], [u0 + uw, v0], [u0 + uw, v0 + vh], [u0, v0 + vh]], dtype=float)
        for tri in ((0, 1, 2), (0, 2, 3)):
            raster(img, zbuf, scr[list(tri)], uv[list(tri)], tex, glow, shade, dark)
    if extra is not None:
        extra(img, project)
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))


def raster(img, zbuf, s, uv, tex, glow, shade, dark):
    size = img.shape[0]
    xmin = max(int(math.floor(s[:, 0].min())), 0)
    xmax = min(int(math.ceil(s[:, 0].max())), size - 1)
    ymin = max(int(math.floor(s[:, 1].min())), 0)
    ymax = min(int(math.ceil(s[:, 1].max())), size - 1)
    if xmin > xmax or ymin > ymax:
        return
    ys, xs = np.mgrid[ymin:ymax + 1, xmin:xmax + 1]
    px = xs + 0.5
    py = ys + 0.5
    (x0, y0, z0), (x1, y1, z1), (x2, y2, z2) = s
    area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
    if abs(area) < 1e-9:
        return
    w0 = ((x1 - px) * (y2 - py) - (x2 - px) * (y1 - py)) / area
    w1 = ((x2 - px) * (y0 - py) - (x0 - px) * (y2 - py)) / area
    w2 = 1 - w0 - w1
    inside = (w0 >= -1e-6) & (w1 >= -1e-6) & (w2 >= -1e-6)
    if not inside.any():
        return
    iz = w0 / z0 + w1 / z1 + w2 / z2
    depth = 1.0 / iz
    u = (w0 * uv[0, 0] / z0 + w1 * uv[1, 0] / z1 + w2 * uv[2, 0] / z2) * depth
    v = (w0 * uv[0, 1] / z0 + w1 * uv[1, 1] / z1 + w2 * uv[2, 1] / z2) * depth
    ui = np.clip(np.floor(u).astype(int), 0, TEX_W - 1)
    vi = np.clip(np.floor(v).astype(int), 0, TEX_H - 1)
    texel = tex[vi, ui]
    ok = inside & (texel[..., 3] >= 26) & (depth < zbuf[ys, xs])
    if not ok.any():
        return
    light = shade * (0.07 if dark else 1.0)
    color = texel[..., :3] * light
    g = glow[vi, ui, :3] * (glow[vi, ui, 3:4] / 255.0)
    color = color + g
    sel_y, sel_x = ys[ok], xs[ok]
    img[sel_y, sel_x] = color[ok]
    zbuf[sel_y, sel_x] = depth[ok]


def preview(tex, glow, out_dir):
    out_dir.mkdir(parents=True, exist_ok=True)
    # The unwrapped texture, 3x, with every face outlined.
    scale = 3
    flat = Image.fromarray(np.clip(tex, 0, 255).astype(np.uint8), "RGBA")
    bg = Image.new("RGBA", flat.size, (40, 90, 40, 255))
    bg.alpha_composite(flat)
    big = bg.resize((TEX_W * scale, TEX_H * scale), Image.NEAREST)
    draw = ImageDraw.Draw(big)
    for p in PARTS:
        for b in p.boxes:
            for (u0, v0, uw, vh) in face_rects(b).values():
                draw.rectangle([u0 * scale, v0 * scale, (u0 + uw) * scale - 1, (v0 + vh) * scale - 1], outline=(255, 255, 0, 90))
    big.save(out_dir / "it_unwrapped.png")
    gflat = Image.fromarray(np.clip(glow, 0, 255).astype(np.uint8), "RGBA").resize((TEX_W * scale, TEX_H * scale), Image.NEAREST)
    gbg = Image.new("RGBA", gflat.size, (0, 0, 0, 255))
    gbg.alpha_composite(gflat)
    gbg.save(out_dir / "it_glow_unwrapped.png")

    def sheet(name, views):
        tiles = [render(tex, glow, pose, yaw, pitch, dark=dark) for (pose, yaw, pitch, dark) in views]
        w = tiles[0].width
        out = Image.new("RGB", (w * len(tiles), w))
        for i, tile in enumerate(tiles):
            out.paste(tile, (i * w, 0))
        out.save(out_dir / name)

    rest = {}
    sheet("it_rest.png", [(rest, 180, 8, False), (rest, 135, 10, False), (rest, 90, 5, False), (rest, 0, 15, False)])
    sheet("it_run.png", [(preview_pose("run", ph, ph * 40), 90, 5, False) for ph in (0.0, 0.25, 0.5, 0.75)])
    sheet("it_poses.png", [(preview_pose("peer", t=3), 120, 8, False), (preview_pose("scream", t=5), 160, 10, False),
                           (preview_pose("kill", t=9), 130, 8, False), (preview_pose("run", 0.1, 4), 150, 12, False)])
    sheet("it_dark.png", [(rest, 180, 6, True), (preview_pose("run", 0.3, 7), 140, 6, True),
                          (preview_pose("scream", t=2), 180, 4, True)])
    vig = Image.fromarray(np.clip(kill_vignette(), 0, 255).astype(np.uint8), "RGBA")
    shot = Image.new("RGBA", (512, 288), (90, 96, 104, 255))
    shot.alpha_composite(vig.resize((512, 288), Image.BILINEAR))
    shot.save(out_dir / "kill_vignette_on_grey.png")


# ====================================================================== main

def save_png(path, rgba):
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(np.clip(np.round(rgba), 0, 255).astype(np.uint8), "RGBA").save(path, optimize=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview", type=pathlib.Path, help="directory for preview renders")
    args = parser.parse_args()
    pack()
    tex, glow = paint()
    save_png(TEX_OUT / "it.png", tex)
    save_png(TEX_OUT / "it_glow.png", glow)
    save_png(GUI_OUT / "kill_vignette.png", kill_vignette())
    write_java()
    print(f"{len(PARTS)} parts, {sum(len(p.boxes) for p in PARTS)} boxes -> {JAVA_OUT.relative_to(ROOT)}")
    if args.preview:
        preview(tex, glow, args.preview)
        print(f"previews in {args.preview}")


if __name__ == "__main__":
    main()
