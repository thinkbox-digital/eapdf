import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
const extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
try{
 await context.setOffline(true);const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker');const page=await context.newPage();
 page.on('console',m=>{if(m.text().startsWith('SUPPLEMENT TEST:'))console.log(m.text());});
 await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
 await page.evaluate(()=>{const i=document.createElement('input');i.id='source-fixture';i.type='file';document.body.append(i);});
 await page.locator('#source-fixture').setInputFiles(resolve('../examples/pst/pst_quality-attached-message_1c.pdf'));
 const results=await page.evaluate(async()=>{
  const {BrowserSession}=await import('./browser-session.js');const input=document.querySelector('#source-fixture').files[0],results=[];
  for(const mode of ['success','failure','cancel','budget']){
   console.log('SUPPLEMENT TEST: '+mode);
   const session=new BrowserSession(mode==='budget'?{maxDecodedBytes:1}:{});let proto,original;
   try{
    const doc=await session.open(input,input.name);
    // This small fixture has direct matches; hide those to exercise the same
    // fallback used by the real transport-header/export-ID mismatch collection.
    const forceFallback=()=>{doc.inspection={...doc.inspection,files:doc.inspection.files.map(f=>f.afRelationship.value==='Supplement'?{...f,owners:{...f.owners,value:null,state:'unavailable'}}:f)};};
    const refresh=session.refresh.bind(session);session.refresh=async d=>{await refresh(d);if(!session.entries.get(d).supplementLinks)forceFallback();};forceFallback();
    proto=Object.getPrototypeOf(doc.pdf);original=proto.getDocumentAssets;let children=0;
    proto.getDocumentAssets=function(...args){if(this!==doc.pdf){children++;if(mode==='failure')return Promise.reject(new Error('Injected child read failure'));if(mode==='cancel'){session.close();return Promise.reject(new Error('Injected cancellation'));}}return original.apply(this,args);};
    let error;try{await session.associateFiles(doc);}catch(e){error=String(e);}
    if(session.decoded!==0)throw new Error('Extraction reservations were not released: '+session.decoded);
    const supplements=doc.inspection.files.filter(f=>f.afRelationship.value==='Supplement');
    const before=supplements.map(f=>f.owners.value);
    if(mode==='success'){
     const file=doc.inspection.files.find(f=>f.afRelationship.value==='Data');await session.bytes(doc,file,file.streams[0]);
     const after=doc.inspection.files.filter(f=>f.afRelationship.value==='Supplement').map(f=>f.owners.value);
     if(JSON.stringify(before)!==JSON.stringify(after))throw new Error('Owners lost on refresh');
     await session.associateFiles(doc);
    }
    const scannedChildren=children,cached=session.cachedMessageIndex(doc);let cacheMatches;
    if(mode==='success'){
      const {indexMessages}=await import('./message-index.js'),reference=new BrowserSession({},{reuseWorker:true});
      try{const root=await reference.open(input,input.name);const expected=await indexMessages(reference,root,()=>{},()=>{},undefined,true);cacheMatches=JSON.stringify(cached)===JSON.stringify(expected);}
      finally{reference.close();}
    }
    results.push({mode,error,children:scannedChildren,cached:!!cached,cacheMatches,count:supplements.length,linked:supplements.filter(f=>f.owners.value?.length===1).length,diagnostics:doc.inspection.diagnostics.map(d=>d.code)});
   }finally{if(proto)proto.getDocumentAssets=original;session.close();}
  }
  return results;
 });
 for(const r of results){
  assert.ok(r.count>0,'fixture has supplements');
  if(r.mode==='success'){assert.equal(r.error,undefined);assert.equal(r.linked,r.count);assert.equal(r.children,7,'one scan, reused after refresh');assert.equal(r.cacheMatches,true,'cached index equals independent full scan');}
  else if(r.mode==='cancel'){assert.match(r.error,/cancelled/);assert.equal(r.cached,false);}
  else {assert.equal(r.cached,false);assert.equal(r.linked,0);assert.ok(r.diagnostics.includes('SUPPLEMENT_ASSOCIATION_INCOMPLETE'));}
  console.log(JSON.stringify(r));
 }
 await page.waitForTimeout(100);assert.equal(page.workers().length,0,'all document workers disposed');
 console.log('PASS: real child membership, retained links, single scan, extraction failure, budget exhaustion, cancellation and worker cleanup.');
}finally{await context.close();}
