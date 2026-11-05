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
  const {BrowserSession}=await import('./browser-session.js');const blob=document.querySelector('#fixture').files[0],sessions=[];
  try{
   const snapshots=[],counts=[];
   for(const eager of [true,false]){
    const session=new BrowserSession();sessions.push(session);const doc=await session.open(blob,'fixture.pdf');await session.associateFiles(doc);
    let refreshes=0;const refresh=session.refresh.bind(session);session.refresh=d=>{refreshes++;return refresh(d);};
    const file=doc.inspection.files.find(f=>f.streams.some(s=>s.mimeType.value==='application/pdf')),stream=file.streams.find(s=>s.mimeType.value==='application/pdf');
    await session.child(doc,file,stream,eager);const duringNavigation=refreshes;
    await session.associateFiles(doc);const afterReturn=refreshes;
    await session.associateFiles(doc);counts.push({duringNavigation,afterReturn,afterSecondReturn:refreshes});
    snapshots.push(JSON.stringify({inspection:doc.inspection,details:[...doc.details]}));
   }
   return {same:snapshots[0]===snapshots[1],counts};
  }finally{sessions.forEach(session=>session.close());}
 });
 assert.equal(result.same,true,'deferred parent metadata must match eager interpretation');
 assert.deepEqual(result.counts,[{duringNavigation:1,afterReturn:1,afterSecondReturn:1},{duringNavigation:0,afterReturn:1,afterSecondReturn:1}]);
 await page.locator('#fixture').setInputFiles(resolve('tests/inspection/controls/s-bad-checksum.pdf'));
 const warning=await page.evaluate(async()=>{
  const {BrowserSession}=await import('./browser-session.js');const session=new BrowserSession();
  try{
   const doc=await session.open(document.querySelector('#fixture').files[0],'bad-checksum.pdf'),file=doc.inspection.files.find(f=>f.streams.length),stream=file.streams[0];
   await session.readBytes(doc,file,stream,false);
   await session.bytes(doc,file,stream);
   return doc.inspection.diagnostics.some(d=>d.code==='CHECKSUM_MISMATCH');
  }finally{session.close();}
 });
 assert.equal(warning,true,'cached bytes must still publish pending checksum diagnostics');
 console.log('PASS: child navigation defers parent parsing, parent return refreshes exactly once with identical metadata, cached downloads retain checksum warnings.');

}finally{await context.close();}
