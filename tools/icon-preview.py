"""Rasterise the MarkView adaptive icon (as it will look on a launcher) to a PNG.

Pure stdlib: the icon is a single stroked polyline (the letter M with round caps and
joins), so a small supersampled software rasteriser is enough. Four variants are drawn:
square, circle mask, squircle mask (typical launchers) and the monochrome layer.
"""
import struct, zlib

CANVAS = 108.0
TILE = 220
SS = 4                      # supersampling factor per axis

# ---- geometry (identical to res/drawable/ic_launcher_foreground.xml) ---------------
# pathData "M40,40 L40,70 M40,40 L54,58 L68,40 L68,70", strokeWidth 6,
# strokeLineCap/strokeLineJoin round.
STROKE = 6.0
M_SEGMENTS = [
    ((40.0, 40.0), (40.0, 70.0)),   # left stem
    ((40.0, 40.0), (54.0, 58.0)),   # left diagonal of the middle V
    ((54.0, 58.0), (68.0, 40.0)),   # right diagonal
    ((68.0, 40.0), (68.0, 70.0)),   # right stem
]

BACKGROUND = (0x1F, 0x6F, 0xEB)     # @color/ic_launcher_background
FOREGROUND = (0xFF, 0xFF, 0xFF)

def distance_to_segment(ax, ay, bx, by, x, y):
    dx, dy = bx - ax, by - ay
    if dx == 0.0 and dy == 0.0:
        return ((x - ax) ** 2 + (y - ay) ** 2) ** 0.5
    t = max(0.0, min(1.0, ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)))
    px, py = ax + t * dx, ay + t * dy
    return ((px - x) ** 2 + (py - y) ** 2) ** 0.5

def mark_at(x, y):
    """Round caps and joins fall out of the point-to-segment distance test."""
    half = STROKE / 2.0
    for (ax, ay), (bx, by) in M_SEGMENTS:
        if distance_to_segment(ax, ay, bx, by, x, y) <= half:
            return True
    return False

def mask_circle(x, y):
    return (x - CANVAS / 2) ** 2 + (y - CANVAS / 2) ** 2 <= (CANVAS / 2) ** 2

def mask_squircle(x, y):
    r = 26.0
    half = CANVAS / 2
    qx = abs(x - half) - (half - r)
    qy = abs(y - half) - (half - r)
    dx = max(qx, 0.0)
    dy = max(qy, 0.0)
    return (dx * dx + dy * dy) ** 0.5 <= r

def pixels_for_tile(mask, bg, fg):
    """Returns TILE x TILE RGB rows."""
    scale = CANVAS / TILE
    rows = []
    inv = 1.0 / (SS * SS)
    for py in range(TILE):
        row = bytearray()
        for px in range(TILE):
            covered = 0
            masked = 0
            for sy in range(SS):
                for sx in range(SS):
                    x = (px + (sx + 0.5) / SS) * scale
                    y = (py + (sy + 0.5) / SS) * scale
                    if mask is None or mask(x, y):
                        masked += 1
                        if mark_at(x, y):
                            covered += 1
            if masked == 0:
                row += bytes((0, 0, 0))
                continue
            alpha_mask = masked * inv
            alpha_ink = covered * inv
            # ink over background, then the mask over black (transparent preview)
            base = tuple(int(round(bg[i] * (1 - alpha_ink) + fg[i] * alpha_ink)) for i in range(3))
            row += bytes(int(round(c * alpha_mask)) for c in base)
        rows.append(bytes(row))
    return rows

def write_png(path, width, height, rows):
    raw = b"".join(b"\x00" + r for r in rows)
    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)
    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) +
           chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
    open(path, "wb").write(png)

tiles = [
    ("square", None, BACKGROUND, FOREGROUND),
    ("circle", mask_circle, BACKGROUND, FOREGROUND),
    ("squircle", mask_squircle, BACKGROUND, FOREGROUND),
    ("monochrome", None, (0xE8, 0xEA, 0xED), (0x1F, 0x23, 0x28)),
]
grid = [[None, None], [None, None]]
for index, (name, mask, bg, fg) in enumerate(tiles):
    rows = pixels_for_tile(mask, bg, fg)
    grid[index // 2][index % 2] = rows
    print("rendered " + name)

GAP = 8
W = TILE * 2 + GAP * 3
H = TILE * 2 + GAP * 3

def tile_index(value, second_start):
    if GAP <= value < GAP + TILE:
        return 0, value - GAP
    if second_start <= value < second_start + TILE:
        return 1, value - second_start
    return None, None

canvas = []
for y in range(H):
    row = bytearray()
    tr, ty = tile_index(y, GAP * 2 + TILE)
    for x in range(W):
        tc, tx = tile_index(x, GAP * 2 + TILE)
        if tc is None or tr is None:
            row += b"\xF0\xF0\xF0"
        else:
            row += grid[tr][tc][ty][tx * 3:tx * 3 + 3]
    canvas.append(bytes(row))
write_png("/home/xstrsx/project/MarkView/tools/icon-preview.png", W, H, canvas)
print("wrote tools/icon-preview.png (%dx%d)" % (W, H))
