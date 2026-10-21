// Run with the normal camera service started and the Android calibration screen closed.
// No photographs or access tokens are saved or printed.
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';

const serial = process.env.ANDROID_SERIAL;
assert.ok(serial, 'Set ANDROID_SERIAL');
const xml = execFileSync('adb', ['-s', serial, 'shell', 'run-as', 'dev.hram.mobileledger.prototype', 'cat', 'shared_prefs/camera.xml'], {encoding: 'utf8'});
const token = xml.match(/name="access_token">([^<]+)/)?.[1];
assert.ok(token);
const base = process.env.CAMERA_BASE || 'http://127.0.0.1:18090';
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const api = (path, body, auth = true) => fetch(base + path, {
  method: body === undefined ? 'GET' : 'POST',
  headers: {...(auth ? {Authorization: 'Bearer ' + token} : {}), 'Content-Type': 'application/json'},
  body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(8000),
});
async function until(run, label, attempts = 60) {
  for (let i = 0; i < attempts; i++) { const result = await run(); if (result) return result; await delay(250); }
  throw Error('Timed out: ' + label);
}
function jpegSize(bytes) {
  assert.equal(bytes.readUInt16BE(0), 0xffd8);
  for (let offset = 2; offset + 8 < bytes.length;) {
    assert.equal(bytes[offset], 0xff);
    const marker = bytes[offset + 1], length = bytes.readUInt16BE(offset + 2);
    if ([0xc0, 0xc1, 0xc2].includes(marker))
      return {width: bytes.readUInt16BE(offset + 7), height: bytes.readUInt16BE(offset + 5)};
    assert.ok(length >= 2 && marker !== 0xda, 'JPEG dimensions must precede scan');
    offset += 2 + length;
  }
  throw Error('No JPEG dimensions');
}
const id = 'remote-' + Date.now();
const a = {clientId: id + '-a', active: true}, b = {clientId: id + '-b', active: true};
const shot = {requestId: id + '-photo', clientId: id};
let originalTolerance;
try {
  const ready = await until(async () => { const s = await (await api('/status')).json(); return s.state === 'ready' && s; }, 'normal camera ready');
  assert.ok(ready.capabilities.includes('calibration-v1')); originalTolerance = ready.calibrationTolerancePercent;
  assert.equal((await api('/capture', shot)).status, 202);
  const pending = await until(async () => { const r = await api('/result?' + new URLSearchParams(shot)); return r.status === 200 && new Uint8Array(await r.arrayBuffer()); }, 'pending original JPEG');
  assert.equal(pending[0], 0xff);
  assert.equal((await api('/calibration/session', a, false)).status, 401);
  assert.equal((await api('/calibration/session', a)).status, 200);
  assert.equal((await api('/calibration/session', b)).status, 200);
  assert.equal((await (await api('/status?passive=1')).json()).state, 'calibrating');
  assert.equal((await api('/capture', {...shot, requestId: id + '-blocked'})).status, 503);
  console.log('PASS remote calibration owns camera without interrupting capture results');
  const packet = await until(async () => {
    await api('/calibration/session', a);
    await api('/calibration/session', b);
    const response = await api('/calibration/frame');
    return response.status === 200 && response.json();
  }, 'synchronised calibration packet');
  assert.ok(packet.width > 0 && packet.height > 0);
  assert.ok(packet.ageMs < 1500);
  assert.ok(packet.frameId > 0);
  const jpeg = Buffer.from(packet.jpegBase64, 'base64');
  const size = jpegSize(jpeg);
  assert.ok(Math.max(size.width, size.height) <= 640, 'transport preview is bounded');
  assert.ok(Math.max(packet.width, packet.height) >= 1280, 'analysis resolution is preserved');
  assert.ok(Math.abs(size.width / size.height - packet.width / packet.height) < .01, 'geometry aspect ratio is preserved');
  assert.equal(typeof packet.markers, 'object');
  console.log('PASS smaller JPEG and full-resolution geometry are delivered in one fresh packet');
  await api('/calibration/tolerance', {tolerancePercent: 0.8});
  await until(async () => {
    await api('/calibration/session', a);
    await api('/calibration/session', b);
    const response = await api('/calibration/frame');
    return response.status === 200 && Math.abs((await response.json()).tolerancePercent - .8) < 1e-9;
  }, 'updated packet tolerance');
  assert.equal((await api('/calibration/session', {...a, active: false})).status, 200);
  assert.equal((await (await api('/status?passive=1')).json()).state, 'calibrating');
  console.log('PASS live tolerance updates and independent browser leases');
  assert.equal((await api('/calibration/session', {...b, active: false})).status, 200);
  assert.equal((await api('/calibration/frame')).status, 409);
  const replay = await api('/result?' + new URLSearchParams(shot));
  assert.equal(replay.status, 200);
  assert.deepEqual(new Uint8Array(await replay.arrayBuffer()), pending);
  assert.equal((await api('/ack', shot)).status, 200);
  await until(async () => (await (await api('/status')).json()).state === 'ready', 'capture mode restored');
  console.log('PASS pending result preserved; capture mode restored after last tab closes');
  await api('/calibration/session', a);
  await delay(15_500);
  assert.notEqual((await (await api('/status?passive=1')).json()).state, 'calibrating');
  assert.equal((await api('/calibration/frame')).status, 409);
  console.log('PASS abandoned browser lease expires automatically');
} finally {
  await api('/calibration/session', {...a, active: false}).catch(() => {});
  await api('/calibration/session', {...b, active: false}).catch(() => {});
  await api('/cancel', shot).catch(() => {});
  if (originalTolerance !== undefined) await api('/calibration/tolerance', {tolerancePercent: originalTolerance}).catch(() => {});
}
