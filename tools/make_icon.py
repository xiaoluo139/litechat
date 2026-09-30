# -*- coding: utf-8 -*-
"""Generate the LiteChat app icon as PNG and as a Windows .ico.

Standard library only: the bitmap is drawn pixel by pixel (no Pillow), then the
PNG is wrapped in an ICO container (Vista and later accept PNG-compressed icon
entries).

Usage:  python make_icon.py <output-dir>
"""

import os
import struct
import sys
import zlib


def _canvas(size):
    """size x size RGBA buffer, pre-filled with the brand teal."""
    bg = (16, 163, 127, 255)      # #10A37F
    return [bytearray(bg * size) for _ in range(size)]


def _set(px, x, y, rgba, size):
    if 0 <= x < size and 0 <= y < size:
        i = x * 4
        px[y][i:i + 4] = bytes(rgba)


def _round_rect(px, size, left, top, right, bottom, radius, rgba):
    """Filled rounded rectangle, judged per pixel (no anti-aliasing, fine here)."""
    for y in range(max(0, top), min(size, bottom)):
        for x in range(max(0, left), min(size, right)):
            dx = 0
            dy = 0
            if x < left + radius:
                dx = left + radius - x
            elif x > right - radius - 1:
                dx = x - (right - radius - 1)
            if y < top + radius:
                dy = top + radius - y
            elif y > bottom - radius - 1:
                dy = y - (bottom - radius - 1)
            if dx * dx + dy * dy <= radius * radius:
                _set(px, x, y, rgba, size)


def _circle(px, size, cx, cy, r, rgba):
    for y in range(max(0, cy - r), min(size, cy + r + 1)):
        for x in range(max(0, cx - r), min(size, cx + r + 1)):
            if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                _set(px, x, y, rgba, size)


def _triangle(px, size, p1, p2, p3, rgba):
    """The little chat-bubble tail."""
    xs = [p1[0], p2[0], p3[0]]
    ys = [p1[1], p2[1], p3[1]]

    def cross(a, b, c):
        return (a[0] - c[0]) * (b[1] - c[1]) - (b[0] - c[0]) * (a[1] - c[1])

    for y in range(max(0, min(ys)), min(size, max(ys) + 1)):
        for x in range(max(0, min(xs)), min(size, max(xs) + 1)):
            d1 = cross((x, y), p1, p2)
            d2 = cross((x, y), p2, p3)
            d3 = cross((x, y), p3, p1)
            neg = (d1 < 0) or (d2 < 0) or (d3 < 0)
            pos = (d1 > 0) or (d2 > 0) or (d3 > 0)
            if not (neg and pos):
                _set(px, x, y, rgba, size)


def render(size=256):
    px = _canvas(size)
    s = size / 256.0
    white = (255, 255, 255, 255)
    teal = (16, 163, 127, 255)

    _round_rect(px, size, int(62 * s), int(66 * s), int(194 * s), int(160 * s),
                int(26 * s), white)
    _triangle(px, size,
              (int(74 * s), int(158 * s)),
              (int(74 * s), int(200 * s)),
              (int(116 * s), int(158 * s)), white)
    for cx in (96, 128, 160):
        _circle(px, size, int(cx * s), int(113 * s), max(1, int(9 * s)), teal)
    return px


def write_png(path, px, size):
    raw = bytearray()
    for row in px:
        raw.append(0)          # PNG filter type 0 (None)
        raw.extend(row)

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)
    return png


def write_ico(path, png_bytes, size):
    header = struct.pack("<HHH", 0, 1, 1)
    entry = struct.pack("<BBBBHHII",
                        0 if size >= 256 else size,    # 0 means 256
                        0 if size >= 256 else size,
                        0, 0, 1, 32, len(png_bytes), 6 + 16)
    with open(path, "wb") as f:
        f.write(header + entry + png_bytes)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(out, exist_ok=True)
    px = render(256)
    png = write_png(os.path.join(out, "litechat.png"), px, 256)
    write_ico(os.path.join(out, "litechat.ico"), png, 256)
    print("wrote", os.path.join(out, "litechat.png"))
    print("wrote", os.path.join(out, "litechat.ico"))


if __name__ == "__main__":
    main()
