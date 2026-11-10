import {test} from 'node:test';
import assert from 'node:assert/strict';
import {openingDestination} from '../dist/chromium/opening-destination.js';
const doc=(dest)=>({run:p=>p,pdf:{numPages:3,getOpenAction:async()=>({dest}),getDestination:async()=>[1,{name:'Fit'}],getPageIndex:async()=>2,getPage:async()=>({getViewport:()=>({width:600,height:800,convertToViewportPoint:(x,y)=>[x,800-y]}),cleanup(){}})}});
test('opening destinations honor zero-based pages, named destinations and zoom modes',async()=>{
 assert.deepEqual(await openingDestination(doc([1,{name:'XYZ'},10,700,1.5]),800,600,()=>{}),{page:2,zoom:'1.5',left:10,top:700});
 assert.equal((await openingDestination(doc('chapter'),800,600,()=>{})).page,2);
 for(const mode of ['Fit','FitB','FitH','FitBH','FitV','FitBV'])assert.ok(await openingDestination(doc([0,{name:mode},null]),800,600,()=>{}));
 assert.ok(Number((await openingDestination(doc([0,{name:'FitR'},10,20,210,220]),800,600,()=>{})).zoom)>0);
});
test('invalid initial destinations fail explicitly',async()=>{
 for(const dest of [[99,{name:'Fit'}],[0,{name:'XYZ'},null,null,-1],[0,{name:'FitR'},0,0,0,0],[0,{name:'Unknown'}]])await assert.rejects(openingDestination(doc(dest),800,600,()=>{}));
});

test('PDF.js Map-shaped opening actions retain their destination',async()=>{const d=doc(null);d.pdf.getOpenAction=async()=>new Map([['dest',[2,{name:'Fit'}]]]);assert.equal((await openingDestination(d,800,600,()=>{})).page,3);});
