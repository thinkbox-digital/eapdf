import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
const extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{channel:'chromium'}),args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
const results=[];
try {
 const requests=[],errors=[];context.on('request',r=>{if(/^https?:/.test(r.url()))requests.push(r.url());});await context.setOffline(true);
 const service=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker');const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
 await page.goto(`chrome-extension://${new URL(service.url()).host}/viewer.html`);
 await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false');
 const ordinary=(await readFile('tests/fixtures/ordinary.pdf')).toString('base64');
 const nested=(await readFile('tests/inspection/controls/c-nested-mixed.pdf')).toString('base64');
 const attachments=(await readFile('tests/attachments/viewing.pdf')).toString('base64');
 for(const [name,options,data] of [['decoded-byte-limit',{maxDecodedBytes:0},attachments]]){
  const result=await page.evaluate(async({options,data})=>{
   const {InspectionSession}=await import('./inspection-session.js');const s=new InspectionSession(options);
   try {const d=await s.open(Uint8Array.from(atob(data),c=>c.charCodeAt(0)));return {payloads:[...d.payloads.values()].reduce((n,b)=>n+b.length,0),unavailable:d.inspection.files.flatMap(f=>f.streams).filter(s=>s.extraction==='unavailable').length,children:d.inspection.collection.value?.children.map(c=>c.state)??[]};}finally{s.close();}
  },{options,data});
  if(name==='decoded-byte-limit'){assert.equal(result.payloads,0);assert.ok(result.unavailable>0);}else assert.ok(result.children.includes('unavailable'));
  results.push({name,status:'PASS'});
 }
 const production=await page.evaluate(async({attachments,nested,ordinary})=>{
  const {BrowserSession}=await import('./browser-session.js');
  const blob=value=>new Blob([Uint8Array.from(atob(value),c=>c.charCodeAt(0))]);
  const rejected=async(action,pattern)=>{try{await action();return false;}catch(error){return pattern.test(String(error));}};
  const choose=(doc,name)=>{
   const file=doc.inspection.files.find(f=>(f.unicodeFilename.value??f.filename.value)===name);
   if(!file?.streams[0])throw new Error(`Missing test attachment ${name}`);
   return [file,file.streams[0]];
  };
  const denied=new BrowserSession({maxDecodedBytes:0});let zero=false;
  try{const doc=await denied.open(blob(attachments),'viewing.pdf');zero=await rejected(()=>denied.bytes(doc,...choose(doc,'plain.txt')),/decoded-byte budget/);}finally{denied.close();}
  const first=new BrowserSession();let textSize=0;
  try{const doc=await first.open(blob(attachments),'viewing.pdf');textSize=(await first.bytes(doc,...choose(doc,'plain.txt'))).length;}finally{first.close();}
  const aggregate=new BrowserSession({maxDecodedBytes:textSize});let repeated=false;
  try{const doc=await aggregate.open(blob(attachments),'viewing.pdf');await aggregate.bytes(doc,...choose(doc,'plain.txt'));repeated=(await aggregate.bytes(doc,...choose(doc,'plain.txt'))).length===textSize;
    const other=doc.inspection.files.find(f=>(f.unicodeFilename.value??f.filename.value)!=='plain.txt'&&f.streams.length);
    if(other)try{await aggregate.bytes(doc,other,other.streams[0]);}catch(error){repeated=repeated&&/byte limit|byte budget/.test(String(error));}}finally{aggregate.close();}
  const count=new BrowserSession({maxChildren:1});let countComplete=false,cached=false;
  try{
   const doc=await count.open(blob(nested),'nested.pdf');
   const files=doc.inspection.files.flatMap(file=>file.streams.filter(s=>s.mimeType.value==='application/pdf').map(stream=>[file,stream]));
   if(files.length<2)throw new Error('Expected two collection members');
   const opened=await count.child(doc,...files[0]);cached=opened===await count.child(doc,...files[0]);
   countComplete=!!(await count.child(doc,...files[1]));
  }finally{count.close();}
  const depth=new BrowserSession({maxChildDepth:0});let depthComplete=false;
  try{const doc=await depth.open(blob(nested),'nested.pdf');const file=doc.inspection.files.find(f=>f.streams.some(s=>s.mimeType.value==='application/pdf'));depthComplete=!!(await depth.child(doc,file,file.streams[0]));}finally{depth.close();}
  const input=new BrowserSession({maxFileBytes:1});let inputBound=false;
  try{inputBound=await rejected(()=>input.open(blob(ordinary),'ordinary.pdf'),/input byte limit/);}finally{input.close();}
  // Exercise the real size gate without allocating a multi-gigabyte buffer.
  const largeInput=new BrowserSession();let limitDefault=false,limitEdge=false,limitAbove=false;
  try{
   const cap=4*1024**3;limitDefault=largeInput.limits.maxFileBytes===cap;
   let read=false;
   const sized=size=>({size,arrayBuffer:async()=>{read=true;throw new Error('size-gate-accepted');}});
   limitEdge=await rejected(()=>largeInput.open(sized(cap),'at-limit.pdf'),/size-gate-accepted/)&&read;
   read=false;
   limitAbove=await rejected(()=>largeInput.open(sized(cap+1),'above-limit.pdf'),/input byte limit/)&&!read;
  }finally{largeInput.close();}
  return {zero,repeated,countComplete,cached,depthComplete,inputBound,limitDefault,limitEdge,limitAbove};
 },{attachments,nested,ordinary});
 for(const [name,passed] of Object.entries(production)){assert.equal(passed,true,name);results.push({name:'production-session-'+name,status:'PASS'});}
 const cancellation=await page.evaluate(async base64=>{
  const {loadManagedPdf}=await import('./pdfjs-adapter.js');const {InspectionSession}=await import('./inspection-session.js');const {BrowserSession}=await import('./browser-session.js');
  const bytes=()=>Uint8Array.from(atob(base64),c=>c.charCodeAt(0));
  const managed=loadManagedPdf(bytes());await managed.run(managed.task.promise);
  const start=performance.now(),pending=managed.run(new Promise(()=>{}));managed.close();
  let error='';try{await pending;}catch(e){error=String(e);}
  const session=new InspectionSession();const opening=session.open(bytes());session.close();let rejected=false;try{await opening;}catch{rejected=true;}
  const interactive=new BrowserSession();const openingBlob=interactive.open(new Blob([bytes()]),'cancelled.pdf');interactive.close();let blobRejected=false;try{await openingBlob;}catch{blobRejected=true;}
  return {elapsed:performance.now()-start,error,rejected,blobRejected};
 },ordinary);
 assert.match(cancellation.error,/cancelled/);assert.ok(cancellation.elapsed<2000);assert.equal(cancellation.rejected,true);assert.equal(cancellation.blobRejected,true);
 results.push({name:'cancel-pending-worker-and-hash',status:'PASS'});
 // The existing dedicated-worker timeout must reject and leave the next load usable.
 const timeout=await page.evaluate(async base64=>{
  const {loadManagedPdf}=await import('./pdfjs-adapter.js');const m=loadManagedPdf(Uint8Array.from(atob(base64),c=>c.charCodeAt(0)));
  try{await m.run(m.task.promise);await m.run(new Promise(()=>{}),10);return false;}catch(e){return String(e).includes('timed out');}finally{m.close();}
 },ordinary);assert.equal(timeout,true);results.push({name:'worker-timeout',status:'PASS'});
 // All DTD/HTML strings remain data, including under the extension origin.
 const hostile=await page.evaluate(async()=>{
  const {parseXmp,NS}=await import('./domain/xmp.js');
  const x='<!DOCTYPE x [<!ENTITY leak SYSTEM "https://example.invalid/private">]><rdf:RDF xmlns:rdf="'+NS.rdf+'"><rdf:Description>&leak;</rdf:Description></rdf:RDF>';
  return parseXmp(x,{documentId:'test',objectNumber:null,generation:null,path:'metadata'},s=>new DOMParser().parseFromString(s,'application/xml')).state;
 });assert.equal(hostile,'unsupported');results.push({name:'xmp-external-entity-rejected',status:'PASS'});
 const deep=await page.evaluate(async()=>{
  const {parseXmp}=await import('./domain/xmp.js');
  return parseXmp('<x>'.repeat(66)+'<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description/></rdf:RDF>'+'</x>'.repeat(66),{documentId:'test',objectNumber:null,generation:null,path:'metadata'},s=>new DOMParser().parseFromString(s,'application/xml')).state;
 });assert.equal(deep,'present');results.push({name:'xmp-depth-complete',status:'PASS'});
 const records=await page.evaluate(async()=>{
  const {parseXmp,NS}=await import('./domain/xmp.js');
  const text='<rdf:RDF xmlns:rdf="'+NS.rdf+'" xmlns:e="'+NS.meta+'"><rdf:Description><e:email><rdf:Bag>'+Array.from({length:10001},(_,i)=>'<rdf:li><e:Mail_GUID>'+i+'</e:Mail_GUID></rdf:li>').join('')+'</rdf:Bag></e:email></rdf:Description></rdf:RDF>';
  const result=parseXmp(text,{documentId:'test',objectNumber:null,generation:null,path:'metadata'},s=>new DOMParser().parseFromString(s,'application/xml'));
  return {state:result.state,count:result.records.length};
 });assert.equal(records.state,'present');assert.equal(records.count,10001);results.push({name:'xmp-records-complete',status:'PASS'});

 // A link only presents a button; navigating to the extension never fetches it.
 const viewerUrl=page.url().split('#')[0];
 for(const source of ['https://example.invalid/file.pdf','file:///tmp/example.pdf']){
  await page.goto('about:blank');await page.goto(viewerUrl+'#source='+encodeURIComponent(source));await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false');
  assert.equal(await page.locator('#linked-file').isVisible(),true);
  assert.equal(await page.locator('#open-linked').isVisible(),source.startsWith('https:'));
 }
 results.push({name:'linked-opening-needs-explicit-action',status:'PASS'});
 assert.deepEqual(requests,[]);assert.deepEqual(errors,[]);
 await mkdir('test-results',{recursive:true});await writeFile('test-results/beta-hardening.json',JSON.stringify({status:'PASS',browser:context.browser()?.version(),results,httpRequests:0,pageErrors:0},null,2)+'\n');
 console.log(`PASS: ${results.length} resource, cancellation and security checks; offline.`);
}finally{await context.close();}
