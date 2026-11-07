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

test('explicit extraction allowance can exceed the old 16 MiB default, but stays bounded',async()=>{
 const f=fixture();await f.reader.inspect();
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


test('shared back-links do not exhaust object budget or hide later files',async()=>{
 const f=fixture(), shared=f.dict({}), pages=[];
 for(let i=0;i<200;i++)pages.push(f.dict({Parent:shared,Resources:shared}));
 shared.set('Kids',pages);f.root.set('Pages',shared);
 const result=await f.reader.inspect({maxObjects:500});
 assert.equal(result.files.length,1);
 assert.ok(!result.diagnostics.some(d=>d.code==='OBJECT_LIMIT'));
});
test('short paths win over long cyclic paths to shared objects',async()=>{
 const f=fixture(), chain=f.dict({});let tail=chain;
 for(let i=0;i<70;i++){const next=f.dict({});tail.set('Next',next);tail=next;}
 tail.set('Back',f.root);f.root.set('Chain',chain);
 // Every node is directly reachable; DFS can otherwise report a false depth limit.
 let node=chain,index=0;while(node){f.root.set('Direct'+index++,node);node=node.get('Next');}
 const result=await f.reader.inspect();
 assert.ok(!result.diagnostics.some(d=>d.code==='OBJECT_LIMIT'));
 assert.equal(result.files.length,1);
});
test('deep graphs and duplicate edge lists are fully traversed',async()=>{
 const f=fixture(),chain=f.dict({});f.root.set('Chain',chain);let tail=chain;
 for(let i=0;i<100;i++){const next=f.dict({});tail.set('Next',next);tail=next;}
 assert.ok(!(await f.reader.inspect()).diagnostics.some(d=>d.code==='OBJECT_LIMIT'));
 const g=fixture();g.root.set('Repeated',Array(2000).fill(g.root));
 assert.ok(!(await g.reader.inspect({maxObjects:100})).diagnostics.some(d=>d.code==='OBJECT_LIMIT'));
});

test('long bookmark sibling lists do not count as nested depth',async()=>{
 const f=fixture(),outline=f.dict({}),first=f.dict({Title:'First',Parent:outline});
 f.root.set('Outlines',outline);outline.set('First',first);let previous=first;
 for(let i=0;i<200;i++){const next=f.dict({Title:'Bookmark',Parent:outline,Prev:previous});previous.set('Next',next);previous=next;}
 previous.set('AF',[Ref.get(3,1)]);
 const result=await f.reader.inspect();
 assert.ok(!result.diagnostics.some(d=>d.code==='OBJECT_LIMIT'));
 assert.equal(result.files[0].origins.length,3,'association at end of sibling chain discovered');
});

test('xref decoding can exceed the ordinary stream allowance within the worker budget',async()=>{
 const {XRef}=await load('src/core/xref.js');const {DecodeStream}=await load('src/core/decode_stream.js');const saved=DecodeStream.remainingBudget;
 try{
  const count=5000,data=new Uint8Array(count*4);for(let i=0;i<count;i++)data[i*4]=1;
  const f=fixture(),compressed=new Stream(deflateSync(data));compressed.dict=f.dict({Type:Name.get('XRef'),W:[1,2,1],Index:[0,count],Size:count});
  const stream=new FlateStream(compressed);stream.maxBufferLength=1024;
  const xref=new XRef(new Stream(new Uint8Array()),{evaluatorOptions:{}});xref.processXRefStream(stream);
  assert.ok(stream.maxBufferLength>1024);assert.equal(stream.bufferLength,0);assert.equal(DecodeStream.remainingBudget,saved);assert.equal(new FlateStream(new Stream(deflateSync(data))).maxBufferLength,64*1024**2);
 }finally{DecodeStream.remainingBudget=saved;}
});
test('xref resource failures propagate without whole-file repair fallback',async()=>{
 const {XRef}=await load('src/core/xref.js');const {DecodeStreamLimitError}=await load('src/core/decode_stream.js');
 const bytes=new TextEncoder().encode('1 0 obj\n<< /Type /XRef /Length 0 >>\nstream\n\nendstream\nendobj\n');
 const xref=new XRef(new Stream(bytes),{evaluatorOptions:{}});xref.setStartXRef(0);
 xref.processXRefStream=()=>{throw new DecodeStreamLimitError('Worker decoded-stream allocation limit exceeded');};
 assert.throws(()=>xref.readXRef(),DecodeStreamLimitError);
});


test('completed object streams release temporary decoder storage and can be read again',async()=>{
 const {XRef}=await load('src/core/xref.js'),{DecodeStream}=await load('src/core/decode_stream.js');
 const budget=DecodeStream.remainingBudget;
 try {
  DecodeStream.remainingBudget=2048;
  const xref=new XRef(new Stream(new Uint8Array()),{});let stream;
  xref.fetch=()=>{
   stream=new FlateStream(new Stream(deflateSync(Buffer.from('1 0 (preserved value)'))));
   stream.dict=new Dict();stream.dict.set('First',4);stream.dict.set('N',1);return stream;
  };
  for(let i=0;i<10;i++){
   assert.equal(xref.fetchCompressed(Ref.get(1,0),{offset:2,gen:0}),'preserved value');
   xref.clearObjectStreamCache();
   assert.equal(stream.buffer.byteLength,0);assert.equal(DecodeStream.remainingBudget,2048);
  }
 } finally {DecodeStream.remainingBudget=budget;}
});

test('attachment annotations share one validated page map',async()=>{
 const {Catalog}=await load('src/core/catalog.js');
 const result={files:[{origins:[{pageRef:{num:8,gen:0}},{pageRef:{num:8,gen:0}},{pageRef:{num:9,gen:0}}]}],diagnostics:[]};
 let scans=0;
 const catalog={_documentAssets:{inspect:()=>result},getAllPageDicts:async()=>{scans++;return new Map([[0,[new Dict(),Ref.get(8,0)]]]);},getPageIndex:()=>{throw Error('Repeated search');}};
 await Catalog.prototype.getDocumentAssets.call(catalog,{});
 assert.equal(scans,1);assert.deepEqual(result.files[0].origins.map(o=>o.pageIndex),[0,0,undefined]);
 assert.equal(result.diagnostics[0].code,'ANNOTATION_PAGE_UNAVAILABLE');
});

test('compact xref retains sparse indexes, large offsets, generations and replacement',async()=>{
 const {CompactXRefEntries}=await load('src/core/xref.js');const entries=new CompactXRefEntries();
 const values=new Map([[0,{offset:0,gen:65535,free:true,uncompressed:false}],[9707298,{offset:2**40,gen:32768,free:false,uncompressed:true}],[4095,{offset:51,gen:3,free:false,uncompressed:false}],[4096,{offset:12,gen:0,free:false,uncompressed:true}]]);
 for(const [index,value]of values)entries.set(index,value);
 assert.deepEqual([...entries.keys()],[0,4095,4096,9707298]);assert.equal(entries.length,9707299);
 for(const [index,value]of values)assert.deepEqual(entries.get(index),value);
 assert.equal(entries.get(1),undefined);assert.equal(entries.has(9707297),false);
 entries.set(4096,values.get(0));assert.deepEqual(entries.get(4096),values.get(0));
 entries.clear();assert.equal(entries.length,0);assert.deepEqual([...entries.keys()],[]);assert.equal(entries.get(0),undefined);
});

test('malformed nested streams keep their structural decoder allocation charged',async()=>{
 const {XRef}=await load('src/core/xref.js'),{DecodeStream}=await load('src/core/decode_stream.js');
 const budget=DecodeStream.remainingBudget;
 try {
  const data=Buffer.from('1 0 [<< /Length 3 >> stream\nabc\nendstream ]');
  const stream=new FlateStream(new Stream(deflateSync(data)));stream.dict=new Dict();stream.dict.set('First',4);stream.dict.set('N',1);
  const xref=new XRef(new Stream(new Uint8Array()),{evaluatorOptions:{}});xref.fetch=()=>stream;
  const result=xref.fetchCompressed(Ref.get(1,0),{offset:2,gen:0});
  assert.equal(new TextDecoder().decode(result[0].getBytes()),'abc');
  xref.clearObjectStreamCache();
  assert.ok(stream.buffer.byteLength>0);assert.ok(DecodeStream.remainingBudget<budget);
 }finally{DecodeStream.remainingBudget=budget;}
});

test('parsed-object eviction preserves live identity and persistent objects across repeated ring wraps',async()=>{
 const {XRef}=await load('src/core/xref.js');const count=300005,offsets=[0],chunks=['\n'];let length=1;
 for(let i=1;i<count;i++){offsets.push(length);const text=`${i} 0 obj\n<< /Value ${i} >>\nendobj\n`;chunks.push(text);length+=text.length;}
 const xref=new XRef(new Stream(new TextEncoder().encode(chunks.join(''))),{evaluatorOptions:{}});
 const bytes=new Uint8Array(count*7),view=new DataView(bytes.buffer);
 for(let i=0;i<count;i++){bytes[i*7]=i?1:0;view.setUint32(i*7+1,offsets[i]);view.setUint16(i*7+5,i?0:65535);}
 const table=new Stream(bytes);table.dict=new Dict();table.dict.set('W',[1,4,2]);table.dict.set('Index',[0,count]);table.dict.set('Size',count);xref.processXRefStream(table);
 const held=xref.fetch(Ref.get(1,0)),persistent=new Dict(),persistentRef=xref.getNewPersistentRef(persistent);
 for(let i=2;i<count;i++){assert.equal(xref.fetch(Ref.get(i,0)).get('Value'),i);if(i%2048===0)await new Promise(resolve=>setTimeout(resolve,0));}
 assert.equal(xref.fetch(Ref.get(1,0)),held);assert.equal(held.get('Value'),1);
 assert.equal(xref.fetch(persistentRef),persistent);
});

test('compressed members are parsed on demand and share decoded bytes',async()=>{
 const {XRef}=await load('src/core/xref.js'),{DecodeStream}=await load('src/core/decode_stream.js');const budget=DecodeStream.remainingBudget;
 const xref=new XRef(new Stream(new Uint8Array()),{evaluatorOptions:{}});let reads=0;
 const stream=new FlateStream(new Stream(deflateSync(Buffer.from('1 0 2 8 (first) << /Bad'))));
 stream.dict=new Dict();stream.dict.set('First',8);stream.dict.set('N',2);xref.fetch=()=>{reads++;return stream;};
 try{
  assert.equal(xref.fetchCompressed(Ref.get(1,0),{offset:3,gen:0}),'first');
  assert.throws(()=>xref.fetchCompressed(Ref.get(2,0),{offset:3,gen:1}),/End of file inside dictionary/);
  assert.equal(reads,1);xref.clearObjectStreamCache();assert.equal(stream.buffer.byteLength,0);assert.equal(DecodeStream.remainingBudget,budget);
 }finally{xref.clearObjectStreamCache();DecodeStream.remainingBudget=budget;}
});

test('compressed object index repair persists in compact xref storage',async()=>{
 const {XRef}=await load('src/core/xref.js');const xref=new XRef(new Stream(new Uint8Array()),{evaluatorOptions:{}});
 const table=new Stream(new Uint8Array([2,3,1,2,3,0]));table.dict=new Dict();table.dict.set('W',[1,1,1]);table.dict.set('Index',[1,2]);table.dict.set('Size',4);xref.processXRefStream(table);
 const stream=new FlateStream(new Stream(deflateSync(Buffer.from('1 0 2 8 (first) (second)'))));stream.dict=new Dict();stream.dict.set('First',8);stream.dict.set('N',2);
 const fetch=xref.fetch.bind(xref);xref.fetch=ref=>ref.num===3?stream:fetch(ref);
 try{assert.equal(xref.fetch(Ref.get(1,0)),'first');assert.equal(xref.getEntry(1).gen,0);assert.equal(xref.getEntry(2).gen,1);assert.equal(xref.fetch(Ref.get(2,0)),'second');}
 finally{xref.clearObjectStreamCache();}
});

test('indirect metadata decoding returns its temporary allocation allowance',async()=>{
 const {DecodeStream}=await load('src/core/decode_stream.js');const budget=DecodeStream.remainingBudget;
 const f=fixture(),reference=Ref.get(80,0),data='<metadata>'+('x'.repeat(1024*1024))+'</metadata>';
 const stream=new FlateStream(new Stream(deflateSync(Buffer.from(data))));stream.dict=f.dict({});f.map.set(reference,stream);f.root.set('Metadata',reference);f.root.assignXref(f.reader.catalog.xref);
 try{const result=await f.reader.inspect();assert.equal(result.metadata[0].text,data);assert.equal(stream.buffer.byteLength,0);assert.equal(DecodeStream.remainingBudget,budget);}
 finally{DecodeStream.remainingBudget=budget;}
});

test('attachment reads release private decoder buffers without a worker restart',async()=>{
 const {DecodeStream}=await load('src/core/decode_stream.js');
 const {remainingBudget:saved}=DecodeStream;
 const payload=new Uint8Array(1024*1024).fill(71),encoded=deflateSync(payload),f=fixture();
 const make=()=>{const input=new Stream(encoded);input.dict=f.dict({Filter:Name.get('FlateDecode')});return new FlateStream(input,encoded.length);};
 const originalFetch=f.reader.catalog.xref.fetch;
 f.reader.catalog.xref.fetch=ref=>ref.num>=100?make():originalFetch(ref);
 for(let i=0;i<12;i++)f.reader.registered.set(`file:${i}|stream:${i}`,{ref:Ref.get(100+i,0),stream:make()});
 try{
  DecodeStream.remainingBudget=2*1024*1024;
  for(let i=0;i<12;i++){
   const request={documentId:'d1',fileId:`file:${i}`,streamId:`stream:${i}`,maxBytes:payload.length};
   const bytes=f.reader.read(request);assert.deepEqual(bytes,payload);bytes[0]=0;
   assert.equal(DecodeStream.remainingBudget,2*1024*1024,'only live decoder buffers consume the worker budget');
   assert.equal(f.reader.read(request)[0],71,'repeated extraction refetches intact compressed bytes');
  }
 }finally{DecodeStream.remainingBudget=saved;}
});

test('failed private extraction releases memory; borrowed direct streams remain readable',async()=>{
 const {DecodeStream}=await load('src/core/decode_stream.js'),saved=DecodeStream.remainingBudget,f=fixture();
 const payload=new Uint8Array(65536).fill(83),encoded=deflateSync(payload);
 const make=()=>{const input=new Stream(encoded);input.dict=f.dict({Filter:Name.get('FlateDecode')});return new FlateStream(input,encoded.length);};
 const ref=Ref.get(100,0);f.reader.catalog.xref.fetch=()=>make();f.reader.registered.set('f|s',{ref});
 const request={documentId:'d1',fileId:'f',streamId:'s',maxBytes:1024};
 try{
  DecodeStream.remainingBudget=1024*1024;
  assert.throws(()=>f.reader.read(request),/byte limit/);assert.equal(DecodeStream.remainingBudget,1024*1024);
  assert.deepEqual(f.reader.read({...request,maxBytes:payload.length}),payload);assert.equal(DecodeStream.remainingBudget,1024*1024);
  const borrowed=make();f.reader.registered.set('f|s',{stream:borrowed});
  assert.deepEqual(f.reader.read({...request,maxBytes:payload.length}),payload);assert.ok(DecodeStream.remainingBudget<1024*1024);
  assert.deepEqual(f.reader.read({...request,maxBytes:payload.length}),payload);assert.ok(borrowed.buffer.byteLength>0);
 }finally{DecodeStream.remainingBudget=saved;}
});

test('metadata above 64 MiB uses its explicit allowance without raising ordinary decoder limits',async()=>{
 const {DecodeStream}=await load('src/core/decode_stream.js'),saved=DecodeStream.remainingBudget;
 const f=fixture(),ref=Ref.get(80,0),data='<metadata>'+('m'.repeat(70*1024*1024))+'</metadata>';
 const stream=new FlateStream(new Stream(deflateSync(Buffer.from(data))));stream.dict=f.dict({});f.map.set(ref,stream);f.root.set('Metadata',ref);
 try{
  DecodeStream.remainingBudget=256*1024*1024;
  const result=await f.reader.inspect({maxMetadataBytes:128*1024*1024});
  assert.equal(result.metadata[0].state,'present');assert.equal(result.metadata[0].text,data);
  assert.equal(stream.maxBufferLength,64*1024*1024);assert.equal(stream.buffer.byteLength,0);assert.equal(DecodeStream.remainingBudget,256*1024*1024);
  assert.equal(new DecodeStream().maxBufferLength,64*1024*1024);
 }finally{DecodeStream.remainingBudget=saved;}
});

test('object-stream member readers remain independent across cleanup and refetch',async()=>{
 const {XRef}=await load('src/core/xref.js'),{DecodeStream}=await load('src/core/decode_stream.js');
 const budget=DecodeStream.remainingBudget,xref=new XRef(new Stream(new Uint8Array()),{evaluatorOptions:{}});
 const first='(first) ',data=Buffer.from(`1 0 2 ${first.length} ${first}(second)`),header=data.indexOf(Buffer.from('(first)'));
 let reads=0;const streams=[];
 xref.fetch=()=>{reads++;const stream=new FlateStream(new Stream(deflateSync(data)));stream.dict=new Dict();stream.dict.set('First',header);stream.dict.set('N',2);streams.push(stream);return stream;};
 try {
  for(const index of [1,0,1,0])assert.equal(xref.fetchCompressed(Ref.get(index+1,0),{offset:3,gen:index}),index?'second':'first');
  assert.equal(reads,1,'member readers must not share a cursor');
  xref.clearObjectStreamCache();xref.clearObjectStreamCache();
  assert.equal(DecodeStream.remainingBudget,budget,'cleanup must not refund twice');
  assert.equal(xref.fetchCompressed(Ref.get(2,0),{offset:3,gen:1}),'second');assert.equal(reads,2);
 }finally{xref.clearObjectStreamCache();DecodeStream.remainingBudget=budget;}
});

test('retained malformed stream readers survive cache cleanup with independent positions',async()=>{
 const {XRef}=await load('src/core/xref.js'),{DecodeStream}=await load('src/core/decode_stream.js');
 const budget=DecodeStream.remainingBudget,xref=new XRef(new Stream(new Uint8Array()),{evaluatorOptions:{}});
 const data=Buffer.from('1 0 [<< /Length 3 >> stream\nabc\nendstream ]');
 xref.fetch=()=>{const stream=new FlateStream(new Stream(deflateSync(data)));stream.dict=new Dict();stream.dict.set('First',4);stream.dict.set('N',1);return stream;};
 try {
  const a=xref.fetchCompressed(Ref.get(1,0),{offset:2,gen:0})[0];
  const b=xref.fetchCompressed(Ref.get(1,0),{offset:2,gen:0})[0];
  assert.equal(a.getByte(),97);assert.equal(b.getByte(),97);
  const charged=DecodeStream.remainingBudget;
  xref.clearObjectStreamCache();xref.clearObjectStreamCache();
  assert.equal(DecodeStream.remainingBudget,charged,'escaped buffer must remain charged');
  assert.equal(new TextDecoder().decode(a.getBytes()),'bc');assert.equal(new TextDecoder().decode(b.getBytes()),'bc');
 }finally{xref.clearObjectStreamCache();DecodeStream.remainingBudget=budget;}
});
