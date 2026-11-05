import {chromium} from 'playwright';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
const extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
try{
 const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker');const page=await context.newPage();await context.setOffline(true);
 await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
 await page.evaluate(()=>{const input=document.createElement('input');input.type='file';input.id='fixture';document.body.append(input);});
 await page.locator('#fixture').setInputFiles(resolve('../examples/pst/pst_quality-attached-message_1c.pdf'));
 const result=await page.evaluate(async()=>{
  const {BrowserSession}=await import('./browser-session.js');const blob=document.querySelector('#fixture').files[0];
  const sessions=[];const open=async()=>{const session=new BrowserSession();sessions.push(session);const doc=await session.open(blob,'fixture.pdf');return {session,doc};};
  const target=doc=>{const file=doc.inspection.files.find(f=>f.streams.some(s=>s.mimeType.value==='application/pdf'));return [file,file.streams.find(s=>s.mimeType.value==='application/pdf')];};
  const digest=async bytes=>Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),n=>n.toString(16).padStart(2,'0')).join('');
  try{
   const reference=await open(),expected=await digest(await reference.session.bytes(reference.doc,...target(reference.doc)));reference.session.close();
   const recovering=await open(),originalPdf=recovering.doc.pdf;
   recovering.doc.pdf.getAssociatedFileBytes=async()=>{throw Error('Worker decoded-stream allocation limit exceeded');};
   // Concurrent callers must share the restart rather than close each other's workers.
   const recovered=await Promise.all([recovering.session.bytes(recovering.doc,...target(recovering.doc)),recovering.session.bytes(recovering.doc,...target(recovering.doc))]);
   const hashes=await Promise.all(recovered.map(digest));const replaced=recovering.doc.pdf!==originalPdf;recovering.session.close();
   const persistent=await open();let attempts=0;const ensure=persistent.session.ensure.bind(persistent.session);
   persistent.session.ensure=async doc=>{await ensure(doc);doc.pdf.getAssociatedFileBytes=async()=>{attempts++;throw Error('Worker decoded-stream allocation limit exceeded');};};
   let persistentError='';try{await persistent.session.bytes(persistent.doc,...target(persistent.doc));}catch(e){persistentError=e.message;}persistent.session.close();
   const other=await open();let otherAttempts=0;other.doc.pdf.getAssociatedFileBytes=async()=>{otherAttempts++;throw Error('Decoded stream byte limit exceeded');};
   let otherError='';try{await other.session.bytes(other.doc,...target(other.doc));}catch(e){otherError=e.message;}other.session.close();
   const cancelled=await open();cancelled.doc.pdf.getAssociatedFileBytes=async()=>{cancelled.session.close();throw Error('Worker decoded-stream allocation limit exceeded');};
   let cancellation='';try{await cancelled.session.bytes(cancelled.doc,...target(cancelled.doc));}catch(e){cancellation=e.message;}
   return {expected,hashes,replaced,attempts,persistentError,otherAttempts,otherError,cancellation};
  }finally{sessions.forEach(session=>session.close());}
 });
 assert.equal(result.replaced,true);assert.deepEqual(result.hashes,[result.expected,result.expected]);assert.equal(result.attempts,2);assert.match(result.persistentError,/allocation limit/);assert.equal(result.otherAttempts,1);assert.match(result.otherError,/Decoded stream byte limit/);assert.match(result.cancellation,/cancelled/i);
 console.log('PASS: exhausted-worker recovery preserves bytes, concurrent recovery is shared, persistent failure retries once, stream limits and cancellation remain effective.');
}finally{await context.close();}
