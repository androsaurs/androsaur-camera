// Load and speed benchmark of the phone camera: preview frames, full-resolution captures, MCP tools.
// Usage: ANDROID_SERIAL=<device> LABEL=usb|wifi CAMERA_BASE=http://host:port [TORCH=on] node scripts/perf-bench.mjs [sections]
// TORCH=on lights the scene with the phone torch for the whole run (the report records the lighting); off by default.
// sections: preview,capture,sustained,contention,mcp (default: all). No photo contents or tokens are written.
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {mkdir, writeFile} from 'node:fs/promises';

const base = process.env.CAMERA_BASE || 'http://127.0.0.1:18088';
const label = process.env.LABEL || 'usb';
const torchOn = process.env.TORCH === 'on';
const sections = new Set((process.argv[2] || 'preview,capture,sustained,contention,mcp').split(','));
const app = 'dev.hram.mobileledger.prototype';
const adb = (...args) => execFileSync('adb', args, {encoding:'utf8', maxBuffer:256 * 1024 * 1024});
const token = adb('shell', 'run-as', app, 'cat', 'shared_prefs/camera.xml').match(/name="access_token">([^<]+)/)?.[1];
assert.ok(token, 'Launch the application once to generate the prototype access token');
const auth = {Authorization:'Bearer '+token};
const delay = ms => new Promise(r => setTimeout(r, ms));
const now = () => performance.now();
const md5 = bytes => createHash('md5').update(bytes).digest('hex');
const round = (v, d = 1) => v === null || v === undefined ? v : Math.round(v * 10 ** d) / 10 ** d;

function stats(values) {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const at = p => sorted[Math.min(sorted.length - 1, Math.floor(p * sorted.length))];
  return {n:sorted.length, min:round(sorted[0]), p50:round(at(0.5)), p90:round(at(0.9)), p95:round(at(0.95)), max:round(sorted.at(-1)),
    mean:round(sorted.reduce((a, b) => a + b, 0) / sorted.length)};
}

async function api(path, body) {
  const t0 = now();
  const response = await fetch(base+path, {method:body ? 'POST' : 'GET', headers:{...auth, ...(body ? {'Content-Type':'application/json'} : {})},
    body:body ? JSON.stringify(body) : undefined, signal:AbortSignal.timeout(30000)});
  const ttfb = now() - t0;
  const bytes = Buffer.from(await response.arrayBuffer());
  return {status:response.status, headers:response.headers, bytes, ttfb, total:now() - t0};
}
const json = r => JSON.parse(r.bytes.toString('utf8'));
let rpcId = 1;
async function rpc(name, args = {}) {
  const t0 = now();
  const response = await fetch(base+'/mcp', {method:'POST', headers:{...auth, 'Content-Type':'application/json'}, signal:AbortSignal.timeout(60000),
    body:JSON.stringify({jsonrpc:'2.0', id:rpcId++, method:'tools/call', params:{name, arguments:args}})});
  const reply = await response.json();
  return {result:reply.result, ms:now() - t0};
}
const mcpData = r => JSON.parse(r.result.content[0].text);

async function waitReady() {
  for (let i = 0; i < 60; i++) {
    const r = await api('/status');
    if (r.status === 200 && json(r).state === 'ready') return json(r);
    await delay(300);
  }
  throw Error('camera not ready');
}

function device() {
  // A value the phone could not report (adb dropped, command failed) is null, never 0.
  const grab = (cmd, re) => { try { return adb('shell', ...cmd).match(re)?.[1] ?? null } catch { return null } };
  const num = value => value === null ? null : Number(value);
  const temp = num(grab(['dumpsys', 'battery'], /temperature: (\d+)/));
  const pss = num(grab(['dumpsys', 'meminfo', app], /TOTAL PSS:\s+(\d+)/) ?? grab(['dumpsys', 'meminfo', app], /TOTAL\s+(\d+)/));
  return {
    battery:num(grab(['dumpsys', 'battery'], /level: (\d+)/)),
    batteryTempC:temp === null ? null : temp / 10,
    pssMb:pss === null ? null : round(pss / 1024),
    pid:grab(['pidof', app], /(\d+)/),
    wakefulness:grab(['dumpsys', 'power'], /mWakefulness=(\w+)/),
    wifiRssiDbm:num(grab(['dumpsys', 'wifi'], /RSSI: (-?\d+)/)),
    wifiLinkMbps:num(grab(['dumpsys', 'wifi'], /Link speed: (\d+)/)),
  };
}

const report = {date:new Date().toISOString(), label, sections:[...sections], method:{
  host:{node:process.version, platform:process.platform, clock:'performance.now() on the host, so every time includes the transport (USB forward or Wi-Fi) and the host HTTP stack'},
  percentiles:'sorted ascending, p = element at index floor(p * n), clamped to n-1 (no interpolation); mean is arithmetic',
  uniquePreviewFrame:'a /frame.jpg response whose X-Frame-Id differs from the previous response (MD5 of the body when the header is absent); uniqueFps = unique frames / elapsed seconds; requestsPerSec counts every 200 response',
  frameAge:'X-Frame-Age-Ms: how old the frame was inside the phone when it answered (excludes the transfer time); framesProducedPerSec = (last id - first id + 1) / elapsed',
  previewLoop:'sequential GET /frame.jpg, next request starts when the previous body is complete; 202 (warming up) answers are retried after 30 ms and not counted',
  previewSections:{warmupSeconds:3, singleViewerSeconds:20, concurrentViewers:[3, 6], concurrentSeconds:15, mcpPreviewCalls:15},
  captureCycle:'POST /capture, then GET /result every 20 ms until not 202, then POST /ack. acceptMs = POST answered; readyMs = from POST start to the headers of the 200 /result; downloadMs = headers to body complete; totalMs = POST start to body complete (ACK excluded); captureMs = X-Capture-Ms header measured on the phone (focus step + exposure + save)',
  readyGranularityMs:20,
  captureByResolution:{shotsPerResolution:10, firstAfterSwitch:'one extra shot right after changing resolution, reported separately', sizes:'advertised 4:3 sizes nearest to widths 4080, 3264, 2592, 1920, 1280', shotsPerMinute:'shots / wall time of the 10 back-to-back cycles'},
  previewWhileShooting:{shots:10, pauseBetweenShotsMs:200, leadInMs:1500, insideShots:'unique preview frames whose arrival lies between POST /capture and the end of the cycle, divided by the summed duration of those windows'},
  lighting:'recorded in report.lighting: torch state and the metrics of one sample shot taken after the torch has had 2.5 s to settle. Frame rate depends on light: in a dim scene auto-exposure uses long exposures and the camera cannot deliver more than about 1000 / exposure ms frames per second',
  statusProbes:40,
  sustained:{shots:60, blocks:'p50 of totalMs over shots 1-20, 21-40, 41-60 to expose slow-down by heat'},
  contention:{rounds:10, how:'two clients POST /capture at the same moment; the loser retries every 50 ms after the winner ACKs; secondClientWait is that retry time'},
  mcp:{takePhoto:15, takePhotoWithPreview:5, viewCapture:5, viewRegion:5, regionFractions:'x=0.3 y=0.3 w=0.3 h=0.3', getCaptureInfo:10, linkDownloads:5, 'ms':'whole JSON-RPC round trip including phone-side metrics, storage and base64'},
}};
const log = (...a) => console.log(...a);

// ---------- preview ----------
async function previewLoop(seconds, timestamps) {
  const start = now(); const latencies = []; const gaps = []; const sizes = []; const ages = [];
  let last = null; let lastAt = null; let unique = 0; let requests = 0; let firstId = null; let lastId = null;
  while (now() - start < seconds * 1000) {
    const r = await api('/frame.jpg');
    if (r.status !== 200) { await delay(30); continue; }
    const t = now(); requests++; latencies.push(r.total); sizes.push(r.bytes.length);
    const frameId = r.headers.get('x-frame-id');
    const h = frameId ?? md5(r.bytes);
    if (frameId !== null) { firstId ??= Number(frameId); lastId = Number(frameId); ages.push(Number(r.headers.get('x-frame-age-ms'))); }
    if (h !== last) { unique++; last = h; timestamps?.push(t); if (lastAt !== null) gaps.push(t - lastAt); lastAt = t; }
  }
  const elapsed = (now() - start) / 1000;
  return {seconds:round(elapsed), requests, requestsPerSec:round(requests / elapsed), uniqueFrames:unique, uniqueFps:round(unique / elapsed, 2),
    duplicateShare:round(1 - unique / Math.max(1, requests), 2), requestMs:stats(latencies), uniqueFrameGapMs:stats(gaps), frameBytes:stats(sizes),
    // Frames the phone encoded in the window (from X-Frame-Id), whether or not this client fetched each of them.
    framesProducedPerSec:firstId === null ? null : round((lastId - firstId + 1) / elapsed, 2), frameAgeMsAtResponse:stats(ages)};
}

async function sectionPreview() {
  log('== preview');
  await waitReady();
  await previewLoop(3);
  const single = await previewLoop(20);
  log('single viewer', single.uniqueFps, 'unique fps, request p50', single.requestMs.p50, 'ms');
  const viewers = [];
  for (const count of [3, 6]) {
    const runs = await Promise.all(Array.from({length:count}, () => previewLoop(15)));
    viewers.push({viewers:count, perViewerUniqueFps:runs.map(r => r.uniqueFps), requestMs:stats(runs.flatMap(r => [r.requestMs.p50, r.requestMs.p95]))});
    log(count, 'viewers: unique fps', runs.map(r => r.uniqueFps).join(' '));
  }
  const mcp = [];
  for (let i = 0; i < 15; i++) { const r = await rpc('preview'); if (!r.result.isError) mcp.push(r.ms); await delay(50); }
  report.preview = {singleViewer:single, concurrentViewers:viewers, mcpPreviewToolMs:stats(mcp)};
}

// ---------- full captures ----------
let seq = 0;
async function captureCycle(size, client = 'perf') {
  const request = {requestId:`perf-${Date.now()}-${seq++}`, clientId:client};
  const body = {...request, ...(size ? {width:size.width, height:size.height} : {})};
  const t0 = now();
  const posted = await api('/capture', body);
  if (posted.status !== 202) return {error:posted.status, detail:json(posted).error};
  const acceptMs = now() - t0;
  let polls = 0; let r;
  for (;;) {
    r = await api('/result?'+new URLSearchParams(request)); polls++;
    if (r.status !== 202) break;
    await delay(20);
  }
  if (r.status !== 200) return {error:r.status, detail:json(r).error};
  const ttfbMs = r.ttfb + (now() - t0 - r.total);
  const totalMs = now() - t0;
  await api('/ack', request);
  return {acceptMs, readyMs:ttfbMs, downloadMs:r.total - r.ttfb, totalMs, captureMs:Number(r.headers.get('x-capture-ms')), focus:r.headers.get('x-focus-status'),
    bytes:r.bytes.length, width:Number(r.headers.get('x-image-width')), height:Number(r.headers.get('x-image-height')), polls};
}
function summarize(cycles) {
  const ok = cycles.filter(c => !c.error);
  return {shots:cycles.length, failed:cycles.length - ok.length, totalMs:stats(ok.map(c => c.totalMs)), readyMs:stats(ok.map(c => c.readyMs)),
    downloadMs:stats(ok.map(c => c.downloadMs)), captureMs:stats(ok.map(c => c.captureMs)), mbPerShot:round(ok.reduce((a, c) => a + c.bytes, 0) / Math.max(1, ok.length) / 1e6, 2),
    downloadMBps:round(ok.reduce((a, c) => a + c.bytes, 0) / 1e6 / (ok.reduce((a, c) => a + c.downloadMs, 0) / 1000 || 1), 2),
    dims:[...new Set(ok.map(c => `${c.width}x${c.height}`))], focus:Object.fromEntries([...new Set(ok.map(c => c.focus))].map(f => [f, ok.filter(c => c.focus === f).length]))};
}

async function sectionCapture() {
  log('== capture by resolution');
  const status = await waitReady();
  const sizes = status.resolutions.filter(s => s.width * 3 === s.height * 4);
  const wanted = [4080, 3264, 2592, 1920, 1280].map(w => sizes.reduce((best, s) => Math.abs(s.width - w) < Math.abs(best.width - w) ? s : best, sizes[0]));
  const unique = [...new Map(wanted.map(s => [`${s.width}x${s.height}`, s])).values()];
  const perResolution = [];
  for (const size of unique) {
    const first = await captureCycle(size);
    const runs = []; const t0 = now();
    for (let i = 0; i < 10; i++) runs.push(await captureCycle(size));
    const elapsed = (now() - t0) / 1000;
    const summary = summarize(runs);
    perResolution.push({requested:`${size.width}x${size.height}`, firstAfterSwitchMs:round(first.totalMs), ...summary, shotsPerMinute:round(runs.length / elapsed * 60)});
    log(`${size.width}x${size.height}: total p50 ${summary.totalMs?.p50} ms, p95 ${summary.totalMs?.p95} ms, capture p50 ${summary.captureMs?.p50}, ${summary.mbPerShot} MB, ${round(runs.length / elapsed * 60)} shots/min`);
  }
  await captureCycle(unique[0]); // back to the largest resolution, the default of the app
  // Preview while shooting
  const stamps = []; let stop = false;
  const viewer = (async () => { const start = now(); while (!stop) { const r = await api('/frame.jpg'); if (r.status === 200) stamps.push({t:now(), h:r.headers.get('x-frame-id') ?? md5(r.bytes)}) } return start })();
  await delay(1500);
  const windows = []; const during = [];
  for (let i = 0; i < 10; i++) { const w0 = now(); const c = await captureCycle(unique[0]); windows.push([w0, now()]); during.push(c); await delay(200); }
  stop = true; const viewerStart = await viewer;
  const uniqueStamps = stamps.filter((s, i) => i === 0 || s.h !== stamps[i - 1].h).map(s => s.t);
  const inWindow = uniqueStamps.filter(t => windows.some(([a, b]) => t >= a && t <= b)).length;
  const windowSeconds = windows.reduce((a, [x, y]) => a + (y - x), 0) / 1000;
  const totalSeconds = (uniqueStamps.at(-1) - viewerStart) / 1000;
  const gaps = uniqueStamps.slice(1).map((t, i) => t - uniqueStamps[i]);
  report.captureByResolution = perResolution;
  report.previewWhileShooting = {uniqueFpsInsideShots:round(inWindow / windowSeconds, 2), uniqueFpsOutsideShots:round((uniqueStamps.length - inWindow) / (totalSeconds - windowSeconds), 2),
    uniqueFrameGapMs:stats(gaps), shots:summarize(during)};
  log('preview during shots: fps inside', report.previewWhileShooting.uniqueFpsInsideShots, 'outside', report.previewWhileShooting.uniqueFpsOutsideShots, 'max gap', report.previewWhileShooting.uniqueFrameGapMs.max, 'ms');
  const statusTimes = []; for (let i = 0; i < 40; i++) statusTimes.push((await api('/status?passive=1')).total);
  report.statusRequestMs = stats(statusTimes);
}

async function sectionSustained() {
  log('== sustained 60 full-resolution shots');
  const status = await waitReady();
  const size = status.resolutions.filter(s => s.width * 3 === s.height * 4)[0];
  const before = device(); const cycles = []; const t0 = now();
  for (let i = 0; i < 60; i++) cycles.push(await captureCycle(size));
  const elapsed = (now() - t0) / 1000;
  const blocks = [0, 1, 2].map(b => ({shots:`${b * 20 + 1}-${b * 20 + 20}`, totalMsP50:stats(cycles.slice(b * 20, b * 20 + 20).filter(c => !c.error).map(c => c.totalMs))?.p50}));
  report.sustained = {resolution:`${size.width}x${size.height}`, ...summarize(cycles), shotsPerMinute:round(cycles.length / elapsed * 60), elapsedSeconds:round(elapsed), blocksP50:blocks, deviceBefore:before, deviceAfter:device()};
  log('sustained', report.sustained.totalMs.p50, 'ms p50,', report.sustained.shotsPerMinute, 'shots/min, temp', before.batteryTempC, '->', report.sustained.deviceAfter.batteryTempC);
}

async function sectionContention() {
  log('== two clients shooting at once');
  const status = await waitReady();
  const size = status.resolutions.filter(s => s.width * 3 === s.height * 4)[0];
  const outcomes = []; const waits = [];
  for (let round_ = 0; round_ < 10; round_++) {
    const make = client => ({requestId:`perf-${Date.now()}-${seq++}-${client}`, clientId:client, width:size.width, height:size.height});
    const a = make('tab-a'); const b = make('tab-b'); const t0 = now();
    const [pa, pb] = await Promise.all([api('/capture', a), api('/capture', b)]);
    const winner = pa.status === 202 ? [a, 'a'] : [b, 'b'];
    const loser = pa.status === 202 ? [b, 'b'] : [a, 'a'];
    const loserStatus = pa.status === 202 ? pb.status : pa.status;
    const loserError = loserStatus === 409 ? json(pa.status === 202 ? pb : pa).error : null;
    outcomes.push({accepted:[pa.status, pb.status].filter(s => s === 202).length, loserStatus, loserError});
    for (;;) { const r = await api('/result?'+new URLSearchParams({requestId:winner[0].requestId, clientId:winner[0].clientId})); if (r.status !== 202) { await api('/ack', winner[0]); break; } await delay(20); }
    let retries = 0; const w0 = now();
    for (;;) { const r = await api('/capture', loser[0]); if (r.status === 202) break; retries++; await delay(50); }
    waits.push(now() - w0);
    for (;;) { const r = await api('/result?'+new URLSearchParams({requestId:loser[0].requestId, clientId:loser[0].clientId})); if (r.status !== 202) { await api('/ack', loser[0]); break; } await delay(20); }
    void t0;
  }
  report.contention = {rounds:outcomes.length, exactlyOneAccepted:outcomes.every(o => o.accepted === 1), loserErrors:[...new Set(outcomes.map(o => `${o.loserStatus}:${o.loserError}`))],
    secondClientWaitAfterWinnerAckMs:stats(waits)};
  log('contention: one winner each round =', report.contention.exactlyOneAccepted, report.contention.loserErrors.join(','));
}

// ---------- MCP ----------
async function sectionMcp() {
  log('== MCP tools');
  await waitReady();
  const handles = [];
  const run = async (n, fn) => { const out = []; for (let i = 0; i < n; i++) out.push(await fn(i)); return out; };
  const shots = await run(15, async () => { const r = await rpc('take_photo'); const d = mcpData(r); if (!r.result.isError) handles.push(d.handle); return {ms:r.ms, captureMs:d.captureMs, bytes:d.bytes, error:r.result.isError}; });
  const withPreview = await run(5, async () => { const r = await rpc('take_photo', {include_preview:true}); const d = mcpData(r); if (!r.result.isError) handles.push(d.handle); return {ms:r.ms, responseKb:round(JSON.stringify(r.result).length / 1024)}; });
  const target = handles.at(-1);
  const views = await run(5, async () => { const r = await rpc('view_capture', {handle:target}); return {ms:r.ms, kb:round(JSON.stringify(r.result).length / 1024)}; });
  const regions = await run(5, async () => { const r = await rpc('view_region', {handle:target, x:0.3, y:0.3, w:0.3, h:0.3}); return {ms:r.ms, kb:round(JSON.stringify(r.result).length / 1024)}; });
  const info = await run(10, async () => (await rpc('get_capture_info', {handle:target})).ms);
  const links = await run(5, async () => {
    const photo = mcpData(await rpc('take_photo'));
    const l = mcpData(await rpc('get_capture_link', {handle:photo.handle}));
    const t0 = now(); const response = await fetch(l.url); const bytes = Buffer.from(await response.arrayBuffer());
    return {downloadMs:now() - t0, mb:round(bytes.length / 1e6, 2), mbps:round(bytes.length / 1e6 / ((now() - t0) / 1000), 2), status:response.status};
  });
  for (const handle of handles) await rpc('release_capture', {handle});
  report.mcp = {takePhotoMs:stats(shots.filter(s => !s.error).map(s => s.ms)), takePhotoCaptureMs:stats(shots.map(s => s.captureMs)), takePhotoFailed:shots.filter(s => s.error).length,
    takePhotoWithPreviewMs:stats(withPreview.map(s => s.ms)), previewResponseKb:stats(withPreview.map(s => s.responseKb)),
    viewCaptureMs:stats(views.map(v => v.ms)), viewCaptureKb:stats(views.map(v => v.kb)), viewRegionMs:stats(regions.map(v => v.ms)), viewRegionKb:stats(regions.map(v => v.kb)),
    getCaptureInfoMs:stats(info), linkDownloadMs:stats(links.map(l => l.downloadMs)), linkDownloadMBps:stats(links.map(l => l.mbps)), linkDownloadMb:links[0]?.mb};
  log('mcp take_photo p50', report.mcp.takePhotoMs.p50, 'ms; link download p50', report.mcp.linkDownloadMs.p50, 'ms');
}

try {
  adb('logcat', '-c');
  const status = await waitReady();
  report.environment = {model:adb('shell', 'getprop', 'ro.product.model').trim(), android:adb('shell', 'getprop', 'ro.build.version.release').trim(),
    appSha256:execFileSync('sha256sum', [new URL('../androidApp/build/outputs/apk/debug/androidApp-debug.apk', import.meta.url).pathname], {encoding:'utf8'}).split(' ')[0],
    gitHead:execFileSync('git', ['rev-parse', '--short', 'HEAD'], {encoding:'utf8', cwd:new URL('..', import.meta.url).pathname}).trim(),
    gitDirty:execFileSync('git', ['status', '--porcelain'], {encoding:'utf8', cwd:new URL('..', import.meta.url).pathname}).trim().length > 0,
    resolutionsAdvertised:status.resolutions.length, largest:status.resolutions[0], deviceBefore:device()};
  if (torchOn) { await api('/torch', {enabled:true}); await delay(2500); }
  {
    const sample = await rpc('take_photo'); const d = mcpData(sample);
    report.lighting = {torch:torchOn ? 'on' : 'off', sample:sample.result.isError ? null : {meanBrightness:d.metrics.meanBrightness, contrast:d.metrics.contrast, sharpness:d.metrics.sharpness, captureMs:d.captureMs, bytes:d.bytes}};
    if (!sample.result.isError) await rpc('release_capture', {handle:d.handle});
  }
  if (sections.has('preview')) await sectionPreview();
  if (sections.has('capture')) await sectionCapture();
  if (sections.has('contention')) await sectionContention();
  if (sections.has('mcp')) await sectionMcp();
  if (sections.has('sustained')) await sectionSustained();
  report.environment.deviceAfter = device();
  // The vendor camera HAL floods the main buffer with E lines, so only the crash buffer and AndroidRuntime are read.
  const crashes = adb('logcat', '-d', '-b', 'crash') + adb('logcat', '-d', '-b', 'main', '-s', 'AndroidRuntime:E');
  report.crashLog = crashes.split('\n').filter(l => /mobileledger|FATAL|OutOfMemory/.test(l)).slice(0, 10);
  const pidBefore = report.environment.deviceBefore.pid; const pidAfter = report.environment.deviceAfter.pid;
  report.processSameAsAtStart = pidBefore && pidAfter ? pidBefore === pidAfter : null; // null: adb could not tell
} finally {
  if (torchOn) { try { await api('/torch', {enabled:false}) } catch {} }
  await mkdir(new URL('../artifacts/', import.meta.url), {recursive:true});
  await writeFile(new URL(`../artifacts/perf-${label}.json`, import.meta.url), JSON.stringify(report, null, 2));
}
console.log('report: artifacts/perf-'+label+'.json');
