import test from 'node:test';import assert from 'node:assert/strict';
import {resolveEmbeddedLink,destinationPage} from '../dist/chromium/embedded-navigation.js';
const document=(parent=null)=>({parent,inspection:{files:[]},run:p=>p,pdf:{numPages:3,getDestination:async name=>name==='chapter'?[2,{name:'Fit'}]:null,getPageIndex:async ref=>ref.num-1}});
test('named and explicit destinations resolve pages and reject missing/out-of-range targets',async()=>{
 const doc=document();assert.equal(await destinationPage(doc,'chapter'),3);assert.equal(await destinationPage(doc,[{num:2},{name:'Fit'}]),2);
 await assert.rejects(()=>destinationPage(doc,'missing'),/unavailable/);await assert.rejects(()=>destinationPage(doc,[3]),/outside/);await assert.rejects(()=>destinationPage(doc,[NaN]),/outside/);
});
test('embedded navigation follows exact raw name keys through children and parents',async()=>{
 const root=document(),child=document(root),leaf=document(child);const stream={efKeys:['/UF']};
 const file=key=>({id:key,streams:[stream],origins:[{kind:'name-tree',nameTreeKey:{raw:[{bytesHex:key}]}}]});root.inspection.files=[file('feff0041')];child.inspection.files=[file('42')];
 let checks=0;const session={ensure:async()=>{},child:async doc=>doc===root?child:leaf};
 const result=await resolveEmbeddedLink(session,child,{error:null,steps:[{relationship:'P'},{relationship:'C',nameHex:'feff0041'},{relationship:'C',nameHex:'42'}],destination:'chapter'},()=>checks++);
 assert.equal(result.doc,leaf);assert.equal(result.page,3);assert.ok(checks>=6);
 root.inspection.files.push(file('feff0041'));await assert.rejects(()=>resolveEmbeddedLink(session,root,{error:null,steps:[{relationship:'C',nameHex:'feff0041'}],destination:'chapter'},()=>{}),/ambiguous/);
});
test('embedded navigation fails safely on unavailable parents, external roots and cancelled work',async()=>{
 const session={ensure:async()=>{}},doc=document(),action={error:null,steps:[{relationship:'P'}],destination:'chapter'};
 await assert.rejects(()=>resolveEmbeddedLink(session,doc,action,()=>{}),/no open parent/);
 await assert.rejects(()=>resolveEmbeddedLink(session,doc,{...action,error:'External PDF unavailable'},()=>{}),/External PDF/);
 await assert.rejects(()=>resolveEmbeddedLink(session,doc,action,()=>{throw new Error('Cancelled');}),/Cancelled/);
});
