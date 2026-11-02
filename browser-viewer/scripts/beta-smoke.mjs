import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
const hash=b=>createHash('sha256').update(b).digest('hex');
const extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{channel:'chromium'}),args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
const results=[];
try {
 const requests=[],errors=[];context.on('request',r=>{if(/^https?:/.test(r.url()))requests.push(r.url());});await context.setOffline(true);
 const service=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker');const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
 await page.goto(`chrome-extension://${new URL(service.url()).host}/viewer.html`);
 await page.waitForFunction(()=>!document.querySelector('#file').disabled);
 const ordinary=(await readFile('tests/fixtures/ordinary.pdf')).toString('base64');
 const nested=(await readFile('tests/inspection/controls/c-nested-mixed.pdf')).toString('base64');
 const attachments=(await readFile('tests/attachments/viewing.pdf')).toString('base64');
 for(const [name,options,data] of [['decoded-byte-limit',{maxDecodedBytes:0},attachments],['child-depth-limit',{maxChildDepth:0},nested],['child-count-limit',{maxChildren:1},nested]]){
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
  try{const doc=await denied.open(blob(attachments),'viewing.pdf');zero=await rejected(()=>denied.bytes(doc,...choose(doc,'plain.txt')),/decoded-byte limit/);}finally{denied.close();}
  const first=new BrowserSession();let textSize=0;
  try{const doc=await first.open(blob(attachments),'viewing.pdf');textSize=(await first.bytes(doc,...choose(doc,'plain.txt'))).length;}finally{first.close();}
  const aggregate=new BrowserSession({maxDecodedBytes:textSize});let repeated=false;
  try{const doc=await aggregate.open(blob(attachments),'viewing.pdf');await aggregate.bytes(doc,...choose(doc,'plain.txt'));repeated=(await aggregate.bytes(doc,...choose(doc,'plain.txt'))).length===textSize;
    const other=doc.inspection.files.find(f=>(f.unicodeFilename.value??f.filename.value)!=='plain.txt'&&f.streams.length);
    if(other)repeated=repeated&&await rejected(()=>aggregate.bytes(doc,other,other.streams[0]),/decoded-byte limit/);}finally{aggregate.close();}
  const count=new BrowserSession({maxChildren:1});let countBound=false,cached=false;
  try{
   const doc=await count.open(blob(nested),'nested.pdf');
   const files=doc.inspection.files.flatMap(file=>file.streams.filter(s=>s.mimeType.value==='application/pdf').map(stream=>[file,stream]));
   if(files.length<2)throw new Error('Expected two collection members');
   const opened=await count.child(doc,...files[0]);cached=opened===await count.child(doc,...files[0]);
   countBound=await rejected(()=>count.child(doc,...files[1]),/count limit/);
  }finally{count.close();}
  const depth=new BrowserSession({maxChildDepth:0});let depthBound=false;
  try{const doc=await depth.open(blob(nested),'nested.pdf');const file=doc.inspection.files.find(f=>f.streams.some(s=>s.mimeType.value==='application/pdf'));depthBound=await rejected(()=>depth.child(doc,file,file.streams[0]),/depth limit/);}finally{depth.close();}
  const input=new BrowserSession({maxFileBytes:1});let inputBound=false;
  try{inputBound=await rejected(()=>input.open(blob(ordinary),'ordinary.pdf'),/input byte limit/);}finally{input.close();}
  return {zero,repeated,countBound,cached,depthBound,inputBound};
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
 const bfo=JSON.parse(await readFile('tests/attachments/bfo.json','utf8')),bytes=await readFile(bfo.sourcePath);assert.equal(hash(bytes),bfo.sourceSha256);
 const actual=await page.evaluate(async base64=>{
  const {InspectionSession}=await import('./inspection-session.js');const {sha256}=await import('./domain/hashes.js');const s=new InspectionSession();
  try{const d=await s.open(Uint8Array.from(atob(base64),c=>c.charCodeAt(0))),m=d.inspection.messages[0];return {pages:d.pdf.numPages,messages:d.inspection.messages.length,profile:d.inspection.identification.declaredProfile.value,guid:await sha256(new TextEncoder().encode(m?.mailGuid.value??'')),subject:await sha256(new TextEncoder().encode(d.details.get(m?.metadataRecordId)?.Subject??'')),files:d.inspection.files.length,validation:d.inspection.identification.validationStatus};}finally{s.close();}
 },bytes.toString('base64'));
 assert.equal(actual.pages,bfo.pages);assert.equal(actual.messages,1);assert.equal(actual.profile,bfo.profile);assert.equal(actual.guid,bfo.mailGuidSha256);assert.equal(actual.subject,bfo.subjectSha256);assert.equal(actual.validation,'not-evaluated');
 await page.locator('#file').setInputFiles(resolve(bfo.sourcePath));await page.waitForFunction(()=>!document.querySelector('#canvas').hidden&&document.querySelector('#viewport').getAttribute('aria-busy')==='false');
 assert.equal(await page.locator('[data-message-id]').count(),1);await page.locator('[data-message-id]').click();await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false');
 assert.equal(await page.locator('#metadata').count(),0);
 assert.equal(await page.locator('#canvas').isVisible(),true);
 results.push({name:'third-party-bfo',status:'PASS',sourceSha256:bfo.sourceSha256,pages:actual.pages,messages:actual.messages,files:actual.files,limit:'No BodyRendering DPart in supplied file; document information and manual pages available, no guessed body destination.'});
 // All DTD/HTML strings remain data, including under the extension origin.
 const hostile=await page.evaluate(async()=>{
  const {parseXmp,NS}=await import('./domain/xmp.js');
  const x='<!DOCTYPE x [<!ENTITY leak SYSTEM "https://example.invalid/private">]><rdf:RDF xmlns:rdf="'+NS.rdf+'"><rdf:Description>&leak;</rdf:Description></rdf:RDF>';
  return parseXmp(x,{documentId:'test',objectNumber:null,generation:null,path:'metadata'},s=>new DOMParser().parseFromString(s,'application/xml')).state;
 });assert.equal(hostile,'unsupported');results.push({name:'xmp-external-entity-rejected',status:'PASS'});
 const deep=await page.evaluate(async()=>{
  const {parseXmp}=await import('./domain/xmp.js');
  return parseXmp('<x>'.repeat(66)+'</x>'.repeat(66),{documentId:'test',objectNumber:null,generation:null,path:'metadata'},s=>new DOMParser().parseFromString(s,'application/xml')).state;
 });assert.equal(deep,'unavailable');results.push({name:'xmp-depth-limited',status:'PASS'});

 // A link only presents a button; navigating to the extension never fetches it.
 const viewerUrl=page.url().split('#')[0];
 for(const source of ['https://example.invalid/file.pdf','file:///tmp/example.pdf']){
  await page.goto('about:blank');await page.goto(viewerUrl+'#source='+encodeURIComponent(source));await page.waitForFunction(()=>!document.querySelector('#file').disabled);
  assert.equal(await page.locator('#linked-file').isVisible(),true);
  assert.equal(await page.locator('#open-linked').isVisible(),source.startsWith('https:'));
 }
 results.push({name:'linked-opening-needs-explicit-action',status:'PASS'});
 assert.deepEqual(requests,[]);assert.deepEqual(errors,[]);
 await mkdir('test-results',{recursive:true});await writeFile('test-results/beta-hardening.json',JSON.stringify({status:'PASS',browser:context.browser()?.version(),results,httpRequests:0,pageErrors:0},null,2)+'\n');
 console.log(`PASS: ${results.length} resource, cancellation and third-party checks; offline.`);
}finally{await context.close();}
