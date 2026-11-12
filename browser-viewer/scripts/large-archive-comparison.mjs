// Development experiment: fresh browser/one PDF worker per case and variant.
import {chromium} from 'playwright';
import {createServer} from 'node:http';
import {createServer as netServer} from 'node:net';
import {spawn} from 'node:child_process';
import {readFile,writeFile,rename} from 'node:fs/promises';
import {resolve,join,extname} from 'node:path';
import {tmpdir} from 'node:os';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
const root=fileURLToPath(new URL('../',import.meta.url));
const work=resolve(process.env.PDFJS_COMPARISON_WORK??join(tmpdir(),'eapdf-pdfjs-comparison'));
const cases=JSON.parse(await readFile(join(work,'corpus.json'),'utf8')).filter(c=>process.env.PDFJS_COMPARISON_CASES?process.env.PDFJS_COMPARISON_CASES.split(',').includes(c.name):!c.diagnosticOnly);
const variants=(process.env.PDFJS_COMPARISON_VARIANTS??'stock,control,full,no-compact,no-parsed-cache,no-reclaim,no-page-scan').split(',');
const trials=Number(process.env.PDFJS_COMPARISON_TRIALS??5),engines=(process.env.PDFJS_COMPARISON_BROWSERS??'chromium,firefox').split(',');
const timeout=90_000,delay=ms=>new Promise(r=>setTimeout(r,ms));
const sha=b=>createHash('sha256').update(b).digest('hex');
for(const c of cases)assert.equal(sha(await readFile(c.path)),c.sha256,'Corpus changed: '+c.name);
const results=[];
const server=createServer(async(req,res)=>{try{
 const p=new URL(req.url,'http://localhost').pathname;
 if(p==='/'){res.setHeader('Content-Type','text/html');res.end('<!doctype html><title>PDF.js comparison</title>');return;}
 let path;
 if(p.startsWith('/case/'))path=cases.find(c=>c.name===decodeURIComponent(p.slice(6)))?.path;
 else if(p.startsWith('/impl/')){const [, ,variant,file]=p.split('/');assert.ok(variants.includes(variant)&&['pdf.mjs','pdf.worker.mjs'].includes(file));path=variant==='stock'?join(root,'node_modules/pdfjs-dist/build',file):join(work,'variants',variant,file);}
 else if(p.startsWith('/assets/')){path=resolve(root,'node_modules/pdfjs-dist',p.slice(8));assert.ok(path.startsWith(join(root,'node_modules/pdfjs-dist')+'/'));}
 if(!path)throw Error('Unavailable');res.setHeader('Content-Type',extname(path)==='.mjs'?'text/javascript':'application/octet-stream');res.end(await readFile(path));
 }catch{res.writeHead(404);res.end();}});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const url=`http://127.0.0.1:${server.address().port}`;
async function workload({variant,name,repetitions=2,attachmentBytes=false}){
 const hash=async b=>Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',b)),n=>n.toString(16).padStart(2,'0')).join('');
 const normalize=v=>Array.isArray(v)?v.map(normalize):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).filter(k=>k!=='documentId').sort().map(k=>[k,normalize(v[k])])):v;
 const digest=async v=>hash(new TextEncoder().encode(JSON.stringify(normalize(v))));
 const api=await import('/impl/'+variant+'/pdf.mjs');api.GlobalWorkerOptions.workerSrc='/impl/'+variant+'/pdf.worker.mjs';
 const bytes=await (await fetch('/case/'+name)).arrayBuffer();
 const start=performance.now(),task=api.getDocument({data:new Uint8Array(bytes),isEvalSupported:false,standardFontDataUrl:'/assets/standard_fonts/',cMapUrl:'/assets/cmaps/',cMapPacked:true,wasmUrl:'/assets/wasm/'});
 let doc,stage='open',completedRounds=0;
 try{doc=await task.promise;const openMs=performance.now()-start;const rounds=[];
 for(let round=0;round<repetitions;round++){
  stage='standard API';const began=performance.now(),pages=[];
  const metadata=await doc.getMetadata(),attachments=await doc.getAttachments(),embedded=[];
  // PDF.js 6.5 returns lazy Map entries. The performance matrix requests
  // listing only; the separate byte comparison explicitly retrieves content.
  if(attachmentBytes){
   if(attachments!==null&&typeof attachments?.entries!=='function')throw Error('Unexpected attachment-list API type');
   for(const [name,file]of attachments?.entries()??[]){
    const data=await doc.getAttachmentContent(name);
    if(!ArrayBuffer.isView(data)||data.BYTES_PER_ELEMENT!==1)throw Error('Named attachment content unavailable: '+name);
    embedded.push({name,filename:file.filename,bytes:data.length,hash:await hash(data)});
   }
  }
  embedded.sort((a,b)=>a.name.localeCompare(b.name));
  let firstRenderMs;
  for(const number of [...new Set([1,Math.ceil(doc.numPages/2),doc.numPages])]){
   const page=await doc.getPage(number),text=(await page.getTextContent()).items.map(i=>i.str??'').join(' '),viewport=page.getViewport({scale:1});
   const canvas=document.createElement('canvas');canvas.width=viewport.width;canvas.height=viewport.height;
   await page.render({canvas,viewport}).promise;if(firstRenderMs===undefined)firstRenderMs=performance.now()-began;
   const pixels=canvas.getContext('2d').getImageData(0,0,canvas.width,canvas.height).data;
   pages.push({number,textHash:await hash(new TextEncoder().encode(text)),pixelHash:await hash(pixels),width:canvas.width,height:canvas.height});page.cleanup();canvas.width=canvas.height=0;
  }
  const standardMs=performance.now()-began;
  const common={pages:doc.numPages,metadata:await digest({info:metadata.info,xmp:metadata.metadata?.getRaw()??null}),embedded,pagesSelected:pages};
  let custom=null,inspectionMs=0;
  if(variant!=='stock'){
   stage='custom inspection/extraction';
   const inspecting=performance.now(),assets=await doc.getDocumentAssets(),parts=await doc.getDocumentParts(),payloads=[];
   for(const file of assets.files)for(const stream of file.streams){
    const data=await doc.getAssociatedFileBytes({documentId:assets.documentId,fileId:file.locator.path,streamId:stream.locator.path,maxBytes:512*1024**2});
    payloads.push({file:file.locator.path,stream:stream.locator.path,bytes:data.length,sha256:await hash(data)});
   }
   custom={inspectionHash:await digest({assets,parts}),files:assets.files.length,metadata:assets.metadata.length,parts:parts.parts.length,folders:assets.collection?.folders?.length??0,diagnostics:assets.diagnostics,payloads};
   inspectionMs=performance.now()-inspecting;
  }
  rounds.push({round,standardMs,inspectionMs,firstRenderMs,totalMs:performance.now()-began,common,custom});
  completedRounds=round+1;await doc.cleanup();
 }
 window.comparisonTask=task;
 return {openMs,rounds,totalMs:performance.now()-start};
 }catch(error){await task.destroy().catch(()=>{});return {failure:{name:error?.name,message:error?.message,details:error?.details,stack:error?.stack,stage,completedRounds}};}
}
async function processPss(pids){let sum=0;for(const pid of pids)try{sum+=Number(/^Pss:\s+(\d+)/m.exec(await readFile(`/proc/${pid}/smaps_rollup`,'utf8'))?.[1]??0);}catch{}return sum;}
async function descendants(pid,seen=new Set()){
 if(seen.has(pid))return seen;seen.add(pid);
 try{for(const c of (await readFile(`/proc/${pid}/task/${pid}/children`,'utf8')).trim().split(/\s+/).filter(Boolean))await descendants(Number(c),seen);}catch{}return seen;
}
async function firefox(){
 const probe=netServer();await new Promise(r=>probe.listen(0,'127.0.0.1',r));const port=probe.address().port;await new Promise(r=>probe.close(r));
 const driver=spawn(process.env.GECKODRIVER_PATH??'geckodriver',['--host','127.0.0.1','--port',String(port),'--log','fatal'],{stdio:'ignore'});let launchError;driver.on('error',e=>{launchError=e;});
 const endpoint=`http://127.0.0.1:${port}`;
 const request=async(path,body,method='POST')=>{const res=await fetch(endpoint+path,{method,headers:{'content-type':'application/json'},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(timeout+10_000)});const {value}=await res.json();if(!res.ok)throw Error(JSON.stringify(value));return value;};
 let session;
 try{
  const deadline=Date.now()+15000;
  while(true){try{await request('/status',undefined,'GET');break;}catch(e){if(launchError||Date.now()>deadline)throw launchError??e;await delay(100);}}
  const created=await request('/session',{capabilities:{alwaysMatch:{browserName:'firefox','moz:firefoxOptions':{binary:process.env.FIREFOX_PATH??'/usr/lib/firefox/firefox',args:['-headless'],prefs:{'pdfjs.disabled':true}}}}});session=created.sessionId;
  const call=(path,body,method)=>request('/session/'+session+path,body,method);
  await call('/timeouts',{script:timeout,pageLoad:timeout});await call('/url',{url});
  return {version:created.capabilities.browserVersion,pss:async()=>processPss(await descendants(created.capabilities['moz:processID'])),evaluate:async(fn,arg)=>call('/execute/async',{script:'const done=arguments[arguments.length-1];('+fn.toString()+')(arguments[0]).then(value=>done({ok:true,value}),error=>done({ok:false,error:String(error?.stack??error)}));',args:[arg??null]}).then(r=>{if(!r.ok)throw Error(r.error);return r.value;}),close:async()=>{try{await call('',undefined,'DELETE');}finally{driver.kill();}}};
 }catch(e){if(session)await request('/session/'+session,undefined,'DELETE').catch(()=>{});driver.kill();throw e;}
}
async function chrome(){
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:['--enable-precise-memory-info','--js-flags=--max-old-space-size=512']});
 const page=await browser.newPage();await page.goto(url);const cdp=await browser.newBrowserCDPSession();
 return {version:browser.version(),pss:async()=>processPss((await cdp.send('SystemInfo.getProcessInfo')).processInfo.map(p=>p.id)),evaluate:(fn,arg)=>page.evaluate(fn,arg),close:()=>browser.close()};
}
try{
 for(const engine of engines)for(let trial=0;trial<trials;trial++)for(const c of cases)for(const variant of trial%2?[...variants].reverse():variants){
  const row={engine,trial,case:c.name,variant,status:'RUNNING'},started=Date.now();let browser,timer,sampling=false,peak=0,samples=0;
  try{
   browser=await(engine==='chromium'?chrome():firefox());row.browser=browser.version;row.baselinePssKiB=await browser.pss();peak=row.baselinePssKiB;
   let budgetReject;const budget=new Promise((_,reject)=>{budgetReject=reject;});
   const sample=async()=>{if(sampling)return;sampling=true;try{peak=Math.max(peak,await browser.pss());samples++;if(peak>1024*1024)budgetReject(Error('External browser PSS limit exceeded (1024 MiB)'));}finally{sampling=false;}};
   timer=setInterval(()=>void sample().catch(()=>{}),100);
   let timeoutId;
   try{row.result=await Promise.race([browser.evaluate(workload,{variant,name:c.name,repetitions:Number(process.env.PDFJS_COMPARISON_ROUNDS??2),attachmentBytes:process.env.PDFJS_COMPARISON_ATTACHMENT_BYTES==='1'}),budget,new Promise((_,reject)=>{timeoutId=setTimeout(()=>reject(Error('External workload timeout')),timeout);})]);}finally{clearTimeout(timeoutId);}
   if(row.result.failure)throw Error(JSON.stringify(row.result.failure));
   await delay(150);row.retainedDocumentPssKiB=await browser.pss();
   await browser.evaluate(async()=>{await window.comparisonTask?.destroy();window.comparisonTask=undefined;});
   await delay(150);row.afterDestroyPssKiB=await browser.pss();row.status='PASS';
  }catch(error){row.status='FAIL';row.error=String(error?.stack??error);}
  finally{clearInterval(timer);while(sampling)await delay(10);row.peakBrowserPssKiB=peak;row.samples=samples;row.wallMs=Date.now()-started;await browser?.close().catch(()=>{});}
  results.push(row);const output=join(work,process.env.PDFJS_COMPARISON_RESULTS??'measurements.json');await writeFile(output+'.partial',JSON.stringify({protocol:JSON.parse(await readFile(join(work,process.env.PDFJS_COMPARISON_PROTOCOL??'protocol.json'),'utf8')),execution:{trials,variants,cases:cases.map(c=>c.name),engines,repetitions:Number(process.env.PDFJS_COMPARISON_ROUNDS??2),attachmentBytes:process.env.PDFJS_COMPARISON_ATTACHMENT_BYTES==='1'},results},null,2)+'\n');await rename(output+'.partial',output);
  console.log(JSON.stringify({engine,trial,case:c.name,variant,status:row.status,milliseconds:row.result?.totalMs,pssKiB:peak,error:row.error?.slice(0,180)}));
 }
}finally{await new Promise(r=>server.close(r));}
