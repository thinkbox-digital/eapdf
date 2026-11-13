// Exercise the raw-XMP adaptation independently of any viewer or EA-PDF schema.
import test from 'node:test';
import assert from 'node:assert/strict';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';
const source=process.env.PDFJS_SOURCE;if(!source)throw Error('PDFJS_SOURCE is required');
const load=name=>import(pathToFileURL(resolve(source,name)));
const {DocumentAssets}=await load('src/core/document_assets.js');
const {Dict,Name,Ref}=await load('src/core/primitives.js');
const {Stream}=await load('src/core/stream.js');
test('raw metadata bytes remain exact, detached, bounded and document-scoped',async()=>{
 const dict=fields=>{const value=new Dict();for(const [key,item]of Object.entries(fields))value.set(key,item);return value;};
 const bytes=new TextEncoder().encode('<?xml version="1.0"?><x:xmpmeta xmlns:x="adobe:ns:meta/">東京</x:xmpmeta>');
 const stream=new Stream(bytes);stream.dict=dict({Type:Name.get('Metadata'),Subtype:Name.get('XML')});
 const ref=Ref.get(41,2),root=dict({Metadata:ref}),xref={fetch:value=>{assert.equal(value,ref);return stream;}};root.assignXref(xref);
 const reader=new DocumentAssets({pdfManager:{docId:'loaded-1'},xref},root);
 const assets=await reader.inspect({maxMetadataBytes:4096});assert.equal(assets.metadata.length,1);
 const request={documentId:'loaded-1',metadataId:assets.metadata[0].locator.path,maxBytes:4096};
 const read=reader.read(request);assert.deepEqual(read,bytes);read.fill(0);assert.deepEqual(reader.read(request),bytes);
 assert.throws(()=>reader.read({...request,maxBytes:8}),/byte limit/);
 assert.throws(()=>reader.read({...request,documentId:'other'}),/Foreign document/);
 assert.throws(()=>reader.read({...request,metadataId:'object:999:0'}),/not discovered/);
});
