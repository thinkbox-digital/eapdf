import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {readFile,mkdir,writeFile} from 'node:fs/promises';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
const extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,acceptDownloads:true,executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
try{
 await context.setOffline(true);const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker');const page=await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
 const ready=()=>page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false',null,{timeout:180000});
 await page.waitForFunction(()=>!document.querySelector('#file').disabled);
 await page.evaluate(async()=>{
  const {BrowserSession}=await import('./browser-session.js');const open=BrowserSession.prototype.open,child=BrowserSession.prototype.child;
  window.exportOpenCount=0;window.exportDelay=0;window.exportFailure=false;window.parentRefreshes=[];window.exportChildDocs=[];
  BrowserSession.prototype.open=async function(...args){window.exportOpenCount++;if(window.exportDelay)await new Promise(r=>setTimeout(r,window.exportDelay));if(window.exportFailure)throw Error('Export test failure');return open.apply(this,args);};
  BrowserSession.prototype.child=async function(...args){window.parentRefreshes.push(args[3]);const d=await child.apply(this,args);window.exportChildDocs.push(d);return d;};
 });
 await page.locator('#file').setInputFiles(resolve('../examples/pst/pst_quality-attached-message_1m.pdf'));await ready();
 const before=await page.evaluate(()=>window.exportOpenCount);
 let saved=page.waitForEvent('download');await page.locator('#export-csv').click();const flat=await saved;await ready();
 assert.equal(await page.evaluate(()=>window.exportOpenCount),before,'flat export must reuse loaded metadata');assert.match(await readFile(await flat.path(),'utf8'),/"Folder paths"/);
 await page.locator('#file').setInputFiles(resolve('../examples/pst/pst_quality-attached-message_1c.pdf'));await ready();
 await page.evaluate(()=>{window.exportDelay=1800;});let downloads=0;page.on('download',()=>downloads++);
 for(const mode of ['Cancel','Escape']){
  await page.locator('#export-csv').click();await page.locator('#export-dialog').waitFor({state:'visible'});assert.match(await page.locator('#export-message').textContent(),/Preparing/);
  if(mode==='Cancel')await page.locator('#export-cancel').click();else await page.keyboard.press('Escape');await ready();
  assert.equal(await page.locator('#export-dialog').isVisible(),false);assert.match(await page.locator('#status').textContent(),/cancelled/);assert.equal(await page.locator('#canvas').isVisible(),true);
 }
 assert.equal(downloads,0);
 await page.evaluate(()=>{window.exportDelay=0;window.exportFailure=true;});await page.locator('#export-csv').click();await ready();assert.match(await page.locator('#status').textContent(),/Export test failure/);assert.equal(await page.locator('#export-dialog').isVisible(),false);
 await page.evaluate(()=>{window.exportFailure=false;window.exportWorkers=0;window.Worker=new Proxy(window.Worker,{construct(target,args){window.exportWorkers++;return Reflect.construct(target,args);}});});saved=page.waitForEvent('download');await page.locator('#export-csv').click();await saved;await ready();
 assert.match(await page.locator('#status').textContent(),/Exported finding aid for 7 messages/);
 assert.equal(await page.evaluate(()=>window.exportWorkers),2,'the export uses a parent worker and reuses a child worker');
 assert.ok(await page.evaluate(()=>window.parentRefreshes.length>0&&window.parentRefreshes.every(v=>v===false)));
 assert.ok(await page.evaluate(()=>window.exportChildDocs.every(d=>!d.active&&!d.parent.children.has(d.parentFileId+'|'+d.parentStreamPath))));
 assert.deepEqual(errors,[]);await mkdir('test-results',{recursive:true});await writeFile('test-results/export.json',JSON.stringify({status:'PASS',checks:['flat metadata reused','immediate dialog','Cancel and Escape without closing reader','no partial download','failure and retry','collection parent inspection not repeated','children released','reused child export worker']},null,2)+'\n');console.log('PASS: export progress, cancellation, retry, metadata reuse and bounded child lifetime.');
}finally{await context.close();}
