// Install the unsigned Firefox package and exercise its actual extension origin offline.
import {spawn} from 'node:child_process';
import {createServer} from 'node:net';
import {once} from 'node:events';
import {mkdir,writeFile,mkdtemp,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';
import {makeAssetFixture} from './asset-fixture.mjs';
const root=resolve(process.env.EAPDF_MINIMAL_ROOT??fileURLToPath(new URL('../',import.meta.url)));
const evidence=resolve(process.env.EAPDF_MINIMAL_EVIDENCE??join(root,'test-results'));
const temporary=await mkdtemp(join(tmpdir(),'eapdf-minimal-firefox-')),downloads=join(temporary,'downloads');await mkdir(downloads);
const fixture=await makeAssetFixture(root,temporary);
const probe=createServer();probe.listen(0,'127.0.0.1');await once(probe,'listening');const port=probe.address().port;await new Promise(done=>probe.close(done));
const driver=spawn(process.env.GECKODRIVER_PATH??'geckodriver',['--allow-system-access','--host','127.0.0.1','--port',String(port),'--log','error'],{stdio:['ignore','ignore','inherit'],env:{...process.env,MOZ_REMOTE_ALLOW_SYSTEM_ACCESS:'1'}});
let launchError,session;driver.on('error',error=>{launchError=error;});
const until=async fn=>{const end=Date.now()+60000;while(Date.now()<end){if(await fn())return;await new Promise(done=>setTimeout(done,100));}throw Error('Firefox readiness timed out');};
const request=async(path,body,method='POST')=>{
 if(launchError)throw launchError;
 const response=await fetch(`http://127.0.0.1:${port}`+path,{method,headers:{'content-type':'application/json'},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(60000)});
 const value=(await response.json()).value;if(!response.ok)throw Error(JSON.stringify(value));return value;
};
const call=(path,body,method)=>request(`/session/${session}${path}`,body,method);
const evaluate=script=>call('/execute/sync',{script,args:[]});
const idle=()=>until(()=>evaluate("return document.querySelector('#viewport').getAttribute('aria-busy')==='false'"));
async function open(path){
 const el=await call('/element',{using:'css selector',value:'#file'});
 await call('/element/'+el['element-6066-11e4-a52e-4f735466cecf']+'/value',{text:resolve(path)});
 await until(()=>evaluate("return !document.querySelector('#page-surface').hidden&&document.querySelector('#viewport').getAttribute('aria-busy')==='false'"));
 assert.ok(await evaluate("return document.querySelector('#canvas').width>0"));
}
try{
 await until(async()=>{try{return !!await request('/status',undefined,'GET');}catch{if(launchError)throw launchError;return false;}});
 const result=await request('/session',{capabilities:{alwaysMatch:{browserName:'firefox','moz:firefoxOptions':{binary:process.env.FIREFOX_PATH??'/usr/lib/firefox/firefox',args:['-headless'],prefs:{'pdfjs.disabled':true,'browser.download.folderList':2,'browser.download.dir':downloads,'browser.download.useDownloadDir':true,'browser.helperApps.neverAsk.saveToDisk':'application/octet-stream,application/pdf,text/plain'}}}}});session=result.sessionId;
 await call('/window/rect',{width:1280,height:800});
 const addon=await call('/moz/addon/install',{path:join(root,'dist/firefox'),temporary:true});assert.equal(addon,'eapdf-viewer-even-more-lite@thinkbox.local');
 await call('/moz/context',{context:'chrome'});
 const id=await evaluate(`Services.io.offline=true;return JSON.parse(Services.prefs.getCharPref('extensions.webextensions.uuids'))[${JSON.stringify(addon)}];`);
 await call('/moz/context',{context:'content'});await call('/url',{url:`moz-extension://${id}/viewer.html`});
 await until(()=>evaluate("return document.querySelector('#file')&&!document.querySelector('#file').disabled"));
 assert.equal(await evaluate('return document.title'),'EA-PDF Even More Lite');
 await open(join(root,'tests/fixtures/ordinary.pdf'));
 await evaluate("document.querySelector('#next').click()");await idle();assert.equal(await evaluate("return document.querySelector('#page').value"),'2');
 assert.ok(await evaluate("return document.querySelectorAll('.textLayer span').length>0"));
 const checks=['offline installed Firefox extension rendering, text selection and page navigation'];
 if(process.env.EAPDF_CORPUS)for(const profile of ['1s','1si','1m','1mi','1c','1ci']){
  await open(join(process.env.EAPDF_CORPUS,`eml_sample_${profile}.pdf`));
  if(['1c','1ci'].includes(profile)){
   assert.ok(await evaluate("return [...document.querySelectorAll('#tree button')].some(button=>/\\.pdf$/i.test(button.textContent))"));
   await evaluate("[...document.querySelectorAll('#tree button')].find(button=>/\\.pdf$/i.test(button.textContent)).click()");await idle();
   assert.equal(await evaluate("return document.querySelector('#back').disabled"),false);
   await evaluate("document.querySelector('#back').click()");await idle();
  }else{await evaluate("document.querySelector('#tree button').click()");await idle();}
  checks.push(profile+' rendering and navigation');
 }
 await open(fixture.path);
 const select=name=>`[...document.querySelectorAll('.asset')].find(row=>row.querySelector('h3').textContent===${JSON.stringify(name)})`;
 const download=async(script,name,expected)=>{
  const path=join(downloads,name);await rm(path,{force:true});await evaluate(script);
  await until(async()=>{try{return (await readFile(path)).equals(expected);}catch{return false;}});await idle();
 };
 for(const name of ['notes.txt','archive.bin','two-pages.pdf','empty.bin'])await download(select(name)+".querySelector('button[aria-label^=\"Download \"]').click()",name,fixture.payloads[name]);
 assert.equal(await evaluate("return /File object:|PDF relationship:|name-tree|catalog-af/.test(document.querySelector('#assets').textContent)"),false);
 await evaluate(select('notes.txt')+".querySelector('button').click()");await idle();assert.equal(await evaluate("return document.querySelector('#page').value"),'2');
 const previewDownload=async script=>{
  await evaluate(script);await until(()=>evaluate("return document.querySelector('#attachment-viewer').open&&document.querySelector('#attachment-viewer').getAttribute('aria-busy')==='false'"));
  await download("document.querySelector('#attachment-download').click()",'notes.txt',fixture.payloads['notes.txt']);await evaluate("document.querySelector('#attachment-close').click()");
 };
 await previewDownload("document.querySelector('.pdf-page[data-page=\"2\"] .file-attachment-link').click()");
 await evaluate("document.querySelector('#previous').click()");await idle();
 await previewDownload("document.querySelectorAll('.pdf-page[data-page=\"1\"] a[aria-label=\"Open linked attachment\"]')[0].click()");
 await evaluate("document.querySelectorAll('.pdf-page[data-page=\"1\"] a[aria-label=\"Open linked attachment\"]')[1].click()");await idle();assert.equal(await evaluate("return document.querySelector('#back').disabled"),false);
 await evaluate("document.querySelector('#back').click()");await idle();
 const widths=await evaluate("return ['navigation-panel','viewport','details-panel'].map(id=>document.getElementById(id).getBoundingClientRect().width)");assert.ok(widths.every(width=>width>150));
 await evaluate("document.querySelector('#toggle-details').click()");assert.equal(await evaluate("return getComputedStyle(document.querySelector('#details-panel')).display"),'none');await evaluate("document.querySelector('#toggle-details').click()");
 checks.push('asset downloads preserve bytes, page locations, attachment/GoToE links, Parent and three-pane layout');
 await evaluate("document.querySelector('#close').click()");assert.equal(await evaluate("return document.querySelector('#empty-state').hidden"),false);
 await mkdir(evidence,{recursive:true});await writeFile(join(evidence,'firefox-checks.json'),JSON.stringify({status:'PASS',browser:result.capabilities.browserVersion,checks},null,2)+'\n');console.log(JSON.stringify({status:'PASS',checks}));
}finally{if(session)await request('/session/'+session,undefined,'DELETE').catch(()=>{});driver.kill();await rm(temporary,{recursive:true,force:true});}
