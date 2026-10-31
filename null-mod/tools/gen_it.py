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
    part("hips", None, (0, -36, 0), (0, 0, 0), [
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
        part(f"shin_{side}", f"thigh_{side}", (0, LEG_THIGH, 0), (1.05, 0, 0), [
            (-2, -1, -2, 4, 27, 4, "sinew"),
            (-1, -2, 1.5, 2, 6, 3, "bone"),
        ] + ([(-2.5, 11, -2.5, 5, 3, 5, "bone")] if sx < 0 else []))
        part(f"cannon_{side}", f"shin_{side}", (0, LEG_SHIN, 0), (LEG_CANNON_BEND, 0, 0), [
            (-1.5, -1, -1.5, 3, 19, 3, "bone"),
        ])
        part(f"foot_{side}", f"cannon_{side}", (0, LEG_CANNON, 0), (0.15, 0, 0), [
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
    # The chest: a hunched, heavy back with the shoulder blades and spine pushing through, caved in
    # and open at the front: broken side walls, the collar bar the arms hang from, the dark wet inside.
    part("chest", "spine", (0, -16, 0), (0.25, 0, 0), [
        (-11, -22, 1, 22, 20, 8, "back"),
        (-8, -27, 2, 16, 6, 8, "back"),
        (5, -24, 8.5, 7, 10, 2, "bone"),
        (-12, -23, 8.5, 6, 9, 2, "bone"),
        (-1, -25, 9.5, 2, 2, 2, "bone"),
        (-1, -20, 9.5, 2, 2, 2, "bone"),
        (-1, -15, 9.5, 2, 2, 2, "bone"),
        (-1, -10, 9.5, 2, 2, 2, "bone"),
        (7.5, -21, -5, 3, 16, 7, "flesh"),
        (-10.5, -18, -4, 3, 11, 6, "flesh"),
        (-15, -25, -3, 30, 3, 7, "bone"),
        (-7, -19, -2, 14, 16, 4, "cavity"),
        (-4, -4, -4, 8, 5, 8, "flesh"),
    ])
    part("sternum", "chest", (0, -22, -4), (0.6, 0, 0.35), [(-1, 0, -1, 2, 8, 2, "bone")])
    # Ribs splayed open like a burst cage; two segments each so they curve. Three on the right,
    # one of them snapped short.
    rib_rows = [(-19, 0.0), (-15, 0.12), (-11, 0.24), (-7, 0.36)]
    for k, (y, drop) in enumerate(rib_rows):
        part(f"rib_l{k}", "chest", (8, y, -4.5), (drop - 0.1, -0.55 - 0.1 * k, 0.15), [(-1, -1, -10, 2, 2, 10, "bone")])
        part(f"rib_l{k}/tip", f"rib_l{k}", (0, 0, -9.5), (0.2, -0.65, 0), [(-0.5, -0.5, -6, 1, 1, 6, "bone")], "tip")
    for k, (y, drop) in enumerate(rib_rows[:3]):
        part(f"rib_r{k}", "chest", (-8, y, -3.5), (drop - 0.05, 0.6 + 0.12 * k, -0.15), [(-1, -1, -10, 2, 2, 10, "bone")])
        if k != 1:
            part(f"rib_r{k}/tip", f"rib_r{k}", (0, 0, -9.5), (0.25, 0.6, 0), [(-0.5, -0.5, -6, 1, 1, 6, "bone")], "tip")
    # Tentacles out of the open chest (forward, drooping) and out of the back (rising, arching).
    for i, (pivot, rot) in enumerate(CHEST_TENTACLES):
        chain(f"ct{i}", "chest", pivot, rot, [6, 6, 6, 6, 5, 5], [3, 3, 2, 2, 2, 1],
              (-0.15, 0.06 * (1 if i % 2 else -1), 0), "tentacle")
    for i, (pivot, rot) in enumerate(BACK_TENTACLES):
        chain(f"bt{i}", "chest", pivot, rot, [8, 8, 8, 7, 7, 6, 6], [4, 4, 3, 3, 3, 2, 1],
              (-0.16, 0.04 * (1 if i % 2 else -1), 0), "tentacle")
    # Neck and the small head with its hanging jaw.
    part("neck", "chest", (0, -25, -2), (0.55, 0, 0), [
        (-2, -13, -2, 4, 13, 4, "sinew"),
        (-1, -12, 2, 2, 2, 2, "bone"),
        (-1, -7, 2, 2, 2, 2, "bone"),
    ])
    part("head", "neck", (0, -12, -1), (-0.85, 0, 0), [
        (-4, -8, -7, 8, 8, 9, "head"),
        (-4.5, -6, -8, 9, 1, 2, "brow"),
        (-3, -9, -4, 6, 1, 6, "head"),
        (-3, 0, -7, 6, 2, 1, "teeth"),
        (2.5, -6, -2, 2, 3, 3, "flesh"),
    ])
    part("jaw", "head", (0, 0, 1.5), (0.35, 0, 0), [
        (-3.5, 0, -8, 7, 2, 8, "jaw"),
        (-3, -2, -8, 6, 2, 1, "teeth"),
    ])
    # Arms: far too long, hanging past the knees; bone blades for fingers.
    for side, sx in (("l", 1), ("r", -1)):
        part(f"shoulder_{side}", "chest", (14 * sx, -23, 1), (0, 0, 0), [(-3 if sx > 0 else -2, -3, -3, 5, 5, 6, "bone")])
        part(f"arm_{side}", f"shoulder_{side}", (1.5 * sx, 0, 0), (-0.15, 0, -0.12 * sx), [
            (-2.5, -2, -2.5, 5, 33, 5, "flesh"),
            (-1, 26, 2, 2, 3, 4, "bone"),
        ])
        part(f"fore_{side}", f"arm_{side}", (0, ARM_UPPER, 0), (-0.3, 0, 0), [
            (-2, -1, -2, 4, 31, 4, "sinew"),
            (-1, 9, -3, 2, 7, 1, "bone"),
        ])
        part(f"wrist_{side}", f"fore_{side}", (0, ARM_FORE, 0), (-0.1, 0, 0), [(-1.5, -1, -1.5, 3, 11, 3, "bone")])
        part(f"hand_{side}", f"wrist_{side}", (0, ARM_WRIST, 0), (0, 0, 0), [
            (-2.5, -1, -1.5, 5, 6, 3, "black"),
            (-2, 4, -2, 4, 1, 1, "bone"),
        ])
        for k in range(4):
            name = f"finger_{side}{k}"
            part(name, f"hand_{side}", (-1.875 + 1.25 * k, 4.5, 0), (-0.25, 0, (k - 1.5) * 0.1 * sx), [(-0.5, 0, -0.5, 1, 11, 1, "blade")],
                 f"finger{k}")
            part(f"{name}/tip", name, (0, 10.5, 0), (-0.35, 0, 0), [(-0.5, 0, -0.5, 1, 9, 1, "blade")], "tip")


# Joint lengths the pose solver needs (pixels); ItModel uses the same values.
LEG_THIGH = 24.0
LEG_SHIN = 26.0
LEG_CANNON = 18.0
LEG_CANNON_BEND = -0.75
ARM_UPPER = 31.0
ARM_FORE = 30.0
ARM_WRIST = 10.0
ARM_PALM = 5.0
CHEST_TENTACLES = [((-3, -13, -2), (-0.35, math.pi - 0.35, 0)), ((3, -10, -2), (-0.45, math.pi + 0.4, 0)),
                   ((0, -16, -2), (-0.1, math.pi + 0.05, 0))]
BACK_TENTACLES = [((-5, -21, 8.5), (0.55, -0.3, 0.1)), ((5, -19, 8.5), (0.65, 0.35, -0.1)),
                  ((-7, -12, 8.5), (0.3, -0.55, 0.0)), ((6, -10, 8.5), (0.35, 0.6, 0.0))]


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
                    if p.name == "head" and face == "north" and b is p.boxes[0]:
                        # Under the glow the eye sockets are black.
                        lit = g[..., 3] > 0
                        tex[v0:v0 + vh, u0:u0 + uw, :3][lit] = [14, 4, 4]
                        tex[v0:v0 + vh, u0:u0 + uw, 3][lit] = 255
    return tex, glow


def glow_for(p, b, face, pts, uw, vh, seed):
    out = np.zeros((vh, uw, 4))
    if p.name == "head" and b is p.boxes[0] and face == "north":
        # Two slit eyes burning white over an ember-red underglow, and two smaller wrong ones.
        eyes = [((1, 3), 2, 1.0), ((5, 3), 2, 1.0), ((4, 1), 1, 0.65), ((6, 5), 1, 0.5)]
        for (x, y), w, strength in eyes:
            for xx in range(x, x + w):
                out[y, xx] = [255 * strength, 240 * strength, 206 * strength, 255]
                if strength >= 1.0 and y + 1 < vh:
                    out[y + 1, xx] = [176, 44, 18, 255]
            if strength >= 1.0:
                for xx in (x - 1, x + w):
                    if 0 <= xx < uw and out[y, xx, 3] == 0:
                        out[y, xx] = [64, 6, 4, 255]
        return out
    if b.material == "cavity" and face == "north":
        n = fbm(pts, 3.0, seed + 91)
        ridge = np.clip(1 - np.abs(fbm(pts, 2.2, seed + 92) - 0.5) * 7, 0, 1)
        v = np.clip(n * 1.4 - 0.5, 0, 1) * 0.6 + ridge * 0.4
        out[..., 0] = 10 + 62 * v
        out[..., 1] = 1 + 4 * v
        out[..., 2] = 1 + 3 * v
        out[..., 3] = 255
        return out
    if p.name == "chest" and b.material == "back" and face == "north":
        # The inside of the back plate, seen through the open chest: the faintest red.
        n = fbm(pts, 4.0, seed + 93)
        v = np.clip(n * 1.4 - 0.5, 0, 1)
        out[..., 0] = 4 + 22 * v
        out[..., 1] = 1 + 1 * v
        out[..., 2] = 1 + 1 * v
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
#
# ItModel poses the model with exactly these formulas (kept in step by hand): the torso from the
# stance blend, the legs by 2-bone IK onto gait foot paths (the shin and cannon act as one rigid
# lower leg), the arms by 3-bone-as-2 IK onto hand targets (or hanging), the tentacles by layered
# waves. All in model space (pixels), angles in radians.

STAND_HIP_Y = -36.0
RUN_HIP_Y = -24.0
CROUCH_HIP_Y = -21.0
ANKLE_Y = 22.0
HAND_Y = 15.0


def rot_zyx(x, y, z):
    cx, sx = math.cos(x), math.sin(x)
    cy, sy = math.cos(y), math.sin(y)
    cz, sz = math.cos(z), math.sin(z)
    rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return rz @ ry @ rx


def euler_zyx(r):
    """Angles (x, y, z) with r = Rz(z) Ry(y) Rx(x), as ModelPart applies them."""
    sy = -r[2, 0]
    y = math.asin(max(-1.0, min(1.0, sy)))
    if math.cos(y) > 1e-4:
        return math.atan2(r[2, 1], r[2, 2]), y, math.atan2(r[1, 0], r[0, 0])
    return math.atan2(-r[1, 2], r[1, 1]), y, 0.0


class Pose:
    """Absolute pivot (pixels) and rotation (radians) of every part, starting at the rest pose."""

    def __init__(self):
        self.v = {p.name: list(p.pivot) + list(p.rot) for p in PARTS}

    def set(self, name, x=None, y=None, z=None, rx=None, ry=None, rz=None):
        v = self.v[name]
        for i, value in enumerate((x, y, z, rx, ry, rz)):
            if value is not None:
                v[i] = value

    def add(self, name, rx=0.0, ry=0.0, rz=0.0):
        v = self.v[name]
        v[3] += rx
        v[4] += ry
        v[5] += rz

    def frame(self, name):
        """Model-space rotation and origin of a part's frame."""
        chain_ = []
        p = BY_NAME[name]
        while p is not None:
            chain_.append(p)
            p = p.parent
        r = np.eye(3)
        o = np.zeros(3)
        for q in reversed(chain_):
            v = self.v[q.name]
            o = o + r @ np.array(v[0:3])
            r = r @ rot_zyx(v[3], v[4], v[5])
        return r, o


def solve_leg(pose, side, target, toe):
    hips = pose.v["hips"]
    thigh = pose.v[f"thigh_{side}"]
    hip_r, hip_o = pose.frame("hips")
    hip = hip_o + hip_r @ np.array(thigh[0:3])
    dx, dy, dz = target[0] - hip[0], target[1] - hip[1], target[2] - hip[2]
    splay = -math.atan2(dx, dy)
    down = math.hypot(dx, dy)
    ly = LEG_SHIN + LEG_CANNON * math.cos(LEG_CANNON_BEND)
    lz = LEG_CANNON * math.sin(LEG_CANNON_BEND)
    l2 = math.hypot(ly, lz)
    delta = math.atan2(lz, ly)
    l1 = LEG_THIGH
    dist = max(abs(l1 - l2) + 0.5, min(l1 + l2 - 0.5, math.hypot(down, dz)))
    phi = math.atan2(dz, down)
    cos_a = (l1 * l1 + dist * dist - l2 * l2) / (2 * l1 * dist)
    a1 = phi - math.acos(max(-1.0, min(1.0, cos_a)))
    ky, kz = l1 * math.cos(a1), l1 * math.sin(a1)
    ty, tz = dist * math.cos(phi), dist * math.sin(phi)
    a2 = math.atan2(tz - kz, ty - ky)
    pose.set(f"thigh_{side}", rx=a1 - hips[3], ry=0.0, rz=splay)
    pose.set(f"shin_{side}", rx=(a2 - delta) - a1)
    pose.set(f"cannon_{side}", rx=LEG_CANNON_BEND)
    pose.set(f"foot_{side}", rx=toe - (a2 - delta + LEG_CANNON_BEND))


def solve_arm(pose, side, target, pole):
    """Upper arm (x, y, z) in its parent frame and the elbow bend, reaching the palm to target."""
    arm = pose.v[f"arm_{side}"]
    sh_r, sh_o = pose.frame(f"shoulder_{side}")
    s = sh_o + sh_r @ np.array(arm[0:3])
    l1 = ARM_UPPER
    l2 = ARM_FORE + ARM_WRIST + ARM_PALM
    d = np.array(target, dtype=float) - s
    length = float(np.linalg.norm(d))
    dn = d / length if length > 1e-6 else np.array([0.0, 1.0, 0.0])
    dist = max(abs(l1 - l2) + 0.5, min(l1 + l2 - 0.5, length))
    a = (l1 * l1 + dist * dist - l2 * l2) / (2 * dist)
    h = math.sqrt(max(0.0, l1 * l1 - a * a))
    q = np.array(pole, dtype=float)
    q = q - (q @ dn) * dn
    ql = float(np.linalg.norm(q))
    q = q / ql if ql > 1e-6 else np.array([0.0, 0.0, 1.0])
    elbow = s + dn * a + q * h
    u = (elbow - s) / l1
    f = s + dn * dist - elbow
    f = f / float(np.linalg.norm(f))
    w = f - (f @ u) * u
    wl = float(np.linalg.norm(w))
    w = w / wl if wl > 1e-6 else np.array([0.0, 0.0, -1.0])
    z_axis = -w
    x_axis = np.cross(u, z_axis)
    rm = np.stack([x_axis, u, z_axis], 1)
    local = sh_r.T @ rm
    x, y, z = euler_zyx(local)
    bend = math.acos(max(-1.0, min(1.0, float(u @ f))))
    return (x, y, z), -bend


def frac(v):
    return v - math.floor(v)


def smooth(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


class State:
    """What ItModel knows about one IT in one frame."""

    def __init__(self, **kw):
        self.t = 0.0          # age in ticks
        self.run = 0.0        # 0 upright .. 1 galloping on all fours
        self.speed = 0.0      # blocks per tick
        self.phase = 0.0      # gait cycles
        self.crouch = 0.0     # 0..1 bent down (at a window, seizing)
        self.scream = 0.0     # 0..1
        self.look_yaw = 0.0
        self.look_pitch = 0.0
        self.jaw = 0.0        # extra jaw opening
        self.right_target = None  # model-space palm targets (kill, scrape, window)
        self.left_target = None
        self.right_weight = 0.0
        self.left_weight = 0.0
        self.grip = 0.0       # 0..1 right fingers closed
        self.pierce = 0.0     # 0..1 left fingers straight and together
        self.tentacle_reach = 0.0
        self.tentacle_target = None
        self.neck_extra = 0.0
        for k, v in kw.items():
            setattr(self, k, v)


def cycle_length(speed):
    """Blocks covered per gait cycle: long slow steps when it prowls, huge bounds at a gallop."""
    return 4.0 + 4.0 * min(1.0, speed / 0.78)


def stride_px(speed, duty):
    return 16.0 * duty * cycle_length(speed) * min(1.0, speed / 0.04)


def pose_model(st):
    pose = Pose()
    run = st.run
    crouch = st.crouch
    ph = st.phase
    tau = 2.0 * math.pi
    breath = math.sin(st.t * 0.09)
    bob = 2.2 * run * math.sin(2.0 * tau * ph)
    hip_y = STAND_HIP_Y + (RUN_HIP_Y - STAND_HIP_Y) * run + (CROUCH_HIP_Y - STAND_HIP_Y) * crouch * (1.0 - run) + bob
    hips_pitch = 0.12 * run + 0.1 * crouch
    spine_pitch = 0.35 + 0.32 * run + 0.42 * crouch + 0.015 * breath
    chest_pitch = 0.25 + 0.26 * run + 0.36 * crouch + 0.02 * breath + 0.07 * run * math.sin(2.0 * tau * ph + 0.6)
    pose.set("hips", y=hip_y, rx=hips_pitch)
    pose.set("spine", rx=spine_pitch)
    pose.set("chest", rx=chest_pitch, rz=0.03 * math.sin(st.t * 0.05))
    neck_pitch = 0.55 - 0.45 * run + 0.2 * crouch + 0.25 * st.scream + st.neck_extra
    torso = hips_pitch + spine_pitch + chest_pitch
    pose.set("neck", rx=neck_pitch)
    pose.set("head", rx=-(torso + neck_pitch) + 0.3 - 0.15 * run + st.look_pitch - 0.25 * st.scream + 0.35 * st.neck_extra,
             ry=st.look_yaw, rz=0.14 * st.scream * math.sin(st.t * 1.9))
    pose.set("jaw", rx=0.35 + 0.15 * breath * (1 - st.scream) + st.jaw + 1.0 * st.scream)

    # Legs: feet on paths under the hips, planted while in stance.
    duty = 0.62 + (0.42 - 0.62) * run
    lift = (5.0 + 9.0 * run) * min(1.0, st.speed / 0.12)
    stride = stride_px(st.speed, duty)
    for side, sx, off_walk, off_run in (("l", 1.0, 0.0, 0.0), ("r", -1.0, 0.5, 0.12)):
        p = frac(ph + off_walk + (off_run - off_walk) * run)
        zc = 2.0 + 3.0 * run - 5.0 * crouch
        x = sx * (7.0 + 2.5 * crouch)
        if p < duty:
            sp = p / duty
            z = zc - stride * 0.5 + stride * sp
            y = ANKLE_Y
            toe = 0.0
        else:
            sp = (p - duty) / (1.0 - duty)
            z = zc + stride * 0.5 - stride * smooth(sp)
            y = ANKLE_Y - lift * math.sin(math.pi * sp)
            toe = 0.55 * math.sin(math.pi * sp)
        solve_leg(pose, side, (x, y, z), toe)

    # Arms: hanging and swinging when it walks, front legs when it gallops, reaching for targets.
    for side, sx, off_walk, off_run, target, weight in (
            ("l", 1.0, 0.5, 0.5, st.left_target, st.left_weight),
            ("r", -1.0, 0.0, 0.62, st.right_target, st.right_weight)):
        swing = 0.3 * min(1.0, st.speed / 0.2) * (1.0 - run) * math.sin(tau * (ph + off_walk))
        hang = (-torso - 0.1 + swing - 0.45 * st.scream, 0.0, -0.1 * sx - 0.55 * sx * st.scream)
        hang_fore = -0.25 - 0.3 * st.scream
        arm = hang
        fore = hang_fore
        planted = 0.0
        if run > 0.01:
            p = frac(ph + off_run)
            sh_r, sh_o = pose.frame(f"shoulder_{side}")
            sz = sh_o[2]
            astride = stride * 0.85
            zc = sz - 18.0
            if p < duty:
                sp = p / duty
                z = zc - astride * 0.5 + astride * sp
                y = HAND_Y
                planted = math.sin(math.pi * sp)
            else:
                sp = (p - duty) / (1.0 - duty)
                z = zc + astride * 0.5 - astride * smooth(sp)
                y = HAND_Y - (6.0 + 14.0 * run) * math.sin(math.pi * sp)
            ik, bend = solve_arm(pose, side, (sx * 15.0, y, z), (sx * 0.7, -0.4, 1.0))
            arm = tuple(h + (i - h) * run for h, i in zip(arm, ik))
            fore = fore + (bend - fore) * run
        if target is not None and weight > 0.0:
            ik, bend = solve_arm(pose, side, target, (sx * 0.6, 0.8, 0.3))
            arm = tuple(h + (i - h) * weight for h, i in zip(arm, ik))
            fore = fore + (bend - fore) * weight
        pose.set(f"arm_{side}", rx=arm[0], ry=arm[1], rz=arm[2])
        pose.set(f"fore_{side}", rx=fore)
        pose.set(f"wrist_{side}", rx=-0.1 * (1.0 - run) * (1.0 - weight))
        grip = st.grip if side == "r" else 0.0
        pierce = st.pierce if side == "l" else 0.0
        for k in range(4):
            spread = (k - 1.5) * sx * (0.1 + 0.25 * st.scream + 0.15 * planted * run) * (1.0 - pierce)
            curl = -0.25 - 0.4 * planted * run - 1.0 * grip + 0.2 * pierce
            pose.set(f"finger_{side}{k}", rx=curl, rz=spread)
            pose.set(f"finger_{side}{k}/tip", rx=-0.35 - 0.9 * grip + 0.3 * pierce + 0.2 * planted * run)

    # Tentacles: layered waves, never in step; dragged back at a gallop, flaring when it screams.
    for p in PARTS:
        base = p.name.split("/")[0]
        if base[:2] not in ("ct", "bt") or not base[2:].isdigit():
            continue
        i = int(base[2:])
        k = 0 if "/" not in p.name else int(p.name.split("/s")[-1])
        back = base.startswith("bt")
        along = (k + 1) / 7.0
        amp = (0.07 + 0.15 * along) * (1.0 if back else 1.4) * (1.0 + 1.5 * st.scream + 0.8 * st.tentacle_reach)
        speed = (0.10 + 0.021 * i) * (1.0 + 1.2 * st.scream + 0.6 * run)
        rx = amp * math.sin(st.t * speed + i * 2.1 + k * 0.7)
        ry = amp * math.cos(st.t * speed * 0.83 + i * 1.3 + k * 0.55)
        if back:
            rx += -0.16 * run * along + (0.3 * st.scream if k == 0 else 0.0)
        else:
            rx += 0.1 * run * along - 0.25 * st.tentacle_reach * (1 if k == 0 else 0.3)
        pose.add(p.name, rx=rx, ry=ry)
    return pose


def world_matrices(pose):
    mats = {}
    for p in PARTS:
        v = pose.v[p.name]
        m = np.eye(4)
        m[:3, 3] = v[0:3]
        m[:3, :3] = rot_zyx(v[3], v[4], v[5])
        mats[p.name] = (mats[p.parent.name] if p.parent else np.eye(4)) @ m
    return mats


def model_to_world(m):
    return np.array([m[0] / 16.0, -m[1] / 16.0 + 1.501, -m[2] / 16.0])


def world_to_model(w):
    return np.array([w[0] * 16.0, (1.501 - w[1]) * 16.0, -w[2] * 16.0])


# ====================================================================== preview renderer

LIGHT0 = np.array([0.2, 1.0, -0.7])
LIGHT1 = np.array([-0.2, 1.0, 0.7])
LIGHT0 /= np.linalg.norm(LIGHT0)
LIGHT1 /= np.linalg.norm(LIGHT1)


def box_quads(x0, y0, z0, x1, y1, z1):
    """The six faces of a box, corners ordered so texture coordinates run (u0,v0) (u1,v0) (u1,v1) (u0,v1)."""
    return {
        "top": [(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)],
        "bottom": [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)],
        "west": [(x0, y0, z1), (x0, y0, z0), (x0, y1, z0), (x0, y1, z1)],
        "north": [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0)],
        "east": [(x1, y0, z0), (x1, y0, z1), (x1, y1, z1), (x1, y1, z0)],
        "south": [(x1, y0, z1), (x0, y0, z1), (x0, y1, z1), (x1, y1, z1)],
    }


GROUND = np.zeros((16, 16, 4))
GROUND[..., 3] = 255
GROUND[..., :3] = np.where(((np.indices((16, 16)).sum(0)) % 2 == 0)[..., None], [70, 66, 52], [58, 55, 44])
VICTIM = np.zeros((2, 2, 4))
VICTIM[...] = [150, 120, 100, 255]


def render(tex, glow, pose, yaw_deg, pitch_deg, size=720, dark=False, distance=12.0, victim=None, target_y=3.0, target_z=0.0):
    """Perspective view of the posed model. World: blocks, y up, IT standing at the origin facing +Z."""
    mats = world_matrices(pose)
    img = np.zeros((size, size, 3))
    img[:] = (6, 6, 8) if dark else (122, 128, 138)
    zbuf = np.full((size, size), np.inf)
    yaw = math.radians(yaw_deg)
    pitch = math.radians(pitch_deg)
    target = np.array([0.0, target_y, target_z])
    eye = target + distance * np.array([math.sin(yaw) * math.cos(pitch), math.sin(pitch), math.cos(yaw) * math.cos(pitch)])
    fwd = target - eye
    fwd /= np.linalg.norm(fwd)
    right = np.cross(fwd, [0, 1, 0])
    right /= np.linalg.norm(right)
    up = np.cross(right, fwd)
    focal = size * 1.15

    def project(w):
        rel = w - eye
        cz = rel @ fwd
        return np.stack([size / 2 + focal * (rel @ right) / cz, size / 2 - focal * (rel @ up) / cz, cz], -1)

    def draw_quad(world, uv, texture, glow_tex, lit):
        scr = project(world)
        if np.any(scr[:, 2] < 0.1):
            return
        normal = np.cross(world[1] - world[0], world[3] - world[0])
        nl = np.linalg.norm(normal)
        if nl < 1e-9:
            return
        normal /= nl
        if np.dot(normal, eye - world[0]) < 0:
            normal = -normal
        shade = min(1.0, 0.4 + 0.6 * (max(0.0, normal @ LIGHT0) + max(0.0, normal @ LIGHT1))) if lit else 1.0
        for tri in ((0, 1, 2), (0, 2, 3)):
            raster(img, zbuf, scr[list(tri)], uv[list(tri)], texture, glow_tex, shade, dark)

    for gx in range(-12, 12, 2):
        for gz in range(-12, 12, 2):
            draw_quad(np.array([[gx, 0, gz], [gx + 2, 0, gz], [gx + 2, 0, gz + 2], [gx, 0, gz + 2]], float),
                      np.array([[0, 0], [2, 0], [2, 2], [0, 2]], float), GROUND, None, True)
    if victim is not None:
        vx, vy, vz = victim
        for pts in box_quads(vx - 0.3, vy, vz - 0.3, vx + 0.3, vy + 1.8, vz + 0.3).values():
            draw_quad(np.array(pts, float), np.array([[0, 0], [2, 0], [2, 2], [0, 2]], float), VICTIM, None, True)
    for p in PARTS:
        m = mats[p.name]
        for b in p.boxes:
            rects = face_rects(b)
            for face, pts in box_quads(b.x, b.y, b.z, b.x + b.w, b.y + b.h, b.z + b.d).items():
                local = np.array(pts, dtype=float)
                homo = np.concatenate([local, np.ones((4, 1))], 1) @ m.T
                world = np.array([model_to_world(c) for c in homo[:, :3]])
                u0, v0, uw, vh = rects[face]
                uv = np.array([[u0, v0], [u0 + uw, v0], [u0 + uw, v0 + vh], [u0, v0 + vh]], dtype=float)
                draw_quad(world, uv, tex, glow, True)
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))


def raster(img, zbuf, s, uv, tex, glow, shade, dark):
    size = img.shape[0]
    th, tw = tex.shape[0], tex.shape[1]
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
    ui = np.clip(np.floor(u).astype(int), 0, tw - 1)
    vi = np.clip(np.floor(v).astype(int), 0, th - 1)
    texel = tex[vi, ui]
    ok = inside & (texel[..., 3] >= 26) & (depth < zbuf[ys, xs])
    if not ok.any():
        return
    color = texel[..., :3] * (shade * (0.07 if dark else 1.0))
    if glow is not None:
        color = color + glow[vi, ui, :3] * (glow[vi, ui, 3:4] / 255.0)
    sel_y, sel_x = ys[ok], xs[ok]
    img[sel_y, sel_x] = color[ok]
    zbuf[sel_y, sel_x] = depth[ok]


def gait_report(speed, run, steps=16):
    """Lowest point of every foot and hand over one gait cycle (blocks above the ground)."""
    rows = []
    for i in range(steps):
        ph = i / steps
        pose = pose_model(State(run=run, speed=speed, phase=ph, t=i * 3.0))
        mats = world_matrices(pose)
        row = []
        for name in ("foot_l", "foot_r", "hand_l", "hand_r", "finger_l1/tip", "finger_r1/tip"):
            m = mats[name]
            lowest = 99.0
            for b in BY_NAME[name].boxes:
                for c in box_quads(b.x, b.y, b.z, b.x + b.w, b.y + b.h, b.z + b.d)["bottom"] + box_quads(b.x, b.y, b.z, b.x + b.w, b.y + b.h, b.z + b.d)["top"]:
                    w = model_to_world((m @ np.array([*c, 1.0]))[:3])
                    lowest = min(lowest, w[1])
            row.append(lowest)
        rows.append((ph, row))
    return rows


def preview(tex, glow, out_dir):
    out_dir.mkdir(parents=True, exist_ok=True)
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

    def sheet(name, views, size=720):
        tiles = [render(tex, glow, v["pose"], v["yaw"], v["pitch"], size=size, dark=v.get("dark", False),
                        victim=v.get("victim"), distance=v.get("distance", 12.0)) for v in views]
        out = Image.new("RGB", (size * len(tiles), size))
        for i, tile in enumerate(tiles):
            out.paste(tile, (i * size, 0))
        out.save(out_dir / name)

    rest = Pose()
    stand = pose_model(State(t=10))
    sheet("it_rest.png", [dict(pose=rest, yaw=0, pitch=10), dict(pose=rest, yaw=90, pitch=5)]
          + [dict(pose=stand, yaw=y, pitch=8) for y in (0, 40, 90, 180)])
    sheet("it_gallop.png", [dict(pose=pose_model(State(run=1.0, speed=0.78, phase=i / 6, t=i * 2.0)), yaw=90, pitch=4)
                            for i in range(6)], size=560)
    sheet("it_gallop_34.png", [dict(pose=pose_model(State(run=1.0, speed=0.78, phase=i / 3 + 0.1, t=i * 3.0)), yaw=y, pitch=10)
                               for i, y in enumerate((30, 150, 210))])
    sheet("it_walk.png", [dict(pose=pose_model(State(run=0.0, speed=0.18, phase=i / 4, t=i * 7.0)), yaw=90, pitch=4)
                          for i in range(4)], size=560)
    sheet("it_poses.png", [dict(pose=pose_model(State(crouch=1.0, t=3)), yaw=110, pitch=8),
                           dict(pose=pose_model(State(scream=1.0, t=5)), yaw=20, pitch=10)])
    for name, view in kill_views():
        sheet(name, view)
    sheet("it_dark.png", [dict(pose=stand, yaw=0, pitch=6, dark=True),
                          dict(pose=pose_model(State(run=1.0, speed=0.78, phase=0.3, t=7)), yaw=40, pitch=6, dark=True),
                          dict(pose=pose_model(State(scream=1.0, t=2)), yaw=0, pitch=4, dark=True)])
    vig = Image.fromarray(np.clip(kill_vignette(), 0, 255).astype(np.uint8), "RGBA")
    shot = Image.new("RGBA", (512, 288), (90, 96, 104, 255))
    shot.alpha_composite(vig.resize((512, 288), Image.BILINEAR))
    shot.save(out_dir / "kill_vignette_on_grey.png")
    lines = []
    for speed, run in ((0.78, 1.0), (0.18, 0.0)):
        lines.append(f"speed {speed} run {run}: phase  foot_l foot_r hand_l hand_r tip_l tip_r (blocks above ground)")
        for ph, row in gait_report(speed, run):
            lines.append(f"  {ph:4.2f}  " + "  ".join(f"{v:6.2f}" for v in row))
    (out_dir / "gait.txt").write_text("\n".join(lines) + "\n")


# ItKillPath's keyframes (victim's feet: right, up, forward) and timeline, mirrored for the previews.
KILL_SEIZE_END, KILL_LIFT_END, KILL_PIERCE_AT, KILL_DEVOUR_START = 20, 45, 52, 72
KILL_BITE_AT, KILL_TEAR_AT, KILL_SWALLOW_AT, KILL_END = 82, 90, 100, 108
KILL_TIMES = [KILL_SEIZE_END, KILL_LIFT_END, KILL_PIERCE_AT, KILL_PIERCE_AT + 5, KILL_DEVOUR_START, KILL_SWALLOW_AT, KILL_END]
KILL_KEYS = [(0.45, 0.25, 2.9), (0.3, 4.7, 3.3), (0.3, 4.78, 3.5), (0.3, 4.66, 3.25), (0.25, 4.64, 3.2), (0.05, 4.55, 2.75),
             (0.0, 4.6, 2.55)]


def kill_path(t, start=(0.0, 0.0, 2.6)):
    low = max(0.0, min(3.0, start[1]))
    if t <= KILL_SEIZE_END:
        a = smooth(t / KILL_SEIZE_END)
        return tuple(s0 + (k + (low if i == 1 else 0.0) - s0) * a for i, (s0, k) in enumerate(zip(start, KILL_KEYS[0])))
    for i in range(1, len(KILL_TIMES)):
        if t <= KILL_TIMES[i]:
            a = smooth((t - KILL_TIMES[i - 1]) / (KILL_TIMES[i] - KILL_TIMES[i - 1]))
            prev = list(KILL_KEYS[i - 1])
            if i == 1:
                prev[1] += low
            return tuple(p0 + (k - p0) * a for p0, k in zip(prev, KILL_KEYS[i]))
    return KILL_KEYS[-1]


def bite(kt, at):
    d = kt - at
    return 0.0 if d < 0 or d > 6 else (d / 1.5 if d < 1.5 else 1.0 - (d - 1.5) / 4.5)


def kill_state(kt):
    """The kill branch of ItModel.setupAnim, for a victim carried along the path."""
    r, u, f = kill_path(kt)
    feet = np.array([-r, u, f])
    rt = world_to_model(feet + np.array([0.0, 1.8 * 0.53, 0.0]))
    chest = world_to_model(feet + np.array([0.0, 1.8 * 0.7, 0.0]))
    st = dict(right_target=rt, right_weight=smooth(kt / 6.0), grip=smooth((kt - 3) / 8.0))
    st["crouch"] = 0.9 if kt < KILL_SEIZE_END else 0.9 * (1.0 - smooth((kt - KILL_SEIZE_END) / 18.0))
    jaw = 0.2 + 0.3 * smooth((kt - KILL_SEIZE_END) / 25.0)
    left_weight = 0.0
    pierce = 0.0
    reach = 0.0
    neck = 0.0
    lt = None
    windup = KILL_PIERCE_AT - 8.0
    if kt >= windup:
        back = smooth((kt - windup) / 6.0)
        strike = smooth((kt - KILL_PIERCE_AT + 1.0) / 2.5)
        lt = np.array([chest[0] + 12.0 * (1 - strike), chest[1] - 4.0 * (1 - strike), chest[2] + 22.0 * (1 - strike) - 8.0 * strike])
        left_weight = back
        pierce = strike
        if kt > KILL_DEVOUR_START + 10:
            hold = smooth((kt - KILL_DEVOUR_START - 10) / 10.0)
            lt = lt + (np.array([chest[0] + 9.0, chest[1] + 2.0, chest[2] + 3.0]) - lt) * hold
            pierce *= 1.0 - hold
    if kt >= KILL_DEVOUR_START:
        devour = smooth((kt - KILL_DEVOUR_START) / 8.0)
        jaw = jaw + (1.35 - jaw) * devour
        jaw -= 0.9 * (bite(kt, KILL_BITE_AT) + bite(kt, KILL_TEAR_AT) + 0.6 * bite(kt, KILL_SWALLOW_AT))
        neck = 0.35 * devour
        reach = devour
    elif kt >= KILL_LIFT_END:
        reach = 0.4
    st.update(jaw=jaw, left_target=lt, left_weight=left_weight, pierce=pierce, tentacle_reach=reach, neck_extra=neck, t=kt)
    return State(**st), tuple(feet)


def kill_views():
    views = []
    for kt in (12, 40, 56, 96):
        state, feet = kill_state(kt)
        views.append(dict(pose=pose_model(state), yaw=-55, pitch=8, victim=feet, distance=11.0))
    return [("it_kill.png", views)]


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
