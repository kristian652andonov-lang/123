#!/usr/bin/env python3
"""Generates the player skins worn by NULL's Visitors and simulated players.

Every skin is original pixel art in the standard 64x64 player skin layout (Minecraft 1.8+:
base layer plus overlay layer, wide or slim arms). There are four categories:

  BLACK      completely black bodies; some have faint white eyes, which also get a
             skin_<n>_eyes.png mask that the renderer draws as a faint glow in the dark
  MISSING    the magenta/black "missing texture" checker, whole or partial
  DISTORTED  ordinary-looking skins damaged like a corrupted file: shifted rows, smears,
             wrong colours, holes
  OLD        simple early-2010s looking skins: plain shirts, jeans, simple faces
             (deliberately not Steve or Alex)

The output is deterministic. The index table printed at the end is mirrored by
dev.kristian.nullmod.entity.SkinSet and must stay in sync with it.

Usage: python3 tools/gen_skins.py [--sheet <contact-sheet.png>]
"""
import argparse
import pathlib
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "src" / "main" / "resources" / "assets" / "null" / "textures" / "entity" / "skins"

# ---------------------------------------------------------------------------- layout

FACES = ("top", "bottom", "right", "front", "left", "back")
BASE_PARTS = ("head", "body", "rarm", "larm", "rleg", "lleg")
OVERLAY_OF = {"head": "hat", "body": "jacket", "rarm": "rsleeve", "larm": "lsleeve",
              "rleg": "rpants", "lleg": "lpants"}


def layout(slim):
    arm = 3 if slim else 4
    return {
        "head": (0, 0, 8, 8, 8), "hat": (32, 0, 8, 8, 8),
        "body": (16, 16, 8, 12, 4), "jacket": (16, 32, 8, 12, 4),
        "rarm": (40, 16, arm, 12, 4), "rsleeve": (40, 32, arm, 12, 4),
        "larm": (32, 48, arm, 12, 4), "lsleeve": (48, 48, arm, 12, 4),
        "rleg": (0, 16, 4, 12, 4), "rpants": (0, 32, 4, 12, 4),
        "lleg": (16, 48, 4, 12, 4), "lpants": (0, 48, 4, 12, 4),
    }


def face_rects(u, v, w, h, d):
    """Texture rectangles (x, y, width, height) of the six faces of a model box."""
    return {
        "top": (u + d, v, w, d),
        "bottom": (u + d + w, v, w, d),
        "right": (u, v + d, d, h),
        "front": (u + d, v + d, w, h),
        "left": (u + d + w, v + d, d, h),
        "back": (u + d + w + d, v + d, w, h),
    }


FACE_SHADE = {"top": 1.06, "bottom": 0.80, "right": 0.90, "front": 1.0, "left": 0.90, "back": 0.95}


def rgb(hexstr):
    hexstr = hexstr.lstrip("#")
    return np.array([int(hexstr[i:i + 2], 16) for i in (0, 2, 4)], dtype=np.float64)


def clamp8(c):
    return np.clip(np.rint(c), 0, 255).astype(np.uint8)


class Skin:
    def __init__(self, slim, seed):
        self.slim = slim
        self.parts = layout(slim)
        self.img = np.zeros((64, 64, 4), np.uint8)
        self.rng = np.random.default_rng(seed)

    def rect(self, part, face):
        return face_rects(*self.parts[part])[face]

    def pixel(self, part, face, x, y, color, alpha=255, shade=True, noise=4):
        u, v, w, h = self.rect(part, face)
        if not (0 <= x < w and 0 <= y < h):
            return
        c = np.array(color, dtype=np.float64)
        if shade:
            c = c * FACE_SHADE[face]
        if noise:
            c = c + self.rng.integers(-noise, noise + 1)
        self.img[v + y, u + x, :3] = clamp8(c)
        self.img[v + y, u + x, 3] = alpha

    def fill(self, part, color, faces=FACES, rows=None, cols=None, alpha=255, shade=True, noise=4, gradient=0.0):
        for face in faces:
            u, v, w, h = self.rect(part, face)
            for y in range(h):
                if rows is not None and not rows(face, y, h):
                    continue
                for x in range(w):
                    if cols is not None and not cols(face, x, w):
                        continue
                    c = np.array(color, dtype=np.float64)
                    if gradient and face not in ("top", "bottom"):
                        c = c * (1.0 - gradient * (y / max(1, h - 1)))
                    self.pixel(part, face, x, y, c, alpha, shade, noise)

    def clear_part(self, part):
        for face in FACES:
            u, v, w, h = self.rect(part, face)
            self.img[v:v + h, u:u + w] = 0

    def get(self, part, face):
        u, v, w, h = self.rect(part, face)
        return self.img[v:v + h, u:u + w].copy()

    def put(self, part, face, block):
        u, v, w, h = self.rect(part, face)
        self.img[v:v + h, u:u + w] = block[:h, :w]

    def save(self, path):
        Image.fromarray(self.img, "RGBA").save(path, optimize=True)


# ---------------------------------------------------------------------------- old-style skins

SKIN_TONES = {
    "pale": "#F3D2B3", "light": "#E8B98F", "tan": "#D49A6A", "olive": "#C08A5B",
    "medium": "#A8703F", "brown": "#7E4E2B", "dark": "#5A3720",
}


def paint_head(s, o):
    tone = rgb(SKIN_TONES[o["tone"]])
    hair = rgb(o["hair"])
    s.fill("head", tone)
    style = o.get("hair_style", "short")
    # top of the head is always hair (a buzz cut is just darker skin with stubble)
    if style == "buzz":
        s.fill("head", hair * 0.55 + tone * 0.45, faces=("top",), noise=10)
        s.fill("head", hair * 0.55 + tone * 0.45, faces=("left", "right", "back"), rows=lambda f, y, h: y < 2, noise=10)
        s.fill("head", hair * 0.55 + tone * 0.45, faces=("front",), rows=lambda f, y, h: y < 1, noise=10)
    else:
        s.fill("head", hair, faces=("top",), noise=7)
        front_rows = {"short": 1, "long": 1, "fringe": 2, "spiky": 1, "bob": 1, "puff": 2, "ponytail": 1}.get(style, 1)
        side_rows = {"short": 3, "long": 8, "fringe": 3, "spiky": 2, "bob": 6, "puff": 4, "ponytail": 3}.get(style, 3)
        back_rows = {"short": 5, "long": 8, "fringe": 5, "spiky": 4, "bob": 7, "puff": 6, "ponytail": 8}.get(style, 5)
        s.fill("head", hair, faces=("front",), rows=lambda f, y, h: y < front_rows, noise=7)
        s.fill("head", hair, faces=("right", "left"), rows=lambda f, y, h: y < side_rows, noise=7)
        s.fill("head", hair, faces=("back",), rows=lambda f, y, h: y < back_rows, noise=7)
        if style in ("long", "bob"):
            # hair framing the face
            for y in range(front_rows, 6 if style == "bob" else 8):
                s.pixel("head", "front", 0, y, hair, noise=7)
                s.pixel("head", "front", 7, y, hair, noise=7)
        if style == "fringe":
            for x in (1, 3, 6):
                s.pixel("head", "front", x, 2, tone)
        if style == "spiky":
            for x in (0, 2, 5, 7):
                s.pixel("head", "front", x, 1, hair, noise=7)
            for x in range(8):
                if x % 2 == 0:
                    s.pixel("head", "right", x, 2, hair, noise=7)
                    s.pixel("head", "left", x, 2, hair, noise=7)
        if style == "puff":
            for x in (0, 1, 6, 7):
                s.pixel("head", "front", x, 2, hair, noise=7)
        if style == "ponytail":
            for y in range(4, 8):
                s.pixel("head", "back", 3, y, hair * 0.85, noise=6)
                s.pixel("head", "back", 4, y, hair * 0.85, noise=6)
    # face
    eye_style = o.get("eyes", "classic")
    iris = rgb(o.get("iris", "#3B2A1C"))
    white = rgb("#F2F2F2")
    if eye_style == "classic":
        s.pixel("head", "front", 1, 4, white, noise=2)
        s.pixel("head", "front", 2, 4, iris, noise=2)
        s.pixel("head", "front", 5, 4, iris, noise=2)
        s.pixel("head", "front", 6, 4, white, noise=2)
    elif eye_style == "dots":
        s.pixel("head", "front", 2, 4, rgb("#1A1410"), noise=2)
        s.pixel("head", "front", 5, 4, rgb("#1A1410"), noise=2)
    elif eye_style == "tall":
        for y in (3, 4):
            s.pixel("head", "front", 2, y, iris, noise=2)
            s.pixel("head", "front", 5, y, iris, noise=2)
        s.pixel("head", "front", 1, 4, white, noise=2)
        s.pixel("head", "front", 6, 4, white, noise=2)
    elif eye_style == "brows":
        for x in (1, 2, 5, 6):
            s.pixel("head", "front", x, 3, hair * 0.8, noise=3)
        s.pixel("head", "front", 2, 4, iris, noise=2)
        s.pixel("head", "front", 5, 4, iris, noise=2)
        s.pixel("head", "front", 1, 4, white, noise=2)
        s.pixel("head", "front", 6, 4, white, noise=2)
    elif eye_style == "sleepy":
        for x in (1, 2, 5, 6):
            s.pixel("head", "front", x, 4, tone * 0.7, noise=2)
        s.pixel("head", "front", 2, 5, iris, noise=2)
        s.pixel("head", "front", 5, 5, iris, noise=2)
    # nose and mouth
    s.pixel("head", "front", 3, 5, tone * 0.88, noise=2)
    s.pixel("head", "front", 4, 5, tone * 0.88, noise=2)
    mouth = o.get("mouth", "line")
    lip = tone * 0.62
    if mouth == "line":
        s.pixel("head", "front", 3, 6, lip, noise=2)
        s.pixel("head", "front", 4, 6, lip, noise=2)
    elif mouth == "smile":
        s.pixel("head", "front", 2, 5, lip, noise=2)
        s.pixel("head", "front", 3, 6, lip, noise=2)
        s.pixel("head", "front", 4, 6, lip, noise=2)
        s.pixel("head", "front", 5, 5, lip, noise=2)
    elif mouth == "beard":
        beard = rgb(o.get("beard", o["hair"]))
        for x in range(1, 7):
            s.pixel("head", "front", x, 7, beard, noise=6)
        for x in (1, 2, 5, 6):
            s.pixel("head", "front", x, 6, beard, noise=6)
        s.pixel("head", "front", 3, 6, lip * 0.8, noise=2)
        s.pixel("head", "front", 4, 6, lip * 0.8, noise=2)
        for y in (5, 6, 7):
            s.pixel("head", "right", 7, y, beard, noise=6)
            s.pixel("head", "left", 0, y, beard, noise=6)
        s.fill("head", beard, faces=("bottom",), noise=6)
    if o.get("freckles"):
        for x, y in ((1, 5), (6, 5), (2, 6)):
            s.pixel("head", "front", x, y, tone * 0.82, noise=2)


def paint_torso_and_limbs(s, o):
    tone = rgb(SKIN_TONES[o["tone"]])
    shirt = rgb(o["shirt"])
    pants = rgb(o["pants"])
    shoes = rgb(o["shoes"])
    style = o.get("shirt_style", "tee")
    s.fill("body", shirt, gradient=0.08)
    # arms: sleeves
    sleeve_rows = {"tee": 4, "long": 11, "hoodie": 11, "stripes": 4, "stripes_long": 11, "collar": 4,
                   "logo": 4, "tank": 0, "button": 11, "vest": 11, "plaid": 11, "overalls": 4,
                   "jersey": 5}.get(style, 4)
    for arm in ("rarm", "larm"):
        s.fill(arm, tone)
        if sleeve_rows:
            s.fill(arm, shirt, faces=("right", "front", "left", "back"), rows=lambda f, y, h: y < sleeve_rows, gradient=0.05)
            s.fill(arm, shirt, faces=("top",))
        if style in ("long", "hoodie", "button", "vest", "plaid", "stripes_long"):
            # cuffs
            s.fill(arm, shirt * 0.82, faces=("right", "front", "left", "back"), rows=lambda f, y, h: y == 10)
    if style == "tank":
        for arm in ("rarm", "larm"):
            s.fill(arm, shirt, faces=("top",))
            s.fill(arm, shirt, faces=("right", "front", "left", "back"), rows=lambda f, y, h: y == 0)
    accent = rgb(o.get("accent", "#FFFFFF"))
    if style in ("stripes", "stripes_long"):
        s.fill("body", accent, faces=("front", "back", "left", "right"), rows=lambda f, y, h: y % 3 == 1)
        for arm in ("rarm", "larm"):
            s.fill(arm, accent, faces=("right", "front", "left", "back"),
                   rows=lambda f, y, h: y % 3 == 1 and y < sleeve_rows)
    elif style == "collar":
        for x in range(1, 7):
            s.pixel("body", "front", x, 0, accent)
        s.pixel("body", "front", 3, 1, accent)
        s.pixel("body", "front", 4, 1, accent)
        for y in (2, 4):
            s.pixel("body", "front", 4, y, shirt * 0.7)
    elif style == "logo":
        shape = o.get("logo", "bar")
        if shape == "bar":
            pixels = [(2, 3), (3, 3), (4, 3), (5, 3), (3, 4), (4, 4)]
        elif shape == "ring":
            pixels = [(2, 3), (3, 3), (4, 3), (5, 3), (2, 4), (5, 4), (2, 5), (3, 5), (4, 5), (5, 5)]
        else:
            pixels = [(3, 3), (4, 3), (3, 4), (4, 4)]
        for x, y in pixels:
            s.pixel("body", "front", x, y, accent, noise=2)
    elif style == "hoodie":
        for y in range(7, 10):
            for x in range(2, 6):
                s.pixel("body", "front", x, y, shirt * 0.85)
        for x in range(1, 7):
            s.pixel("body", "front", x, 0, shirt * 0.78)
        for y in (1, 2, 3):
            s.pixel("body", "front", 2, y, accent, noise=2)
        for y in (1, 2):
            s.pixel("body", "front", 5, y, accent, noise=2)
        for x in range(8):
            s.pixel("body", "back", x, 0, shirt * 0.8)
    elif style == "button":
        tie = rgb(o.get("tie", "#8B1A1A"))
        for y in range(0, 9):
            s.pixel("body", "front", 3, y, tie if y > 0 else tie * 0.8, noise=3)
            s.pixel("body", "front", 4, y, tie * 0.85, noise=3)
        s.pixel("body", "front", 2, 0, accent)
        s.pixel("body", "front", 5, 0, accent)
    elif style == "vest":
        vest = rgb(o.get("vest", "#5C4033"))
        s.fill("body", vest, faces=("left", "right", "back"))
        for y in range(12):
            for x in (0, 1, 6, 7):
                s.pixel("body", "front", x, y, vest)
        for y in (3, 6, 9):
            s.pixel("body", "front", 1, y, vest * 0.6)
    elif style == "plaid":
        dark = rgb(o.get("accent", "#1E1E1E"))

        def plaid(face, part):
            u, v, w, h = s.rect(part, face)
            for y in range(h):
                for x in range(w):
                    if part in ("rarm", "larm") and y >= sleeve_rows:
                        continue
                    if x % 4 == 1 or y % 4 == 1:
                        s.pixel(part, face, x, y, (dark + shirt) / 2.0 if (x % 4 == 1) != (y % 4 == 1) else dark, noise=3)

        for face in ("front", "back", "left", "right"):
            for part in ("body", "rarm", "larm"):
                plaid(face, part)
    elif style == "overalls":
        denim = rgb(o.get("accent", "#3A5A8C"))
        for y in range(5, 12):
            for x in range(8):
                s.pixel("body", "front", x, y, denim)
                s.pixel("body", "back", x, y, denim)
        for y in range(4):
            s.pixel("body", "front", 1, y, denim)
            s.pixel("body", "front", 6, y, denim)
            s.pixel("body", "back", 1, y, denim)
            s.pixel("body", "back", 6, y, denim)
        s.fill("body", denim, faces=("left", "right"), rows=lambda f, y, h: y >= 5)
        s.pixel("body", "front", 1, 4, rgb("#C9B037"))
        s.pixel("body", "front", 6, 4, rgb("#C9B037"))
    elif style == "jersey":
        for y in range(12):
            s.pixel("body", "front", 0, y, accent)
            s.pixel("body", "front", 7, y, accent)
        for x, y in ((2, 2), (3, 2), (4, 2), (5, 2), (5, 3), (4, 4), (4, 5), (3, 6), (3, 7)):
            s.pixel("body", "front", x, y, accent)
        for arm in ("rarm", "larm"):
            s.fill(arm, accent, faces=("right", "front", "left", "back"), rows=lambda f, y, h: y == sleeve_rows - 1)
    # belt
    if o.get("belt"):
        belt = rgb(o["belt"])
        s.fill("body", belt, faces=("front", "back", "left", "right"), rows=lambda f, y, h: y == 11)
        s.pixel("body", "front", 3, 11, rgb("#9A9A9A"))
        s.pixel("body", "front", 4, 11, rgb("#9A9A9A"))
    # legs
    pstyle = o.get("pants_style", "jeans")
    for leg in ("rleg", "lleg"):
        s.fill(leg, pants, gradient=0.06, noise=6 if pstyle == "jeans" else 4)
        if pstyle == "shorts":
            s.fill(leg, tone, faces=("right", "front", "left", "back"), rows=lambda f, y, h: y >= 5)
            if o.get("socks"):
                s.fill(leg, rgb(o["socks"]), faces=("right", "front", "left", "back"), rows=lambda f, y, h: 8 <= y < 10)
        if pstyle == "jeans":
            # a darker inner seam
            s.fill(leg, pants * 0.8, faces=("front", "back"),
                   cols=lambda f, x, w, l=leg: x == (w - 1 if l == "rleg" else 0), rows=lambda f, y, h: y < 10)
        s.fill(leg, shoes, faces=("right", "front", "left", "back"), rows=lambda f, y, h: y >= 10)
        s.fill(leg, shoes * 0.7, faces=("bottom",))
        if o.get("laces"):
            s.pixel(leg, "front", 1, 10, rgb(o["laces"]))
            s.pixel(leg, "front", 2, 10, rgb(o["laces"]))


def paint_overlay(s, o):
    ov = o.get("overlay")
    if not ov:
        return
    kind = ov[0]
    color = rgb(ov[1]) if len(ov) > 1 else None
    if kind == "cap":
        s.fill("hat", color, faces=("top",))
        s.fill("hat", color, faces=("front",), rows=lambda f, y, h: y < 2)
        s.fill("hat", color, faces=("right", "left", "back"), rows=lambda f, y, h: y < 2)
        s.fill("hat", color * 0.75, faces=("front",), rows=lambda f, y, h: y == 2)
        s.pixel("hat", "top", 3, 3, rgb("#FFFFFF"))
    elif kind == "beanie":
        s.fill("hat", color, faces=("top",), noise=8)
        s.fill("hat", color, faces=("front", "right", "left", "back"), rows=lambda f, y, h: y < 3, noise=8)
        s.fill("hat", color * 0.8, faces=("front", "right", "left", "back"), rows=lambda f, y, h: y == 2, noise=4)
    elif kind == "hood":
        s.fill("hat", color, faces=("top", "back", "left", "right"), noise=6)
        s.fill("hat", color, faces=("front",), rows=lambda f, y, h: y < 1, noise=6)
        s.fill("hat", color, faces=("front",), cols=lambda f, x, w: x in (0, 7), noise=6)
        s.fill("hat", color * 0.6, faces=("bottom",), noise=4)
    elif kind == "headband":
        s.fill("hat", color, faces=("front", "right", "left", "back"), rows=lambda f, y, h: y == 2, noise=3)
    elif kind == "glasses":
        frame = rgb("#151515")
        for x in (0, 1, 2, 3, 4, 5, 6, 7):
            s.pixel("hat", "front", x, 3, frame, noise=2)
        for x in (0, 3, 4, 7):
            s.pixel("hat", "front", x, 4, frame, noise=2)
        for y in (3,):
            s.pixel("hat", "right", 7, y, frame, noise=2)
            s.pixel("hat", "left", 0, y, frame, noise=2)
    elif kind == "jacket":
        s.fill("jacket", color, faces=("back", "left", "right", "top", "bottom"), noise=5)
        s.fill("jacket", color, faces=("front",), cols=lambda f, x, w: x in (0, 1, 6, 7), noise=5)
        for y in range(2, 11, 3):
            s.pixel("jacket", "front", 1, y, color * 0.7)
        for sleeve in ("rsleeve", "lsleeve"):
            s.fill(sleeve, color, faces=("right", "front", "left", "back", "top"), rows=lambda f, y, h: y < 10, noise=5)


OLD_OUTFITS = [
    dict(tone="light", hair="#8A6A3F", hair_style="short", eyes="classic", iris="#2F6B2F", mouth="line",
         shirt="#B3262B", shirt_style="tee", pants="#2B2B30", shoes="#E6E6E6", laces="#B0B0B0"),
    dict(tone="tan", hair="#151515", hair_style="spiky", eyes="dots", mouth="smile",
         shirt="#2E6B34", shirt_style="hoodie", accent="#E8E8E8", pants="#6E6E6E", shoes="#1C1C1C"),
    dict(tone="pale", hair="#B5531F", hair_style="long", eyes="classic", iris="#3866A8", mouth="line", freckles=True,
         shirt="#EDEDED", shirt_style="logo", logo="bar", accent="#C0392B", pants="#3B5A8F", shoes="#7A7A7A", slim=True),
    dict(tone="dark", hair="#0F0F0F", hair_style="buzz", eyes="brows", iris="#2A1A10", mouth="line",
         shirt="#D9781E", shirt_style="collar", accent="#FFFFFF", pants="#B59A6A", shoes="#4A2E1A", belt="#3A2414"),
    dict(tone="medium", hair="#3A2414", hair_style="fringe", eyes="tall", iris="#1E1E1E", mouth="line",
         shirt="#5B2E7A", shirt_style="long", pants="#1D1D22", shoes="#0E0E0E", overlay=("beanie", "#5A5A5E")),
    dict(tone="light", hair="#D9C27A", hair_style="bob", eyes="classic", iris="#3B6FB5", mouth="smile",
         shirt="#E58DB0", shirt_style="stripes", accent="#FAFAFA", pants="#F0F0F0", pants_style="shorts",
         shoes="#C7C7C7", socks="#FFFFFF", slim=True),
    dict(tone="olive", hair="#1B1B1B", hair_style="short", eyes="dots", mouth="line",
         shirt="#A9C4E0", shirt_style="button", accent="#FFFFFF", tie="#7A1C1C", pants="#55565C", shoes="#151515",
         belt="#1A1A1A", overlay=("glasses",)),
    dict(tone="medium", hair="#4B2E19", hair_style="short", eyes="brows", iris="#2B1A0E", mouth="beard",
         beard="#3A2312", shirt="#A3221E", shirt_style="plaid", accent="#1E1E1E", pants="#40618F", shoes="#5B3A21"),
    dict(tone="light", hair="#121212", hair_style="long", eyes="sleepy", iris="#2A2A2A", mouth="line",
         shirt="#1B1B1D", shirt_style="logo", logo="ring", accent="#D8D8D8", pants="#3A3A40", shoes="#A82424", slim=True),
    dict(tone="tan", hair="#B08A3E", hair_style="spiky", eyes="classic", iris="#2E7D4F", mouth="smile",
         shirt="#23407A", shirt_style="stripes_long", accent="#EDEDED", pants="#E3E3E3", shoes="#23407A"),
    dict(tone="pale", hair="#5A3B22", hair_style="short", eyes="dots", mouth="line",
         shirt="#8C8C8C", shirt_style="hoodie", accent="#FFFFFF", pants="#3C5C8E", shoes="#F2F2F2",
         overlay=("cap", "#B22222")),
    dict(tone="dark", hair="#101010", hair_style="puff", eyes="brows", iris="#24170D", mouth="smile",
         shirt="#4E8A2E", shirt_style="tank", pants="#A68B5B", pants_style="shorts", shoes="#6B4423", slim=True),
    dict(tone="light", hair="#BDBDBD", hair_style="buzz", eyes="dots", mouth="beard", beard="#E0E0E0",
         shirt="#F2F2F2", shirt_style="vest", vest="#6B4A2E", pants="#5E4A36", shoes="#1A1A1A", belt="#2A1C10"),
    dict(tone="medium", hair="#1A1A1A", hair_style="ponytail", eyes="classic", iris="#3A2618", mouth="line",
         shirt="#E8C33A", shirt_style="overalls", accent="#3D5F92", pants="#3D5F92", shoes="#202020", slim=True),
    dict(tone="pale", hair="#9E3B1B", hair_style="short", eyes="tall", iris="#2C5F8A", mouth="line", freckles=True,
         shirt="#F4F4F4", shirt_style="tee", pants="#232328", shoes="#F4F4F4", overlay=("jacket", "#1F2A4D")),
    dict(tone="medium", hair="#2E1C10", hair_style="short", eyes="brows", iris="#20140B", mouth="smile",
         shirt="#E0661B", shirt_style="jersey", accent="#FFFFFF", pants="#1B1B1B", pants_style="shorts",
         shoes="#111111", socks="#F5F5F5", overlay=("headband", "#F5F5F5")),
]


def old_skin(index, outfit, seed):
    s = Skin(outfit.get("slim", False), seed)
    paint_head(s, outfit)
    paint_torso_and_limbs(s, outfit)
    paint_overlay(s, outfit)
    return s


# ---------------------------------------------------------------------------- black skins

def black_skin(seed, slim, eyes, variation=False, mouth=False):
    """Pure black body. eyes: list of (x, y) on the face front, shown faint white."""
    s = Skin(slim, seed)
    for part in BASE_PARTS:
        if variation:
            s.fill(part, (4, 4, 5), shade=False, noise=3)
        else:
            s.fill(part, (0, 0, 0), shade=False, noise=0)
    if mouth:
        for x in range(2, 6):
            s.pixel("head", "front", x, 6, (14, 14, 16), shade=False, noise=0)
    mask = np.zeros((64, 64, 4), np.uint8)
    for (x, y) in eyes:
        s.pixel("head", "front", x, y, (200, 200, 205), shade=False, noise=0)
        u, v, _, _ = s.rect("head", "front")
        mask[v + y, u + x] = (255, 255, 255, 255)
    return s, (mask if eyes else None)


# ---------------------------------------------------------------------------- missing texture

MAGENTA = np.array([248, 0, 248], np.float64)
BLACK = np.array([0, 0, 0], np.float64)


def checker_parts(s, parts, cell, colors=(MAGENTA, BLACK), per_face=False):
    for part in parts:
        for face in FACES:
            u, v, w, h = s.rect(part, face)
            for y in range(h):
                for x in range(w):
                    if per_face:
                        a = (x * 2 // max(1, w)) + (y * 2 // max(1, h))
                    else:
                        a = ((u + x) // cell) + ((v + y) // cell)
                    c = colors[a % 2]
                    s.pixel(part, face, x, y, c, shade=False, noise=0)


def missing_skin(seed, slim, variant):
    s = Skin(slim, seed)
    if variant == "checker4":
        checker_parts(s, BASE_PARTS, 4)
    elif variant == "checker2":
        checker_parts(s, BASE_PARTS, 2)
    elif variant == "per_face":
        checker_parts(s, BASE_PARTS, 0, per_face=True)
    elif variant == "half":
        s = old_skin(0, OLD_OUTFITS[9], seed)
        for part in ("body", "rarm", "larm", "rleg", "lleg"):
            s.clear_part(part)
        checker_parts(s, ("body", "rarm", "larm", "rleg", "lleg"), 4)
    elif variant == "head":
        s = old_skin(0, OLD_OUTFITS[3], seed)
        s.clear_part("head")
        s.clear_part("hat")
        checker_parts(s, ("head",), 4)
    elif variant == "dim":
        checker_parts(s, BASE_PARTS, 4, colors=(np.array([150, 0, 160], np.float64), np.array([12, 0, 14], np.float64)))
        shift_rows(s.img, s.rng, bands=3, max_shift=5, rows_per_band=(2, 4))
    return s


# ---------------------------------------------------------------------------- distortion

def shift_rows(img, rng, bands, max_shift, rows_per_band=(2, 6), region=(0, 64)):
    for _ in range(bands):
        y0 = int(rng.integers(region[0], region[1] - 1))
        n = int(rng.integers(rows_per_band[0], rows_per_band[1] + 1))
        shift = int(rng.integers(1, max_shift + 1)) * (1 if rng.random() < 0.5 else -1)
        for y in range(y0, min(64, y0 + n)):
            img[y] = np.roll(img[y], shift, axis=0)


def face_shift(s, part, face, shifts):
    block = s.get(part, face)
    for y, dx in shifts.items():
        if 0 <= y < block.shape[0]:
            block[y] = np.roll(block[y], dx, axis=0)
    s.put(part, face, block)


def eye_bleed(s, xs, color=(10, 6, 8)):
    for x in xs:
        for y in range(4, 8):
            s.pixel("head", "front", x, y, color, shade=False, noise=2)
        for y in range(0, 5):
            s.pixel("body", "front", min(7, x), y, color, shade=False, noise=2)


def recolor(img, region, fn):
    y0, y1, x0, x1 = region
    sub = img[y0:y1, x0:x1]
    mask = sub[..., 3] > 0
    rgbv = sub[..., :3].astype(np.float64)
    out = fn(rgbv)
    sub[..., :3] = np.where(mask[..., None], clamp8(out), sub[..., :3])


def distorted_skin(seed, outfit_index, glitches, slim=None, tweak=None):
    outfit = dict(OLD_OUTFITS[outfit_index])
    if tweak:
        outfit.update(tweak)
    if slim is not None:
        outfit["slim"] = slim
    s = old_skin(0, outfit, seed)
    rng = s.rng
    img = s.img
    for g in glitches:
        if g == "rowshift_head":
            face_shift(s, "head", "front", {3: 2, 4: 3, 5: -2, 6: 1})
            face_shift(s, "hat", "front", {3: 2, 4: 3})
        elif g == "eye_bleed":
            eye_bleed(s, (2, 5))
        elif g == "faceless":
            tone = rgb(SKIN_TONES[outfit["tone"]])
            for y in range(2, 8):
                for x in range(8):
                    s.pixel("head", "front", x, y, tone, noise=2)
        elif g == "desaturate":
            def desat(c):
                gray = c.mean(axis=-1, keepdims=True)
                return gray * 0.85 + c * 0.15
            recolor(img, (0, 64, 0, 64), desat)
        elif g == "bands":
            shift_rows(img, rng, bands=4, max_shift=9, rows_per_band=(2, 5), region=(8, 60))
        elif g == "channel_swap":
            recolor(img, (16, 64, 0, 64), lambda c: c[..., [2, 0, 1]])
        elif g == "noise_blocks":
            for _ in range(7):
                x = int(rng.integers(0, 60))
                y = int(rng.integers(16, 60))
                w = int(rng.integers(1, 4))
                h = int(rng.integers(1, 3))
                col = rng.integers(0, 256, size=3)
                region = img[y:y + h, x:x + w]
                m = region[..., 3] > 0
                region[..., :3] = np.where(m[..., None], col, region[..., :3])
        elif g == "smear":
            block = s.get("body", "front")
            for y in range(3, 9):
                block[y, :] = block[y, 2]
            s.put("body", "front", block)
            for leg in ("rleg", "lleg"):
                b = s.get(leg, "front")
                b[4:, :] = b[4]
                s.put(leg, "front", b)
        elif g == "invert_half":
            recolor(img, (0, 32, 32, 64), lambda c: 255.0 - c)
            recolor(img, (0, 16, 0, 32), lambda c: 255.0 - c)
        elif g == "rowshift":
            shift_rows(img, rng, bands=2, max_shift=4, rows_per_band=(1, 3), region=(16, 32))
        elif g == "face_on_chest":
            face = s.get("head", "front")
            body = s.get("body", "front")
            body[1:9, 0:8] = face[0:8, 0:8]
            s.put("body", "front", body)
        elif g == "black_eyes":
            for x in (1, 2, 5, 6):
                s.pixel("head", "front", x, 4, (0, 0, 0), shade=False, noise=0)
            for x in (2, 5):
                s.pixel("head", "front", x, 3, (0, 0, 0), shade=False, noise=0)
        elif g == "corrupt_pixels":
            for _ in range(40):
                x = int(rng.integers(0, 64))
                y = int(rng.integers(0, 64))
                if img[y, x, 3] > 0:
                    img[y, x, :3] = rng.choice([0, 255], size=3) if rng.random() < 0.5 else rng.integers(0, 256, size=3)
        elif g == "holes":
            for part, face, x, y in (("body", "front", 2, 5), ("body", "front", 3, 5), ("body", "front", 5, 8),
                                     ("rarm", "front", 1, 6), ("head", "back", 4, 3), ("head", "back", 5, 3),
                                     ("lleg", "front", 2, 3), ("body", "back", 1, 9), ("head", "left", 2, 6)):
                u, v, w, h = s.rect(part, face)
                if x < w and y < h:
                    img[v + y, u + x] = 0
        elif g == "posterize":
            palette = np.array([[16, 16, 16], [200, 30, 40], [240, 240, 240], [40, 170, 60]], np.float64)

            def post(c):
                d = ((c[..., None, :] - palette[None, None, :, :]) ** 2).sum(axis=-1)
                return palette[d.argmin(axis=-1)]
            recolor(img, (0, 64, 0, 64), post)
        elif g == "missing_patch":
            for part, face in (("larm", "front"), ("larm", "left"), ("larm", "back"), ("larm", "right"), ("larm", "top")):
                u, v, w, h = s.rect(part, face)
                for y in range(h):
                    for x in range(w):
                        c = MAGENTA if ((u + x) // 2 + (v + y) // 2) % 2 == 0 else BLACK
                        s.pixel(part, face, x, y, c, shade=False, noise=0)
        elif g == "wide_mouth":
            for x in range(0, 8):
                s.pixel("head", "front", x, 6, (20, 6, 6), shade=False, noise=2)
            for x in range(1, 7):
                s.pixel("head", "front", x, 7, (40, 10, 10), shade=False, noise=2)
        elif g == "shear_head":
            face_shift(s, "head", "front", {y: (y - 3) // 2 for y in range(8)})
            face_shift(s, "head", "left", {y: (y - 3) // 2 for y in range(8)})
            face_shift(s, "head", "right", {y: (y - 3) // 2 for y in range(8)})
        elif g == "stretch_face":
            block = s.get("head", "front")
            out = block.copy()
            for y in range(8):
                src = min(7, 2 + y // 2)
                out[y] = block[src]
            s.put("head", "front", out)
        elif g == "wrong_tone":
            def wrong(c):
                return c[..., [1, 2, 0]] * np.array([0.7, 1.15, 1.2])
            recolor(img, (0, 16, 0, 32), wrong)
            recolor(img, (16, 32, 40, 56), wrong)
            recolor(img, (48, 64, 32, 48), wrong)
        else:
            raise ValueError(g)
    return s


# ---------------------------------------------------------------------------- the set

def build():
    """Returns a list of (category, slim, skin, eyes_mask)."""
    skins = []

    def add(category, skin, eyes=None):
        skins.append((category, skin.slim, skin, eyes))

    # BLACK 0-7
    add("BLACK", *black_skin(1001, False, []))
    add("BLACK", *black_skin(1002, False, [], variation=True))
    add("BLACK", *black_skin(1003, False, [(2, 4), (5, 4)]))
    add("BLACK", *black_skin(1004, False, [(1, 2), (6, 2)]))
    add("BLACK", *black_skin(1005, True, [(5, 4)]))
    add("BLACK", *black_skin(1006, False, [(2, 3), (2, 4), (5, 3), (5, 4)]))
    add("BLACK", *black_skin(1007, False, [(2, 4), (5, 4)], mouth=True))
    add("BLACK", *black_skin(1008, True, [(2, 4), (6, 5)], variation=True))
    # MISSING 8-13
    for i, variant in enumerate(("checker4", "checker2", "per_face", "half", "head", "dim")):
        add("MISSING", missing_skin(2001 + i, i in (1, 4), variant))
    # DISTORTED 14-23
    distorted = [
        (0, ("rowshift_head", "eye_bleed"), None, {"shirt": "#4A4A4A"}),
        (6, ("faceless", "desaturate"), None, {"overlay": None}),
        (9, ("bands",), None, None),
        (1, ("channel_swap", "noise_blocks"), None, None),
        (13, ("smear", "rowshift"), True, {"shirt": "#C9C9C9"}),
        (10, ("invert_half", "rowshift"), None, None),
        (2, ("face_on_chest", "black_eyes"), True, {"shirt_style": "tee"}),
        (14, ("corrupt_pixels", "holes"), None, {"overlay": None}),
        (7, ("posterize", "missing_patch"), None, None),
        (4, ("black_eyes", "wide_mouth", "shear_head", "wrong_tone"), None, {"overlay": None}),
    ]
    for i, (outfit, glitches, slim, tweak) in enumerate(distorted):
        add("DISTORTED", distorted_skin(3001 + i, outfit, glitches, slim, tweak))
    # OLD 24-39
    for i, outfit in enumerate(OLD_OUTFITS):
        add("OLD", old_skin(i, outfit, 4001 + i))
    return skins


# ---------------------------------------------------------------------------- contact sheet

def flat_view(skin, back=False):
    """16x32 front (or back) view of a skin with the overlay applied."""
    img = skin.img
    view = np.zeros((32, 16, 4), np.uint8)
    arm = 3 if skin.slim else 4
    face = "back" if back else "front"

    def blit(part, x, y, mirror=False):
        u, v, w, h = skin.rect(part, face)
        block = img[v:v + h, u:u + w]
        if mirror:
            block = block[:, ::-1]
        dst = view[y:y + h, x:x + w]
        a = block[..., 3:4].astype(np.float64) / 255.0
        dst[..., :3] = clamp8(block[..., :3] * a + dst[..., :3] * (1 - a))
        dst[..., 3] = np.maximum(dst[..., 3], block[..., 3])

    # viewer's left is the entity's right on the front view, the reverse from behind
    left_arm, right_arm = ("larm", "rarm") if back else ("rarm", "larm")
    left_leg, right_leg = ("lleg", "rleg") if back else ("rleg", "lleg")
    for layer in (False, True):
        def p(part):
            return OVERLAY_OF[part] if layer else part
        blit(p("head"), 4, 0)
        blit(p("body"), 4, 8)
        blit(p(left_arm), 4 - arm, 8)
        blit(p(right_arm), 12, 8)
        blit(p(left_leg), 4, 20)
        blit(p(right_leg), 8, 20)
    return view


def contact_sheet(skins, path):
    scale = 6
    cell_w, cell_h = (16 * 2 + 6) * scale, 32 * scale + 22
    cols = 8
    rows = (len(skins) + cols - 1) // cols
    sheet = Image.new("RGBA", (cols * cell_w + 10, rows * cell_h + 10), (128, 140, 150, 255))
    draw = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 14)
    except OSError:
        font = ImageFont.load_default()
    for i, (cat, slim, skin, eyes) in enumerate(skins):
        cx = 5 + (i % cols) * cell_w
        cy = 5 + (i // cols) * cell_h
        for j, back in enumerate((False, True)):
            v = Image.fromarray(flat_view(skin, back), "RGBA").resize((16 * scale, 32 * scale), Image.NEAREST)
            sheet.alpha_composite(v, (cx + j * (16 + 2) * scale, cy))
        draw.text((cx, cy + 32 * scale + 2), f"{i} {cat}{' slim' if slim else ''}{' eyes' if eyes is not None else ''}",
                  fill=(0, 0, 0, 255), font=font)
    sheet.save(path)


def texture_sheet(skins, path):
    scale = 4
    cols = 8
    rows = (len(skins) + cols - 1) // cols
    sheet = Image.new("RGBA", (cols * (64 * scale + 6), rows * (64 * scale + 6)), (90, 160, 90, 255))
    for i, (_, _, skin, _) in enumerate(skins):
        t = Image.fromarray(skin.img, "RGBA").resize((64 * scale, 64 * scale), Image.NEAREST)
        sheet.alpha_composite(t, ((i % cols) * (64 * scale + 6), (i // cols) * (64 * scale + 6)))
    sheet.save(path)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sheet", help="write a contact sheet of front/back views here")
    parser.add_argument("--textures", help="write a sheet of the raw textures here")
    args = parser.parse_args()

    skins = build()
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob("skin_*.png"):
        old.unlink()
    for i, (cat, slim, skin, eyes) in enumerate(skins):
        skin.save(OUT / f"skin_{i}.png")
        if eyes is not None:
            Image.fromarray(eyes, "RGBA").save(OUT / f"skin_{i}_eyes.png", optimize=True)
    if args.sheet:
        contact_sheet(skins, args.sheet)
    if args.textures:
        texture_sheet(skins, args.textures)
    print(f"{len(skins)} skins -> {OUT.relative_to(ROOT)}")
    print("SkinSet table (index, category, slim, eyes):")
    for i, (cat, slim, skin, eyes) in enumerate(skins):
        print(f"    new Skin({i}, Category.{cat}, {str(slim).lower()}, {str(eyes is not None).lower()}),")
    return 0


if __name__ == "__main__":
    sys.exit(main())
