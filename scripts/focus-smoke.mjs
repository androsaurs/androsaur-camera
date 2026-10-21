// Emulator regression only. No tokens, image contents, or user photographs are saved/logged.
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
const serial = process.env.ANDROID_SERIAL;
assert.ok(serial?.startsWith('emulator-'), 'Run this capture test only on a disposable emulator');
const xml = execFileSync('adb', ['-s', serial, 'shell', 'run-as', 'dev.hram.mobileledger.prototype', 'cat', 'shared_prefs/camera.xml'], {encoding: 'utf8'});
const token = xml.match(/name="access_token">([^<]+)/)?.[1]; assert.ok(token);
const base = process.env.CAMERA_BASE || 'http://127.0.0.1:18090';
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const api = (path, body, auth = true) => fetch(base + path, {method: body === undefined ? 'GET' : 'POST',
  headers: {'Content-Type': 'application/json', ...(auth ? {Authorization: 'Bearer ' + token} : {})},
  body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(8000)});
async function until(run, label) {
  for (let i = 0; i < 100; i++) { const result = await run().catch(() => false); if (result) return result; await delay(150); }
  throw Error('Timed out: ' + label);
}
const get = async () => (await api('/focus')).json();
const settled = () => until(async () => {const s = await get(); return !['focusing', 'locking', 'restoring'].includes(s.state) && s;}, 'focus settles');
const id = 'focus-' + Date.now(), client = {clientId: id, active: true}, shot = {requestId: id, clientId: id};
try {
  const status = await until(async () => {const s = await (await api('/status')).json();return s.state === 'ready' && s;}, 'camera ready');
  assert.ok(status.capabilities.includes('focus-v1'));
  assert.equal((await api('/focus', {action: 'focus'}, false)).status, 401);
  assert.equal((await api('/focus', {action: 'focus', x: -1})).status, 400);
  assert.equal((await api('/focus', {action: 'focus', y: '0.5'})).status, 400);
  const initial = await until(async () => {const s = await get(); return s.afMode === 0 && s;}, 'AF OFF at startup');
  assert.equal(initial.scanCount, 0);
  console.log('PASS no automatic AF at startup; commands authenticated and validated');
  const response = await api('/focus', {action: 'focus', x: .25, y: .75});
  assert.ok([202, 409].includes(response.status));
  const focused = await settled();
  assert.equal(focused.scanCount, response.status === 202 ? 1 : 0);
  console.log('PASS explicit focus command settles (or reports unsupported hardware)');
  let pinned = false, distance;
  if (focused.manualLock) {
    assert.equal((await api('/focus', {action: 'lock'})).status, 202);
    const locked = await settled(); assert.equal(locked.state, 'locked');
    assert.equal(locked.afMode, 0); distance = locked.distance; pinned = true;
    console.log('PASS current lens position can be frozen with AF OFF');
  } else {
    assert.equal((await api('/focus', {action: 'lock'})).status, 409);
    console.log('PASS unavailable manual lens lock is reported honestly');
  }
  const before = await get();
  const size = status.resolutions.at(-1);
  assert.equal((await api('/capture', {...shot, ...(size || {})})).status, 202);
  assert.equal((await api('/focus', {action: 'focus'})).status, 409);
  await until(async () => {const r = await api('/result?' + new URLSearchParams(shot)); if (r.status !== 200) return false;
    assert.equal(new Uint8Array(await r.arrayBuffer())[0], 0xff);
    if (pinned) assert.equal(r.headers.get('X-Focus-Status'), 'locked'); return true;}, 'original JPEG');
  const after = await get(); assert.equal(after.scanCount, before.scanCount); assert.equal(after.afMode, 0);
  if (pinned) {assert.equal(after.state, 'locked'); assert.equal(after.distance, distance);}
  await api('/ack', shot);
  console.log('PASS capture/rebind do not trigger AF' + (pinned ? '; pinned distance survives resolution change' : ' (manual lens control unavailable)'));
  assert.equal((await api('/calibration/session', client)).status, 200);
  await until(async () => {await api('/calibration/session', client);const r = await api('/calibration/frame');return r.status === 200;}, 'calibration image');
  const calibration = await settled(); assert.equal(calibration.afMode, 0);
  if (pinned) assert.equal(calibration.distance, distance);
  const focusInCalibration = await api('/focus', {action: 'focus', x: .7, y: .3});
  assert.ok([202, 409].includes(focusInCalibration.status));
  await settled();
  await api('/calibration/session', {...client, active: false});
  await until(async () => (await (await api('/status')).json()).state === 'ready', 'capture restored');
  const end = await until(async () => {const s = await get(); return s.afMode === 0 && !['focusing', 'locking', 'restoring'].includes(s.state) && s;}, 'AF OFF after mode switch');
  assert.equal(end.afMode, 0);
  assert.equal(end.scanCount, before.scanCount + (focusInCalibration.status === 202 ? 1 : 0));
  console.log('PASS explicit focus works in calibration; mode switches never start an AF scan');
} finally {
  await api('/calibration/session', {...client, active: false}).catch(() => {});
  await api('/cancel', shot).catch(() => {});
}
