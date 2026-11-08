// Compare the actual indexed and PDF.js paths on one diverse, generated archive.
// Inputs/output are explicit so real/private mail never becomes a tracked fixture.
import {chromium} from 'playwright';
import {readFile,writeFile,mkdir,stat} from 'node:fs/promises';
import {resolve,dirname} from 'node:path';
import assert from 'node:assert/strict';
const [filename,output]=process.argv.slice(2);if(!filename||!output)throw Error('Usage: node scripts/diverse-index-benchmark.mjs input.pdf /tmp/report.json');
const extension=resolve(process.env.EAPDF_BENCH_EXTENSION??'dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{channel:'chromium'}),args:['--enable-precise-memory-info',`--load-extension=${extension}`,`--disable-extensions-except=${extension}`]});
let timer;const memory=[];
try{
 await context.setOffline(true);const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage();
 await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
 await page.evaluate(()=>{const input=document.createElement('input');input.type='file';input.id='benchmark';document.body.append(input);});
 await page.locator('#benchmark').setInputFiles(resolve(filename));
 const browserCdp=await context.browser().newBrowserCDPSession(),cdp=await context.newCDPSession(page);let sampling=false;
 const sample=async()=>{if(sampling)return;sampling=true;try{const heap=(await cdp.send('Runtime.getHeapUsage')).usedSize,{processInfo}=await browserCdp.send('SystemInfo.getProcessInfo');let pss=0;for(const p of processInfo)try{pss+=Number(/^Pss:\s+(\d+)/m.exec(await readFile(`/proc/${p.id}/smaps_rollup`,'utf8'))?.[1]??0);}catch{}memory.push({heap,pss});}finally{sampling=false;}};
 timer=setInterval(()=>void sample().catch(()=>{}),500);await sample();
 const results=await page.evaluate(async()=>{
  const {BrowserSession}=await import('./browser-session.js'),{searchCollection}=await import('./collection-search.js'),{readFullTextIndex}=await import('./fulltext-index.js');
  const session=new BrowserSession({},{reuseWorker:true}),child=session.child.bind(session);let opened=0;
  session.child=async(...args)=>{opened++;return child(...args);};
  try{
   let start=performance.now();const root=await session.open(document.querySelector('#benchmark').files[0],'diverse.pdf'),openingMs=performance.now()-start;
   start=performance.now();const loaded=await readFullTextIndex(session,root,()=>{}),indexLoadingMs=performance.now()-start;
   if(!loaded.index)throw Error(loaded.diagnostic);
   const index=loaded.index,frequency=new Map();
   for(const text of index.texts)for(const term of new Set(text.split(' ')))if(/^[a-z]{5,18}$/.test(term))frequency.set(term,(frequency.get(term)??0)+1);
   const terms=[...frequency].sort((a,b)=>b[1]-a[1]||a[0].localeCompare(b[0])),common=terms[0]?.[0],rare=terms.find(([,count])=>count===1)?.[0];
   const phrase=index.texts.find(t=>/[a-z]{5,} [a-z]{5,}/.test(t))?.match(/[a-z]{5,} [a-z]{5,}/)?.[0];
   if(!common||!rare||!phrase)throw Error('Corpus lacks required query diversity');
   const queries=[{kind:'common',query:common},{kind:'rare',query:rare},{kind:'phrase',query:phrase}],reports=[];
   const signature=async matches=>{
    const stable=matches.map(m=>({path:m.path,page:m.page,count:m.count}));
    return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(JSON.stringify(stable)))),b=>b.toString(16).padStart(2,'0')).join('');
   };
   for(const {kind,query} of queries){
    const measured=[];
    for(const mode of ['indexed','indexed-repeat','scanned']){
     const before=opened;start=performance.now();const result=await searchCollection(session,root,query,()=>{},()=>{},mode==='scanned',1800000),elapsedMs=performance.now()-start;
     if(result.incomplete.length)throw Error(result.incomplete.join(' '));
     if(mode!=='scanned'&&(result.source!=='indexed'||opened!==before))throw Error('Fast path opened child or fell back');
     measured.push({mode,elapsedMs,childOpens:opened-before,pages:result.matches.length,matches:result.matches.reduce((n,m)=>n+m.count,0),fingerprint:await signature(result.matches)});
    }
    reports.push({kind,query,measured});
   }
   return {openingMs,indexLoadingMs,documents:index.documents.length,pages:index.pages.length,uniquePageTexts:index.texts.length,terms:index.terms.size,indexBase64Characters:root.fullTextIndex.length,reports};
  }finally{session.close();}
 });
 clearInterval(timer);timer=undefined;while(sampling)await new Promise(r=>setTimeout(r,10));await sample();
 for(const q of results.reports)for(const r of q.measured)assert.equal(r.fingerprint,q.measured[0].fingerprint,'Destinations and per-page counts differ: '+q.query);
 const report={scope:'One instrumented trial; common, rare and phrase selected deterministically from actual indexed text. Same PDF and session; scan bypasses index. Each path returns its complete result set, so first-result latency equals complete-query latency. PSS samples include browser processes; 30-minute benchmark scan budget.',inputBytes:(await stat(filename)).size,...results,peakMainHeapBytes:Math.max(...memory.map(m=>m.heap)),peakBrowserPssKiB:Math.max(...memory.map(m=>m.pss)),memorySamples:memory.length};
 await mkdir(dirname(resolve(output)),{recursive:true});await writeFile(output,JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
}finally{clearInterval(timer);await context.close();}
