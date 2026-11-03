import test from 'node:test';
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';
import { deflateSync } from 'node:zlib';
const source = process.env.PDFJS_SOURCE;
if (!source) throw new Error('PDFJS_SOURCE is required');
const load = p => import(pathToFileURL(resolve(source, p)));
const { DocumentAssets, boundedBytes } = await load('src/core/document_assets.js');
const { Dict, Ref, Name } = await load('src/core/primitives.js');
const { Stream } = await load('src/core/stream.js');
const { FlateStream } = await load('src/core/flate_stream.js');
function fixture() {
  const dict = fields => { const d = new Dict(); for (const [k,v] of Object.entries(fields)) d.set(k,v); return d; };
  const stream = new Stream(new Uint8Array([1,2,3])); stream.dict = dict({ Subtype: Name.get('application/octet-stream') });
  const sr = Ref.get(4, 2), fr = Ref.get(3,1), spec = dict({ F:'../example.bin', EF:dict({F:sr,UF:sr}) });
  const root = dict({ AF:[fr], Nested:dict({AF:[fr]}) });
  const map = new Map([[sr,stream],[fr,spec]]);
  const catalog = { pdfManager:{docId:'d1'}, xref:{fetch:r=>map.get(r)} };
  for (const d of [root,spec,spec.get('EF'),root.get('Nested')]) d.assignXref(catalog.xref);
  return { dict, root, spec, stream, map, reader:new DocumentAssets(catalog,root) };
}
test('discovers catalog and object AF with shared EF identity; returns exact detached bytes',()=>{
  const f=fixture(), result=f.reader.inspect();
  assert.equal(result.files.length,1); assert.equal(result.files[0].streams.length,1);
  assert.deepEqual(result.files[0].streams[0].efKeys,['/F','/UF']);
  assert.deepEqual(result.files[0].origins.map(o=>o.kind).sort(),['catalog-af','object-af']);
  const req={documentId:'d1',fileId:'object:3:1',streamId:'object:4:2',maxBytes:3};
  const bytes=f.reader.read(req); assert.deepEqual([...bytes],[1,2,3]); bytes[0]=9;
  assert.equal(f.reader.read(req)[0],1);
  assert.throws(()=>f.reader.read({...req,documentId:'other'}),/Foreign/);
  assert.throws(()=>f.reader.read({...req,streamId:'object:99:0'}),/discovered/);
});
test('decompression limit is enforced before buffer growth; failed streams never yield partial downloads',()=>{
  const encoded=new Stream(deflateSync(new Uint8Array(1024*1024)));
  encoded.dict=new Dict(); encoded.dict.set('Filter',Name.get('FlateDecode'));
  const stream=new FlateStream(encoded,encoded.length);
  assert.throws(()=>boundedBytes(stream,4096),/byte limit/);
  assert.ok(stream.buffer.byteLength<=4097);
  assert.throws(()=>boundedBytes(stream,2097152),/Previous decode failed/);
});
test('zero-byte streams are extractable and unknown filters are not executed',()=>{
  assert.equal(boundedBytes(new Stream(new Uint8Array()),0).length,0);
  const f=fixture();f.stream.dict.set('Filter',Name.get('JBIG2Decode'));
  assert.throws(()=>boundedBytes(f.stream,100),/Unsupported extraction filter/);
});
test('object and collection cycles stop; user limits cannot exceed ceilings',()=>{
  const f=fixture(), folder=f.dict({ID:0,Name:'Root'}); folder.set('Next',folder);
  f.root.set('Self',f.root);f.root.set('Collection',f.dict({Folders:folder}));
  assert.ok(f.reader.inspect().diagnostics.some(d=>d.code==='FOLDER_CYCLE'));
  assert.equal(f.reader.inspect({maxObjects:1}).files.length,1);
  assert.throws(()=>f.reader.inspect({maxObjects:Infinity}),/Invalid/);
});

test('explicit extraction allowance can exceed the old 16 MiB default, but stays bounded',()=>{
 const f=fixture();f.reader.inspect();
 const request={documentId:'d1',fileId:'object:3:1',streamId:'object:4:2',maxBytes:32*1024*1024};
 assert.deepEqual([...f.reader.read(request)],[1,2,3]);
 assert.throws(()=>f.reader.read({...request,maxBytes:512*1024*1024+1}),/Invalid extraction limit/);
});

// These guards apply without calling the attachment inspection API.
test('normal decoder allocation rejects an oversized stream before allocating',async()=>{
 const {DecodeStream}=await load('src/core/decode_stream.js');
 const stream=new DecodeStream();
 assert.throws(()=>stream.ensureBuffer(64*1024*1024+1),/byte limit/);
 assert.equal(stream.buffer.byteLength,0);
});
test('normal decoder instances share a finite per-worker allocation budget',async()=>{
 const {DecodeStream}=await load('src/core/decode_stream.js');
 const previous=DecodeStream.remainingBudget;
 try {
  DecodeStream.remainingBudget=1024;
  const first=new DecodeStream();first.ensureBuffer(1024);
  assert.throws(()=>new DecodeStream().ensureBuffer(1),/Worker decoded-stream allocation limit/);
 }finally{DecodeStream.remainingBudget=previous;}
});


test('shared back-links do not exhaust object budget or hide later files',()=>{
 const f=fixture(), shared=f.dict({}), pages=[];
 for(let i=0;i<200;i++)pages.push(f.dict({Parent:shared,Resources:shared}));
 shared.set('Kids',pages);f.root.set('Pages',shared);
 const result=f.reader.inspect({maxObjects:500});
 assert.equal(result.files.length,1);
 assert.ok(!result.diagnostics.some(d=>d.code==='OBJECT_LIMIT'));
});
test('short paths win over long cyclic paths to shared objects',()=>{
 const f=fixture(), chain=f.dict({});let tail=chain;
 for(let i=0;i<70;i++){const next=f.dict({});tail.set('Next',next);tail=next;}
 tail.set('Back',f.root);f.root.set('Chain',chain);
 // Every node is directly reachable; DFS can otherwise report a false depth limit.
 let node=chain,index=0;while(node){f.root.set('Direct'+index++,node);node=node.get('Next');}
 const result=f.reader.inspect();
 assert.ok(!result.diagnostics.some(d=>d.code==='OBJECT_LIMIT'));
 assert.equal(result.files.length,1);
});
test('deep graphs and duplicate edge lists are fully traversed',()=>{
 const f=fixture(),chain=f.dict({});f.root.set('Chain',chain);let tail=chain;
 for(let i=0;i<100;i++){const next=f.dict({});tail.set('Next',next);tail=next;}
 assert.ok(!f.reader.inspect().diagnostics.some(d=>d.code==='OBJECT_LIMIT'));
 const g=fixture();g.root.set('Repeated',Array(2000).fill(g.root));
 assert.ok(!g.reader.inspect({maxObjects:100}).diagnostics.some(d=>d.code==='OBJECT_LIMIT'));
});

test('long bookmark sibling lists do not count as nested depth',()=>{
 const f=fixture(),outline=f.dict({}),first=f.dict({Title:'First',Parent:outline});
 f.root.set('Outlines',outline);outline.set('First',first);let previous=first;
 for(let i=0;i<200;i++){const next=f.dict({Title:'Bookmark',Parent:outline,Prev:previous});previous.set('Next',next);previous=next;}
 previous.set('AF',[Ref.get(3,1)]);
 const result=f.reader.inspect();
 assert.ok(!result.diagnostics.some(d=>d.code==='OBJECT_LIMIT'));
 assert.equal(result.files[0].origins.length,3,'association at end of sibling chain discovered');
});
