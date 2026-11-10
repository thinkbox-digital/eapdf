import {test} from 'node:test';
import assert from 'node:assert/strict';
import {filesInMessageOrder,messageMemberOrder} from '../dist/chromium/files-message-order.js';
const present=value=>({state:'present',value}),file=(id,owners=[],pdf=false)=>({id,unicodeFilename:present(id+(pdf?'.pdf':'.eml')),filename:present(''),owners:present(owners),streams:[{mimeType:present(pdf?'application/pdf':'message/rfc822')}]});
test('each category follows message order while ties and unknown owners remain stable',()=>{
 const files=[file('later',['b']),file('first',['a']),file('same',['a']),file('unknown')],inspection={messages:[{id:'a'},{id:'b'}],files,collection:present(null)};
 assert.deepEqual(filesInMessageOrder(inspection,files,()=>0).map(f=>f.id),['first','same','later','unknown']);
 assert.deepEqual(filesInMessageOrder(inspection,files,f=>f.id==='later'?0:1).map(f=>f.id),['later','first','same','unknown']);
});
test('collection folders use natural order and shared files use their first placement',()=>{
 const files=[file('ten',[],true),file('two',[],true),file('shared',[],true)],collection={folders:[{id:'ten',parentId:null,name:present('10')},{id:'two',parentId:null,name:present('2')}],entries:[{fileId:'ten',folderIds:present(['ten'])},{fileId:'two',folderIds:present(['two'])},{fileId:'shared',folderIds:present(['ten'])},{fileId:'shared',folderIds:present(['two'])}]};
 const inspection={messages:[],files,collection:present(collection)};assert.deepEqual([...messageMemberOrder(inspection).keys()],['two','shared','ten']);assert.deepEqual(filesInMessageOrder(inspection,files,()=>0).map(f=>f.id),['two','shared','ten']);
});
test('source owners follow verified indexed collection routes',()=>{
 const a=file('a',[],true),b=file('b',[],true),sourceA=file('sourceA',['ma']),sourceB=file('sourceB',['mb']);
 const inspection={messages:[{id:'ma',mailGuid:present('ga')},{id:'mb',mailGuid:present('gb')}],files:[a,b,sourceA,sourceB],collection:present({folders:[{id:'f1',parentId:null,name:present('1')},{id:'f2',parentId:null,name:present('2')}],entries:[{fileId:'b',folderIds:present(['f1'])},{fileId:'a',folderIds:present(['f2'])}]})};
 const indexed=[{mailGuid:'ga',path:[{fileId:'a'}]},{mailGuid:'gb',path:[{fileId:'b'}]}];assert.deepEqual(filesInMessageOrder(inspection,[sourceA,sourceB],()=>0,indexed).map(f=>f.id),['sourceB','sourceA']);
});

test('each group uses owning message dates, not folder order, with invalid dates last',()=>{
 const files=[file('new',['new']),file('undated',['undated']),file('old',['old']),file('shared',['new','old']),file('invalid',['invalid'])];
 const messages=['new','undated','old','invalid'].map(id=>({id,metadataRecordId:id}));
 const inspection={messages,files,collection:present(null)},details=new Map([['new',{Sent:'2026-02-08T12:00:00Z'}],['old',{Sent:'2026-02-04T15:00:00+03:00'}],['invalid',{Sent:'invalid'}]]);
 assert.deepEqual(filesInMessageOrder(inspection,files,()=>0,[],details).map(f=>f.id),['shared','old','new','undated','invalid']);
 assert.deepEqual(filesInMessageOrder(inspection,files,f=>f.id==='new'?0:1,[],details).map(f=>f.id),['new','shared','old','undated','invalid']);
});
test('collection PDFs and original sources share verified indexed dates across folders',()=>{
 const a=file('a',[],true),b=file('b',[],true),sourceA=file('sourceA',['ma']),sourceB=file('sourceB',['mb']);
 const inspection={messages:[{id:'ma',mailGuid:present('ga')},{id:'mb',mailGuid:present('gb')}],files:[a,b,sourceA,sourceB],collection:present({folders:[],entries:[]})};
 const indexed=[{mailGuid:'ga',path:[{fileId:'a'}],sent:'2026-02-08T12:00:00Z'},{mailGuid:'gb',path:[{fileId:'b'}],sent:'2026-02-04T12:00:00Z'}];
 assert.deepEqual(filesInMessageOrder(inspection,[a,b],()=>0,indexed).map(f=>f.id),['b','a']);
 assert.deepEqual(filesInMessageOrder(inspection,[sourceA,sourceB],()=>0,indexed).map(f=>f.id),['sourceB','sourceA']);
});
