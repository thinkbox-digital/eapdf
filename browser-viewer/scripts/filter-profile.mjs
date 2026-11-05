import {chromium} from 'playwright';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {writeFile,mkdir,readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
const input=resolve('../out/pst/pst_21_04+April+2003+Public+2/pst_21_04+April+2003+Public+2_1c.pdf');
const extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
try{
 const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker');const page=await context.newPage();await context.setOffline(true);
 await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
 const ready=()=>page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false',null,{timeout:240000});
 await page.waitForFunction(()=>!document.querySelector('#file').disabled);
 await page.evaluate(async()=>{const {BrowserSession}=await import('./browser-session.js');window.openCount=0;const original=BrowserSession.prototype.open;BrowserSession.prototype.open=async function(...args){window.openCount++;return original.apply(this,args);};});
 await page.locator('#file').setInputFiles(input);await ready();const before=await page.evaluate(()=>window.openCount);
 await page.locator('#tree details').evaluateAll(items=>items.forEach(item=>{item.open=true;}));
 const position=await page.locator('#message-filter').boundingBox();await page.locator('#navigation-panel').evaluate(panel=>{panel.scrollTop=1500;});assert.deepEqual(await page.locator('#message-filter').boundingBox(),position);
 const timings=[];
 for(const query of ['medicaid','budget']){
  await page.locator('#search-status').evaluate(e=>{e.textContent='';});
  const started=Date.now();await page.locator('#message-filter').fill(query);await page.waitForFunction(()=>/^\d+ matching emails/.test(document.querySelector('#search-status').textContent),null,{timeout:180000});
  const status=await page.locator('#search-status').textContent();assert.match(status,/All reachable email metadata searched/);timings.push({query,ms:Date.now()-started,status});
 }
 assert.equal(await page.evaluate(()=>window.openCount),before,'filtering must reuse the completed archive scan');
 await page.locator('#filter-results button').first().click();await ready();assert.match(await page.locator('#status').textContent(),/Page 1 of/);
 const result={status:'PASS',input,sha256:createHash('sha256').update(await readFile(input)).digest('hex'),timings,checks:['no PDF reopen for either filter','complete metadata index','result destination opens','filter stays pinned while archive scrolls']};
 await mkdir('test-results',{recursive:true});await writeFile('test-results/filter-profile.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
}finally{await context.close();}
