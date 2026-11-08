import {test} from 'node:test';
import assert from 'node:assert/strict';
import {retainedSize} from '../dist/chromium/retained-size.js';
test('metadata accounting handles shared references, cycles, maps and byte buffers',()=>{
 const payload={value:'x'.repeat(1000)},tree={a:payload,b:payload};tree.loop=tree;
 assert.ok(retainedSize(tree)>2000);assert.ok(retainedSize(tree)<3000);
 assert.ok(retainedSize(new Map([['key',new Uint8Array(4096)]]))>=4096);
 assert.equal(retainedSize(tree,1000),1000);
});
