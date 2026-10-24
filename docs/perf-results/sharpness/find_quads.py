import sys, glob, json, collections
import numpy as np, cv2
scr = sys.argv[1]
found = collections.defaultdict(list)
files = []
for light in ('dark', 'lit'):
    files += sorted(glob.glob(f'{scr}/shots/{light}/s0[5-7]_*.jpg')) + sorted(glob.glob(f'{scr}/shots/{light}/s12_*.jpg'))
dets = {'std': cv2.QRCodeDetector(), 'aruco': cv2.QRCodeDetectorAruco()}
for f in files:
    img = cv2.imread(f, cv2.IMREAD_GRAYSCALE)
    for scale in (0.5, 0.75, 1.0):
        small = img if scale == 1.0 else cv2.resize(img, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
        for name, det in dets.items():
            try: ok, texts, pts, _ = det.detectAndDecodeMulti(small)
            except Exception: continue
            if not ok or pts is None: continue
            for q, t in zip(pts, texts):
                if t.startswith('mobile-ledger:calibration:v1:'):
                    found[t.split(':')[-1]].append((q / scale).tolist())
for label in ('TL', 'TR', 'BL', 'BR'):
    if not found[label]: print(label, 'NOT FOUND'); continue
    qs = np.array(found[label]); med = np.median(qs, axis=0)
    print(label, len(qs), 'detections; max deviation from median (px):', round(float(np.max(np.abs(qs - med))), 1), 'centre', med.mean(axis=0).round(0).tolist())
json.dump({k: np.median(np.array(v), axis=0).tolist() for k, v in found.items()}, open(scr + '/quads.json', 'w'))
