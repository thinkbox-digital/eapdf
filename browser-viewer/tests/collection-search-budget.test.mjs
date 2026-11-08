import {test} from 'node:test';
import assert from 'node:assert/strict';
import {searchCollection} from '../dist/chromium/collection-search.js';

const file=id=>({id,filename:{value:id+'.pdf'},unicodeFilename:{value:null},streams:[{mimeType:{value:'application/pdf'},locator:{path:id}}]});
const document=(name,pdf,files=[])=>({name,pdf,run:p=>p,inspection:{collection:files.length?{state:'present',value:{entries:files.map(f=>({fileId:f.id}))}}:{state:'missing'},coverage:{associatedFiles:'present'},files}});
async function withClock(run){
  const original=Object.getOwnPropertyDescriptor(performance,'now');let elapsed=0;
  Object.defineProperty(performance,'now',{configurable:true,value:()=>elapsed});
  try{await run(value=>{elapsed+=value;});}
  finally{if(original)Object.defineProperty(performance,'now',original);else delete performance.now;}
}

test('fallback stops opening unreadable children once its time budget expires',()=>withClock(async advance=>{
  const root=document('root.pdf',{numPages:0},['a','b','c'].map(file)),opened=[];
  const result=await searchCollection({child:async(_parent,f)=>{opened.push(f.id);advance(120001);throw Error('malformed PDF');}},root,'needle',()=>{},()=>{},true);
  assert.deepEqual(opened,['a']);
  assert.deepEqual(result.matches,[]);
  assert.deepEqual(result.incomplete,['a.pdf: malformed PDF','Search time budget reached.']);
  assert.equal(result.source,'scanned');
}));

test('fallback releases zero-page children and stops before opening later members',()=>withClock(async advance=>{
  const root=document('root.pdf',{numPages:0},['a','b'].map(file));
  const empty=document('empty.pdf',{numPages:0}),opened=[],released=[];
  const result=await searchCollection({child:async(_parent,f)=>{opened.push(f.id);advance(120001);return empty;},release:async child=>released.push(child)},root,'needle',()=>{},()=>{},true);
  assert.deepEqual(opened,['a']);assert.deepEqual(released,[empty]);
  assert.deepEqual(result.matches,[]);assert.deepEqual(result.incomplete,['Search time budget reached.']);
}));

test('fallback keeps explicit partial results and releases the active page and child on timeout',()=>withClock(async advance=>{
  let cleanups=0;const pages=[],released=[],progress=[];
  const child=document('child.pdf',{numPages:2,getPage:async n=>{pages.push(n);return {getTextContent:async()=>{advance(120001);return {items:[{str:'needle needle'}]};},cleanup(){cleanups++;}};}});
  const root=document('root.pdf',{numPages:0},[file('a')]);
  const result=await searchCollection({child:async()=>child,release:async d=>released.push(d)},root,'needle',()=>{},n=>progress.push(n),true);
  assert.deepEqual(pages,[1]);assert.equal(cleanups,1);assert.deepEqual(released,[child]);assert.deepEqual(progress,[1]);
  assert.equal(result.matches.length,1);assert.equal(result.matches[0].count,2);
  assert.deepEqual(result.matches[0].path,[{fileId:'a',streamPath:'a'}]);assert.equal(result.matches[0].page,1);
  assert.deepEqual(result.incomplete,['Search time budget reached.']);
}));

test('fallback cancellation after text extraction releases its page and child',async()=>{
  let cancelled=false,cleanups=0;const released=[];
  const child=document('child.pdf',{numPages:1,getPage:async()=>({getTextContent:async()=>{cancelled=true;return {items:[{str:'needle'}]};},cleanup(){cleanups++;}})});
  const root=document('root.pdf',{numPages:0},[file('a')]);
  await assert.rejects(searchCollection({child:async()=>child,release:async d=>released.push(d)},root,'needle',()=>{if(cancelled)throw Error('cancelled');},()=>{},true),/cancelled/);
  assert.equal(cleanups,1);assert.deepEqual(released,[child]);
});
