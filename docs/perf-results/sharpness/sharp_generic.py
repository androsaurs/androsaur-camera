"""Marker-free candidate: Tenengrad / contrast^2 over the content tiles of a 1024 px copy (no knowledge of the QR codes)."""
import json, sys
import numpy as np, cv2
from scipy.stats import spearmanr
scr = sys.argv[1]
doc = json.load(open(f'{scr}/sharpness-results.json')); R = doc['results']
meta = {s['path']: s for s in json.load(open(f'{scr}/shots/meta.json'))['shots']}
def tiles_metric(img8, grid=8, min_contrast=30):
    h, w = img8.shape; s = 1024 / max(h, w); a = cv2.resize(img8, None, fx=s, fy=s, interpolation=cv2.INTER_AREA).astype(np.float32)
    gx = cv2.Sobel(a, cv2.CV_32F, 1, 0, ksize=3); gy = cv2.Sobel(a, cv2.CV_32F, 0, 1, ksize=3); e = gx * gx + gy * gy
    H, W = a.shape; vals = []
    for i in range(grid):
        for j in range(grid):
            sl = (slice(i * H // grid, (i + 1) * H // grid), slice(j * W // grid, (j + 1) * W // grid))
            c = np.percentile(a[sl], 95) - np.percentile(a[sl], 5)
            if c > min_contrast: vals.append(float(e[sl].mean() / (c * c)))
    return (float(np.median(vals)), float(np.percentile(vals, 75)), len(vals)) if vals else (float('nan'),) * 2 + (0,)
for r, sh in zip(R, json.load(open(f'{scr}/shots/meta.json'))['shots']):
    g = tiles_metric(cv2.imread(sh['path'], cv2.IMREAD_GRAYSCALE)); r['gen_tile_median'], r['gen_tile_p75'], r['gen_tiles_used'] = g
json.dump(doc, open(f'{scr}/sharpness-results.json', 'w'), indent=1)
truth = np.array([r['sfr_mtf50'] for r in R]); deltas = sorted({r['delta'] for r in R})
print('tiles used per frame (min/median/max):', min(r['gen_tiles_used'] for r in R), int(np.median([r['gen_tiles_used'] for r in R])), max(r['gen_tiles_used'] for r in R))
for m in ('gen_tile_median', 'gen_tile_p75', 'qr_tenengrad_norm', 'app_global'):
    v = np.array([r[m] for r in R]); print(f'{m:20} rho_truth={spearmanr(v, truth)[0]:.3f}')
    for light in ('dark', 'lit'):
        best = np.mean([r[m] for r in R if r['light'] == light and r['delta'] == 0.0])
        print('   ', f'{light:4}', ' '.join(f'{np.mean([r[m] for r in R if r["light"]==light and r["delta"]==d])/best:5.2f}' for d in deltas))
print('deltas:', ' '.join(f'{d:+5.2f}' for d in deltas))
