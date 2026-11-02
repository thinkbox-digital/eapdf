import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
const manifest=JSON.parse(await readFile('tests/inspection/manifest.json','utf8'));
const cases=['example-1s','example-1si','example-1m','example-1mi','example-1c','example-1ci','s-object-af-only','s-two-streams','s-missing-stream','c-nested-mixed','c-folder-cycle'];
const extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{channel:'chromium'}),args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
const network=[],results=[];
try{
 context.on('request',r=>{if(/^https?:/.test(r.url()))network.push(r.url());});await context.setOffline(true);
 const service=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker');const page=await context.newPage();
 await page.goto(`chrome-extension://${new URL(service.url()).host}/viewer.html`);
 for(const id of cases){
  const c=manifest.fixtures.find(c=>c.id===id),data=await readFile(resolve('..',c.sourcePath));
  assert.equal(createHash('sha256').update(data).digest('hex'),c.sha256);
  const expected=JSON.parse(await readFile(resolve('tests/inspection',c.expected),'utf8'));
  const actual=await page.evaluate(async b64=>{
   const {loadPdf}=await import('./pdfjs-adapter.js');const task=loadPdf(Uint8Array.from(atob(b64),c=>c.charCodeAt(0)));
   try{
    const pdf=await task.promise,assets=await pdf.getDocumentAssets();const bytes=[];
    for(const f of assets.files)for(const s of f.streams)if(s.state==='present'){
     const req={documentId:assets.documentId,fileId:f.locator.path,streamId:s.locator.path,maxBytes:16777216};
     const raw=await pdf.getAssociatedFileBytes(req),hash=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',raw)),x=>x.toString(16).padStart(2,'0')).join('');
     let childPages=null;
     if(assets.collection.state==='present'&&s.subtype.value==='application/pdf'){
      const child=loadPdf(raw.slice());try{childPages=(await child.promise).numPages;}finally{await child.destroy();}
     }
     bytes.push({file:f.locator.path,stream:s.locator.path,length:raw.length,sha256:hash,childPages});
     let rejected=false;try{await pdf.getAssociatedFileBytes({...req,documentId:'foreign'});}catch{rejected=true;}if(!rejected)throw new Error('Foreign locator accepted');
    }
    return {assets,bytes};
   }finally{await task.destroy();}
  },data.toString('base64'));
  assert.equal(actual.assets.files.length,expected.files.length,id);
  for(const file of expected.files){
   const found=actual.assets.files.find(f=>f.locator.path===file.locator.path);assert.ok(found,id);
   assert.equal(found.embedding,file.embedding,id);assert.equal(found.streams.length,file.streams.length,id);
   assert.equal(found.filename.value??null,file.filename.value,id);
   for(const stream of file.streams){
    const bytes=actual.bytes.find(b=>b.file===file.locator.path&&b.stream===stream.locator.path);
    assert.ok(bytes,id);assert.equal(bytes.length,stream.decodedSize.value,id);assert.equal(bytes.sha256,stream.sha256.value,id);
   }
   assert.deepEqual(found.origins.filter(o=>o.kind==='annotation').map(o=>o.pageIndex).sort(),file.origins.filter(o=>o.kind==='annotation').map(o=>o.pageIndex).sort(),id);
  }
  for(const child of expected.collection.value?.children??[]){
   const entry=actual.bytes.find(b=>b.file===child.fileId.split('#')[1]&&b.stream===child.stream.path);
   assert.equal(entry.childPages,child.inspection.pageCount,id);
  }
  results.push({id,files:actual.assets.files.length,streams:actual.bytes.length,diagnostics:actual.assets.diagnostics.map(d=>d.code),status:'PASS'});
 }
 assert.deepEqual(network,[]);await mkdir('test-results',{recursive:true});await writeFile('test-results/assets-smoke.json',JSON.stringify({status:'PASS',results},null,2)+'\n');console.log(JSON.stringify(results,null,2));
}finally{await context.close();}
