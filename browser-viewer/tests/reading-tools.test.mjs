import {boundedStructureTree} from '../dist/chromium/structure-tree.js';
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {textMatches,DocumentSearch} from '../dist/chromium/document-search.js';
import {sourceMessageId} from '../dist/chromium/source-email.js';
import {searchCollection} from '../dist/chromium/collection-search.js';
test('search preserves original offsets after expanding Unicode case conversion',()=>{
  assert.deepEqual(textMatches('İstanbul CAT cat','cat'),[{start:9,end:12},{start:13,end:16}]);
  assert.deepEqual(textMatches('one two one','one',1),[{start:0,end:3}]);
});
test('search visits occurrences on the current page before wrapping and reuses bounded cached text',async()=>{
  let reads=0;const pdf={numPages:2,getPage:async n=>({getTextContent:async()=>{reads++;return {items:[{str:n===1?'one one':'one'}]};},cleanup(){}})},doc={pdf,run:p=>p};
  const search=new DocumentSearch();const a=await search.find(doc,'one',1,1,()=>{}),b=await search.find(doc,'one',1,1,()=>{}),c=await search.find(doc,'one',1,1,()=>{}),d=await search.find(doc,'one',2,1,()=>{});
  assert.deepEqual([a,b,c,d],[{page:1,index:0,count:2},{page:1,index:1,count:2},{page:2,index:0,count:1},{page:1,index:0,count:2}]);assert.equal(reads,2);
  const previous=await search.find(doc,'one',1,-1,()=>{});assert.deepEqual(previous,{page:2,index:0,count:1});
});
test('search cancellation stops page traversal',async()=>{
  const search=new DocumentSearch();let pages=0;const doc={pdf:{numPages:100,getPage:async()=>{pages++;throw new Error('should not read');}},run:p=>p};
  await assert.rejects(search.find(doc,'x',1,1,()=>{throw new Error('cancelled');}),/cancelled/);assert.equal(pages,0);
});
test('email ownership requires one complete syntactically valid source Message-ID header',()=>{
  const bytes=s=>new TextEncoder().encode(s);
  assert.equal(sourceMessageId(bytes('Subject: A\r\nMessage-ID: <a@example.org>\r\n\r\nBody')),'<a@example.org>');
  assert.equal(sourceMessageId(Uint8Array.from([...bytes('Message-ID: <a@example.org>\r\n\r\n'),255])),'<a@example.org>');
  assert.equal(sourceMessageId(Uint8Array.from([...bytes('Message-ID: <'),255,...bytes('@example.org>\r\n\r\n')])),undefined);
  assert.equal(sourceMessageId(bytes('%PDF-1.7\nMessage-ID: <fake@example.org>\n\n')),undefined);
  for(const s of ['Message-ID: <a@example.org>','Message-ID: <a@example.org>\nMessage-ID: <b@example.org>\n\n','Subject: x\n\nMessage-ID: <fake@example.org>','Message-ID: invalid\n\n'])assert.equal(sourceMessageId(bytes(s)),undefined);
});
test('collection search preserves member paths and reports unreadable children as incomplete',async()=>{
  const doc=(name,text,collection)=>({name,pdf:{numPages:1,getPage:async()=>({getTextContent:async()=>({items:[{str:text}]}),cleanup(){}})},run:p=>p,inspection:{collection:collection?{state:'present',value:{}}:{state:'missing',value:null},coverage:{associatedFiles:'present'},files:[]}});
  const root=doc('root','cover',true),child=doc('child','wanted email',false);
  root.inspection.files=[{id:'a',filename:{value:'a.pdf'},unicodeFilename:{value:null},streams:[{mimeType:{value:'application/pdf'},locator:{path:'one'}}]},{id:'b',filename:{value:'b.pdf'},unicodeFilename:{value:null},streams:[{mimeType:{value:'application/pdf'},locator:{path:'two'}}]}];
  const result=await searchCollection({child:async(_d,f)=>{if(f.id==='b')throw new Error('broken child');return child;}},root,'wanted',()=>{},()=>{});
  assert.equal(result.matches.length,1);assert.deepEqual(result.matches[0].path,[{fileId:'a',streamPath:'one'}]);assert.match(result.incomplete[0],/broken child/);
});

test('structure rendering accepts deep trees and rejects cycles or excessive text',()=>{
  const valid={role:'Document',children:[{role:'P',children:[{type:'content',id:'p1'}]}]};assert.equal(boundedStructureTree(valid),true);assert.equal(boundedStructureTree(null),true);
  const cycle={children:[]};cycle.children.push(cycle);assert.equal(boundedStructureTree(cycle),false);
  let deep={};for(let i=0;i<66;i++)deep={children:[deep]};assert.equal(boundedStructureTree(deep),true);
  assert.equal(boundedStructureTree({alt:'x'.repeat(1_000_001)}),false);
});

test('row offsets stay accurate after scattered measurements and remeasurement',async()=>{
  const {RowHeights}=await import('../dist/chromium/row-heights.js');
  const h=new RowHeights(10000,40),values=Array(10000).fill(40);
  for(let i=0;i<10000;i+=37){values[i]=20+i%91;h.set(i,values[i]);}
  values[74]=150;h.set(74,150);
  let sum=0;for(let i=0;i<=values.length;i++){assert.equal(h.offset(i),sum);sum+=values[i]??0;}
});
test('email index visits unopened collection members without reading page text and preserves exact destinations',async()=>{
  const {indexMessages}=await import('../dist/chromium/message-index.js');
  const leaf={name:'same.pdf',inspection:{collection:{state:'missing'},coverage:{metadata:'present'},diagnostics:[],messages:[{id:'m',metadataRecordId:'r'}]},details:new Map([['r',{Subject:'Wanted',From:'Someone',Sent:'2026-11-02'}]])};
  const file=id=>({id,filename:{value:'same.pdf'},unicodeFilename:{value:null},streams:[{mimeType:{value:'application/pdf'},locator:{path:id}}]});
  const root={name:'root.pdf',inspection:{collection:{state:'present',value:{entries:[{fileId:'a'},{fileId:'b'},{fileId:'broken'}]}},coverage:{metadata:'present',associatedFiles:'present'},diagnostics:[],files:['a','b','broken','unrelated'].map(file)}};
  const visited=[];
  const result=await indexMessages({child:async(_parent,f)=>{visited.push(f.id);if(f.id==='broken')throw new Error('unreadable');return leaf;}},root,()=>{},()=>{});
  assert.deepEqual(visited,['a','b','broken']);assert.equal(result.messages.length,2);
  assert.deepEqual(result.messages.map(m=>m.path[0].fileId),['a','b']);assert.equal(result.messages[0].messageId,'m');assert.match(result.messages[0].searchText,/wanted someone/);assert.match(result.incomplete[0],/unreadable/);
  await assert.rejects(indexMessages({},root,()=>{throw new Error('cancelled');},()=>{}),/cancelled/);
});

test('document and collection search inspect pages and matches beyond former cutoffs',async()=>{
  assert.equal(textMatches('hit '.repeat(2501),'hit').length,2501);
  const pdf={numPages:2501,getPage:async n=>({getTextContent:async()=>({items:[{str:n===2501?'last':'ordinary'}]}),cleanup(){}})};
  const doc={name:'large.pdf',pdf,run:p=>p,inspection:{collection:{state:'missing'},files:[]}};
  assert.deepEqual(await new DocumentSearch().find(doc,'last',1,1,()=>{}),{page:2501,index:0,count:1});
  const result=await searchCollection({},doc,'ordinary',()=>{},()=>{});
  assert.equal(result.matches.length,2500);assert.deepEqual(result.incomplete,[]);
});

test('search does not charge released page text against later pages',async()=>{
  const text='x'.repeat(4_000_001);
  const doc={name:'text.pdf',pdf:{numPages:3,getPage:async n=>({getTextContent:async()=>({items:[{str:text+(n===3?' wanted':'')}]}),cleanup(){}})},run:p=>p,inspection:{collection:{state:'missing'},files:[]}};
  assert.deepEqual(await new DocumentSearch().find(doc,'wanted',1,1,()=>{}),{page:3,index:0,count:1});
  const result=await searchCollection({},doc,'wanted',()=>{},()=>{});
  assert.equal(result.matches.length,1);assert.equal(result.matches[0].page,3);assert.deepEqual(result.incomplete,[]);
});
