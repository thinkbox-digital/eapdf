import {test} from 'node:test';
import assert from 'node:assert/strict';
import {folderFiles} from '../dist/chromium/navigation.js';
const present=value=>({state:'present',value});
const fixture=()=>({identification:{declaredProfile:present('ci')},diagnostics:[],files:['a','b','c','d','root'].map(id=>({id})),collection:present({folders:[{id:'one',parentId:null},{id:'two',parentId:null},{id:'nested',parentId:'one'}],entries:[['a','one'],['b','two'],['c','one'],['d','nested'],['root',null]].map(([fileId,folder])=>({fileId,folderIds:present(folder?[folder]:[])}))})});
test('only immediate folder siblings are returned, in sidebar order',()=>{
 const d=fixture();assert.deepEqual(folderFiles(d,'a').map(f=>f.id),['a','c']);assert.deepEqual(folderFiles(d,'d').map(f=>f.id),['d']);assert.deepEqual(folderFiles(d,'root').map(f=>f.id),['root']);
});
test('unknown, ambiguous and conflicting memberships cannot become root-folder siblings',()=>{
 for(const value of [{state:'unavailable',value:null},present(['one','two']),present(['unknown'])]){
  const d=fixture();d.collection.value.entries[0].folderIds=value;assert.deepEqual(folderFiles(d,'a'),[]);
 }
 const d=fixture();d.collection.value.entries.push({fileId:'a',folderIds:present(['two'])});assert.deepEqual(folderFiles(d,'a'),[]);assert.deepEqual(folderFiles(d,'absent'),[]);
});
test('cycles, folder diagnostics and non-collection profiles disable automatic navigation',()=>{
 const d=fixture();d.collection.value.folders[0].parentId='nested';assert.deepEqual(folderFiles(d,'a'),[]);
 const bad=fixture();bad.diagnostics=[{code:'FOLDER_CYCLE'}];assert.deepEqual(folderFiles(bad,'a'),[]);
 const archive=fixture();archive.identification.declaredProfile=present('mi');assert.deepEqual(folderFiles(archive,'a'),[]);
});
