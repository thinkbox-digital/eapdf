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

function exportDocument(name, child) {
 const file = child && {
  id: child.name,
  filename: {value: child.name},
  unicodeFilename: {value: null},
  streams: [{locator: {path: child.name}, mimeType: {value: 'application/pdf'}}]
 };
 return {
  name,
  inspection: {
   collection: file ? {state: 'present', value: {entries: [{fileId: file.id}]}} : {state: 'missing', value: null},
   files: file ? [file] : []
  },
  run: promise => promise,
  pdf: {getDocumentAssets: async () => ({diagnostics: [], metadata: []})}
 };
}

test('await child teardown before releasing the enclosing collection', async () => {
 const leaf = exportDocument('leaf.pdf');
 const collection = exportDocument('collection.pdf', leaf);
 const root = exportDocument('root.pdf', collection);
 const open = new Map();
 const released = [];
 const session = {
  ensure: async () => {},
  child: async (parent, file) => {
   const child = file.id === collection.name ? collection : leaf;
   open.set(child, parent);
   return child;
  },
  release: async child => {
   assert.ok(![...open.values()].includes(child), 'release descendants before their parent');
   await new Promise(resolve => setImmediate(resolve));
   open.delete(child);
   released.push(child.name);
  }
 };
 const result = await collectXmp(root, session, () => {}, () => {});
 assert.deepEqual(result.issues, []);
 assert.deepEqual(released, [leaf.name, collection.name]);
 assert.equal(open.size, 0);
});

test('report teardown failures and preserve cancellation during teardown', async () => {
 const child = exportDocument('child.pdf');
 const root = exportDocument('root.pdf', child);
 const cancellation = new Error('cancelled during release');
 let cancelled = false;
 let cancelOnRelease = false;
 const session = {
  ensure: async () => {},
  child: async () => child,
  release: async () => {
   await new Promise(resolve => setImmediate(resolve));
   cancelled = cancelOnRelease;
   throw new Error('teardown failed');
  }
 };
 const check = () => { if (cancelled) throw cancellation; };
 const result = await collectXmp(root, session, check, () => {});
 assert.match(result.issues[0], /child\.pdf.*teardown failed/);
 cancelOnRelease = true;
 await assert.rejects(collectXmp(root, session, check, () => {}), error => error === cancellation);
});
