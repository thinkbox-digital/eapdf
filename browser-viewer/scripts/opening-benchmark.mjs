import { chromium } from 'playwright';
import { readFile, writeFile, stat, mkdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
process.chdir(fileURLToPath(new URL('../', import.meta.url)));
const output=resolve(process.env.BENCH_OUTPUT??'../output/banner-xmp-review-2026-11-03/viewer-opening.json');
const inputs=(process.env.BENCH_FILES?JSON.parse(process.env.BENCH_FILES):[
 '../examples/pst/pst_quality-attached-message_1m.pdf',
 '../output/banner-xmp-review-2026-11-03/unbranded/enron_1m.pdf',
 '../out/enron_1m_2.pdf','../out/enron_1c.pdf','../out/pst_21_04+April+2003+Public+2_1c.pdf'
]).map(p=>resolve(p));
const variants=process.env.BENCH_VARIANTS?JSON.parse(process.env.BENCH_VARIANTS):{
 current:resolve('dist/chromium')};
const report={started:new Date().toISOString(),browser:'',method:'Fresh headless Chromium extension context per trial; local files, network offline; repeated OS-cache-warm reads; variant order alternated; times from file change event. Variants supplied through BENCH_VARIANTS; dialog/control comparisons remove only loading-dialog presentation, retaining identical PDF.js and retry behavior.',files:[],runs:[]};
for(const file of inputs)report.files.push({file,bytes:(await stat(file)).size,sha256:createHash('sha256').update(await readFile(file)).digest('hex')});
await mkdir(resolve(output,'..'),{recursive:true});
for(let repeat=0;repeat<Number(process.env.BENCH_REPEATS??3);repeat++)for(const file of inputs){
 const order=Object.entries(variants);if(repeat%2)order.reverse();
 for(const [variant,extension]of order){
  const row={repeat,variant,file,errors:[],consoleErrors:[]};let context,page,profiler;
  try{
   context=await chromium.launchPersistentContext('',{headless:true,executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',viewport:{width:1400,height:950},args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
   report.browser=context.browser()?.version();await context.setOffline(true);
   const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker');
   page=await context.newPage();page.on('pageerror',error=>row.errors.push(error.message));page.on('console',message=>{if(message.type()==='error')row.consoleErrors.push(message.text());if(message.text().startsWith('BENCH:')){(row.liveStages??=[]).push(JSON.parse(message.text().slice(6)));}});
   await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
   await page.evaluate(async()=>{
    const {BrowserSession}=await import('./browser-session.js');
    window.bench={stages:[]};const mark=(name)=>{if(window.bench.start!==undefined){const stage={name,ms:performance.now()-window.bench.start};window.bench.stages.push(stage);console.info('BENCH:'+JSON.stringify(stage));}};
    document.querySelector('#file').addEventListener('change',()=>{window.bench.start=performance.now();mark('file-selected');},{capture:true});
    const associate=BrowserSession.prototype.associateFiles;
    BrowserSession.prototype.associateFiles=async function(doc){
      await associate.call(this,doc);
      window.bench.coverage=doc.inspection.coverage;
      window.bench.diagnostics=doc.inspection.diagnostics;
      window.bench.inventory=doc.inspection.files.map(f=>({id:f.id,name:f.unicodeFilename.value??f.filename.value,relationship:f.afRelationship.value,owners:f.owners,streams:f.streams}));
      window.bench.messageIds=doc.inspection.messages.map(m=>m.id);
    };
    const original=BrowserSession.prototype.open;
    BrowserSession.prototype.open=async function(...args){
      mark('session-start');const preview=args[6];
      if(preview)args[6]=async(...values)=>{mark('pdf-loaded');try{return await preview(...values);}finally{mark('preview-complete');}};
      try{const doc=await original.apply(this,args);mark('inspection-complete');window.bench.messages=doc.inspection.messages.length;window.bench.files=doc.inspection.files.length;return doc;}
      catch(error){mark('session-error');window.bench.error=String(error);throw error;}
    };
    const seen=new Set();new MutationObserver(()=>{
      const once=(name,condition)=>{if(condition&&!seen.has(name)){seen.add(name);mark(name);}};
      once('first-page-rendered',document.querySelector('[data-rendered="true"]'));
      once('dialog-shown',document.querySelector('#loading-dialog').open);
      const status=document.querySelector('#status').textContent;
      if(status!==window.bench.status){window.bench.status=status;mark('status: '+status);}
      if(window.bench.start!==undefined&&document.querySelector('#viewport').getAttribute('aria-busy')==='false'){
        once('operation-finished',true);window.bench.done=true;window.bench.dialogOpen=document.querySelector('#loading-dialog').open;
      }
    }).observe(document.body,{attributes:true,childList:true,subtree:true,characterData:true});
   });
   if(process.env.BENCH_PROFILE==='1'){profiler=await context.newCDPSession(page);await profiler.send('Profiler.enable');await profiler.send('Profiler.start');}
   await page.locator('#file').setInputFiles(file);
   await page.waitForFunction(()=>window.bench?.done,{},{timeout:Number(process.env.BENCH_TIMEOUT??150000)});
   row.measurements=await page.evaluate(()=>({...window.bench,readingNotesPresent:!!document.querySelector('#reading-notes'),fileNotes:[...document.querySelectorAll('#files > p')].map(p=>p.textContent)}));
  }catch(error){row.error=String(error);}
  finally{if(profiler){try{const profile=await profiler.send('Profiler.stop');const path=output+`.${report.runs.length}.cpuprofile`;await writeFile(path,JSON.stringify(profile.profile));row.profile=path;}catch{}}await context?.close();}
  report.runs.push(row);await writeFile(output,JSON.stringify(report,null,2)+'\n');
  const stages=row.measurements?.stages??[];
  console.log(JSON.stringify({variant,repeat,file,first:stages.find(s=>s.name==='first-page-rendered')?.ms,finished:stages.find(s=>s.name==='operation-finished')?.ms,error:row.error??row.measurements?.error}));
 }
}
