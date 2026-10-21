// Requires a running phone service. Never prints its token or camera frames.
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';

const device = process.env.ANDROID_SERIAL;
assert.ok(device, 'Set ANDROID_SERIAL explicitly');
const adb = (...args) => execFileSync('adb', ['-s', device, ...args], {encoding: 'utf8'});
const xml = adb('shell', 'run-as', 'dev.hram.mobileledger.prototype', 'cat', 'shared_prefs/camera.xml');
const token = xml.match(/name="access_token">([^<]+)/)?.[1];
assert.ok(token, 'Open the application first');
const base = process.env.CAMERA_BASE || 'http://127.0.0.1:18089';
const api = (path, body, authenticated = true) => fetch(base + path, {
  method: body === undefined ? 'GET' : 'POST',
  headers: {...(authenticated ? {Authorization: 'Bearer ' + token} : {}), 'Content-Type': 'application/json'},
  body: body === undefined ? undefined : JSON.stringify(body),
  signal: AbortSignal.timeout(8000),
});
const status = async () => (await api('/status?passive=1')).json();
const original = (await status()).calibrationTolerancePercent;
assert.equal(typeof original, 'number');
try {
  assert.equal((await api('/calibration/tolerance', {tolerancePercent: 1}, false)).status, 401);
  console.log('PASS authorization');
  for (const value of [0.1, 0.5, 1, 2.5, 5]) {
    const response = await api('/calibration/tolerance', {tolerancePercent: value});
    assert.equal(response.status, 200);
    assert.equal((await response.json()).tolerancePercent, value);
    assert.ok(Math.abs((await status()).calibrationTolerancePercent - value) < 1e-9);
  }
  console.log('PASS runtime updates and range boundaries');
  for (const body of [{}, {tolerancePercent: 0}, {tolerancePercent: -1}, {tolerancePercent: 5.1},
    {tolerancePercent: '1'}, {tolerancePercent: null}, {tolerancePercent: true}]) {
    const response = await api('/calibration/tolerance', body);
    assert.equal(response.status, 400);
    assert.equal((await response.json()).error, 'invalid_calibration_tolerance');
    assert.equal((await status()).calibrationTolerancePercent, 5);
  }
  console.log('PASS invalid commands preserve current tolerance');
  for (let attempt = 0; attempt < 20; attempt++) {
    const settings = adb('shell', 'run-as', 'dev.hram.mobileledger.prototype', 'cat', 'shared_prefs/calibration.xml');
    if (/name="tolerancePercent">5\.0<\/string>/.test(settings)) break;
    assert.ok(attempt < 19, 'Setting was not persisted');
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  console.log('PASS private settings persisted');
} finally {
  assert.equal((await api('/calibration/tolerance', {tolerancePercent: original})).status, 200);
}
