import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {mkdir,writeFile} from 'node:fs/promises';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
const extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
try{
 const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage();await context.setOffline(true);const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
 await page.evaluate(async()=>{const {BrowserSession}=await import('./browser-session.js');const open=BrowserSession.prototype.open;window.collectionScans=0;window.scanDelay=0;BrowserSession.prototype.open=async function(...args){if(this.options.reuseWorker&&!args[3]){window.collectionScans++;if(window.scanDelay)await new Promise(resolve=>setTimeout(resolve,window.scanDelay));}return open.apply(this,args);};});
 const ready=()=>page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false',null,{timeout:180000});
 const fixture=resolve('../out/pst/pst_21_04+April+2003+Public+2/pst_21_04+April+2003+Public+2_1c.pdf');
 await page.locator('#file').setInputFiles(fixture);await ready();
 await page.locator('#search-scope').selectOption('collection');await page.locator('#search').fill('medicaid');const started=Date.now();await page.locator('#find').click();await ready();
 const searchMs=Date.now()-started,first=await page.locator('#search-status').textContent();assert.match(first,/^Match 1 of \d+ in collection\.$/);assert.ok(await page.locator('#text-layer .current-match').count()>0);assert.equal(await page.evaluate(()=>window.collectionScans),1);
 const nextStart=Date.now();await page.locator('#find').click();await ready();const nextMs=Date.now()-nextStart;assert.match(await page.locator('#search-status').textContent(),/^Match 2 of/);assert.ok(await page.locator('#text-layer .current-match').count()>0);assert.equal(await page.evaluate(()=>window.collectionScans),1);
 await page.locator('#find-previous').click();await ready();assert.equal(await page.locator('#search-status').textContent(),first);assert.equal(await page.evaluate(()=>window.collectionScans),1);assert.deepEqual(errors,[]);
 const result={status:'PASS',fixture,searchMs,nextMs,first,summary:await page.locator('#collection-search-summary').textContent(),checks:['first match visible and highlighted','next advances without rescanning','previous returns without rescanning']};await mkdir('test-results',{recursive:true});await writeFile('test-results/collection-matches-large.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
}finally{await context.close();}
