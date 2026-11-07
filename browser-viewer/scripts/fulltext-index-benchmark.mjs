import {chromium} from 'playwright';
import {readFile,writeFile,mkdir,stat} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import assert from 'node:assert/strict';
const directory=process.env.EAPDF_FULLTEXT_BENCHMARK;if(!directory)throw Error('Set EAPDF_FULLTEXT_BENCHMARK');
const kind=process.env.EAPDF_QUERY_KIND??'common',query={common:'amsterdam',rare:'messages',phrase:'BODY TEXT'}[kind];if(!query)throw Error('Unknown query kind');
const reports=[],modes=(process.env.EAPDF_BENCH_MODES??'indexed,legacy').split(',');
for(const count of (process.env.EAPDF_INDEX_COUNTS??'100,1000,10000').split(',').map(Number)){
 const pair=[];
 for(const mode of modes){
  const filename=join(directory,`eml_repeated_${count}${mode==='legacy'?'_legacy':''}_1ci.pdf`),extension=resolve('dist/chromium');
  const context=await chromium.launchPersistentContext('',{headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{channel:'chromium'}),args:['--enable-precise-memory-info',`--load-extension=${extension}`,`--disable-extensions-except=${extension}`]});let timer;
  try{
   await context.setOffline(true);const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage();await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
   await page.evaluate(()=>{const input=document.createElement('input');input.id='benchmark';input.type='file';document.body.append(input);});await page.locator('#benchmark').setInputFiles(filename);
   const browserCdp=await context.browser().newBrowserCDPSession(),cdp=await context.newCDPSession(page);let sampling=false,peakPssKiB=0,peakHeap=0,samples=0;
   const sample=async()=>{if(sampling)return;sampling=true;try{peakHeap=Math.max(peakHeap,(await cdp.send('Runtime.getHeapUsage')).usedSize);const {processInfo}=await browserCdp.send('SystemInfo.getProcessInfo');let pss=0;for(const p of processInfo)try{pss+=Number(/^Pss:\s+(\d+)/m.exec(await readFile(`/proc/${p.id}/smaps_rollup`,'utf8'))?.[1]??0);}catch{}peakPssKiB=Math.max(peakPssKiB,pss);samples++;}finally{sampling=false;}};
   timer=setInterval(()=>void sample().catch(()=>{}),1000);await sample();
   page.on('console',message=>{if(message.text().startsWith('BENCH:'))console.log(message.text());});
   const result=await page.evaluate(async({query,mode})=>{
    const {BrowserSession}=await import('./browser-session.js'),{searchCollection}=await import('./collection-search.js'),{readFullTextIndex,fullTextPageOwners}=await import('./fulltext-index.js');
    const session=new BrowserSession({},{reuseWorker:true}),child=session.child.bind(session),owners=new Map();let opened=0;
    session.child=async(...args)=>{opened++;if(opened%1000===0)console.log('BENCH: children '+opened);const doc=await child(...args);owners.set(args[1].id,fullTextPageOwners(doc));return doc;};
    try{
     const start=performance.now(),root=await session.open(document.querySelector('#benchmark').files[0],'benchmark.pdf'),openingMs=performance.now()-start;
     let indexLoadingMs=null;if(mode==='indexed'){const before=performance.now(),loaded=await readFullTextIndex(session,root,()=>{});indexLoadingMs=performance.now()-before;if(!loaded.index)throw Error(loaded.diagnostic);}
     const results=[];
     // Two actual engine executions: the second refines case, retaining the parsed index.
     for(const term of [query,query.toUpperCase()]){
      const before=performance.now(),prior=opened;
      const result=await searchCollection(session,root,term,()=>{},()=>{},false,1800000);
      const elapsedMs=performance.now()-before;if(mode==='indexed'&&result.source!=='indexed')throw Error('Indexed query fell back: '+JSON.stringify(result.notices));if(result.incomplete.length)throw Error(result.incomplete.join(' '));
      const files=new Map(root.inspection.files.map(f=>[f.id,f]));
      const normalized=result.matches.map(m=>({document:m.document,page:m.page,count:m.count,guid:m.indexed?.guid??(m.path.length?owners.get(m.path[0].fileId)?.get(m.page):fullTextPageOwners(root).get(m.page))??'',route:m.path.map(p=>{const file=files.get(p.fileId);return {keys:file.origins.filter(o=>o.kind==='name-tree').map(o=>o.nameTreeKey.value).sort(),md5:file.streams.find(s=>s.locator.path===p.streamPath).declaredMd5.value};})}));
      const fingerprint=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(JSON.stringify(normalized)))),b=>b.toString(16).padStart(2,'0')).join('');
      results.push({elapsedMs,firstResultMs:result.matches.length?elapsedMs:null,pages:result.matches.length,matches:result.matches.reduce((n,m)=>n+m.count,0),opened:opened-prior,source:result.source,fingerprint});
     }
     return {openingMs,indexLoadingMs,results};
    }finally{session.close();}
   },{query,mode});
   clearInterval(timer);timer=undefined;while(sampling)await new Promise(r=>setTimeout(r,10));await sample();
   const report={count,mode,query,kind,bytes:(await stat(filename)).size,...result,peakMainHeapBytes:peakHeap,peakBrowserPssKiB:peakPssKiB,memorySamples:samples};
   if(mode==='indexed')assert.ok(result.results.every(r=>r.opened===0&&r.source==='indexed'));
   pair.push(report);reports.push(report);console.log(JSON.stringify(report));
  }finally{clearInterval(timer);await context.close();}
 }
 if(pair.length===2)for(let i=0;i<2;i++)assert.equal(pair[0].results[i].fingerprint,pair[1].results[i].fingerprint,'All destinations, messages and counts must agree');
 await mkdir('test-results',{recursive:true});await writeFile(`test-results/fulltext-benchmark-${kind}${modes.length===1?'-'+modes[0]:''}.json`,JSON.stringify({scope:'One instrumented trial per mode on repeated public email; two actual engine executions, second case refinement; 30-minute benchmark-only scan budget (UI remains 120 seconds); Linux process PSS and main JS heap sampled every second.',reports},null,2)+'\n');
}
