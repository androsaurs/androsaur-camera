"""Compare sharpness metrics on a controlled defocus series of the printed calibration sheet.

Input: <dir>/meta.json and shots/, quads.json (QR corner quads found on near-focus frames; sheet and camera are fixed).
Output: <dir>/sharpness-results.json (per-shot numbers, no image data) and a printed summary.
"""
import glob, json, math, sys
import numpy as np, cv2

scr = sys.argv[1]
meta = json.load(open(f'{scr}/shots/meta.json'))
quads = {k: np.array(v, float) for k, v in json.load(open(f'{scr}/quads.json')).items() if isinstance(v, list)}
LABELS = [k for k in ('TL', 'TR', 'BL', 'BR') if k in quads]
MODULES = 33.0


def lap_var(a):
    return float(cv2.Laplacian(a, cv2.CV_32F, ksize=1).var())


def tenengrad(a):
    gx = cv2.Sobel(a, cv2.CV_32F, 1, 0, ksize=3); gy = cv2.Sobel(a, cv2.CV_32F, 0, 1, ksize=3)
    return float((gx * gx + gy * gy).mean())


def roi(img, q, margin=0.08):
    h, w = img.shape
    x0, y0 = q.min(axis=0); x1, y1 = q.max(axis=0); m = margin * max(x1 - x0, y1 - y0)
    return img[max(0, int(y0 - m)):min(h, int(y1 + m)), max(0, int(x0 - m)):min(w, int(x1 + m))]


def edge_width_marziliano(a, rng, limit=3000):
    """Median distance (px) between the intensity extrema around strong edge pixels, both directions."""
    a = cv2.GaussianBlur(a, (0, 0), 1.0)
    widths = []
    for horizontal in (True, False):
        g = cv2.Sobel(a, cv2.CV_32F, 1 if horizontal else 0, 0 if horizontal else 1, ksize=3)
        mag = np.abs(g); t = 0.35 * mag.max()
        if horizontal: nms = (mag >= np.roll(mag, 1, 1)) & (mag >= np.roll(mag, -1, 1))
        else: nms = (mag >= np.roll(mag, 1, 0)) & (mag >= np.roll(mag, -1, 0))
        ys, xs = np.nonzero((mag >= t) & nms)
        if len(ys) > limit:
            pick = rng.choice(len(ys), limit, replace=False); ys, xs = ys[pick], xs[pick]
        line = a if horizontal else a.T
        for y, x in zip(ys, xs):
            r, c = (y, x) if horizontal else (x, y)
            row = line[r]; s = 1 if g[y, x] > 0 else -1
            left = c
            while left > 0 and s * (row[left] - row[left - 1]) > 0: left -= 1
            right = c
            while right < len(row) - 1 and s * (row[right + 1] - row[right]) > 0: right += 1
            widths.append(right - left)
    return float(np.median(widths)) if widths else float('nan')


def esf_edge(img, p, d, nrm, half_len, outside=30, inside=10):
    """Slanted-edge measurement on one straight edge: returns (edge width 10-90 %, MTF50 in cycles/px) or None."""
    h, w = img.shape
    # pixels of the strip: along-edge |t| <= half_len, signed normal distance from the nominal edge in [-outside, inside]
    pad = int(outside + half_len + 6)
    x0, y0 = int(p[0] - pad), int(p[1] - pad); x1, y1 = int(p[0] + pad), int(p[1] + pad)
    if x0 < 0 or y0 < 0 or x1 >= w or y1 >= h: return None
    patch = img[y0:y1, x0:x1].astype(np.float64)
    yy, xx = np.mgrid[y0:y1, x0:x1]
    rel = np.stack([xx - p[0], yy - p[1]], -1)
    t = rel @ d; s = rel @ nrm
    sel = (np.abs(t) <= half_len) & (s >= -outside) & (s <= inside)
    if sel.sum() < 400: return None
    # refine the edge line: per position along the edge, centroid of |gradient along normal|
    gx = cv2.Sobel(patch, cv2.CV_64F, 1, 0, ksize=3); gy = cv2.Sobel(patch, cv2.CV_64F, 0, 1, ksize=3)
    gn = np.abs(gx * nrm[0] + gy * nrm[1])
    ts = np.arange(-half_len, half_len + 1, 2.0); pts = []
    for tc in ts:
        m = sel & (np.abs(t - tc) <= 1.0)
        if m.sum() < 5: continue
        wgt = gn[m]
        if wgt.sum() < 1e-6: continue
        pts.append((tc, float((s[m] * wgt).sum() / wgt.sum())))
    if len(pts) < 8: return None
    pts = np.array(pts); coef = np.polyfit(pts[:, 0], pts[:, 1], 1)
    for _ in range(2):
        res = pts[:, 1] - np.polyval(coef, pts[:, 0]); keep = np.abs(res) < 2.5 * (res.std() + 0.3)
        if keep.sum() >= 6: coef = np.polyfit(pts[keep, 0], pts[keep, 1], 1)
    dist = s - np.polyval(coef, t)  # signed distance to the refined edge
    sel2 = sel & (dist >= -outside + 2) & (dist <= inside - 1)
    bins = np.round(dist[sel2] / 0.25).astype(int); vals = patch[sel2]
    lo, hi = bins.min(), bins.max(); cnt = np.bincount(bins - lo, minlength=hi - lo + 1); sm = np.bincount(bins - lo, weights=vals, minlength=hi - lo + 1)
    ok = cnt > 0
    if ok.sum() < 20: return None
    esf = np.interp(np.arange(hi - lo + 1), np.nonzero(ok)[0], (sm[ok] / cnt[ok]))
    # orient dark->bright ... normalise
    lo_v, hi_v = np.percentile(esf, 5), np.percentile(esf, 95)
    if hi_v - lo_v < 15: return None
    e = (esf - lo_v) / (hi_v - lo_v)
    if e[: len(e) // 4].mean() > e[-len(e) // 4:].mean(): e = 1 - e
    e = np.clip(e, 0, 1)
    k = np.ones(3) / 3; e = np.convolve(np.pad(e, 1, mode='edge'), k, mode='valid')  # edge padding: zero padding fakes a step at the end
    xs_ = np.arange(len(e)) * 0.25
    try:
        x10 = xs_[np.nonzero(e >= 0.1)[0][0]]; x90 = xs_[np.nonzero(e >= 0.9)[0][0]]
    except IndexError: return None
    width = float(x90 - x10)
    lsf = np.gradient(e); peak = int(np.argmax(np.abs(lsf))); half = min(peak, len(lsf) - 1 - peak, 60)
    if half < 12: return None
    seg = lsf[peak - half: peak + half + 1] * np.hanning(2 * half + 1)
    mtf = np.abs(np.fft.rfft(seg, 1024)); mtf /= mtf[0] + 1e-12
    f = np.fft.rfftfreq(1024, d=0.25)  # cycles per pixel
    below = np.nonzero(mtf < 0.5)[0]
    if len(below) == 0 or below[0] == 0: return None
    i = below[0]; f50 = float(f[i - 1] + (mtf[i - 1] - 0.5) / (mtf[i - 1] - mtf[i]) * (f[i] - f[i - 1]))
    return width, f50


def edge_segments():
    """Finder-pattern outer edges of every QR whose tilt to the pixel grid is large enough for slanted-edge use."""
    segs = []
    for label in LABELS:
        q = quads[label]; cen = q.mean(axis=0)
        for i in range(4):
            a, b = q[i], q[(i + 1) % 4]; side = b - a; ln = np.hypot(*side); d = side / ln
            ang = abs(math.degrees(math.atan2(d[1], d[0]))) % 90; tilt = min(ang, 90 - ang)
            if not (1.4 <= tilt <= 10): continue
            nrm = np.array([-d[1], d[0]])
            if (cen - a) @ nrm < 0: nrm = -nrm  # make the normal point inside the QR
            for frac_c in (3.5 / MODULES, 1 - 3.5 / MODULES):  # centre of a finder side, 7 modules wide
                p = a + side * frac_c
                segs.append({'label': label, 'p': p, 'd': d, 'n': nrm, 'half': 2.4 * ln / MODULES, 'tilt': tilt})
    return segs


SEGS = edge_segments()
print('edges for slanted-edge analysis:', len(SEGS), 'tilts', sorted({round(s['tilt'], 1) for s in SEGS}))
rng = np.random.default_rng(1)

# blank paper patch for the noise floor: centre of the sheet from the QR centres we have
cens = {k: quads[k].mean(axis=0) for k in LABELS}
if all(k in cens for k in ('TL', 'BR')): sheet_c = (cens['TL'] + cens['BR']) / 2
else: sheet_c = np.mean(list(cens.values()), axis=0)

results = []
import os
for sh in meta['shots'][:int(os.environ.get('LIMIT', '100000'))]:
    img8 = cv2.imread(sh['path'], cv2.IMREAD_GRAYSCALE)
    img = img8.astype(np.float32); h, w = img.shape
    r = {k: sh[k] for k in ('light', 'index', 'delta', 'k', 'actual', 'captureMs', 'exposure', 'iso', 'bytes')}
    # --- the metric the app computes today: Laplacian variance on a 1024 px copy ---
    scale = 1024 / max(h, w); small = cv2.resize(img, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA); sh_, sw_ = small.shape
    r['app_global'] = lap_var(small)
    r['app_center'] = lap_var(small[sh_ // 4: sh_ * 3 // 4, sw_ // 4: sw_ * 3 // 4])
    tiles = [lap_var(small[i * sh_ // 4:(i + 1) * sh_ // 4, j * sw_ // 4:(j + 1) * sw_ // 4]) for i in range(4) for j in range(4)]
    r['app_tile_max'] = max(tiles); r['app_tile_median'] = float(np.median(tiles))
    # --- content-aware: QR regions ---
    rois = [roi(img, quads[k]) for k in LABELS]
    r['qr_lapvar_full'] = float(np.mean([lap_var(x) for x in rois]))
    r['qr_tenengrad_full'] = float(np.mean([tenengrad(x) for x in rois]))
    r['qr_contrast'] = float(np.mean([np.percentile(x, 95) - np.percentile(x, 5) for x in rois]))
    r['qr_tenengrad_norm'] = r['qr_tenengrad_full'] / (r['qr_contrast'] ** 2 + 1e-9)
    r['qr_lapvar_norm'] = r['qr_lapvar_full'] / (r['qr_contrast'] ** 2 + 1e-9)
    srois = [roi(small, quads[k] * scale) for k in LABELS]
    r['qr_lapvar_1024'] = float(np.mean([lap_var(x) for x in srois]))
    # --- edge-based ---
    r['edge_width_marziliano'] = float(np.nanmedian([edge_width_marziliano(x, rng) for x in rois]))
    meas = [esf_edge(img, s['p'], s['d'], s['n'], s['half']) for s in SEGS]
    meas = [m for m in meas if m]
    r['sfr_edges_used'] = len(meas)
    r['sfr_width10_90'] = float(np.median([m[0] for m in meas])) if meas else float('nan')
    r['sfr_mtf50'] = float(np.median([m[1] for m in meas])) if meas else float('nan')
    # --- noise floor on blank paper, and a noise-compensated variant ---
    cx, cy = int(sheet_c[0]), int(sheet_c[1]); blank = img[cy - 200:cy + 200, cx - 200:cx + 200]
    r['blank_lapvar'] = lap_var(blank); r['blank_std'] = float(blank.std())
    r['qr_lapvar_minus_noise'] = max(0.0, r['qr_lapvar_full'] - r['blank_lapvar'])
    # --- practical: do the QR codes still decode? (full resolution and 1024 px copy) ---
    det = cv2.QRCodeDetector()
    def decodes(a8, q):
        c = roi(a8, q, 0.12)
        try: t, _, _ = det.detectAndDecode(c)
        except Exception: t = ''
        return t.startswith('mobile-ledger:calibration:v1:')
    r['qr_decoded_full'] = sum(decodes(img8, quads[k]) for k in LABELS)
    small8 = cv2.resize(img8, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
    r['qr_decoded_1024'] = sum(decodes(small8, quads[k] * scale) for k in LABELS)
    results.append(r)
    print(f"{r['light']:4} d={r['delta']:+.2f} k{r['k']}  app={r['app_global']:7.1f}  qrLap={r['qr_lapvar_full']:8.1f}  W={r['edge_width_marziliano']:5.2f}  sfrW={r['sfr_width10_90']:6.2f}  mtf50={r['sfr_mtf50']:.4f} ({r['sfr_edges_used']})  dec={r['qr_decoded_full']}/{r['qr_decoded_1024']}", flush=True)
json.dump({'d0': meta['d0'], 'labels': LABELS, 'edges': len(SEGS), 'results': results}, open(f'{scr}/sharpness-results.json', 'w'), indent=1)
print('saved', len(results))
