import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {mkdir,writeFile} from 'node:fs/promises';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
const extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
try{
 await context.setOffline(true);const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
 await page.evaluate(async()=>{
  const {BrowserSession}=await import('./browser-session.js');const child=BrowserSession.prototype.child,close=BrowserSession.prototype.close,open=BrowserSession.prototype.open;
  // Exercise legacy background scanning even when the regenerated fixture has an embedded metadata index.
  BrowserSession.prototype.open=async function(...args){const doc=await open.apply(this,args);doc.embeddedIndex=undefined;if(!this.options.reuseWorker)window.mainDocument=doc;return doc;};
  window.holdIndex=true;window.indexChildren=0;window.indexClosed=0;
  BrowserSession.prototype.child=async function(...args){if(this.options.reuseWorker&&++window.indexChildren===2&&window.holdIndex)await new Promise(resolve=>{window.releaseIndex=resolve;});return child.apply(this,args);};
  BrowserSession.prototype.close=function(){if(this.options.reuseWorker)window.indexClosed++;return close.apply(this);};
 });
 const ready=()=>page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false',null,{timeout:120000});
 const fixture=resolve('../examples/pst/pst_quality-attached-message_1c.pdf');
 await page.locator('#file').setInputFiles(fixture);await ready();
 await page.waitForFunction(()=>window.indexChildren===2,null,{timeout:120000});
 assert.equal(await page.locator('#loading-dialog').isVisible(),false);
 assert.equal(await page.locator('#file').isEnabled(),true);assert.equal(await page.locator('#search').isEnabled(),true);
 assert.equal(await page.locator('#canvas').isVisible(),true);assert.equal(await page.locator('#subject-index-cancel').isVisible(),true);
 const changed=await page.locator('#tree button[data-embedded-file-id]').evaluateAll(rows=>rows.filter(row=>row.textContent!==row.title).length);assert.ok(changed>0,'subjects appear before indexing completes');
 await page.locator('#tree details').evaluateAll(rows=>rows.forEach(row=>row.open=true));
 await page.locator('#tree button[data-embedded-file-id]').first().click();await ready();assert.equal(await page.locator('#back').isEnabled(),true,'open child while background index is paused');
 await page.locator('#subject-index-cancel').click();assert.match(await page.locator('#subject-index-status').innerText(),/stopped/);assert.ok(await page.evaluate(()=>window.indexClosed)>0);assert.equal(await page.locator('#canvas').isVisible(),true);
 await page.evaluate(()=>window.releaseIndex());await page.locator('#back').click();await ready();assert.match(await page.locator('#subject-index-status').innerText(),/stopped/,'late index response cannot overwrite cancellation');
 await page.locator('#close').click();assert.equal(await page.locator('#subject-index-progress').isVisible(),false);
 await page.evaluate(()=>{window.holdIndex=false;window.indexChildren=0;});
 await page.locator('#file').setInputFiles(fixture);await ready();await page.waitForFunction(()=>document.querySelector('#subject-index-status').textContent.startsWith('Subjects ready'),null,{timeout:120000});
 assert.equal(await page.locator('#subject-index-cancel').isVisible(),false);
 const linked=await page.evaluate(()=>window.mainDocument.inspection.files.filter(f=>f.afRelationship.value==='Supplement'&&f.owners.value?.length).length);assert.ok(linked>0,'background association results must reach the displayed document');
 await page.locator('#message-filter').fill('Feature');await page.locator('#filter-results button').first().waitFor();
 assert.ok(await page.locator('#filter-results button').count()>0,'completed background index is reused for filtering');
 assert.deepEqual(errors,[]);await mkdir('test-results',{recursive:true});await writeFile('test-results/progressive-loading.json',JSON.stringify({status:'PASS',checks:['initial view usable during paused indexing','incremental subjects','child navigation during indexing','independent cancellation','stale response isolation','close resets progress','completed index reused for filtering','background file associations adopted by displayed document']},null,2)+'\n');console.log('PASS: progressive loading, navigation, cancellation and metadata reuse.');
}finally{await context.close();}
