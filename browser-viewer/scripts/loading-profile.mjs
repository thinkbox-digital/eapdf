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
  for(const name of ['open','refresh','ensure','readBytes','associateFiles','associateSupplements','restartExtractionWorker']){
   const original=BrowserSession.prototype[name];
   BrowserSession.prototype[name]=function(...args){
    const start=performance.now(),done=()=>{window.navigationMetrics.push({method:name,document:args[0]?.name,ms:performance.now()-start});if(name==='associateFiles')window.loadInspection=args[0].inspection;};
    try{const result=original.apply(this,args);if(result?.then)return result.finally(done);done();return result;}catch(error){done();throw error;}
   };
  }
 });
 const started=Date.now();await page.locator('#file').setInputFiles(input);await ready();const elapsedMs=Date.now()-started;
 const result=await page.evaluate(()=>{
  const metrics=window.navigationMetrics,stages={};for(const m of metrics){const s=stages[m.method]??={count:0,totalMs:0,maxMs:0};s.count++;s.totalMs+=m.ms;s.maxMs=Math.max(s.maxMs,m.ms);}
  const inspection=window.loadInspection;const supplements=inspection.files.filter(f=>f.afRelationship.value==='Supplement');return {supplements:{total:supplements.length,linked:supplements.filter(f=>f.owners.value).length},diagnostics:inspection.diagnostics.map(d=>({code:d.code,message:d.message})),stages,status:document.querySelector('#status').textContent};
 });
 assert.doesNotMatch(result.status,/Unable|failed/i);
 await mkdir('test-results',{recursive:true});const report={input,elapsedMs,sha256:createHash('sha256').update(await readFile(input)).digest('hex'),...result};
 await writeFile(`test-results/loading-profile-${process.env.LOAD_LABEL??'after'}.json`,JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
}finally{await context.close();}
