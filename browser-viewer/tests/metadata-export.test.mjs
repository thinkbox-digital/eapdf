import {test} from 'node:test';
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {collectXmp,metadataZip} from '../dist/chromium/metadata-export.js';
test('ZIP keeps original XML bytes, owner locations, selection and incomplete status',async()=>{
 const bytes=new Uint8Array([255,254,60,0,120,0,47,0,62,0]);
 const packet={name:'metadata-0001.xmp',document:'root.pdf',locator:'17R',owners:['page/0','catalog'],bytes,text:'<x/>'};
 for(const issues of [[],['unreadable metadata']]){
  const result={packets:[packet],issues},zip=metadataZip(result,[packet]);
  const json=execFileSync('python3',['-c',`import sys,io,zipfile,json; z=zipfile.ZipFile(io.BytesIO(sys.stdin.buffer.read())); assert z.testzip() is None; print(json.dumps({'bytes':list(z.read('metadata-0001.xmp')),'manifest':json.loads(z.read('manifest.json'))}))`],{input:Buffer.from(await zip.arrayBuffer())});
  const unpacked=JSON.parse(json);assert.deepEqual(unpacked.bytes,[...bytes]);assert.equal(unpacked.manifest.complete,!issues.length);assert.deepEqual(unpacked.manifest.streams[0].owners,packet.owners);
 }
 assert.throws(()=>metadataZip({packets:[packet],issues:[]},[]),/Select at least/);
});
test('collect original metadata bytes even when text decoding is unavailable; report failed streams',async()=>{
 const bytes=new Uint8Array([0xff,0x80]);const loc=path=>({path});
 const doc={name:'root.pdf',inspection:{collection:{state:'missing',value:null}},run:p=>p,pdf:{getDocumentAssets:async()=>({documentId:'doc',diagnostics:[],metadata:['raw','bad'].map(path=>({locator:loc(path),owners:[loc('catalog')],text:null}))}),getAssociatedFileBytes:async req=>{assert.equal(req.documentId,'doc');if(req.metadataId==='bad')throw Error('unreadable');return bytes;}}};
 const result=await collectXmp(doc,{ensure:async()=>{}},()=>{},()=>{});assert.equal(result.packets.length,1);assert.deepEqual(result.packets[0].bytes,bytes);assert.match(result.issues[0],/unreadable/);
 await assert.rejects(collectXmp(doc,{ensure:async()=>{}},()=>{throw Error('cancelled');},()=>{}),/cancelled/);
});
