import {test} from 'node:test';
import assert from 'node:assert/strict';
import {boundedResponseBlob,downloadLinkedPdf} from '../dist/chromium/bounded-download.js';

test('downloads count actual chunks when Content-Length is missing or incorrect',async()=>{
 for(const headers of [{},{'Content-Length':'1'}]){
  let cancelled=false;
  const body=new ReadableStream({start(c){c.enqueue(new Uint8Array([1,2,3]));c.enqueue(new Uint8Array([4,5,6]));},cancel(){cancelled=true;}});
  await assert.rejects(boundedResponseBlob(new Response(body,{headers}),4,new AbortController().signal),/byte limit/);
  assert.ok(cancelled);
 }
 const blob=await boundedResponseBlob(new Response(new Uint8Array([1,2,3])),3,new AbortController().signal);
 assert.deepEqual([...new Uint8Array(await blob.arrayBuffer())],[1,2,3]);
});

test('an oversized declared response is cancelled before its body is read',async()=>{
 let cancelled=false;
 const response=new Response(new ReadableStream({cancel(){cancelled=true;}}),{headers:{'Content-Length':'99999'}});
 await assert.rejects(boundedResponseBlob(response,100,new AbortController().signal),/byte limit/);
 assert.ok(cancelled);
});

test('a stalled response times out and releases its stream; fetch omits credentials and rejects redirects',async()=>{
 const previous=globalThis.fetch;let cancelled=false;
 globalThis.fetch=async(url,options)=>{
  assert.equal(options.credentials,'omit');assert.equal(options.redirect,'error');
  return new Response(new ReadableStream({cancel(){cancelled=true;}}));
 };
 try{
  await assert.rejects(downloadLinkedPdf('https://example.invalid/',new AbortController(),1024,20),/timed out/);
  assert.ok(cancelled);
 }finally{globalThis.fetch=previous;}
});

test('user cancellation does not return a partial download',async()=>{
 const controller=new AbortController();let cancelled=false;
 const response=new Response(new ReadableStream({start(c){c.enqueue(new Uint8Array([1]));},cancel(){cancelled=true;}}));
 const pending=boundedResponseBlob(response,100,controller.signal);
 controller.abort();await assert.rejects(pending);assert.ok(cancelled);
});
