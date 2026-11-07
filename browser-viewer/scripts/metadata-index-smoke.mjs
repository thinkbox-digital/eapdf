import {chromium} from 'playwright';
import {resolve} from 'node:path';
import {readFile,mkdir,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
const extension=resolve('dist/chromium'),context=await chromium.launchPersistentContext('',{headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{channel:'chromium'}),args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
try{
 const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage();await context.setOffline(true);
 await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
 await page.evaluate(async()=>{
  const {BrowserSession}=await import('./browser-session.js'),open=BrowserSession.prototype.open,child=BrowserSession.prototype.child;
  window.openedChildren=0;window.indexMode='valid';
  BrowserSession.prototype.open=async function(...args){const doc=await open.apply(this,args);if(!doc.parent&&window.indexMode==='corrupt')doc.embeddedIndex='AAAA';return doc;};
  BrowserSession.prototype.child=async function(...args){window.openedChildren++;const doc=await child.apply(this,args);window.selectedGuids=doc.inspection.messages.map(m=>m.mailGuid.value);if(window.indexMode==='stale-child'){window.indexMode='valid';return {...doc,inspection:{...doc.inspection,messages:doc.inspection.messages.map(m=>({...m,mailGuid:{...m.mailGuid,value:'different-child'}}))}};}return doc;};
 });
 const manifest=JSON.parse(await readFile('tests/metadata-index/manifest.json','utf8')),reports=[];
 const ready=()=>page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false'&&document.querySelector('#subject-index-progress').dataset.complete==='true',null,{timeout:120000});
 for(const [filename,hash] of Object.entries(manifest.files)){
  assert.equal(createHash('sha256').update(await readFile('tests/metadata-index/'+filename)).digest('hex'),hash);
  await page.evaluate(()=>{window.indexMode='valid';window.openedChildren=0;});
  await page.locator('#file').setInputFiles(resolve('tests/metadata-index/'+filename));await ready();
  assert.equal(await page.evaluate(()=>window.openedChildren),0,'indexed browse must not open children');
  await page.locator('#message-filter').fill('power');await page.locator('#filter-results button').first().waitFor();await page.waitForFunction(()=>document.querySelector('#filter-status').textContent.includes('1 matching emails'));
  assert.equal(await page.evaluate(()=>window.openedChildren),0,'filter must not open children');
  await page.locator('#filter-results button').first().click();await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false'&&document.querySelector('#back').disabled===false);
  assert.equal((await page.evaluate(()=>window.selectedGuids)).length,1);assert.ok(await page.evaluate(()=>window.openedChildren)>0);
  assert.match(await page.locator('#breadcrumbs').innerText(),/Power VaR/);
  await page.locator('#close').click();
  await page.evaluate(()=>{window.indexMode='corrupt';window.openedChildren=0;});
  await page.locator('#file').setInputFiles(resolve('tests/metadata-index/'+filename));await ready();
  assert.equal(await page.evaluate(()=>window.openedChildren),2,'corrupt index must inspect both children');
  assert.match(await page.locator('#subject-index-status').innerText(),/index unavailable/);
  await page.locator('#message-filter').fill('power');await page.locator('#filter-results button').first().waitFor();await page.waitForFunction(()=>document.querySelector('#filter-status').textContent.includes('1 matching emails'));
  assert.match(await page.locator('#filter-status').innerText(),/index unavailable/);
  await page.locator('#close').click();reports.push({filename,status:'PASS',indexedChildOpens:0,fallbackChildOpens:2});
 }
 // A binding that disagrees with the actual selected child must rebuild, not navigate.
 await page.evaluate(()=>{window.indexMode='valid';window.openedChildren=0;});
 await page.locator('#file').setInputFiles(resolve('tests/metadata-index/mbox_sample_1ci.pdf'));await ready();
 await page.locator('#message-filter').fill('power');await page.locator('#filter-results button').first().waitFor();
 await page.evaluate(()=>{window.indexMode='stale-child';});
 await page.locator('#filter-results button').first().click();
 await page.waitForFunction(()=>document.querySelector('#status').textContent.includes('Search was rebuilt from child PDFs'));
 assert.equal(await page.locator('#back').isDisabled(),true,'must not display the mismatched child');
 assert.ok(await page.evaluate(()=>window.openedChildren)>=3,'must scan both children after mismatch');
 await page.locator('#filter-results button').first().click();
 await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false'&&!document.querySelector('#back').disabled);
 assert.match(await page.locator('#breadcrumbs').innerText(),/Power VaR/);
 await page.locator('#close').click();
 // Compare every searchable value and the actual child identity/path, not just counts.
 await page.evaluate(()=>{const input=document.createElement('input');input.type='file';input.id='comparison';document.body.append(input);});
 await page.locator('#comparison').setInputFiles(resolve('tests/metadata-index/mbox_sample_1ci.pdf'));
 const equal=await page.evaluate(async()=>{
  window.indexMode='valid';const {BrowserSession}=await import('./browser-session.js'),{indexMessages}=await import('./message-index.js');
  const s=new BrowserSession({},{reuseWorker:true}),root=await s.open(document.querySelector('#comparison').files[0],'sample.pdf'),identities=new Map(),child=s.child.bind(s);
  s.child=async(...args)=>{const doc=await child(...args);for(const m of doc.inspection.messages)identities.set(m.id,m.mailGuid.value);return doc;};
  try{const fast=await indexMessages(s,root,()=>{},()=>{}),slow=await indexMessages(s,root,()=>{},()=>{},undefined,true);
   const normalize=result=>result.messages.map(({messageId,mailGuid,...item})=>({...item,guid:mailGuid??identities.get(messageId)}));return JSON.stringify(normalize(fast))===JSON.stringify(normalize(slow));
  }finally{s.close();}
 });assert.equal(equal,true);
 await mkdir('test-results',{recursive:true});await writeFile('test-results/metadata-index.json',JSON.stringify({status:'PASS',equal,reports},null,2)+'\n');console.log(JSON.stringify({equal,reports}));
}finally{await context.close();}
