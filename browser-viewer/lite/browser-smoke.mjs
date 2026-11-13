// Test installed Lite extensions against real EA-PDFs; never ships with the viewer.
import {chromium} from 'playwright';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {createServer} from 'node:net';
import {mkdir,readFile,readdir,writeFile} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';
import {checkIndexingNavigation} from './indexing-checks.mjs';
const root=resolve(process.env.EAPDF_LITE_ROOT??fileURLToPath(new URL('../',import.meta.url)));
const corpus=process.env.EAPDF_CORPUS,output=resolve(process.env.EAPDF_LITE_EVIDENCE??join(root,'test-results'));
await mkdir(output,{recursive:true});
const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const until=async fn=>{const end=Date.now()+90000;while(Date.now()<end){if(await fn())return;await wait(100);}throw Error('Viewer readiness timed out');};
const samples=[];
if(corpus)for(const profile of ['1s','1si','1m','1mi','1c','1ci']) {
 const directory=join(corpus,'eml');const name=(await readdir(directory)).find(name=>name.endsWith('_'+profile+'.pdf')&&!name.includes('missing_date'));
 assert.ok(name,`Missing real ${profile} regression`);samples.push({profile,path:join(directory,name)});
}
async function exercise(browser,label) {
 const checks=[];
 await until(()=>browser.evaluate(()=>!!document.querySelector('#file')&&!document.querySelector('#file').disabled));
 if(process.env.EAPDF_LITE_INDEXING_ONLY)return checkIndexingNavigation(browser,process.env.EAPDF_INDEXING_SAMPLE,until);
 const idle=()=>until(()=>browser.evaluate(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false'));
 const open=async path=>{
  await browser.evaluate(()=>document.querySelector('#close').click());await browser.upload(path);
  await until(()=>browser.evaluate(()=>!document.querySelector('#page-surface').hidden&&document.querySelector('#viewport').getAttribute('aria-busy')==='false'||document.querySelector('#status').textContent.startsWith('Unable to display PDF:')));
  assert.doesNotMatch(await browser.evaluate(()=>document.querySelector('#status').textContent),/^Unable/);
  await idle();
 };
 assert.equal(await browser.evaluate(()=>document.title),'EA-PDF Viewer Lite');
 assert.equal(await browser.evaluate(()=>!!document.querySelector('#portfolio-toggle,#portfolio-show,#portfolio-panel')),false);
 assert.equal(await browser.evaluate(()=>!!document.querySelector('#thread-preview-enable')),false);
 await open(join(root,'tests/fixtures/ordinary.pdf'));
 assert.equal(await browser.evaluate(()=>document.querySelector('#page').value),'1');
 await browser.evaluate(()=>document.querySelector('#next').click());await idle();
 assert.equal(await browser.evaluate(()=>document.querySelector('#page').value),'2');
 await browser.evaluate(()=>{document.querySelector('#open-search').click();document.querySelector('#search').value='page 1';document.querySelector('#search-form').requestSubmit();});
 await until(()=>browser.evaluate(()=>document.querySelector('#search-status').textContent.startsWith('Match')));
 assert.equal(await browser.evaluate(()=>document.querySelector('#page').value),'1');checks.push('ordinary rendering, navigation and PDF.js search');
 for(const sample of samples) {
  await open(sample.path);
  assert.match(await browser.evaluate(()=>document.querySelector('#declared-profile').textContent),new RegExp(sample.profile));
  assert.equal(await browser.evaluate(()=>!!document.querySelector('#thread-preview-enable')),false);
  const pixels=await browser.evaluate(()=>{const c=document.querySelector('#canvas'),v=c.getContext('2d').getImageData(0,0,c.width,c.height).data;let dark=0;for(let n=0;n<v.length;n+=4)if(v[n]<220||v[n+1]<220||v[n+2]<220)dark++;return dark;});assert.ok(pixels>100,'Rendered page has marks');
  checks.push(sample.profile+' real EA-PDF opens and renders');
  if(sample.profile==='1s') {
   const layout=await browser.evaluate(()=>['navigation-panel','viewport','details-panel'].map(id=>{const r=document.getElementById(id).getBoundingClientRect();return {x:r.x,width:r.width};}));assert.ok(layout.every(r=>r.width>100));assert.ok(layout[0].x<layout[1].x&&layout[1].x<layout[2].x);
   await browser.screenshot(join(output,label+'-lite-layout.png'));
   await browser.evaluate(()=>document.querySelector('#export-csv').click());
   await browser.evaluate(()=>{document.querySelector('#metadata-format').value='xmp';document.querySelector('#metadata-format').dispatchEvent(new Event('change'));document.querySelector('#metadata-load').click();});
   await until(()=>browser.evaluate(()=>/streams found|could not/.test(document.querySelector('#metadata-state').textContent)));
   assert.match(await browser.evaluate(()=>document.querySelector('#metadata-state').textContent),/[1-9]\d* metadata streams found/);
   assert.match(await browser.evaluate(()=>document.querySelector('#metadata-preview').value),/rdf:RDF/);
   await browser.evaluate(()=>document.querySelector('#metadata-close').click());
   checks.push('three-pane layout and original XMP extraction');
  }
 }
 if(samples.length) {
  await open(samples.find(sample=>sample.profile==='1c').path);
  await until(()=>browser.evaluate(()=>document.querySelector('#subject-index-progress').dataset.complete==='true'));
  assert.ok(await browser.evaluate(()=>document.querySelectorAll('#tree button[data-embedded-file-id]').length)>0);
  await browser.evaluate(()=>document.querySelector('#tree button[data-embedded-file-id]:not(:disabled)').click());await idle();
  assert.equal(await browser.evaluate(()=>document.querySelector('#back').disabled),false);
  await browser.evaluate(()=>document.querySelector('#back').click());await idle();checks.push('collection subject scanning, child navigation and Parent');
 }
 await browser.evaluate(()=>{document.querySelector('#close').click();const input=document.querySelector('#file'),dt=new DataTransfer();dt.items.add(new File([new Uint8Array(32*1024*1024+1)],'oversized.pdf',{type:'application/pdf'}));input.files=dt.files;input.dispatchEvent(new Event('change'));});
 await until(()=>browser.evaluate(()=>document.querySelector('#status').textContent.includes('32 MiB')));
 checks.push('oversized inputs rejected explicitly');
 await browser.evaluate(()=>document.querySelector('#close').click());await open(join(root,'tests/fixtures/ordinary.pdf'));checks.push('close/reopen recovers after limit rejection');
 if(process.env.EAPDF_INDEXING_SAMPLE)checks.push(...await checkIndexingNavigation(browser,process.env.EAPDF_INDEXING_SAMPLE,until));
 return checks;
}
const mode=process.env.EAPDF_LITE_BROWSER??'chromium';
if(mode==='chromium') {
 const context=await chromium.launchPersistentContext('',{executablePath:process.env.CHROMIUM_PATH??chromium.executablePath(),headless:true,viewport:{width:1440,height:1000},args:[`--disable-extensions-except=${join(root,'dist/chromium')}`,`--load-extension=${join(root,'dist/chromium')}`]});
 try {
  const errors=[],requests=[];context.on('request',r=>{if(/^https?:/.test(r.url()))requests.push(r.url());});await context.setOffline(true);
  const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker');const id=new URL(worker.url()).host;
  const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));await page.goto(`chrome-extension://${id}/viewer.html`);
  const checks=await exercise({evaluate:fn=>page.evaluate(fn),upload:path=>page.locator('#file').setInputFiles(path),screenshot:path=>page.screenshot({path,fullPage:true})},mode);
  if(samples.length) {
   await page.locator('#close').click();await page.locator('#file').setInputFiles(samples[0].path);await page.waitForFunction(()=>!document.querySelector('#page-surface').hidden&&document.querySelector('#viewport').getAttribute('aria-busy')==='false');
   const save=page.locator('#files button[data-file-id]').first();assert.ok(await save.count());const download=page.waitForEvent('download');await save.click();const payload=await download;assert.ok((await readFile(await payload.path())).length>0);checks.push('attachment download');
   await page.locator('#export-csv').click();const csv=page.waitForEvent('download');await page.locator('#metadata-save').click();const file=await csv;const text=await readFile(await file.path(),'utf8');assert.match(text,/Subject/);checks.push('CSV metadata download');
  }
  assert.deepEqual(errors,[]);assert.deepEqual(requests,[]);
  await writeFile(join(output,'lite-chromium.json'),JSON.stringify({status:'PASS',browser:context.browser()?.version(),checks,errors,networkRequests:requests},null,2)+'\n');console.log(JSON.stringify({browser:mode,checks}));
 }finally{await context.close();}
}else {
 const probe=createServer();probe.listen(0,'127.0.0.1');await once(probe,'listening');const port=probe.address().port;await new Promise(done=>probe.close(done));
 const driver=spawn(process.env.GECKODRIVER_PATH??'geckodriver',['--allow-system-access','--host','127.0.0.1','--port',String(port),'--log','error'],{stdio:['ignore','ignore','inherit'],env:{...process.env,MOZ_REMOTE_ALLOW_SYSTEM_ACCESS:'1'}});
 let launchError;driver.on('error',error=>{launchError=error;});let session;
 const request=async(path,body,method='POST')=>{if(launchError)throw launchError;const response=await fetch(`http://127.0.0.1:${port}`+path,{method,headers:{'content-type':'application/json'},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(90000)});const value=(await response.json()).value;if(!response.ok)throw Error(JSON.stringify(value));return value;};
 const call=(path,body,method)=>request(`/session/${session}${path}`,body,method);
 try {
  await until(async()=>{try{return !!await request('/status',undefined,'GET');}catch{if(launchError)throw launchError;return false;}});
  const result=await request('/session',{capabilities:{alwaysMatch:{browserName:'firefox','moz:firefoxOptions':{binary:process.env.FIREFOX_PATH??'/usr/lib/firefox/firefox',args:['-headless'],prefs:{'pdfjs.disabled':true}}}}});session=result.sessionId;
  await call('/window/rect',{width:1440,height:1000});const addon=await call('/moz/addon/install',{path:join(root,'dist/firefox'),temporary:true});assert.equal(addon,'eapdf-viewer-lite@thinkbox.local');
  await call('/moz/context',{context:'chrome'});const id=await call('/execute/sync',{script:"Services.io.offline=true;return JSON.parse(Services.prefs.getCharPref('extensions.webextensions.uuids'))[arguments[0]];",args:[addon]});await call('/moz/context',{context:'content'});await call('/url',{url:`moz-extension://${id}/viewer.html`});
  const checks=await exercise({evaluate:fn=>call('/execute/sync',{script:'return ('+fn.toString()+')();',args:[]}),upload:async path=>{const el=await call('/element',{using:'css selector',value:'#file'});await call('/element/'+el['element-6066-11e4-a52e-4f735466cecf']+'/value',{text:resolve(path)});},screenshot:async path=>writeFile(path,Buffer.from(await call('/screenshot',undefined,'GET'),'base64'))},mode);
  await writeFile(join(output,'lite-firefox.json'),JSON.stringify({status:'PASS',browser:result.capabilities.browserVersion,checks},null,2)+'\n');console.log(JSON.stringify({browser:mode,checks}));
 } finally {if(session)await request('/session/'+session,undefined,'DELETE').catch(()=>{});driver.kill();}
}
