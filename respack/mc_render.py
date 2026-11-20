#!/usr/bin/env python3
"""Мини-рендерер моделей Minecraft (орбитальная камера + z-буфер).

Скопирован из hahtojer/respack/build.py, чтобы ресурспак HahGPS собирался
самостоятельно, без соседнего репозитория.
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image


class Cam:
    def __init__(self, yaw_deg: float, pitch_deg: float, scale: float,
                 w: int, h: int, cx: float | None = None, cy: float | None = None):
        self.yaw = math.radians(yaw_deg)
        self.pitch = math.radians(pitch_deg)
        self.scale = scale
        self.w, self.h = w, h
        self.cx = w / 2 if cx is None else cx
        self.cy = h / 2 if cy is None else cy

    def project(self, pts: np.ndarray):
        x, y, z = pts[:, 0], pts[:, 1], pts[:, 2]
        cy, sy = math.cos(self.yaw), math.sin(self.yaw)
        x1 = cy * x - sy * z
        z1 = sy * x + cy * z
        cp, sp = math.cos(self.pitch), math.sin(self.pitch)
        y2 = cp * y - sp * z1
        z2 = sp * y + cp * z1
        sx = self.cx + x1 * self.scale
        sy_ = self.cy - y2 * self.scale
        return np.stack([sx, sy_], axis=1), z2


def translate(t) -> np.ndarray:
    m = np.eye(4)
    m[:3, 3] = t
    return m


def scale_m(s) -> np.ndarray:
    m = np.eye(4)
    m[0, 0], m[1, 1], m[2, 2] = s
    return m


def rot_m(axis: str, deg: float, origin=(0, 0, 0)) -> np.ndarray:
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    if axis == "y":
        r = np.array([[c, 0, s, 0], [0, 1, 0, 0], [-s, 0, c, 0], [0, 0, 0, 1]], float)
    elif axis == "x":
        r = np.array([[1, 0, 0, 0], [0, c, -s, 0], [0, s, c, 0], [0, 0, 0, 1]], float)
    else:
        r = np.array([[c, -s, 0, 0], [s, c, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]], float)
    return translate(origin) @ r @ translate((-origin[0], -origin[1], -origin[2]))


def rot_from_y(d: np.ndarray) -> np.ndarray:
    """Матрица, переводящая +Y в направление d (единичное)."""
    d = d / (np.linalg.norm(d) or 1.0)
    up = np.array([0.0, 1.0, 0.0])
    dot = float(np.clip(np.dot(up, d), -1.0, 1.0))
    if dot > 0.9999:
        return np.eye(4)
    if dot < -0.9999:
        return rot_m("x", 180.0)
    axis = np.cross(up, d)
    axis /= np.linalg.norm(axis)
    x, y, z = axis
    a = math.acos(dot)
    c, s = math.cos(a), math.sin(a)
    t = 1 - c
    r = np.array([
        [t * x * x + c, t * x * y - s * z, t * x * z + s * y, 0],
        [t * x * y + s * z, t * y * y + c, t * y * z - s * x, 0],
        [t * x * z - s * y, t * y * z + s * x, t * z * z + c, 0],
        [0, 0, 0, 1],
    ], float)
    return r


FACE_CORNERS = {
    "north": lambda b: [(b[0], b[1], b[2]), (b[3], b[1], b[2]), (b[3], b[4], b[2]), (b[0], b[4], b[2])],
    "south": lambda b: [(b[3], b[1], b[5]), (b[0], b[1], b[5]), (b[0], b[4], b[5]), (b[3], b[4], b[5])],
    "west": lambda b: [(b[0], b[1], b[5]), (b[0], b[1], b[2]), (b[0], b[4], b[2]), (b[0], b[4], b[5])],
    "east": lambda b: [(b[3], b[1], b[2]), (b[3], b[1], b[5]), (b[3], b[4], b[5]), (b[3], b[4], b[2])],
    "up": lambda b: [(b[0], b[4], b[2]), (b[3], b[4], b[2]), (b[3], b[4], b[5]), (b[0], b[4], b[5])],
    "down": lambda b: [(b[0], b[1], b[5]), (b[3], b[1], b[5]), (b[3], b[1], b[2]), (b[0], b[1], b[2])],
}
FACE_UV_ORDER = {           # порядок UV-углов для каждого из 4 углов выше: (u,v) индексы
    "north": [(0, 1), (1, 1), (1, 0), (0, 0)],
    "south": [(0, 1), (1, 1), (1, 0), (0, 0)],
    "west": [(0, 1), (1, 1), (1, 0), (0, 0)],
    "east": [(0, 1), (1, 1), (1, 0), (0, 0)],
    "up": [(0, 0), (1, 0), (1, 1), (0, 1)],
    "down": [(0, 0), (1, 0), (1, 1), (0, 1)],
}


def face_shade(n: np.ndarray) -> float:
    """Освещение как у блоков: верх 1.0, низ 0.5, север/юг 0.8, восток/запад 0.6."""
    nx, ny, nz = abs(n[0]), abs(n[1]), abs(n[2])
    total = nx + ny + nz
    if total < 1e-6:
        return 0.8
    return (0.6 * nx + 1.0 * max(0.0, n[1]) + 0.5 * max(0.0, -n[1]) + 0.8 * nz) / total


class Scene:
    def __init__(self, w: int, h: int, bg=(24, 26, 31, 255), bg2=None):
        self.w, self.h = w, h
        self.rgb = np.zeros((h, w, 3), float)
        top = np.array(bg[:3], float)
        bottom = np.array((bg2 or bg)[:3], float)
        if bg2 is None:
            self.rgb[:, :] = top
        else:
            ramp = np.linspace(0.0, 1.0, h)[:, None, None]
            self.rgb[:, :] = top[None, None, :] * (1 - ramp) + bottom[None, None, :] * ramp
        self.z = np.full((h, w), np.inf)
        self.faces = []

    def add_model(self, model: dict, mat: np.ndarray, images: dict[str, Image.Image],
                  textures: dict[str, str] | None = None):
        tex_refs = dict(model.get("textures") or {})
        if textures:
            tex_refs.update(textures)
        for el in model.get("elements", []):
            b = list(el["from"]) + list(el["to"])
            m = mat
            if el.get("rotation"):
                r = el["rotation"]
                m = m @ rot_m(r["axis"], r["angle"], r.get("origin", (8, 8, 8)))
            for face, data in el["faces"].items():
                tex_name = data["texture"].lstrip("#")
                ref = tex_refs.get(tex_name, tex_name)
                img = images.get(ref) or images.get(tex_name)
                if img is None:
                    continue
                arr = np.asarray(img.convert("RGBA"), float)
                corners = np.array(FACE_CORNERS[face](b), float)
                pts = np.c_[corners, np.ones(4)] @ m.T
                pts = pts[:, :3]
                uv = data["uv"]
                u0, v0, u1, v1 = uv
                order = FACE_UV_ORDER[face]
                uvs = np.array([[u0 if ou == 0 else u1, v0 if ov == 0 else v1] for ou, ov in order], float)
                normal = np.cross(corners[1] - corners[0], corners[2] - corners[0])
                ln = np.linalg.norm(normal)
                if ln < 1e-9:
                    continue
                normal /= ln
                self.faces.append((pts, uvs, arr, face_shade(normal)))

    def raster(self, cam: Cam) -> Image.Image:
        for pts, uvs, arr, shade in self.faces:
            scr, dep = cam.project(pts)
            th, tw = arr.shape[:2]
            for tri in ((0, 1, 2), (0, 2, 3)):
                p = scr[list(tri)]
                uv = uvs[list(tri)]
                d = dep[list(tri)]
                self._triangle(p, uv, d, arr, shade, tw, th)
        img = np.clip(self.rgb, 0, 255).astype(np.uint8)
        return Image.fromarray(img, "RGB")

    def _triangle(self, p, uv, dep, arr, shade, tw, th):
        x0, y0 = p[0]
        x1, y1 = p[1]
        x2, y2 = p[2]
        minx = max(int(math.floor(min(x0, x1, x2))), 0)
        maxx = min(int(math.ceil(max(x0, x1, x2))), self.w - 1)
        miny = max(int(math.floor(min(y0, y1, y2))), 0)
        maxy = min(int(math.ceil(max(y0, y1, y2))), self.h - 1)
        if minx > maxx or miny > maxy:
            return
        den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
        if abs(den) < 1e-9:
            return
        xs = np.arange(minx, maxx + 1)
        ys = np.arange(miny, maxy + 1)
        X, Y = np.meshgrid(xs, ys)
        w0 = ((y1 - y2) * (X - x2) + (x2 - x1) * (Y - y2)) / den
        w1 = ((y2 - y0) * (X - x2) + (x0 - x2) * (Y - y2)) / den
        w2 = 1.0 - w0 - w1
        mask = (w0 >= -1e-6) & (w1 >= -1e-6) & (w2 >= -1e-6)
        if not mask.any():
            return
        z = w0 * dep[0] + w1 * dep[1] + w2 * dep[2]
        zmask = mask & (z < self.z[miny:maxy + 1, minx:maxx + 1])
        if not zmask.any():
            return
        u = w0 * uv[0][0] + w1 * uv[1][0] + w2 * uv[2][0]
        v = w0 * uv[0][1] + w1 * uv[1][1] + w2 * uv[2][1]
        ui = np.floor(u).astype(int) % tw
        vi = np.floor(v).astype(int) % th
        col = arr[vi, ui, :3] * shade
        sub = self.rgb[miny:maxy + 1, minx:maxx + 1]
        sub[zmask] = col[zmask]
        self.z[miny:maxy + 1, minx:maxx + 1][zmask] = z[zmask]


