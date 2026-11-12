import {test} from 'node:test';
import assert from 'node:assert/strict';
import {sourceFileDescription} from '../dist/chromium/files-panel.js';
test('legacy source descriptions are shortened without rewriting unrelated descriptions',()=>{
 for(const [old,expected]of [
  ['Exported RFC 822 conversion source. MBOX preprocessing: full provenance','Email source extracted from MBOX'],
  ['Exported RFC 822 conversion source. PST preprocessing: full provenance','Email source exported from PST'],
  ['Original RFC 822 source','Original EML source'],
  ['Original international email source','Original EML source'],
  ['Original Outlook MSG source','Original Outlook MSG source'],
  ['Original Gmail email source','Original Gmail email source'],
  ['Custom archive description','Custom archive description']
 ]){assert.equal(sourceFileDescription(old,true),expected);assert.equal(sourceFileDescription(old,false),old);}
});

test('legacy PST attached-email description retains omission and integrity details',()=>{
 const old='Attached PST email reconstructed as RFC 822 with headers, body, and nested attachments';
 for(const suffix of ['', '; contents omitted by policy; original size=123; original MD5=abc']){
  assert.equal(sourceFileDescription(old+suffix,false),'Attached email exported from PST'+suffix);
 }
 assert.equal(sourceFileDescription('Custom attachment description',false),'Custom attachment description');
});
