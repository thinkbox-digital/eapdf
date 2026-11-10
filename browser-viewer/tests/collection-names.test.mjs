import {test} from 'node:test';
import assert from 'node:assert/strict';
import {checkCollectionNames} from '../dist/chromium/collection-names.js';
const doc=names=>({collection:{value:{entries:names.map(value=>({nameTreeKey:{value}})),folders:[]}}});
test('reject ambiguous or invalid collection paths before opening members',()=>{
 for(const names of [['<1>bad/name.pdf'],['<1>../a.pdf'],['<1>A.pdf','<1>a.pdf'],['<1>'],['<1>x\\y.pdf']])assert.throws(()=>checkCollectionNames(doc(names)),/Invalid/);
 assert.doesNotThrow(()=>checkCollectionNames(doc(['<1>é.pdf','<2>é.pdf'])));
});

test('unnamed collection root is allowed while unnamed child folders are rejected',()=>{
 const d=doc(['<0>message.pdf']);d.collection.value.folders=[{parentId:null,name:{value:''}}];assert.doesNotThrow(()=>checkCollectionNames(d));
 d.collection.value.folders.push({parentId:'root',name:{value:''}});assert.throws(()=>checkCollectionNames(d),/folder name/);
});

test('Unicode byte-order marks do not turn valid folder prefixes into filename characters',()=>{
 assert.doesNotThrow(()=>checkCollectionNames(doc(['\uFEFF<0>Archive.zip'])));
 for(const names of [['\uFEFF<0>bad/name.pdf'],['\uFEFF<0>x.pdf','<0>x.pdf']])assert.throws(()=>checkCollectionNames(doc(names)),/Invalid/);
});
