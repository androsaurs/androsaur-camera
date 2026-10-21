// Device checks; no photograph contents or access token are written to the report.
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdir, writeFile} from 'node:fs/promises';

const base = process.env.CAMERA_BASE || 'http://127.0.0.1:18088';
const xml = execFileSync('adb', ['shell', 'run-as', 'dev.hram.mobileledger.prototype', 'cat', 'shared_prefs/camera.xml'], {encoding:'utf8'});
const token = xml.match(/name="access_token">([^<]+)/)?.[1];
assert.ok(token, 'Launch the application once to generate the prototype access token');
const delay = ms => new Promise(r => setTimeout(r, ms));
const report = {date:new Date().toISOString(), base, checks:[], captures:[]};
async function api(path, body) {
  return fetch(base+path, {method:body?'POST':'GET', headers:{Authorization:'Bearer '+token,...(body?{'Content-Type':'application/json'}:{})}, body:body?JSON.stringify(body):undefined, signal:AbortSignal.timeout(8000)});
}
async function check(name, run) { await run(); report.checks.push(name); console.log('PASS',name) }
async function waitReady() {
  for (let i=0;i<30;i++) {
    const s=await (await api('/status')).json();
    if(s.state==='ready') return s;
    if(s.state==='needs_user') throw Error('Camera needs foreground launch: '+s.error);
    await delay(300);
  }
  throw Error('Camera did not become ready');
}
async function capture(request) {
  const started=performance.now();
  assert.equal((await api('/capture', request)).status,202);
  for(let i=0;i<100;i++) {
    const response=await api('/result?'+new URLSearchParams(request));
    if(response.status===202){await delay(100);continue}
    assert.equal(response.status,200,await response.clone().text().then(s=>s.slice(0,150)));
    const bytes=new Uint8Array(await response.arrayBuffer());
    assert.equal(bytes[0],0xff);assert.equal(bytes[1],0xd8);
    report.captures.push({elapsedMs:Math.round(performance.now()-started),bytes:bytes.length,focus:response.headers.get('x-focus-status'),width:response.headers.get('x-image-width'),height:response.headers.get('x-image-height')});
    return bytes;
  }
  throw Error('Capture timed out');
}
const client='smoke-'+Date.now();
const first={requestId:client+'-1',clientId:client};
const second={requestId:client+'-2',clientId:client};
try {
  await check('unauthenticated commands rejected',async()=>assert.equal((await fetch(base+'/status')).status,401));
  await check('camera ready with capabilities',async()=>{const s=await waitReady();assert.ok(s.resolutions.length);report.resolutions=s.resolutions});
  await check('preview JPEG',async()=>{let r;for(let i=0;i<30;i++){r=await api('/frame.jpg');if(r.status===200)break;await delay(300)}assert.equal(r.status,200);assert.ok((await r.arrayBuffer()).byteLength>1000)});
  await check('capture original and duplicate replay',async()=>{
    const bytes=await capture(first);
    assert.equal((await api('/capture',first)).status,202);
    const replay=await api('/result?'+new URLSearchParams(first));
    assert.deepEqual(new Uint8Array(await replay.arrayBuffer()),bytes);
  });
  await check('result delivered only to requester',async()=>assert.equal((await api('/result?'+new URLSearchParams({...first,clientId:'another-tab'}))).status,410));
  await check('new command replaces unacknowledged result',async()=>{
    await capture(second);
    assert.equal((await api('/result?'+new URLSearchParams(first))).status,410);
    assert.equal((await api('/capture',first)).status,410);
  });
  await check('browser ACK deletes phone copy and prevents recapture',async()=>{
    assert.equal((await api('/ack',second)).status,200);
    assert.equal((await api('/result?'+new URLSearchParams(second))).status,410);
    assert.equal((await api('/capture',second)).status,410);
    const files=execFileSync('adb',['shell','run-as','dev.hram.mobileledger.prototype','ls','files/captures'],{encoding:'utf8'}).trim();
    assert.equal(files,'');
  });
  await check('cancellation discards request',async()=>{
    const request={requestId:client+'-cancel',clientId:client};
    assert.equal((await api('/capture',request)).status,202);
    const busy=await api('/capture',{requestId:client+'-overlap',clientId:'second-tab'});
    assert.equal(busy.status,409);
    assert.equal((await api('/cancel',request)).status,200);
    assert.equal((await api('/result?'+new URLSearchParams(request))).status,410);
    await delay(2000);
  });
  if(process.argv.includes('--idle')) {
    await check('camera released without viewers and reopened from background',async()=>{
      console.log('Waiting 19 seconds without camera subscriptions…');
      await delay(19000);
      assert.equal((await (await api('/status?passive=1')).json()).state,'idle');
      await waitReady();
      const request={requestId:client+'-after-idle',clientId:client};
      await capture(request);
      assert.equal((await api('/ack',request)).status,200);
    });
  }
} finally {
  await mkdir('artifacts',{recursive:true});
  await writeFile('artifacts/device-smoke.json',JSON.stringify(report,null,2)+'\n');
  console.log(JSON.stringify({passed:report.checks.length,captures:report.captures},null,2));
}
