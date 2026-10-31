#!/usr/bin/env python3
"""The figure ("stalker"): its model, its textures and offline previews (deterministic, original art).

This script is the single source of truth for the figure's geometry. It
  * describes every part of the model (pivots, rest rotations, boxes, materials),
  * packs the boxes onto a 64x64 texture and paints them,
  * writes src/main/java/dev/kristian/nullmod/client/stalker/StalkerModelLayer.java (the
    LayerDefinition, generated: never edit it by hand),
  * writes the textures into src/main/resources/assets/null/textures/entity/stalker/:
      stalker.png       a tall, thin, featureless black body (and its old pickaxe)
      stalker_eyes.png  emissive layer: two pin-prick eyes, nothing else
  * and with --preview DIR renders what it made: the unwrapped texture with every box outlined,
    and the model (software rasteriser, Minecraft's entity lighting, emissive layer) in the poses
    StalkerModel animates: standing, peeking around a tree trunk, at a window, digging, running,
    hunched under a low ceiling; lit and in near-total darkness.

The figure is player-shaped but wrong: about 2.4 blocks tall, a small head on a long neck, a
narrow chest, arms that hang to the middle of the shins, long fingers, long thin legs.

usage: python3 tools/gen_stalkers.py [--preview DIR]
"""
import argparse
import math
import pathlib

import numpy as np
from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent.parent
TEX_OUT = ROOT / "src" / "main" / "resources" / "assets" / "null" / "textures" / "entity" / "stalker"
JAVA_OUT = ROOT / "src" / "main" / "java" / "dev" / "kristian" / "nullmod" / "client" / "stalker" / "StalkerModelLayer.java"
TEX_W = 64
TEX_H = 64
SEED = 0x5354414C  # "STAL"


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
    def __init__(self, name, parent, pivot, rot, boxes):
        self.name = name
        self.parent = parent
        self.pivot = tuple(float(c) for c in pivot)
        self.rot = tuple(float(c) for c in rot)
        self.boxes = [Box(*b) for b in boxes]
        self.children = []
        if parent is not None:
            assert all(c.name != self.name for c in parent.children), f"duplicate child {self.name}"
            parent.children.append(self)


PARTS = []
BY_NAME = {}


def part(name, parent, pivot, rot, boxes):
    assert name not in BY_NAME, f"duplicate part {name}"
    p = Part(name, BY_NAME[parent] if parent else None, pivot, rot, boxes)
    PARTS.append(p)
    BY_NAME[name] = p
    return p


# Rest pose constants, mirrored by StalkerModel (keep in step by hand).
HIP_Y = 7.0           # hip joint above the ground: 24 - 17 px of leg
BODY_LEAN = 0.10      # the chest leans forward a little
NECK_BEND = 0.18
HEAD_LIFT = -0.26     # the head lifts again so the face looks ahead
SHOULDER_X = 3.8


def build_model():
    # Model space (pixels): +X is the figure's left, +Y is down, -Z is its front; the ground is y = 24.
    part("hips", None, (0, HIP_Y, 0), (0, 0, 0), [
        (-3, -1.5, -1.5, 6, 3, 3, "skin"),
    ])
    part("body", "hips", (0, -1, 0), (BODY_LEAN, 0, 0), [
        (-3, -11, -1.5, 6, 11, 3, "skin"),
        # The shoulder blades show through the back.
        (-2.5, -10, 1.5, 2, 3, 1, "skin"),
        (0.5, -10, 1.5, 2, 3, 1, "skin"),
    ])
    part("neck", "body", (0, -11, 0), (NECK_BEND, 0, 0), [
        (-1, -3, -1, 2, 3, 2, "skin"),
    ])
    part("head", "neck", (0, -3, -0.2), (HEAD_LIFT, 0, 0), [
        (-2.5, -6, -2.5, 5, 6, 5, "face"),
    ])
    for side, sx in (("right", -1), ("left", 1)):
        part(f"arm_{side}", "body", (SHOULDER_X * sx, -10, 0), (0.05, 0, 0.07 * sx), [
            (-1, -1, -1, 2, 10, 2, "skin"),
        ])
        part(f"forearm_{side}", f"arm_{side}", (0, 9, 0), (-0.12, 0, 0), [
            (-1, 0, -1, 2, 9, 2, "skin"),
        ])
        part(f"hand_{side}", f"forearm_{side}", (0, 9, 0), (0.0, 0, 0), [
            (-1, 0, -1, 2, 2, 2, "skin"),
        ])
        # Long fingers, nested so they can curl.
        part(f"fingers_{side}", f"hand_{side}", (0, 2, 0), (0.15, 0, 0), [
            (-1, 0, -1, 1, 5, 1, "finger"),
            (0, 0, -1, 1, 4, 1, "finger"),
            (-0.5 if sx < 0 else -0.5, 0, 0, 1, 3, 1, "finger"),
        ])
        part(f"thigh_{side}", "hips", (1.6 * sx, 0, 0), (0, 0, 0.015 * sx), [
            (-1.5, 0, -1.5, 3, 9, 3, "skin"),
        ])
        part(f"shin_{side}", f"thigh_{side}", (0, 9, 0), (0, 0, 0), [
            (-1, 0, -1, 2, 7, 2, "skin"),
        ])
        part(f"foot_{side}", f"shin_{side}", (0, 7, 0), (0, 0, 0), [
            (-1, 0, -3, 2, 1, 4, "skin"),
        ])
    # The old pickaxe in the right hand (only shown while it digs): the handle hangs on from the fist
    # like a longer forearm, the head crosses its end with the long striking spike behind, so it
    # leads when the swing comes over the top and down.
    part("pickaxe", "hand_right", (0, 1, 0), (0, 0, 0), [
        (-0.5, 0, -0.5, 1, 12, 1, "wood"),
        (-0.5, 11, -3, 1, 1, 9, "iron"),
        (-0.5, 12, 5, 1, 1, 2, "iron_tip"),
        (-0.5, 12, -4, 1, 1, 1, "iron_tip"),
    ])


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
        "west": (u, v + d, d, h),
        "north": (u + d, v + d, w, h),
        "east": (u + d + w, v + d, d, h),
        "south": (u + 2 * d + w, v + d, w, h),
    }


# ====================================================================== painting

def hash2(ix, iy, seed):
    h = (ix * 374761393 + iy * 668265263 + seed * 2246822519) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((h ^ (h >> 16)) & 0xFFFF) / 65535.0


def paint():
    rng = np.random.default_rng(SEED)
    tex = np.zeros((TEX_H, TEX_W, 4), dtype=np.float64)
    glow = np.zeros((TEX_H, TEX_W, 4), dtype=np.float64)
    for p in PARTS:
        for bi, b in enumerate(p.boxes):
            for face, (u0, v0, uw, vh) in face_rects(b).items():
                for j in range(vh):
                    for i in range(uw):
                        n = hash2(u0 + i, v0 + j, SEED) - 0.5
                        n2 = hash2((u0 + i) // 2, (v0 + j) // 2, SEED + 7) - 0.5
                        top = face == "top"
                        edge = i == 0 or j == 0 or i == uw - 1 or j == vh - 1
                        if b.material in ("skin", "face"):
                            # Matte black with the faintest bloom of grey, darker at the edges.
                            base = 15.0 + 6.0 * n2 + 3.0 * n + (4.0 if top else 0.0) - (4.0 if edge else 0.0)
                            c = (base, base, base + 1.5)
                        elif b.material == "finger":
                            base = 9.0 + 3.0 * n
                            c = (base, base, base + 1.0)
                        elif b.material == "wood":
                            grain = math.sin((j + 0.5) * 1.7 + 3.0 * n2) * 0.5 + 0.5
                            c = (36 + 14 * grain + 6 * n, 28 + 10 * grain + 4 * n, 22 + 7 * grain + 3 * n)
                        elif b.material == "iron":
                            rust = hash2((u0 + i) * 3, (v0 + j) * 5, SEED + 11)
                            if rust > 0.72:
                                c = (78 + 20 * n, 44 + 10 * n, 28 + 6 * n)
                            else:
                                c = (52 + 12 * n2 + 6 * n, 49 + 10 * n2 + 5 * n, 48 + 9 * n2 + 5 * n)
                        else:  # iron_tip: a worn, brighter edge
                            c = (96 + 10 * n, 92 + 10 * n, 88 + 10 * n)
                        tex[v0 + j, u0 + i] = (*c, 255)
                if b.material == "face" and face == "north":
                    # Two pin-prick eyes, 2 px apart, a third of the way down the face, each in a
                    # socket of pure black so they read as holes with light inside.
                    row = 2
                    for col in (1, 3):
                        tex[v0 + row, u0 + col] = (2, 2, 3, 255)
                        glow[v0 + row, u0 + col] = (244, 240, 232, 255)
                    for col in (0, 2, 4):
                        tex[v0 + row, u0 + col, :3] *= 0.55
                    for col in (1, 3):
                        tex[v0 + row - 1, u0 + col, :3] *= 0.6
                        tex[v0 + row + 1, u0 + col, :3] *= 0.7
    return np.clip(tex, 0, 255), glow


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
        call = f'{parent_var}.addOrReplaceChild("{p.name}", {cubes},\n                {pose});'
        lines.append(f"        {'PartDefinition ' + var + ' = ' if var else ''}{call}")
        for c in p.children:
            emit(c, var)

    for p in PARTS:
        if p.parent is None:
            emit(p, "root")
    body = "\n".join(lines)
    boxes = sum(len(p.boxes) for p in PARTS)
    java = f'''package dev.kristian.nullmod.client.stalker;

import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

/**
 * The figure's geometry: {len(PARTS)} parts, {boxes} boxes on a {TEX_W}x{TEX_H} texture.
 * <p>
 * GENERATED by {{@code tools/gen_stalkers.py}}, which also paints the textures for exactly these
 * boxes: change the model there and run the script again, never edit this file by hand.
 * <p>
 * Model space is in pixels (1/16 block): +X is the figure's left, +Y is down, -Z is its front, the
 * ground is at y = 24. Every part name is unique, so {{@link StalkerModel}} finds them by name.
 */
final class StalkerModelLayer {{
    static final int TEXTURE_WIDTH = {TEX_W};
    static final int TEXTURE_HEIGHT = {TEX_H};

    private StalkerModelLayer() {{
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


# ====================================================================== posing (mirror of StalkerModel)

def rot_zyx(x, y, z):
    cx, sx = math.cos(x), math.sin(x)
    cy, sy = math.cos(y), math.sin(y)
    cz, sz = math.cos(z), math.sin(z)
    rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return rz @ ry @ rx


class State:
    """The inputs StalkerModel.apply() reads (StalkerModel.Pose)."""

    def __init__(self, **kw):
        self.walk = 0.0          # stride phase in radians
        self.walk_amount = 0.0   # 0..1
        self.run = 0.0           # 0..1 sprint
        self.peek = 0.0          # -1..1: lean out to its right (-) or left (+)
        self.dig = 0.0           # 0..1 digging stance
        self.dig_phase = 0.0     # 0..1 through one swing
        self.glass = 0.0         # 0..1 hand raised flat against a window
        self.hunch = 0.0         # 0..1 bent under a low ceiling
        self.head_yaw = 0.0      # radians
        self.head_pitch = 0.0    # radians
        self.time = 0.0          # ticks
        self.__dict__.update(kw)


SWING_READY = -0.95
SWING_RAISED = -3.35
SWING_HIT = -1.35


def swing_curve(phase):
    """Arm angle through one pickaxe swing: a slow lift over the head, a fast blow down in front, a
    short rest (mirrors StalkerModel.swing). The blow lands at phase 0.68."""
    if phase < 0.55:
        t = phase / 0.55
        return SWING_READY + (SWING_RAISED - SWING_READY) * (t * t * (3 - 2 * t))
    if phase < 0.68:
        t = (phase - 0.55) / 0.13
        return SWING_RAISED + (SWING_HIT - SWING_RAISED) * t * t
    t = (phase - 0.68) / 0.32
    return SWING_HIT + (SWING_READY - SWING_HIT) * t * t


def pose_model(st):
    """Per-part (x, y, z, xRot, yRot, zRot) exactly as StalkerModel.apply sets them."""
    v = {p.name: [*p.pivot, *p.rot] for p in PARTS}
    breathe = 0.02 * math.sin(st.time * 0.08)
    run = st.run
    hunch = st.hunch
    # Hips: drop when hunched or running; shift sideways to peek.
    v["hips"][0] += 4.0 * st.peek
    v["hips"][1] += 1.5 * hunch + 1.2 * run
    v["hips"][5] += -0.08 * st.peek
    # Digging: leaning back as the pick goes up, forward into the blow.
    dig_lean = st.dig * (0.28 - 0.4 * (swing_curve(st.dig_phase) - SWING_HIT) / (SWING_RAISED - SWING_HIT)) if st.dig > 0.0 else 0.0
    v["body"][3] = BODY_LEAN + breathe + 0.5 * run + 0.42 * hunch + dig_lean
    v["body"][5] = 0.42 * st.peek
    v["neck"][3] = NECK_BEND + 0.35 * hunch
    v["neck"][4] = st.head_yaw * 0.35
    v["head"][3] = HEAD_LIFT - 0.4 * run - 0.6 * hunch + st.head_pitch
    v["head"][4] = st.head_yaw * 0.65
    v["head"][5] = 0.25 * st.peek
    # Legs: long slow strides, or a sprint.
    stride = 0.5 * st.walk_amount * (1 - run) + 1.05 * run
    for side, sign in (("right", 1.0), ("left", -1.0)):
        phase = st.walk + (0.0 if side == "right" else math.pi)
        swing = math.sin(phase) * stride
        knee = max(0.0, -math.sin(phase - 0.7)) * (0.7 * st.walk_amount * (1 - run) + 1.5 * run)
        v[f"thigh_{side}"][3] = swing - 0.25 * hunch - 0.35 * run
        v[f"shin_{side}"][3] = knee + 0.45 * hunch
        v[f"foot_{side}"][3] = -0.2 * knee
    # Arms: hanging, barely swinging when it walks; trailing behind at a sprint.
    for side, sx in (("right", -1.0), ("left", 1.0)):
        phase = st.walk + (math.pi if side == "right" else 0.0)
        arm = v[f"arm_{side}"]
        fore = v[f"forearm_{side}"]
        fingers = v[f"fingers_{side}"]
        arm[3] = 0.05 + 0.08 * math.sin(phase) * st.walk_amount * (1 - run) + run * (0.95 + 0.15 * math.sin(phase))
        arm[5] = 0.07 * sx + 0.12 * sx * run
        fore[3] = -0.12 - 0.2 * run
        fingers[3] = 0.15
    # Peeking: the near hand comes round the trunk's edge at head height, fingers curled over it.
    if st.peek != 0.0:
        near = "left" if st.peek > 0 else "right"
        sx = 1.0 if near == "left" else -1.0
        amount = abs(st.peek)
        v[f"arm_{near}"][3] += -1.6 * amount
        v[f"arm_{near}"][5] += -0.82 * sx * amount
        v[f"forearm_{near}"][3] += -0.58 * amount
        v[f"fingers_{near}"][3] += 0.95 * amount
    # Digging: both hands on the handle, the swing from the shoulders.
    if st.dig > 0.0:
        a = swing_curve(st.dig_phase)
        for side, sx in (("right", -1.0), ("left", 1.0)):
            v[f"arm_{side}"][3] = (1 - st.dig) * v[f"arm_{side}"][3] + st.dig * a
            v[f"arm_{side}"][5] = (1 - st.dig) * v[f"arm_{side}"][5] + st.dig * (-0.3 * sx)
            v[f"forearm_{side}"][3] = (1 - st.dig) * v[f"forearm_{side}"][3] + st.dig * -0.25
            v[f"fingers_{side}"][3] = 0.9
    # At the window: one hand flat on the glass.
    if st.glass > 0.0:
        g = st.glass
        v["arm_left"][3] = (1 - g) * v["arm_left"][3] + g * -2.9
        v["arm_left"][5] = (1 - g) * v["arm_left"][5] + g * -0.2
        v["forearm_left"][3] = (1 - g) * v["forearm_left"][3] + g * 1.9
        v["fingers_left"][3] = (1 - g) * v["fingers_left"][3] + g * -0.2
    return v


def world_matrices(pose):
    mats = {}
    for p in PARTS:
        v = pose[p.name]
        m = np.eye(4)
        m[:3, 3] = v[0:3]
        m[:3, :3] = rot_zyx(v[3], v[4], v[5])
        mats[p.name] = (mats[p.parent.name] if p.parent else np.eye(4)) @ m
    return mats


def model_to_world(m):
    return np.array([m[0] / 16.0, -m[1] / 16.0 + 1.501, -m[2] / 16.0])


# ====================================================================== preview renderer

LIGHT0 = np.array([0.2, 1.0, -0.7])
LIGHT1 = np.array([-0.2, 1.0, 0.7])
LIGHT0 /= np.linalg.norm(LIGHT0)
LIGHT1 /= np.linalg.norm(LIGHT1)


def box_quads(x0, y0, z0, x1, y1, z1):
    return {
        "top": [(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)],
        "bottom": [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)],
        "west": [(x0, y0, z1), (x0, y0, z0), (x0, y1, z0), (x0, y1, z1)],
        "north": [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0)],
        "east": [(x1, y0, z0), (x1, y0, z1), (x1, y1, z1), (x1, y1, z0)],
        "south": [(x1, y0, z1), (x0, y0, z1), (x0, y1, z1), (x1, y1, z1)],
    }


def solid(rgb):
    t = np.zeros((2, 2, 4))
    t[...] = (*rgb, 255)
    return t


GROUND = np.zeros((16, 16, 4))
GROUND[..., 3] = 255
GROUND[..., :3] = np.where(((np.indices((16, 16)).sum(0)) % 2 == 0)[..., None], [70, 92, 52], [60, 80, 44])
BARK = solid((92, 70, 46))
WALL = solid((150, 118, 80))
GLASS_EDGE = solid((190, 210, 220))


def render(tex, glow, pose, yaw_deg, pitch_deg, size=520, dark=False, distance=6.5, target_y=1.25, props=(), show=None):
    mats = world_matrices(pose)
    img = np.zeros((size, size, 3))
    img[:] = (5, 5, 7) if dark else (126, 134, 146)
    zbuf = np.full((size, size), np.inf)
    yaw = math.radians(yaw_deg)
    pitch = math.radians(pitch_deg)
    target = np.array([0.0, target_y, 0.0])
    eye = target + distance * np.array([math.sin(yaw) * math.cos(pitch), math.sin(pitch), math.cos(yaw) * math.cos(pitch)])
    fwd = target - eye
    fwd /= np.linalg.norm(fwd)
    right = np.cross(fwd, [0, 1, 0])
    right /= np.linalg.norm(right)
    up = np.cross(right, fwd)
    focal = size * 1.1

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

    unit = np.array([[0, 0], [2, 0], [2, 2], [0, 2]], float)
    for gx in range(-5, 5):
        for gz in range(-5, 5):
            draw_quad(np.array([[gx, 0, gz], [gx + 1, 0, gz], [gx + 1, 0, gz + 1], [gx, 0, gz + 1]], float),
                      np.array([[0, 0], [16, 0], [16, 16], [0, 16]], float), GROUND, None, True)
    for (x0, y0, z0, x1, y1, z1, texture) in props:
        for pts in box_quads(x0, y0, z0, x1, y1, z1).values():
            draw_quad(np.array(pts, float), unit, texture, None, True)
    for p in PARTS:
        if show is not None and not show(p):
            continue
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
    color = texel[..., :3] * (shade * (0.06 if dark else 1.0))
    if glow is not None:
        color = color + glow[vi, ui, :3] * (glow[vi, ui, 3:4] / 255.0)
    sel_y, sel_x = ys[ok], xs[ok]
    img[sel_y, sel_x] = color[ok]
    zbuf[sel_y, sel_x] = depth[ok]


def preview(tex, glow, out_dir):
    out_dir.mkdir(parents=True, exist_ok=True)
    # The unwrapped texture, 8x, with every box outlined.
    big = Image.fromarray(np.clip(tex, 0, 255).astype(np.uint8), "RGBA").resize((TEX_W * 8, TEX_H * 8), Image.NEAREST)
    sheet = Image.new("RGBA", big.size, (90, 60, 90, 255))
    sheet.alpha_composite(big)
    gbig = Image.fromarray(np.clip(glow, 0, 255).astype(np.uint8), "RGBA").resize((TEX_W * 8, TEX_H * 8), Image.NEAREST)
    sheet.alpha_composite(gbig)
    draw = ImageDraw.Draw(sheet)
    for p in PARTS:
        for b in p.boxes:
            for (u0, v0, uw, vh) in face_rects(b).values():
                draw.rectangle([u0 * 8, v0 * 8, (u0 + uw) * 8 - 1, (v0 + vh) * 8 - 1], outline=(255, 200, 0, 255))
    sheet.save(out_dir / "texture_sheet.png")

    hide_pick = lambda p: p.name != "pickaxe"
    rest = State(time=10)
    bright = tex.copy()
    bright[..., :3] = np.clip(bright[..., :3] * 7.0, 0, 255)
    shots = [
        ("front", render(tex, glow, pose_model(rest), 0, 8, show=hide_pick)),
        ("front_bright", render(bright, glow, pose_model(rest), 20, 8, show=hide_pick)),
        ("three_quarter", render(tex, glow, pose_model(State(time=10, head_yaw=0.5)), 35, 10, show=hide_pick)),
        ("side", render(tex, glow, pose_model(rest), 90, 5, show=hide_pick)),
        ("back", render(tex, glow, pose_model(rest), 180, 10, show=hide_pick)),
        ("dark_front", render(tex, glow, pose_model(rest), 0, 8, dark=True, show=hide_pick)),
        ("walk", render(tex, glow, pose_model(State(walk=1.2, walk_amount=1.0)), 70, 6, show=hide_pick)),
    ]
    # Peeking around a trunk, seen by the player standing in front of it (the trunk is between).
    trunk = (-0.5 + 0.0, 0.0, 0.55, 0.5, 4.0, 1.55, BARK)
    for amount, label in ((0.0, "hidden"), (1.0, "peek_left"), (-1.0, "peek_right")):
        pose = pose_model(State(time=10, peek=amount, head_yaw=-0.25 * amount))
        shots.append((f"tree_{label}", render(tex, glow, pose, 0, 4, distance=9.0, target_y=1.4, props=[trunk], show=hide_pick)))
    shots.append(("tree_peek_dark", render(tex, glow, pose_model(State(time=10, peek=1.0)), 0, 4, distance=9.0, target_y=1.4,
                                           dark=True, props=[trunk], show=hide_pick)))
    # Digging: the swing from the side, and the tunnel view (dark) from the front.
    for phase, label in ((0.0, "ready"), (0.5, "raised"), (0.64, "blow"), (0.69, "hit")):
        shots.append((f"dig_{label}", render(tex, glow, pose_model(State(time=10, dig=1.0, dig_phase=phase)), 75, 5)))
    shots.append(("dig_dark_tunnel", render(tex, glow, pose_model(State(time=10, dig=1.0, dig_phase=0.62, hunch=1.0)), 0, 0,
                                            dark=True, distance=8.0, target_y=1.2)))
    # Running at the viewer, and in profile.
    shots.append(("run_front", render(tex, glow, pose_model(State(run=1.0, walk=0.9, time=10)), 0, 5, show=hide_pick)))
    shots.append(("run_side", render(tex, glow, pose_model(State(run=1.0, walk=0.9, time=10)), 90, 5, show=hide_pick)))
    shots.append(("run_dark", render(tex, glow, pose_model(State(run=1.0, walk=2.4, time=10)), 0, 5, dark=True,
                                     show=hide_pick)))
    # At a window: a wall with a one-block hole of glass, the figure outside with a hand on the glass.
    wall = [(-2.0, 0.0, 0.45, -0.5, 3.2, 0.55, WALL), (0.5, 0.0, 0.45, 2.0, 3.2, 0.55, WALL),
            (-0.5, 0.0, 0.45, 0.5, 1.5, 0.55, WALL), (-0.5, 2.5, 0.45, 0.5, 3.2, 0.55, WALL)]
    window_pose = pose_model(State(time=10, glass=1.0))
    shots.append(("window_inside", render(tex, glow, window_pose, 0, 0, distance=4.0, target_y=2.0, props=wall, show=hide_pick)))
    shots.append(("window_inside_dark", render(tex, glow, window_pose, 0, 0, distance=4.0, target_y=2.0, props=wall, dark=True,
                                               show=hide_pick)))
    shots.append(("window_side", render(tex, glow, window_pose, 90, 0, distance=4.0, target_y=1.6, props=wall, show=hide_pick)))
    shots.append(("hunch", render(tex, glow, pose_model(State(time=10, hunch=1.0)), 80, 5, show=hide_pick)))
    for name, image in shots:
        image.save(out_dir / f"{name}.png")
    # One contact sheet.
    cols = 6
    w, h = shots[0][1].size
    rows = (len(shots) + cols - 1) // cols
    contact = Image.new("RGB", (cols * w // 2, rows * (h // 2 + 14)), (0, 0, 0))
    draw = ImageDraw.Draw(contact)
    for i, (name, image) in enumerate(shots):
        x = (i % cols) * w // 2
        y = (i // cols) * (h // 2 + 14)
        contact.paste(image.resize((w // 2, h // 2)), (x, y + 14))
        draw.text((x + 4, y + 1), name, fill=(230, 230, 230))
    contact.save(out_dir / "contact.png")


# ====================================================================== main

def save_png(path, rgba):
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(np.clip(rgba, 0, 255).astype(np.uint8), "RGBA").save(path, optimize=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview", type=pathlib.Path, help="directory for preview renders")
    args = parser.parse_args()
    build_model()
    pack()
    tex, glow = paint()
    save_png(TEX_OUT / "stalker.png", tex)
    save_png(TEX_OUT / "stalker_eyes.png", glow)
    write_java()
    boxes = sum(len(p.boxes) for p in PARTS)
    print(f"{len(PARTS)} parts, {boxes} boxes -> {JAVA_OUT.relative_to(ROOT)}; textures -> {TEX_OUT.relative_to(ROOT)}")
    if args.preview:
        preview(tex, glow, args.preview)
        print(f"previews -> {args.preview}")


if __name__ == "__main__":
    main()
