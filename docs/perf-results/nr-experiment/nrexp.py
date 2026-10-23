import io, json, statistics, subprocess, sys, time, urllib.request
from PIL import Image, ImageOps
BASE = 'http://127.0.0.1:18088'
xml = subprocess.run(['adb','shell','run-as','dev.hram.mobileledger.prototype','cat','shared_prefs/camera.xml'],capture_output=True,text=True).stdout
import re
TOKEN = re.search(r'name="access_token">([^<]+)', xml).group(1)
H = {'Authorization': 'Bearer ' + TOKEN, 'Content-Type': 'application/json'}
def req(path, body=None):
    r = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body is not None else None, headers=H, method='POST' if body is not None else 'GET')
    try:
        with urllib.request.urlopen(r, timeout=30) as resp: return resp.status, resp.headers, resp.read()
    except urllib.error.HTTPError as e: return e.code, e.headers, e.read()
def ready():
    for _ in range(40):
        req('/status'); s = json.loads(req('/status')[2])
        if s['state'] == 'ready': return
        time.sleep(0.3)
    raise SystemExit('camera not ready')
seq = 0
def shot():
    global seq; seq += 1
    rid = {'requestId': f'nr-{int(time.time()*1000)}-{seq}', 'clientId': 'nrexp'}
    t0 = time.perf_counter(); st, _, _ = req('/capture', rid)
    if st != 202: return {'error': st}
    while True:
        st, h, b = req('/result?requestId=%s&clientId=nrexp' % rid['requestId'])
        if st != 202: break
        time.sleep(0.02)
    total = (time.perf_counter() - t0) * 1000
    req('/ack', rid)
    if st != 200: return {'error': st}
    out = {'total': total, 'capture': float(h['X-Capture-Ms']), 'write': float(h['X-Write-Ms']) if h['X-Write-Ms'] else None, 'bytes': len(b)}
    try:
        sub = Image.open(io.BytesIO(b)).getexif().get_ifd(0x8769); out['exp'] = float(sub.get(33434)) if sub.get(33434) else None; out['iso'] = sub.get(34855)
    except Exception: out['exp'] = out['iso'] = None
    out['jpeg'] = b
    return out
configs = [('default', None, False), ('nr=off', 'off', False), ('nr=fast', 'fast', False), ('nr=hq', 'hq', False), ('nr=minimal', 'minimal', False), ('memory', None, True), ('nr=off+memory', 'off', True)]
light = sys.argv[1] if len(sys.argv) > 1 else 'dark'
if light == 'lit': req('/torch', {'enabled': True}); time.sleep(2.5)
ready()
info = json.loads(req('/debug/capture-mode', {'nr': None, 'memory': False})[2]); print('available NR modes:', info.get('availableNrModes')); time.sleep(2.8); ready()
rows = {c[0]: [] for c in configs}; first = {}
try:
    for rnd in range(2):
        for name, nr, mem in configs:
            req('/status'); req('/debug/capture-mode', {'nr': nr, 'memory': mem}); time.sleep(2.8); ready()
            shot()  # warm-up, discarded
            for _ in range(5):
                s = shot()
                if 'error' in s: print(name, 'error', s); continue
                if name not in first: first[name] = s['jpeg']
                s.pop('jpeg'); rows[name].append(s)
finally:
    req('/debug/capture-mode', {'nr': None, 'memory': False})
    if light == 'lit': req('/torch', {'enabled': False})
med = lambda k, rs: round(statistics.median([r[k] for r in rs if r.get(k) is not None]), 3) if any(r.get(k) is not None for r in rs) else None
print(f'light={light}: median over {len(rows["default"])} shots per config')
print(f'{"config":16}{"capture":>9}{"write":>7}{"total":>8}{"MB":>6}{"exp s":>8}{"ISO":>6}')
res = {}
for name, rs in rows.items():
    res[name] = {'capture': med('capture', rs), 'write': med('write', rs), 'total': med('total', rs), 'mb': round(statistics.median([r['bytes'] for r in rs]) / 1e6, 2), 'exp': med('exp', rs), 'iso': med('iso', rs), 'n': len(rs)}
    r = res[name]; print(f'{name:16}{r["capture"]:>9}{str(r["write"]):>7}{r["total"]:>8}{r["mb"]:>6}{str(r["exp"]):>8}{str(r["iso"]):>6}')
json.dump(res, open(sys.argv[2] if len(sys.argv) > 2 else '/tmp/nrexp-result.json', 'w'))
# one center crop per configuration at native resolution, for a visual noise comparison (full frames are not kept)
tiles = []
for name in [c[0] for c in configs]:
    if name in first:
        im = ImageOps.exif_transpose(Image.open(io.BytesIO(first[name]))).convert('RGB'); w, h = im.size
        tiles.append((name, im.crop((w//2-300, h//2-300, w//2+300, h//2+300))))
if tiles:
    from PIL import ImageDraw
    cols = 4; rows_n = (len(tiles) + cols - 1) // cols
    sheet = Image.new('RGB', (cols * 600, rows_n * 620), 'white'); d = ImageDraw.Draw(sheet)
    for i, (name, t) in enumerate(tiles):
        x, y = (i % cols) * 600, (i // cols) * 620; sheet.paste(t, (x, y + 20)); d.text((x + 6, y + 4), name, fill='black')
    sheet.save(sys.argv[3] if len(sys.argv) > 3 else '/tmp/nrexp-crops.jpg', quality=90)
