// MCP endpoint checks; no photograph contents or access token are written to the report.
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {mkdir, writeFile} from 'node:fs/promises';

const base = process.env.CAMERA_BASE || 'http://127.0.0.1:18088';
const app = 'dev.hram.androsaur.camera';
const xml = execFileSync('adb', ['shell', 'run-as', app, 'cat', 'shared_prefs/camera.xml'], {encoding:'utf8'});
const token = xml.match(/name="access_token">([^<]+)/)?.[1];
assert.ok(token, 'Launch the application once to generate the prototype access token');
const auth = {Authorization:'Bearer '+token};
const delay = ms => new Promise(r => setTimeout(r, ms));
const report = {date:new Date().toISOString(), base, checks:[]};
let nextId = 1;
async function post(body, headers = {}) {
  return fetch(base+'/mcp', {method:'POST', headers:{...auth,'Content-Type':'application/json',...headers}, body:JSON.stringify(body), signal:AbortSignal.timeout(30000)});
}
async function rpc(method, params) {
  const response = await post({jsonrpc:'2.0', id:nextId++, method, params});
  assert.equal(response.status, 200, method);
  return await response.json();
}
async function tool(name, args = {}) {
  const reply = await rpc('tools/call', {name, arguments:args});
  assert.ok(reply.result, JSON.stringify(reply).slice(0, 200));
  return reply.result;
}
const data = result => JSON.parse(result.content[0].text);
const images = result => result.content.filter(c => c.type === 'image');
const jpegBytes = b64 => Buffer.from(b64, 'base64');
const isJpeg = bytes => bytes[0] === 0xff && bytes[1] === 0xd8;
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
async function check(name, run) { await run(); report.checks.push(name); console.log('PASS', name) }
async function waitReady() {
  for (let i = 0; i < 40; i++) {
    try {
      await fetch(base+'/status', {headers:auth});
      const s = data(await tool('status'));
      if (s.state === 'ready') return s;
    } catch {}
    await delay(400);
  }
  throw Error('Camera did not become ready');
}
async function download(url, headers = {}) { return fetch(url, {headers, signal:AbortSignal.timeout(15000)}) }
function expectOk(result, label) { assert.equal(result.isError, false, label+': '+result.content[0].text); return data(result) }

let first, second;
try {
  await check('unauthenticated MCP rejected', async () => assert.equal((await fetch(base+'/mcp', {method:'POST', body:'{}'})).status, 401));
  await check('browser Origin rejected', async () => assert.equal((await post({jsonrpc:'2.0', id:0, method:'ping'}, {Origin:'http://evil.example'})).status, 403));
  await check('GET /mcp is not allowed', async () => assert.equal((await fetch(base+'/mcp', {headers:auth})).status, 405));
  await check('initialize, notification, ping, unknown method', async () => {
    const init = await rpc('initialize', {protocolVersion:'2025-06-18', capabilities:{}, clientInfo:{name:'smoke', version:'1'}});
    assert.equal(init.result.serverInfo.name, 'androsaur-camera');
    assert.equal((await post({jsonrpc:'2.0', method:'notifications/initialized'})).status, 202);
    assert.deepEqual((await rpc('ping')).result, {});
    assert.equal((await rpc('no/such')).error.code, -32601);
  });
  await check('tools/list: eleven tools with annotations', async () => {
    const tools = (await rpc('tools/list')).result.tools;
    assert.deepEqual(tools.map(t => t.name).sort(), ['focus','get_capture_info','get_capture_link','list_captures','preview','release_capture','status','take_photo','torch','view_capture','view_region']);
    assert.ok(tools.every(t => t.annotations && 'readOnlyHint' in t.annotations && t.inputSchema.type === 'object'));
    assert.equal(tools.find(t => t.name === 'release_capture').annotations.destructiveHint, true);
    assert.equal(tools.find(t => t.name === 'view_region').annotations.readOnlyHint, true);
  });
  await check('status: ready camera and empty capture store', async () => {
    const s = await waitReady();
    assert.ok(s.resolutions.length);
    assert.equal(s.captureStore.count, 0);
    assert.equal(typeof s.sensors.light.available, 'boolean');
    if (s.sensors.light.available && s.sensors.light.lux !== undefined) assert.ok(s.sensors.light.lux >= 0 && s.sensors.light.sinceChangeMs >= 0);
    assert.match(s.captureStore.bootId, /^[0-9a-f]{4}$/);
    report.bootId = s.captureStore.bootId;
  });
  await check('preview returns an image', async () => {
    let result;
    for (let i = 0; i < 6; i++) { result = await tool('preview'); if (!result.isError) break; await delay(500) }
    assert.equal(result.isError, false);
    assert.ok(isJpeg(jpegBytes(images(result)[0].data)));
  });
  await check('take_photo: handle, no image, no link, and no metric processing unless asked', async () => {
    const started = performance.now();
    const result = await tool('take_photo');
    const photo = expectOk(result, 'take_photo');
    first = photo;
    assert.equal(images(result).length, 0);
    assert.match(photo.handle, new RegExp('^'+report.bootId+'-[0-9a-f]{8}$'));
    assert.ok(photo.bytes > 10000); assert.equal(photo.sha256.length, 64);
    assert.equal(photo.metrics, undefined, 'metrics must not be computed unless requested');
    assert.equal(data(await tool('get_capture_info', {handle:photo.handle})).metrics, undefined, 'plain info must not compute metrics either');
    assert.equal(data(await tool('list_captures')).captures.find(c => c.handle === photo.handle).sharpness, undefined);
    const asked = data(await tool('get_capture_info', {handle:photo.handle, include_metrics:true}));
    const m = asked.metrics;
    assert.ok(m && m.sharpness >= 0 && m.sharpnessCenter >= 0);
    assert.equal(m.sharpnessGrid.length, 4); assert.ok(m.sharpnessGrid.every(row => row.length === 4));
    assert.ok(m.meanBrightness >= 0 && m.meanBrightness <= 255 && m.contrast >= 0);
    assert.ok(m.overexposedPercent >= 0 && m.overexposedPercent <= 100);
    assert.ok(!('originalUrl' in photo) && !('url' in photo));
    assert.deepEqual(data(await tool('get_capture_info', {handle:photo.handle})).metrics, m, 'computed metrics are kept with the photo');
    const inline = expectOk(await tool('take_photo', {include_metrics:true}), 'take_photo with metrics');
    assert.ok(inline.metrics && inline.metrics.sharpness >= 0, 'include_metrics returns metrics inline');
    await tool('release_capture', {handle:inline.handle});
    report.firstPhoto = {elapsedMs:Math.round(performance.now()-started), bytes:photo.bytes, width:photo.width, height:photo.height, focus:photo.focus, metrics:m};
  });
  await check('take_photo with include_preview attaches one smaller image', async () => {
    const result = await tool('take_photo', {include_preview:true, preview_max_edge:800});
    const photo = expectOk(result, 'take_photo preview');
    second = photo;
    assert.equal(images(result).length, 1);
    const bytes = jpegBytes(images(result)[0].data);
    assert.ok(isJpeg(bytes) && bytes.length < photo.bytes);
    assert.ok(Math.max(photo.preview.width, photo.preview.height) <= 800);
    assert.notEqual(photo.handle, first.handle);
  });
  await check('both handles stay in the store (no replacement by newer shots)', async () => {
    const list = data(await tool('list_captures'));
    assert.deepEqual(list.captures.map(c => c.handle).sort(), [first.handle, second.handle].sort());
    assert.equal(list.usage.count, 2);
  });
  await check('browser capture neither deletes nor is blocked by agent photos', async () => {
    const request = {requestId:'tab-'+Date.now(), clientId:'tab-smoke'};
    assert.equal((await fetch(base+'/capture', {method:'POST', headers:{...auth,'Content-Type':'application/json'}, body:JSON.stringify(request)})).status, 202);
    let status;
    for (let i = 0; i < 60; i++) { status = (await fetch(base+'/result?'+new URLSearchParams(request), {headers:auth})).status; if (status !== 202) break; await delay(100) }
    assert.equal(status, 200);
    assert.equal((await fetch(base+'/ack', {method:'POST', headers:{...auth,'Content-Type':'application/json'}, body:JSON.stringify(request)})).status, 200);
    expectOk(await tool('get_capture_info', {handle:first.handle}), 'info after browser shot');
  });
  await check('request_id is idempotent', async () => {
    const key = 'doc-'+Date.now()+'-p1';
    const a = expectOk(await tool('take_photo', {request_id:key}), 'first');
    const b = expectOk(await tool('take_photo', {request_id:key}), 'repeat');
    assert.equal(a.handle, b.handle); assert.equal(a.replayed, false); assert.equal(b.replayed, true);
    assert.equal(data(await tool('list_captures')).usage.count, 3);
    expectOk(await tool('release_capture', {handle:a.handle}), 'release');
  });
  await check('view_capture respects the pixel limit and reports clamping', async () => {
    const result = await tool('view_capture', {handle:first.handle});
    const view = expectOk(result, 'view_capture');
    assert.equal(images(result).length, 1);
    assert.ok(view.view.width * view.view.height <= 1_150_000);
    assert.equal(view.view.estimatedImageTokens, Math.floor(view.view.width * view.view.height / 750));
    const huge = expectOk(await tool('view_capture', {handle:first.handle, max_edge:9000}), 'view_capture huge');
    assert.ok(Math.max(huge.view.width, huge.view.height) <= 1568);
    const small = expectOk(await tool('view_capture', {handle:first.handle, max_edge:300}), 'view_capture small');
    assert.ok(Math.max(small.view.width, small.view.height) <= 300);
    report.viewTokens = view.view.estimatedImageTokens;
  });
  await check('view_region is a native-resolution crop, never upscaled', async () => {
    const result = await tool('view_region', {handle:first.handle, x:0.25, y:0.25, w:0.5, h:0.5});
    const view = expectOk(result, 'view_region');
    assert.equal(images(result).length, 1);
    const wholeW = first.width, wholeH = first.height;
    assert.ok(Math.abs(view.view.sourceWidth - wholeW / 2) <= 2 && Math.abs(view.view.sourceHeight - wholeH / 2) <= 2);
    assert.ok(view.view.width <= view.view.sourceWidth);
    if (view.view.sourceWidth * view.view.sourceHeight <= 1_150_000) assert.equal(view.view.scale, 1);
    const tiny = expectOk(await tool('view_region', {handle:first.handle, x:0, y:0, w:0.05, h:0.05}), 'tiny');
    assert.equal(tiny.view.scale, 1); assert.ok(tiny.view.width < view.view.width);
  });
  await check('view_region rejects invalid regions', async () => {
    for (const region of [{x:-0.1,y:0,w:0.5,h:0.5},{x:0.6,y:0,w:0.5,h:0.5},{x:0,y:0,w:0,h:0.5},{x:0,y:0,w:1,h:1.5}]) {
      const result = await tool('view_region', {handle:first.handle, ...region});
      assert.equal(result.isError, true); assert.equal(data(result).error, 'region_invalid');
    }
  });
  await check('one-time link: no Bearer needed, hash matches, photo deleted after the last byte', async () => {
    const photo = expectOk(await tool('take_photo'), 'photo for the one-time link');
    const result = await tool('get_capture_link', {handle:photo.handle});
    const link = expectOk(result, 'link');
    assert.equal(link.scope, 'local-network-only'); assert.equal(link.oneTime, true); assert.equal(link.expiresInSeconds, 60);
    assert.equal(images(result).length, 0);
    const original = await download(link.url);
    assert.equal(original.status, 200);
    assert.equal(original.headers.get('etag'), '"'+photo.sha256+'"');
    const bytes = Buffer.from(await original.arrayBuffer());
    assert.ok(isJpeg(bytes)); assert.equal(bytes.length, photo.bytes); assert.equal(sha256(bytes), photo.sha256);
    assert.equal((await download(link.url)).status, 404, 'one-time link must not work twice');
    const gone = await tool('get_capture_info', {handle:photo.handle});
    assert.equal(gone.isError, true); assert.deepEqual([data(gone).error, data(gone).reason], ['not_found', 'delivered']);
    assert.ok(!data(await tool('list_captures')).captures.some(c => c.handle === photo.handle));
  });
  await check('partial one-time download keeps the photo until the last byte', async () => {
    const photo = expectOk(await tool('take_photo'), 'photo');
    const link = expectOk(await tool('get_capture_link', {handle:photo.handle}), 'link');
    assert.equal((await download(link.url, {Range:'bytes=0-99'})).status, 206);
    expectOk(await tool('get_capture_info', {handle:photo.handle}), 'still stored after a partial range');
    const rest = await download(link.url, {Range:`bytes=100-`});
    assert.equal(rest.status, 206); assert.equal(Buffer.from(await rest.arrayBuffer()).length, photo.bytes - 100);
    assert.equal(data(await tool('get_capture_info', {handle:photo.handle})).reason, 'delivered');
  });
  await check('link supports Range and rejects bad tokens', async () => {
    const link = expectOk(await tool('get_capture_link', {handle:first.handle, one_time:false, ttl_s:60}), 'link');
    const head = await download(link.url, {Range:'bytes=0-99'});
    assert.equal(head.status, 206);
    assert.equal(head.headers.get('content-range'), `bytes 0-99/${first.bytes}`);
    const part = Buffer.from(await head.arrayBuffer()); assert.equal(part.length, 100); assert.ok(isJpeg(part));
    const tail = Buffer.from(await (await download(link.url, {Range:'bytes=-50'})).arrayBuffer()); assert.equal(tail.length, 50);
    assert.equal((await download(link.url, {Range:`bytes=${first.bytes}-`})).status, 416);
    assert.equal((await download(link.url)).status, 200, 'reusable link survives a full download');
    assert.equal((await download(base+'/dl/'+'0'.repeat(32))).status, 404);
    assert.equal((await download(base+'/dl/not-a-token')).status, 404);
    report.reusableLink = link.url.replace(/\/dl\/.*/, '/dl/<redacted>');
  });
  await check('unused photo expires after one minute; a live link keeps its photo', async () => {
    const unused = expectOk(await tool('take_photo'), 'unused');
    const linked = expectOk(await tool('take_photo'), 'linked');
    const link = expectOk(await tool('get_capture_link', {handle:linked.handle, one_time:false, ttl_s:100}), 'link');
    assert.ok(unused.expiresInSeconds <= 60);
    await delay(63000);
    const expired = await tool('get_capture_info', {handle:unused.handle});
    assert.equal(expired.isError, true); assert.equal(data(expired).error, 'expired');
    assert.equal((await download(link.url)).status, 200, 'photo with a live link must still be there');
    await tool('release_capture', {handle:linked.handle});
  });
  await check('link expires', async () => {
    const photo = expectOk(await tool('take_photo'), 'photo for the expiring link');
    const link = expectOk(await tool('get_capture_link', {handle:photo.handle, ttl_s:10, one_time:false}), 'link');
    await delay(10500);
    assert.equal((await download(link.url)).status, 410);
  });
  await check('release_capture deletes the photo and kills its links', async () => {
    const photo = expectOk(await tool('take_photo'), 'photo to release');
    const link = expectOk(await tool('get_capture_link', {handle:photo.handle, one_time:false}), 'link');
    expectOk(await tool('release_capture', {handle:photo.handle}), 'release');
    const gone = await tool('get_capture_info', {handle:photo.handle});
    assert.equal(gone.isError, true); assert.deepEqual([data(gone).error, data(gone).reason], ['not_found', 'released']);
    assert.equal((await download(link.url)).status, 404);
    assert.equal((await tool('release_capture', {handle:photo.handle})).isError, true);
    assert.equal(data(await tool('get_capture_info', {handle:report.bootId+'-ffffffff'})).reason, 'never_existed');
  });
  await check('invalid arguments are tool errors, not protocol errors', async () => {
    assert.equal((await tool('get_capture_info', {handle:'bad handle!'})).isError, true);
    assert.equal((await tool('view_region', {handle:first.handle})).isError, true);
    assert.equal((await tool('focus', {action:'nope'})).isError, true);
    assert.equal((await tool('no_such_tool')).isError, true);
  });
  await check('a full store evicts the oldest photo and never blocks take_photo', async () => {
    const victim = expectOk(await tool('take_photo'), 'victim');
    const handles = [];
    let evictedSeen = [];
    let newest = victim;
    for (let i = 0; i < 40; i++) {
      const result = await tool('take_photo', {request_id:`fill-${Date.now()}-${i}`});
      assert.equal(result.isError, false, 'take_photo must not block: '+result.content[0].text);
      newest = data(result); handles.push(newest.handle);
      if (newest.evicted) evictedSeen.push(...newest.evicted);
      for (const handle of handles) await tool('get_capture_info', {handle}); // keep the newer photos inside their 1 minute life
    }
    const list = data(await tool('list_captures'));
    assert.ok(list.usage.count <= list.usage.maxCount, 'store grew past its limit');
    assert.ok(list.captures.some(c => c.handle === newest.handle), 'the newest photo must be present');
    const gone = data(await tool('get_capture_info', {handle:victim.handle}));
    const reason = gone.error === 'expired' ? 'expired' : gone.reason;
    assert.ok(['evicted', 'expired'].includes(reason), JSON.stringify(gone));
    if (evictedSeen.length) {
      assert.equal(reason, 'evicted');
      assert.ok(evictedSeen.includes(victim.handle), 'the least recently used photo goes first');
    }
    report.fillShots = handles.length + 1; report.evictedCount = evictedSeen.length; report.victimReason = reason;
    for (const handle of handles) await tool('release_capture', {handle});
  });
  await check('torch and focus', async () => {
    const s = data(await tool('status'));
    assert.equal((await tool('torch', {enabled:false})).isError, false);
    if (s.hasFlash) { assert.equal((await tool('torch', {enabled:true})).isError, false); await tool('torch', {enabled:false}) }
    report.focusIsError = (await tool('focus', {action:'focus', x:0.5, y:0.5})).isError;
  });
  await check('app restart: new bootId, old handle is not_found/restarted', async () => {
    execFileSync('adb', ['shell', 'am', 'force-stop', app]);
    execFileSync('adb', ['shell', 'am', 'start', '-n', `${app}/dev.hram.androsaur.camera.MainActivity`]);
    await delay(4000);
    const s = await waitReady();
    assert.notEqual(s.captureStore.bootId, report.bootId);
    assert.equal(s.captureStore.count, 0);
    const old = await tool('get_capture_info', {handle:first.handle});
    assert.equal(old.isError, true); assert.deepEqual([data(old).error, data(old).reason], ['not_found', 'restarted']);
  });
} finally {
  report.passed = report.checks.length;
  await mkdir(new URL('../artifacts/', import.meta.url), {recursive:true});
  await writeFile(new URL('../artifacts/mcp-smoke.json', import.meta.url), JSON.stringify(report, null, 2));
}
console.log(`PASS ${report.checks.length} MCP checks`);
