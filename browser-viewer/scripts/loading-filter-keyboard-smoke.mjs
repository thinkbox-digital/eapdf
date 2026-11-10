import {chromium} from 'playwright';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';
const extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--load-extension=${extension}`,`--disable-extensions-except=${extension}`]});
try{
 const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage();await page.setViewportSize({width:1600,height:1000});
 await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
 await page.evaluate(async()=>{const {BrowserSession}=await import('./browser-session.js');const open=BrowserSession.prototype.open;window.opens=0;BrowserSession.prototype.open=async function(...args){window.opens++;const doc=await open.apply(this,args);window.probeSession=this;window.probeDoc=doc;return doc;};});
 const ready=()=>page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false');
 const fixture=resolve('../examples/mbox/mbox_enron_1m.pdf');await page.locator('#file').setInputFiles(fixture);await ready();
 const parity=await page.evaluate(async()=>{
  const {interpret}=await import('./domain/interpret.js'),doc=window.probeDoc,entry=window.probeSession.entries.get(doc);
  const input={documentId:doc.inspection.documentId,byteLength:entry.size,headerVersion:entry.header,pageCount:doc.pdf.numPages,parts:entry.parts,assets:entry.assets,streams:entry.facts,limits:window.probeSession.limits};
  const run=data=>new Promise((resolve,reject)=>{const w=new Worker('./interpret-worker.js',{type:'module'});w.onmessage=e=>{w.terminate();e.data.error?reject(Error(e.data.error)):resolve(e.data.result);};w.onerror=e=>{w.terminate();reject(Error(e.message));};w.postMessage({input:data});});
  const projection=r=>JSON.stringify({...r,details:[...r.details]});
  if(projection(await run(input))!==projection(interpret({...input,parseXml:x=>new DOMParser().parseFromString(x,'application/xml')})))throw Error('worker/native interpretation differs');
  const bad=structuredClone(input),metadata=bad.assets.metadata.find(m=>m.locator.path===bad.assets.catalogMetadata.path);metadata.text='<broken>';
  const result=await run(bad);if(result.inspection.coverage.metadata!=='invalid')throw Error('worker accepted malformed XML');return true;
 });assert.ok(parity);
 const opens=await page.evaluate(()=>window.opens);await page.locator('#message-filter').fill('Power');await page.locator('#filter-results button').nth(1).waitFor();
 assert.equal(await page.evaluate(()=>window.opens),opens,'first 1m filter must reuse loaded metadata');
 await page.locator('#filter-results button').first().click();await ready();await page.locator('#filter-results button').first().focus();await page.keyboard.press('ArrowDown');await ready();
 assert.equal(await page.locator('#filter-results button[aria-current]').getAttribute('data-message-index'),'1');assert.equal(await page.evaluate(()=>document.activeElement.dataset.messageIndex),'1');
 await page.keyboard.press('ArrowUp');await ready();assert.equal(await page.locator('#filter-results button[aria-current]').getAttribute('data-message-index'),'0');
 await page.locator('#file').setInputFiles(fixture);assert.equal(await page.locator('#filter-status').innerText(),'','reopen clears previous status');await ready();
 const first=page.locator('#tree button[data-message-id]').first(),second=page.locator('#tree button[data-message-id]').nth(1),id=await second.getAttribute('data-message-id');
 await first.click();await ready();assert.equal(await first.evaluate(e=>e===document.activeElement),true,'mouse-opened message must retain keyboard focus');await page.keyboard.press('ArrowDown');await ready();assert.equal(await page.locator('#tree button[aria-current]').getAttribute('data-message-id'),id);
 await page.keyboard.press('ArrowUp');await ready();assert.equal(await first.getAttribute('aria-current'),'true');
 // Opening an embedded message replaces its tree row; focus must follow the replacement.
 await page.locator('#file').setInputFiles(resolve('tests/metadata-index/mbox_sample_1ci.pdf'));await ready();
 await page.locator('#tree details').evaluateAll(nodes=>nodes.forEach(n=>n.open=true));await page.locator('#tree button[data-embedded-file-id]').first().click();await ready();
 const selected=page.locator('#tree button[aria-current]');const firstChild=await selected.getAttribute('data-document-id');
 assert.equal(await selected.evaluate(e=>e===document.activeElement),true,'opened collection child must receive focus');
 await page.keyboard.press('ArrowDown');await ready();assert.notEqual(await selected.getAttribute('data-document-id'),firstChild);
 await page.keyboard.press('ArrowUp');await ready();assert.equal(await selected.getAttribute('data-document-id'),firstChild);
 await page.locator('#navigation-panel').focus();await page.keyboard.press('ArrowDown');await ready();assert.notEqual(await selected.getAttribute('data-document-id'),firstChild);
 await page.locator('#file').setInputFiles(resolve('tests/fulltext-index/mbox_sample_1mi.pdf'));await ready();
 await page.locator('#open-search').click();await page.locator('#search-scope').selectOption('collection');await page.locator('#search').fill('Power VaR');await page.locator('#search-submit').click();await ready();
 await page.locator('#collection-matches button[data-match-index="1"]').waitFor();await page.locator('#collection-matches button[data-match-index="0"]').focus();await page.keyboard.press('ArrowDown');await ready();
 assert.equal(await page.locator('#collection-matches button[aria-current]').getAttribute('data-match-index'),'1');assert.ok(await page.locator('#text-layer .current-match').count()>0);
 await page.keyboard.press('ArrowUp');await ready();assert.equal(await page.locator('#collection-matches button[aria-current]').getAttribute('data-match-index'),'0');
 console.log('PASS: worker/native parity, malformed XML, zero-reopen first filter, same-file reset, Messages/filter/search arrow activation and focus.');
}finally{await context.close();}
