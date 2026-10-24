import json, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont
scr = sys.argv[1]
R = json.load(open(f'{scr}/sharpness-results.json'))['results']
deltas = sorted({r['delta'] for r in R})
def curve(light, m):
    best = np.mean([r[m] for r in R if r['light'] == light and r['delta'] == 0.0])
    return [np.mean([r[m] for r in R if r['light'] == light and r['delta'] == d]) / best for d in deltas]
series = [('sfr_mtf50', 'MTF50 по краям QR (эталон)', (0, 0, 0), 3), ('qr_tenengrad_norm', 'Tenengrad / контраст, по QR', (31, 119, 180), 2),
          ('qr_lapvar_1024', 'лапласиан по QR, 1024 px', (255, 127, 14), 2), ('app_global', 'как в приложении сейчас (весь кадр)', (214, 39, 40), 2)]
W, H = 1200, 520; ml, mr, mt, mb = 70, 20, 60, 70
font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 14); small = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 12); big = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 16)
img = Image.new('RGB', (W, H), 'white'); d = ImageDraw.Draw(img)
pw = (W - 2 * ml - 30) // 2
ymax = 1.15
for pi, (light, title) in enumerate((('dark', 'без подсветки (ISO 862)'), ('lit', 'с подсветкой (ISO 325)'))):
    x0 = ml + pi * (pw + 30 + 0); x1 = x0 + pw - (0 if pi else 0)
    if pi == 1: x0 = ml + pw + 60; x1 = x0 + pw
    y0, y1 = mt, H - mb
    X = lambda v: x0 + (v + 2.0) / 4.0 * (x1 - x0) * 1.0 if False else x0 + (v - (-2.0)) / (2.1 - (-2.0)) * (x1 - x0)
    Y = lambda v: y1 - v / ymax * (y1 - y0)
    d.rectangle([X(-0.3), y0, X(0.3), y1], fill=(232, 245, 232))
    for gy in np.arange(0, 1.01, 0.25):
        d.line([x0, Y(gy), x1, Y(gy)], fill=(225, 225, 225)); d.text((x0 - 36, Y(gy) - 7), f'{gy:.2f}', fill='black', font=small)
    for gx in (-2, -1.5, -1, -0.5, 0, 0.5, 1, 1.5, 2):
        d.line([X(gx), y0, X(gx), y1], fill=(235, 235, 235)); d.text((X(gx) - 10, y1 + 4), f'{gx:+.1f}', fill='black', font=small)
    d.rectangle([x0, y0, x1, y1], outline='black')
    d.text((x0, y0 - 24), title, fill='black', font=big)
    d.text((x0 + (x1 - x0) // 2 - 130, y1 + 28), 'смещение объектива от лучшего фокуса, диоптрии', fill='black', font=small)
    for m, label, col, wd in series:
        pts = [(X(dd), Y(v)) for dd, v in zip(deltas, curve(light, m))]
        d.line(pts, fill=col, width=wd)
        for p in pts: d.ellipse([p[0] - 2.5, p[1] - 2.5, p[0] + 2.5, p[1] + 2.5], fill=col)
d.text((6, 8), 'по вертикали: значение / значение в фокусе', fill='black', font=small)
lx, ly = ml + 6, H - 24
for m, label, col, wd in series:
    d.line([lx, ly + 7, lx + 22, ly + 7], fill=col, width=wd); d.text((lx + 28, ly), label, fill='black', font=small); lx += 40 + int(d.textlength(label, font=small))
img.save(f'{scr}/sharpness-curves.png'); print('saved', img.size)
