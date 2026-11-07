// Run after scripts/java/eapdf/MetadataIndexBenchmark.java; no network service required.
import {chromium} from 'playwright';
import {resolve,join} from 'node:path';
import {readFile,writeFile,mkdir,stat} from 'node:fs/promises';
import assert from 'node:assert/strict';
const directory=resolve(process.env.EAPDF_INDEX_BENCHMARK??'');
if(!process.env.EAPDF_INDEX_BENCHMARK)throw Error('Set EAPDF_INDEX_BENCHMARK to generated fixture directory');
const counts=(process.env.EAPDF_INDEX_COUNTS??'100,1000,10000').split(',').map(Number),trials=Number(process.env.EAPDF_INDEX_TRIALS??1),reports=[];
for(const count of counts)for(let trial=0;trial<trials;trial++){
 const pair=[];
 for(const mode of trial%2?['legacy','indexed']:['indexed','legacy']){
  const filename=join(directory,`eml_repeated_${count}${mode==='legacy'?'_legacy':''}_1ci.pdf`),extension=resolve('dist/chromium');
  const context=await chromium.launchPersistentContext('',{headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{channel:'chromium'}),args:['--enable-precise-memory-info',`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
  let timer;
  try{
   const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage();await context.setOffline(true);
   await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
   await page.evaluate(()=>{const input=document.createElement('input');input.type='file';input.id='benchmark-file';document.body.append(input);});
   await page.locator('#benchmark-file').setInputFiles(filename);
   const browserCdp=await context.browser().newBrowserCDPSession(),cdp=await context.newCDPSession(page);
   let peakMainHeap=0,peakPssKiB=0,samples=0,sampling=false;
   const sample=async()=>{if(sampling)return;sampling=true;try{
    peakMainHeap=Math.max(peakMainHeap,(await cdp.send('Runtime.getHeapUsage')).usedSize);
    const {processInfo}=await browserCdp.send('SystemInfo.getProcessInfo');let pss=0;
    for(const p of processInfo)try{const value=await readFile(`/proc/${p.id}/smaps_rollup`,'utf8');pss+=Number(/^Pss:\s+(\d+)/m.exec(value)?.[1]??0);}catch{}
    peakPssKiB=Math.max(peakPssKiB,pss);samples++;
   }finally{sampling=false;}};
   timer=setInterval(()=>void sample().catch(()=>{}),100);await sample();
   page.on('console',message=>{if(message.text().startsWith('BENCH:'))console.log(message.text());});
   const result=await page.evaluate(async()=>{
    const {BrowserSession}=await import('./browser-session.js'),{indexMessages}=await import('./message-index.js');
    const session=new BrowserSession({},{reuseWorker:true}),start=performance.now();let opened=0,first=null;
    const child=session.child.bind(session),guids=new Map();
    session.child=async(...args)=>{opened++;if(opened%1000===0)console.info('BENCH: child '+opened);const doc=await child(...args);guids.set(args[1].id,doc.inspection.messages[0]?.mailGuid.value);return doc;};
    try{
     const doc=await session.open(document.querySelector('#benchmark-file').files[0],'benchmark.pdf'),rootLoadMs=performance.now()-start,loading=performance.now();
     const result=await indexMessages(session,doc,()=>{},()=>{},batch=>{if(batch.length&&first===null)first=performance.now()-start;});
     const indexLoadMs=performance.now()-loading;
     const search=[];for(let n=0;n<101;n++){const t=performance.now();result.messages.filter(m=>m.searchText.includes(n%2?'amsterdam':'no-match-term'));search.push(performance.now()-t);}search.sort((a,b)=>a-b);
     const files=new Map(doc.inspection.files.map(f=>[f.id,f]));
     const projection=result.messages.map(m=>({guid:m.mailGuid??guids.get(m.path[0]?.fileId),label:m.label,searchText:m.searchText,sender:m.sender,sent:m.sent,path:m.path.map(p=>{const f=files.get(p.fileId);return {keys:f.origins.filter(o=>o.kind==='name-tree').map(o=>o.nameTreeKey.value).sort(),md5:f.streams.find(s=>s.locator.path===p.streamPath).declaredMd5.value};})}));
     const bytes=new TextEncoder().encode(JSON.stringify(projection));const fingerprint=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),v=>v.toString(16).padStart(2,'0')).join('');
     return {source:result.source,messages:result.messages.length,incomplete:result.incomplete,notices:result.notices,opened,rootLoadMs,indexLoadMs,firstResultMs:first,completeMetadataMs:rootLoadMs+indexLoadMs,completeMs:performance.now()-start,subsequentSearchMedianMs:search[50],fingerprint};
    }finally{session.close();}
   });
   clearInterval(timer);timer=undefined;while(sampling)await new Promise(r=>setTimeout(r,10));await sample();
   assert.equal(result.messages,count);assert.deepEqual(result.incomplete,[]);assert.equal(result.source,mode==='indexed'?'embedded':'scanned');assert.equal(result.opened,mode==='indexed'?0:count);
   const report={count,trial,mode,bytes:(await stat(filename)).size,...result,peakMainHeapBytes:peakMainHeap,peakBrowserPssKiB:peakPssKiB,memorySamples:samples};pair.push(report);reports.push(report);console.log(JSON.stringify(report));
  }finally{clearInterval(timer);await context.close();}
 }
 assert.equal(pair[0].fingerprint,pair[1].fingerprint,'metadata and logical destinations differ');
 await mkdir('test-results',{recursive:true});await writeFile('test-results/metadata-index-benchmark.json',JSON.stringify({scope:'Repeated public email scaling fixture; fresh Chromium per mode; main-thread heap and Linux browser-process PSS sampled every 100 ms, not exact instantaneous peaks.',reports},null,2)+'\n');
}
