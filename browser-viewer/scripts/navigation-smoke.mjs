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
 await page.evaluate(async()=>{
  const {BrowserSession}=await import('./browser-session.js');window.navigationMetrics=[];
  for(const name of ['refresh','ensure','child','associateFiles']){
   const original=BrowserSession.prototype[name];
   BrowserSession.prototype[name]=function(...args){
    const start=performance.now(),done=()=>window.navigationMetrics.push({method:name,document:args[0]?.name,ms:performance.now()-start});
    try{const result=original.apply(this,args);if(result?.then)return result.finally(done);done();return result;}catch(error){done();throw error;}
   };
  }
 });
 await page.locator('#file').setInputFiles(input);await ready();console.log('Opened: '+await page.locator('#status').textContent());
 await page.locator('#tree details').evaluateAll(items=>items.forEach(item=>{item.open=true;}));
 const samples=[];
 for(const label of ['(no subject)_000009_1s.pdf','(no subject)_000013_1s.pdf','(no subject)_000053_1s.pdf','(no subject)_000186_1s.pdf','(no subject)_000199_1s.pdf']){
  await page.evaluate(()=>{window.navigationMetrics=[];});const started=Date.now();
  const button=page.locator('#tree button').filter({hasText:label}).first();await button.click();await ready();
  const status=await page.locator('#status').textContent();const sample={label,elapsedMs:Date.now()-started,metrics:await page.evaluate(()=>window.navigationMetrics)};samples.push(sample);console.log(JSON.stringify(sample));assert.doesNotMatch(status,/Unable|allocation limit|failed/i);assert.match(status,/Page 1 of/);
 }
 await mkdir('test-results',{recursive:true});await writeFile(`test-results/navigation-${process.env.NAV_LABEL??'after'}.json`,JSON.stringify({status:'PASS',input,sha256:createHash('sha256').update(await readFile(input)).digest('hex'),samples},null,2)+'\n');
}finally{await context.close();}
