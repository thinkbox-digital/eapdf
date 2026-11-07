// Moderate real archive under a constrained V8 heap; not a 4-GiB capacity claim.
import {chromium} from 'playwright';
import {resolve} from 'node:path';
import {mkdir,writeFile,stat} from 'node:fs/promises';
import assert from 'node:assert/strict';
const fixture=resolve(process.env.EAPDF_MEMORY_PDF??'tests/attachments/source/pst_sample_1c.pdf');
const extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{channel:'chromium'}),args:['--js-flags=--max-old-space-size=512',`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
try{
 const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage();await context.setOffline(true);
 await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
 await page.evaluate(()=>{const input=document.createElement('input');input.type='file';input.id='memory-fixture';document.body.append(input);});
 await page.locator('#memory-fixture').setInputFiles(fixture);
 const cdp=await context.newCDPSession(page),rounds=[];
 for(let round=0;round<2;round++){
  const result=await page.evaluate(async round=>{
   const {BrowserSession}=await import('./browser-session.js'),{searchCollection}=await import('./collection-search.js');
   const blob=document.querySelector('#memory-fixture').files[0],session=new BrowserSession();const started=performance.now();
   try{
    const doc=await session.open(blob,'archive.pdf');
    const memberIds=new Set(doc.inspection.collection.value.entries.map(e=>e.fileId));
    const file=doc.inspection.files.find(f=>memberIds.has(f.id)&&f.streams.some(s=>s.mimeType.value==='application/pdf'));
    const stream=file.streams.find(s=>s.mimeType.value==='application/pdf');
    const bytes=await session.bytes(doc,file,stream),sha256=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),v=>v.toString(16).padStart(2,'0')).join('');
    const child=await session.child(doc,file,stream,false);
    for(const number of [1,child.pdf.numPages]){
     const p=await child.run(child.pdf.getPage(number)),viewport=p.getViewport({scale:1}),canvas=document.createElement('canvas');canvas.width=viewport.width;canvas.height=viewport.height;
     await child.run(p.render({canvas,viewport}).promise);p.cleanup();canvas.width=canvas.height=0;
    }
    await session.release(child);
    const search=round===0?await searchCollection(session,doc,'enron',()=>{},()=>{}):null;
    return {members:memberIds.size,sha256,search:search&&{matches:search.matches.length,incomplete:search.incomplete},elapsedMs:performance.now()-started};
   }finally{session.close();}
  },round);
  assert.ok(result.members>0);if(result.search){assert.ok(result.search.matches>0);assert.deepEqual(result.search.incomplete,[]);}
  await cdp.send('HeapProfiler.collectGarbage');result.mainThreadHeap=await cdp.send('Runtime.getHeapUsage');rounds.push(result);
 }
 assert.equal(rounds[0].sha256,rounds[1].sha256);
 const report={status:'PASS',bytes:(await stat(fixture)).size,v8OldSpaceMiB:512,rounds,limits:'Main-thread heap metrics exclude worker/browser/native memory. Two opens of this moderate archive do not establish multi-GiB capacity.'};
 await mkdir('test-results',{recursive:true});await writeFile('test-results/archive-memory.json',JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
}finally{await context.close();}
