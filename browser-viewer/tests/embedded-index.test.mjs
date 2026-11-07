import {test} from 'node:test';
import assert from 'node:assert/strict';
import {gzipSync} from 'node:zlib';
import {createHash} from 'node:crypto';
import {decodeIndex,readEmbeddedIndex,forgetEmbeddedIndex,INDEX_LIMITS} from '../dist/chromium/embedded-index.js';
import {indexMessages} from '../dist/chromium/message-index.js';
const observed=value=>({state:'present',value});
function fixture(count=2){
 const doc={name:'root.pdf',inspection:{identification:{declaredProfile:observed('ci')},coverage:{metadata:'present',associatedFiles:'present'},diagnostics:[],messages:[],files:[],collection:observed({entries:[],folders:[]})},details:new Map()};
 const rows=[];
 for(let n=0;n<count;n++){
  const guid='guid-'+n,id='file-'+n,key='child-'+n+'.pdf',md5='a'.repeat(32),data={Subject:'Duplicate — 東京', 'Raw-From':'José <j@example.org>','Raw-To':'目标 <to@example.org>','Raw-Cc':'cc@example.org','Raw-Bcc':'bcc@example.org',Sent:'2026-11-07T12:00:00Z'};
  doc.inspection.messages.push({id:'root-'+n,metadataRecordId:guid,mailGuid:observed(guid),internetMessageId:observed('<duplicate@example.org>')});doc.details.set(guid,data);
  doc.inspection.files.push({id,filename:observed(key),unicodeFilename:observed(null),streams:[{locator:{path:'stream-'+n},mimeType:observed('application/pdf'),declaredSize:observed(100),declaredMd5:observed(md5)}]});
  doc.inspection.collection.value.entries.push({fileId:id,nameTreeKey:observed(key),folderIds:observed([])});
  rows.push([guid,'<duplicate@example.org>',data.Subject,data['Raw-From'],data['Raw-To'],data['Raw-Cc'],data['Raw-Bcc'],data.Sent,[key],md5,100,[''],1]);
 }
 const pack=(editHeader={},lines=rows)=>{const payload=Buffer.from(lines.map(r=>JSON.stringify(r)+'\n').join(''));const header={format:'eapdf-metadata-index',version:1,profile:'1ci',messages:lines.length,sha256:createHash('sha256').update(payload).digest('hex'),...editHeader};doc.embeddedIndex=gzipSync(Buffer.concat([Buffer.from(JSON.stringify(header)+'\n'),payload])).toString('base64');forgetEmbeddedIndex(doc);};
 pack();return {doc,rows,pack};
}
test('Unicode, duplicate subjects/Message-IDs and exact metadata destinations use zero child opens',async()=>{
 const {doc}=fixture();let opened=0;
 const result=await indexMessages({child(){opened++;throw Error('must not open');}},doc,()=>{},()=>{});
 assert.equal(result.source,'embedded');assert.equal(opened,0);assert.equal(result.messages.length,2);
 assert.notEqual(result.messages[0].mailGuid,result.messages[1].mailGuid);
 for(const item of result.messages){assert.ok(item.searchText.includes('東京'));assert.ok(item.searchText.includes('cc@example.org'));assert.ok(item.searchText.includes('bcc@example.org'));assert.ok(item.searchText.includes('2026-11-07'));}
 assert.deepEqual(result.messages[1].path,[{fileId:'file-1',streamPath:'stream-1'}]);
});
test('empty collection index is complete',async()=>{assert.deepEqual(await decodeIndex(fixture(0).doc,()=>{}),[]);});
test('multiple folder memberships remain one message and are all checked',async()=>{
 const {doc,rows,pack}=fixture(1),c=doc.inspection.collection.value;
 c.folders=[{id:'a',name:observed('Inbox'),parentId:null},{id:'b',name:observed('保管'),parentId:null}];
 c.entries[0].folderIds=observed(['a']);c.entries.push({fileId:'file-0',nameTreeKey:observed('alias.pdf'),folderIds:observed(['b'])});
 rows[0][8]=['alias.pdf','child-0.pdf'];rows[0][11]=['Inbox','保管'];pack();assert.equal((await decodeIndex(doc,()=>{})).length,1);
 rows[0][11]=['Inbox'];pack();await assert.rejects(decodeIndex(doc,()=>{}),/folder membership/);
});
for(const [name,edit,pattern] of [
 ['unsupported version',f=>f.pack({version:2}),/version/],
 ['wrong profile',f=>f.pack({profile:'1c'}),/profile/],
 ['digest mismatch',f=>f.pack({sha256:'0'.repeat(64)}),/integrity/],
 ['missing row',f=>f.pack({},[f.rows[0]]),/incomplete/],
 ['metadata edited with recomputed digest',f=>{f.rows[0][2]='changed';f.pack();},/catalog XMP/],
 ['child replaced',f=>{f.doc.inspection.files[0].streams[0].declaredMd5=observed('b'.repeat(32));},/checksum/],
 ['unknown child',f=>{f.rows[0][8]=['unknown.pdf'];f.pack();},/child/],
 ['duplicate index identity',f=>{f.rows[1][0]=f.rows[0][0];f.pack();},/identity/],
 ['ambiguous authoritative GUID',f=>{f.doc.inspection.messages[1].mailGuid=observed('guid-0');},/identity/],
 ['missing authoritative GUID',f=>{f.doc.inspection.messages[0].mailGuid=observed(null);},/identity/],
 ['unavailable metadata',f=>{f.doc.inspection.coverage.metadata='unavailable';},/metadata/],
 ['malformed metadata',f=>{f.doc.inspection.diagnostics=[{code:'XMP_RECORD_STRUCTURE'}];},/metadata/],
 ['invalid page',f=>{f.rows[0][12]=0;f.pack();},/page/],
 ['extra child',f=>{f.doc.inspection.files.push({...f.doc.inspection.files[0],id:'extra'});f.doc.inspection.collection.value.entries.push({fileId:'extra',nameTreeKey:observed('extra.pdf'),folderIds:observed([])});},/unindexed/],
 ['missing child stream',f=>{f.doc.inspection.files[0].streams=[];},/PDF stream/],
 ['malformed gzip',f=>{f.doc.embeddedIndex='AAAA';},/./],
 ['ambiguous payload',f=>{f.doc.embeddedIndex=null;},/ambiguous/],
 ['oversized compressed payload',f=>{f.doc.embeddedIndex='A'.repeat(4*Math.ceil(INDEX_LIMITS.compressed/3)+4);},/size/],
 ['decompression bomb',f=>{f.doc.embeddedIndex=gzipSync(Buffer.alloc(INDEX_LIMITS.decoded+1,32)).toString('base64');},/decompressed/],
 ['row limit',f=>{f.rows[0][2]='x'.repeat(INDEX_LIMITS.row);f.pack();},/row/],
 ['message limit',f=>f.pack({messages:INDEX_LIMITS.messages+1}),/count/],
])test(name+' triggers safe index rejection',async()=>{const f=fixture();edit(f);await assert.rejects(decodeIndex(f.doc,()=>{}),pattern);const result=await readEmbeddedIndex(f.doc,()=>{});assert.match(result.diagnostic,/Reading child PDFs instead/);});
function leaf(root,n){return {name:'child-'+n+'.pdf',inspection:{coverage:{metadata:'present'},collection:{state:'missing'},diagnostics:[],messages:[{id:'child-record-'+n,metadataRecordId:'guid-'+n}]},details:root.details};}
test('legacy and corrupt indexes fall back with identical metadata and paths',async()=>{
 const {doc,pack}=fixture();const fast=await indexMessages({},doc,()=>{},()=>{});let opened=0,released=0;
 const session={child:async(_d,file)=>{opened++;return leaf(doc,Number(file.id.slice(5)));},release:async()=>{released++;}};
 const legacy=await indexMessages(session,doc,()=>{},()=>{},undefined,true);
 const projection=result=>result.messages.map(({messageId,mailGuid,...rest})=>rest);
 assert.deepEqual(projection(fast),projection(legacy));assert.equal(opened,2);assert.equal(released,2);
 pack({version:2});const fallback=await indexMessages(session,doc,()=>{},()=>{});assert.deepEqual(projection(fallback),projection(legacy));assert.equal(fallback.incomplete.length,0);assert.match(fallback.notices[0],/version/);
});
test('nested collections can use a child index without opening its message PDFs',async()=>{
 const {doc:child}=fixture();const root={name:'outer.pdf',inspection:{coverage:{metadata:'present',associatedFiles:'present'},diagnostics:[],collection:observed({entries:[{fileId:'nested'}]}),files:[{id:'nested',filename:observed('nested.pdf'),unicodeFilename:observed(null),streams:[{mimeType:observed('application/pdf'),locator:{path:'nested-stream'}}]}]}};
 let opened=0;const result=await indexMessages({child:async()=>{opened++;return child;},release:async()=>{}},root,()=>{},()=>{});
 assert.equal(opened,1);assert.equal(result.messages.length,2);assert.equal(result.messages[0].path.length,2);assert.match(result.messages[0].document,/outer.pdf \/ nested.pdf \/ child-0.pdf/);
});
test('cancellation propagates and rejected cache entries are reusable',async()=>{
 const {doc}=fixture();await assert.rejects(readEmbeddedIndex(doc,()=>{throw Error('cancelled');}),/cancelled/);
 assert.equal((await readEmbeddedIndex(doc,()=>{})).messages.length,2);
 const first=await readEmbeddedIndex(doc,()=>{});assert.equal(await readEmbeddedIndex(doc,()=>{}),first);
 forgetEmbeddedIndex(doc);assert.notEqual(await readEmbeddedIndex(doc,()=>{}),first);
});

test('cancellation during decompression releases the reader and permits retry',async()=>{
 const {doc}=fixture();let checks=0;
 await assert.rejects(readEmbeddedIndex(doc,()=>{if(++checks>=5)throw Error('cancelled during decode');}),/cancelled/);
 assert.equal((await readEmbeddedIndex(doc,()=>{})).messages.length,2);
});
