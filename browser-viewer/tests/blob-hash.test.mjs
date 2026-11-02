import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {md5Responsive} from '../dist/chromium/domain/hashes.js';
import {sha256Blob} from '../dist/chromium/blob-hash.js';
test('chunked file identities match independent SHA-256 across padding and chunk boundaries',async()=>{
 for(const size of [0,1,55,56,63,64,65,127,128,1024*1024,1024*1024+57,3*1024*1024+29]){
  const bytes=Buffer.alloc(size);for(let i=0;i<size;i++)bytes[i]=(i*37+19)%256;
  assert.equal(await md5Responsive(bytes),createHash('md5').update(bytes).digest('hex'),String(size));
  assert.equal(await sha256Blob(new Blob([bytes])),createHash('sha256').update(bytes).digest('hex'),String(size));
 }
});
test('chunked file hashing stops when cancelled',async()=>{
 let checks=0;await assert.rejects(sha256Blob(new Blob([new Uint8Array(2*1024*1024)]),()=>{if(++checks>2)throw new Error('cancelled');}),/cancelled/);
});
