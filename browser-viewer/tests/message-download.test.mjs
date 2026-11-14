import test from 'node:test';import assert from 'node:assert/strict';
import {build} from 'esbuild';import {mkdtemp,rm} from 'node:fs/promises';import {tmpdir} from 'node:os';import {join} from 'node:path';import {pathToFileURL,fileURLToPath} from 'node:url';import {after} from 'node:test';
const temporary=await mkdtemp(join(tmpdir(),'eapdf-download-tests-'));after(()=>rm(temporary,{recursive:true,force:true}));
const modulePath=join(temporary,'message-download.mjs');
await build({entryPoints:[fileURLToPath(new URL('../src/message-download.ts',import.meta.url))],outfile:modulePath,bundle:true,format:'esm',platform:'node'});
const {messageDownload,messagePdf}=await import(pathToFileURL(modulePath));
const present=value=>({state:'present',value});
function document(){const message={id:'one',metadataRecordId:'record',partIds:present(['body'])};return {name:'archive.pdf',pdf:{numPages:5},run:p=>p,details:new Map([['record',{Subject:'Selected email'}]]),inspection:{pageCount:5,identification:{declaredProfile:present('m')},coverage:{metadata:'present',documentParts:'present',associatedFiles:'present',collection:'missing'},diagnostics:[],collection:{state:'missing',value:null},messages:[message,{id:'two'}],parts:[{id:'body',dpm:{Mail_ContentSetType:present('BodyRendering')},messageIds:present(['one']),pageRange:present({start:1,end:3})}]}};}
test('aggregate downloads use every proven body page and do not export the whole archive',()=>{const doc=document();assert.deepEqual(messageDownload(doc,doc.inspection.messages[0]),{name:'Selected email.pdf',pages:[2,3,4]});assert.equal(messageDownload(doc),undefined);});
test('ambiguous or unavailable body mappings cannot produce a partial message download',()=>{for(const mutate of [d=>d.inspection.parts[0].messageIds=present(['one','two']),d=>d.inspection.parts[0].pageRange={state:'unavailable',value:null},d=>{d.inspection.messages[0].partIds.value.push('bad');d.inspection.parts.push({...d.inspection.parts[0],id:'bad',pageRange:present({start:6,end:8})});}]){const doc=document();mutate(doc);assert.equal(messageDownload(doc,doc.inspection.messages[0]),undefined);}});
test('complete standalone s/si scope preserves original bytes and collection wrappers are unavailable',async()=>{for(const profile of ['s','si']){const doc=document();doc.inspection.messages.length=1;doc.inspection.identification.declaredProfile=present(profile);doc.inspection.parts[0].pageRange=present({start:0,end:4});const source=new Blob(['original bytes']);const plan=messageDownload(doc);assert.equal(plan.pages,null);assert.equal(await messagePdf({sourceBlob:async()=>source},doc,plan,()=>{}),source);doc.inspection.collection.value={};assert.equal(messageDownload(doc),undefined);}});
test('one recognized message in m/mi or unknown profiles exports only proven body pages',()=>{
 for(const profile of ['m','mi','c','ci','unknown',null]){const doc=document();doc.inspection.messages.length=1;doc.inspection.coverage.documentParts='unavailable';doc.inspection.identification.declaredProfile=profile?present(profile):{state:'missing',value:null};assert.deepEqual(messageDownload(doc),{name:'Selected email.pdf',pages:[2,3,4]});}
});
test('incomplete or contradictory standalone scope never copies the original PDF',()=>{
 for(const mutate of [d=>d.inspection.coverage.metadata='unavailable',d=>d.inspection.coverage.documentParts='invalid',d=>d.inspection.coverage.associatedFiles='unavailable',d=>d.inspection.coverage.collection='unavailable',d=>d.inspection.collection.state='unavailable',d=>d.inspection.diagnostics.push({code:'PART_LIMIT'}),d=>d.inspection.pageCount=6,d=>d.inspection.parts[0].pageRange=present({start:1,end:3}),d=>d.inspection.parts.push({...d.inspection.parts[0],id:'unmapped'})]){
  const doc=document();doc.inspection.messages.length=1;doc.inspection.identification.declaredProfile=present('s');doc.inspection.parts[0].pageRange=present({start:0,end:4});mutate(doc);assert.ok(Array.isArray(messageDownload(doc)?.pages));
 }
});
test('partial aggregate body export cannot read the source archive',async()=>{
 const doc=document();doc.inspection.messages.length=1;doc.inspection.coverage.documentParts='unavailable';let extracted=false;
 doc.pdf.extractPages=async (args,copyLevels,options)=>{assert.equal(copyLevels,null);assert.deepEqual(options,{pageOnly:true});assert.deepEqual(args,[{document:null,includePages:[1,2,3]}]);extracted=true;return new TextEncoder().encode('body pages');};
 const blob=await messagePdf({ensure:async()=>{},sourceBlob:async()=>{assert.fail('Original archive must not be read');}},doc,messageDownload(doc),()=>{});
 assert.equal(await blob.text(),'body pages');assert.equal(extracted,true);
 doc.inspection.parts[0].pageRange={state:'unavailable',value:null};assert.equal(messageDownload(doc),undefined);
});
test('extraction explicitly uses the current document and refuses empty output',async()=>{const doc=document(),plan=messageDownload(doc,doc.inspection.messages[0]);doc.pdf.extractPages=async (args,copyLevels,options)=>{assert.equal(copyLevels,null);assert.deepEqual(options,{pageOnly:true});assert.deepEqual(args,[{document:null,includePages:[1,2,3]}]);return null;};await assert.rejects(messagePdf({ensure:async()=>{}},doc,plan,()=>{}),/could not be exported/);});

test('Portfolio mode downloads the complete active PDF regardless of email metadata',async()=>{
 const doc=document();doc.inspection.messages=[];doc.inspection.collection.value={};
 const plan=messageDownload(doc,undefined,true);assert.deepEqual(plan,{name:doc.name,pages:null});
 const source=new Blob(['active PDF bytes']);assert.equal(await messagePdf({sourceBlob:async active=>{assert.equal(active,doc);return source;}},doc,plan,()=>{}),source);
 assert.equal(messageDownload(doc),undefined);
});

test('unsupported page-only extraction fails without falling back to archive bytes',async()=>{
 const doc=document();doc.pdf.extractPages=async()=>{throw Error('Unknown action: ExtractPageContent');};
 await assert.rejects(messagePdf({ensure:async()=>{},sourceBlob:async()=>assert.fail('Must not copy archive bytes')},doc,messageDownload(doc,doc.inspection.messages[0]),()=>{}),/ExtractPageContent/);
});
