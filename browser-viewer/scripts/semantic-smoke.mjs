import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
const manifest=JSON.parse(await readFile('tests/inspection/manifest.json','utf8'));
const extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{channel:'chromium'}),args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
const results=[],actualRecords=[],requests=[];
function observation(o){return {state:o.state,value:o.value,candidates:o.candidates};}
function compare(actual,expected,label){
 assert.equal(actual.documentId,expected.documentId,label);assert.equal(actual.pageCount,expected.pageCount,label);
 assert.equal(actual.identification.validationStatus,'not-evaluated');
 for(const key of ['declaredProfile','eapdfVersion','eapdfRevision','pdfaPart','pdfaConformance'])assert.deepEqual(observation(actual.identification[key]),observation(expected.identification[key]),label+': '+key);
 assert.equal(actual.messages.length,expected.messages.length,label);
 for(const e of expected.messages){const a=actual.messages.find(a=>a.id===e.id);assert.ok(a,label);
  for(const key of ['mailGuid','internetMessageId','partIds','sourceAssetIds'])assert.deepEqual(observation(a[key]),observation(e[key]),label+': '+key);
  assert.deepEqual(a.attachmentRecordIds,e.attachmentRecordIds,label);
 }
 assert.equal(actual.parts.length,expected.parts.length,label);
 for(const e of expected.parts){const a=actual.parts.find(a=>a.id===e.id);assert.ok(a,label);assert.deepEqual(observation(a.pageRange),observation(e.pageRange),label);
  assert.deepEqual(observation(a.messageIds),observation(e.messageIds),label+': DPart owners');}
 assert.equal(actual.files.length,expected.files.length,label);
 for(const e of expected.files){const a=actual.files.find(a=>a.id===e.id);assert.ok(a,label);
  for(const key of ['owners','roles','filename','afRelationship'])assert.deepEqual(observation(a[key]),observation(e[key]),label+': file '+key);
  assert.equal(a.embedding,e.embedding,label);
  for(const s of e.streams){const b=a.streams.find(b=>b.locator.path===s.locator.path);assert.ok(b,label);
   for(const key of ['decodedSize','sha256','computedMd5','declaredMd5'])assert.deepEqual(observation(b[key]),observation(s[key]),label+': '+key);}
 }
 for(const e of expected.metadataRecords){const a=actual.metadataRecords.find(a=>a.id===e.id);assert.ok(a,label);
  for(const key of ['owners','files'])assert.deepEqual(observation(a[key]),observation(e[key]),label+': metadata '+key);}
 assert.equal(actual.collection.state,expected.collection.state,label);
 if(expected.collection.value){
  const ac=actual.collection.value,ec=expected.collection.value;assert.equal(ac.folders.length,ec.folders.length,label);assert.equal(ac.children.length,ec.children.length,label);
  for(let i=0;i<ec.children.length;i++){const e=ec.children[i],a=ac.children.find(a=>a.fileId===e.fileId&&a.stream.path===e.stream.path);assert.ok(a,label);assert.equal(a.state,e.state,label);
   if(e.inspection)compare(a.inspection,e.inspection,label+'/child');}
 }
}
try{
 context.on('request',r=>{if(/^https?:/.test(r.url()))requests.push(r.url());});await context.setOffline(true);
 const service=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker');const page=await context.newPage();await page.goto(`chrome-extension://${new URL(service.url()).host}/viewer.html`);
 for(const fixture of manifest.fixtures){
  const data=await readFile(resolve('..',fixture.sourcePath)),expected=JSON.parse(await readFile(resolve('tests/inspection',fixture.expected),'utf8'));
  assert.equal(createHash('sha256').update(data).digest('hex'),fixture.sha256);
  const actual=await page.evaluate(async base64=>{
   const {InspectionSession}=await import('./inspection-session.js');const session=new InspectionSession();
   try{return (await session.open(Uint8Array.from(atob(base64),c=>c.charCodeAt(0)))).inspection;}finally{session.close();}
  },data.toString('base64'));
  compare(actual,expected,fixture.id);actualRecords.push({id:fixture.id,inspection:actual});results.push({id:fixture.id,status:'PASS'});
 }
 assert.deepEqual(requests,[]);await mkdir('test-results',{recursive:true});await writeFile('test-results/semantic-actual.json',JSON.stringify(actualRecords));
 await writeFile('test-results/semantic-smoke.json',JSON.stringify({status:'PASS',fixtures:results},null,2)+'\n');console.log(`PASS: ${results.length} independent semantic expectations, including nested child inspections; no network requests.`);
}finally{await context.close();}
