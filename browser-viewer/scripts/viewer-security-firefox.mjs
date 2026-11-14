import {spawn,execFileSync} from 'node:child_process';
import {createServer} from 'node:net';
import {once} from 'node:events';
import {resolve,join} from 'node:path';
import {mkdtemp,readFile,readdir,rm,writeFile,mkdir} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import assert from 'node:assert/strict';
import {downloadScopeFixture} from './download-scope-fixture.mjs';
const work=await mkdtemp(join(tmpdir(),'eapdf-security-firefox-')),downloads=join(work,'downloads');await mkdir(downloads);
const probe=createServer();probe.listen(0,'127.0.0.1');await once(probe,'listening');const port=probe.address().port;await new Promise(done=>probe.close(done));
const driver=spawn(process.env.GECKODRIVER_PATH??'geckodriver',['--allow-system-access','--host','127.0.0.1','--port',String(port),'--log','error'],{stdio:['ignore','ignore','inherit'],env:{...process.env,MOZ_REMOTE_ALLOW_SYSTEM_ACCESS:'1'}});
let session;const reports=[];const delay=ms=>new Promise(r=>setTimeout(r,ms));
async function request(path,body,method='POST'){
 const r=await fetch(`http://127.0.0.1:${port}`+path,{method,headers:{'content-type':'application/json'},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(45000)}),v=(await r.json()).value;if(!r.ok)throw Error(JSON.stringify(v));return v;
}
const call=(path,body,method)=>request(`/session/${session}${path}`,body,method);
const execute=(s,args=[])=>call('/execute/sync',{script:s,args});
const asyncExecute=(s,args=[])=>call('/execute/async',{script:`const done=arguments[arguments.length-1];(async()=>{${s}})().then(done,e=>done({error:String(e)}));`,args}).then(v=>{if(v?.error)throw Error(v.error);return v;});
async function until(fn){const end=Date.now()+30000;while(Date.now()<end){const v=await fn();if(v)return v;await delay(80);}throw Error('Firefox condition timed out');}
async function input(selector,path){const el=await call('/element',{using:'css selector',value:selector});await call(`/element/${el['element-6066-11e4-a52e-4f735466cecf']}/value`,{text:resolve(path)});}
const ready=()=>until(()=>execute("return !document.querySelector('#file').disabled&&document.querySelector('#viewport').getAttribute('aria-busy')==='false';"));
const click=id=>execute('document.getElementById(arguments[0]).click();',[id]);
async function keys(key){await call('/actions',{actions:[{type:'key',id:'keys',actions:[{type:'keyDown',value:'\uE009'},{type:'keyDown',value:key},{type:'keyUp',value:key},{type:'keyUp',value:'\uE009'}]}]});}
async function inspect(path){await input('#security-reference',path);return asyncExecute(`
 const {getDocument,GlobalWorkerOptions}=await import('./vendor/pdfjs/pdf.mjs');GlobalWorkerOptions.workerSrc=new URL('./vendor/pdfjs/pdf.worker.mjs',location.href).href;
 const task=getDocument({data:new Uint8Array(await document.querySelector('#security-reference').files[0].arrayBuffer()),isEvalSupported:false});
 try{const pdf=await task.promise,text=[];for(let n=1;n<=pdf.numPages;n++)text.push((await (await pdf.getPage(n)).getTextContent()).items.filter(i=>'str'in i).map(i=>i.str).join(''));const first=await pdf.getPage(1),view=first.getViewport({scale:1}),canvas=document.createElement('canvas');canvas.width=Math.ceil(view.width);canvas.height=Math.ceil(view.height);await first.render({canvas,viewport:view,annotationMode:0}).promise;const pixels=[...new Uint8Array(await crypto.subtle.digest('SHA-256',canvas.getContext('2d').getImageData(0,0,canvas.width,canvas.height).data))].join(',');return {pixels,pages:pdf.numPages,text,parts:await pdf.getDocumentParts(),assets:await pdf.getDocumentAssets(),info:(await pdf.getMetadata()).info};}finally{await task.destroy();}
`);}
try{
 await until(async()=>{try{return await request('/status',undefined,'GET');}catch{return false;}});
 const result=await request('/session',{capabilities:{alwaysMatch:{browserName:'firefox','moz:firefoxOptions':{binary:process.env.FIREFOX_PATH??'/usr/lib/firefox/firefox',args:['-headless'],prefs:{'browser.download.folderList':2,'browser.download.dir':downloads,'browser.download.useDownloadDir':true,'browser.download.alwaysOpenPanel':false,'browser.helperApps.neverAsk.saveToDisk':'application/pdf,application/octet-stream','pdfjs.disabled':true}}}}});session=result.sessionId;
 const upstream=resolve('tests/fixtures/issue16880.pdf'),mixed=join(work,'mixed.pdf');execFileSync('python3',[resolve('scripts/make-rtl-mixed-fixture.py'),upstream,mixed]);
 for(const [edition,extension]of [['full',resolve('dist/firefox')],['lite',resolve(process.env.LITE_FIREFOX_EXTENSION??'dist/lite/firefox')]]){
  const addon=await call('/moz/addon/install',{path:extension,temporary:true});await call('/moz/context',{context:'chrome'});
  const uuid=await execute("Services.io.offline=true;return JSON.parse(Services.prefs.getCharPref('extensions.webextensions.uuids'))[arguments[0]];",[addon]);await call('/moz/context',{context:'content'});await call('/url',{url:`moz-extension://${uuid}/viewer.html`});await until(()=>execute("return !!document.querySelector('#file')&&!document.querySelector('#file').disabled;"));
  await execute("const e=document.createElement('input');e.type='file';e.id='security-reference';e.hidden=true;document.body.append(e);");
  for(const profile of ['m','mi','s','si']){
   for(const n of await readdir(downloads))await rm(join(downloads,n),{force:true});
   const standalone=['s','si'].includes(profile),path=join(work,`${edition}_${profile}.pdf`),bytes=downloadScopeFixture(profile,{partial:!standalone,standalone});await writeFile(path,bytes);await input('#file',path);await ready();assert.equal(await execute("return document.querySelector('#download-message').disabled;"),false);
   const original=standalone?null:await inspect(path);await click('download-message');const saved=await until(async()=>{const names=(await readdir(downloads)).filter(n=>n.endsWith('.pdf'));return names.length===1?join(downloads,names[0]):false;});await ready();
   if(standalone)assert.deepEqual(await readFile(saved),bytes);else{const actual=await inspect(saved);assert.equal(actual.pixels,original.pixels);assert.equal(actual.assets.files.length,0);assert.equal(actual.assets.catalogMetadata,null);assert.equal(actual.parts.status,'missing');assert.equal(actual.info.Title,undefined);assert.equal(actual.pages,1);assert.deepEqual(actual.text,['SELECTED BODY']);}
   reports.push({edition,profile,bodyOnly:!standalone,originalBytes:standalone});
  }
  for(const path of [upstream,mixed]){
   await input('#file',path);await ready();const extracted=await inspect(path);assert.ok(extracted.text.some(t=>t.includes('سلام کلاس لاله')));if(path===mixed)assert.ok(extracted.text.some(t=>t.includes('Mixed Latin 123')));
   await click('open-search');await execute("const s=document.querySelector('#search');s.value='سلام';s.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('#search-scope').value='document';");await click('search-submit');await ready();await until(()=>execute("return document.querySelector('.textLayer mark')?.textContent==='سلام';"));
   const selected=await execute("document.activeElement.blur();const range=document.createRange();range.selectNodeContents(document.querySelector('.textLayer'));const s=getSelection();s.removeAllRanges();s.addRange(range);return s.toString();");assert.match(selected,/سلام/);await keys('c');
   await execute("const e=document.createElement('textarea');e.id='clipboard-probe';document.body.append(e);e.focus();");await keys('v');const copied=await execute("const e=document.querySelector('#clipboard-probe'),s=e.value;e.remove();return s;");assert.match(copied,/سلام/);assert.doesNotMatch(copied,/سالم/);if(path===mixed)assert.match(copied,/Mixed Latin 123/);
   reports.push({edition,mixed:path===mixed,extraction:true,search:true,highlight:true,copyPaste:true});
  }
 }
 await mkdir('test-results',{recursive:true});await writeFile('test-results/viewer-security-firefox.json',JSON.stringify({version:result.capabilities.browserVersion,reports},null,2)+'\n');console.log(JSON.stringify(reports));
}finally{if(session)await request(`/session/${session}`,undefined,'DELETE').catch(()=>{});driver.kill();await rm(work,{recursive:true,force:true});}
