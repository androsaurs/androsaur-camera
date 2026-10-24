"""Capture a controlled defocus series of the printed calibration sheet (needs the temporary focus-distance debug patch)."""
import io, json, os, re, subprocess, sys, time, urllib.request
from PIL import Image
BASE = 'http://127.0.0.1:18088'; OUT = sys.argv[1]; K = 3
xml = subprocess.run(['adb','shell','run-as','dev.hram.mobileledger.prototype','cat','shared_prefs/camera.xml'],capture_output=True,text=True).stdout
H = {'Authorization': 'Bearer ' + re.search(r'name="access_token">([^<]+)', xml).group(1), 'Content-Type': 'application/json'}
def req(path, body=None, raw=False):
    r = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body is not None else None, headers=H, method='POST' if body is not None else 'GET')
    try:
        with urllib.request.urlopen(r, timeout=40) as x: data = x.read(); return (x.status, x.headers, data) if raw else json.loads(data)
    except urllib.error.HTTPError as e:
        return (e.code, e.headers, e.read()) if raw else {'error': e.code}
def ready():
    for _ in range(40):
        req('/status')
        if req('/status').get('state') == 'ready': return
        time.sleep(0.3)
    raise SystemExit('camera not ready')
def shoot(path):
    rid = {'requestId': f'sh-{int(time.time()*1000)}', 'clientId': 'sharp'}
    req('/capture', rid, raw=True)
    while True:
        st, h, b = req('/result?requestId=%s&clientId=sharp' % rid['requestId'], raw=True)
        if st != 202: break
        time.sleep(0.02)
    req('/ack', rid)
    if st != 200: return None
    open(path, 'wb').write(b)
    ex = Image.open(io.BytesIO(b)).getexif().get_ifd(0x8769)
    return {'captureMs': float(h['X-Capture-Ms']), 'exposure': float(ex.get(33434) or 0), 'iso': ex.get(34855), 'bytes': len(b)}
ready()
print('AF on the top-left QR'); req('/focus', {'action': 'focus', 'x': 0.17, 'y': 0.21})
for _ in range(40):
    f = req('/focus')
    if f['state'] in ('locked', 'failed'): break
    time.sleep(0.3)
d0 = f['distance']; print('reference lens distance (diopters):', d0, f['state'])
deltas = [-1.9, -1.5, -1.0, -0.6, -0.3, -0.15, 0.0, 0.15, 0.3, 0.6, 1.0, 2.0, 0.0]
meta = {'d0': d0, 'shots': []}
for light in ('dark', 'lit'):
    req('/torch', {'enabled': light == 'lit'}); time.sleep(2.5 if light == 'lit' else 1.0)
    os.makedirs(f'{OUT}/{light}', exist_ok=True)
    for i, delta in enumerate(deltas):
        target = max(0.0, d0 + delta)
        req('/debug/focus-distance', {'diopters': target})
        for _ in range(30):
            f = req('/focus')
            if f['state'] == 'locked' and f['distance'] is not None and abs(f['distance'] - target) < 0.05: break
            time.sleep(0.2)
        actual = f['distance']
        if not (f['state'] == 'locked' and actual is not None and abs(actual - target) < 0.05):
            print(light, delta, 'lens did not confirm', f['state'], actual); continue
        time.sleep(0.4)
        for k in range(K):
            path = f'{OUT}/{light}/s{i:02d}_{delta:+.2f}_{k}.jpg'
            info = shoot(path)
            if info: meta['shots'].append({'light': light, 'index': i, 'delta': delta, 'k': k, 'target': target, 'actual': actual, 'path': path, **info})
        print(light, f'delta {delta:+.2f} D (lens {actual:.3f}) shots', K, 'exp', info and info['exposure'], 'iso', info and info['iso'])
req('/torch', {'enabled': False})
json.dump(meta, open(f'{OUT}/meta.json', 'w'), indent=1)
print('done', len(meta['shots']), 'shots')
