import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
const extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
try{
 await context.setOffline(true);
 const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker');
 const page=await context.newPage(),debug=[],errors=[];
 page.on('console',m=>{if(m.type()==='debug')debug.push(m.text());});page.on('pageerror',e=>errors.push(e.message));
 await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
 await page.evaluate(async()=>{
  const {BrowserSession}=await import('./browser-session.js'),associate=BrowserSession.prototype.associateFiles;
  BrowserSession.prototype.associateFiles=async function(doc){
   await associate.call(this,doc);
   if(window.testPartial){doc.inspection.coverage.associatedFiles='unavailable';doc.inspection.diagnostics.push({code:'OBJECT_LIMIT',state:'unavailable',message:'Injected bounded scan failure',evidence:[]});}
  };
 });
 const fixture=resolve('../examples/pst/pst_quality-attached-message_1m.pdf');
 for(const partial of [false,true,false]){
  await page.evaluate(value=>{window.testPartial=value;},partial);
  await page.locator('#file').setInputFiles(fixture);
  await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false');
  assert.equal(await page.locator('#reading-notes').count(),0);
  assert.equal(await page.locator('#files').getByText('Some embedded files could not be listed.',{exact:true}).count(),partial?1:0);
  assert.ok(!(await page.locator('body').innerText()).includes('OBJECT_LIMIT'),'technical diagnostic stays out of UI');
 }
 assert.ok(debug.some(t=>t.includes('[EA-PDF inspection]')&&t.includes('OBJECT_LIMIT')),'diagnostic retained in debug output');
 assert.deepEqual(errors,[]);
 console.log('PASS: no Reading Notes panel; contextual warning only for incomplete scans; debug diagnostics retained; successful reopen clears warning.');
}finally{await context.close();}
