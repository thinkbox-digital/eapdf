// Generated from shared hook regressions; update through the Lite exporter.
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
test('discovers catalog and object AF with shared EF identity; returns exact detached bytes',async()=>{
  const f=fixture(), result=await f.reader.inspect();
  assert.equal(result.files.length,1); assert.equal(result.files[0].streams.length,1);
  assert.deepEqual(result.files[0].streams[0].efKeys,['/F','/UF']);
  assert.deepEqual(result.files[0].origins.map(o=>o.kind).sort(),['catalog-af','object-af']);
  const req={documentId:'d1',fileId:'object:3:1',streamId:'object:4:2',maxBytes:3};
  const bytes=f.reader.read(req); assert.deepEqual([...bytes],[1,2,3]); bytes[0]=9;
  assert.equal(f.reader.read(req)[0],1);
  assert.throws(()=>f.reader.read({...req,documentId:'other'}),/Foreign/);
  assert.throws(()=>f.reader.read({...req,streamId:'object:99:0'}),/discovered/);
});
test('decompression limit is enforced before buffer growth; failed streams never yield partial downloads',async()=>{
  const encoded=new Stream(deflateSync(new Uint8Array(1024*1024)));
  encoded.dict=new Dict(); encoded.dict.set('Filter',Name.get('FlateDecode'));
  const stream=new FlateStream(encoded,encoded.length);
  assert.throws(()=>boundedBytes(stream,4096),/byte limit/);
  assert.ok(stream.buffer.byteLength<=4097);
  assert.throws(()=>boundedBytes(stream,2097152),/Previous decode failed/);
});
test('zero-byte streams are extractable and unknown filters are not executed',async()=>{
  assert.equal(boundedBytes(new Stream(new Uint8Array()),0).length,0);
  const f=fixture();f.stream.dict.set('Filter',Name.get('JBIG2Decode'));
  assert.throws(()=>boundedBytes(f.stream,100),/Unsupported extraction filter/);
});
test('object and collection cycles stop; user limits cannot exceed ceilings',async()=>{
  const f=fixture(), folder=f.dict({ID:0,Name:'Root'}); folder.set('Next',folder);
  f.root.set('Self',f.root);f.root.set('Collection',f.dict({Folders:folder}));
  assert.ok((await f.reader.inspect()).diagnostics.some(d=>d.code==='FOLDER_CYCLE'));
  assert.equal((await f.reader.inspect({maxObjects:1})).files.length,1);
  await assert.rejects(()=>f.reader.inspect({maxObjects:Infinity}),/Invalid/);
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
test('ordinary asset inspection retains its API shape without Portfolio presentation',async()=>{
 const f=fixture();f.root.set('Collection',f.dict({Schema:f.dict({Topic:f.dict({N:'Topic'})})}));
 const normal=await f.reader.inspect();assert.equal('presentation' in normal,false);assert.deepEqual(normal.collection.schemaKeys,['Topic']);
});

test('embedded actions preserve nested byte keys, annotation targets and named destinations; reject cycles and external roots',async()=>{
 const {embeddedAction}=await load('src/core/collection_navigation.js');const f=fixture();
 const action=f.dict({D:Name.get('Chapter 1'),T:f.dict({R:Name.get('P'),T:f.dict({R:Name.get('C'),N:'\xfe\xff\x00A',T:f.dict({R:Name.get('C'),P:'page-name',A:'annotation-name'})})})});
 assert.deepEqual(embeddedAction(action),{steps:[{relationship:'P'},{relationship:'C',nameHex:'feff0041'},{relationship:'C',page:'page-name',annotation:'annotation-name'}],destination:'Chapter 1',error:null});
 action.set('D',[2,Name.get('Fit')]);assert.deepEqual(embeddedAction(action).destination,[2,{name:'Fit'}]);
 const target=action.get('T');target.set('T',target);assert.match(embeddedAction(action).error,/cyclic/);
 action.set('F','elsewhere.pdf');assert.match(embeddedAction(action).error,/external PDF/);
});