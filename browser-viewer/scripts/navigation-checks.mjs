import {expandFolders} from './ui-checks.mjs';
import assert from 'node:assert/strict';
import {readFile,rm} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {createHash} from 'node:crypto';
import {makeNavigationFixtures} from './navigation-fixtures.mjs';
export async function checkNavigation(browser){
 const directory=await makeNavigationFixtures(),results=[];
 const state=()=>browser.evaluate(()=>({status:document.querySelector('#status').textContent,path:document.querySelector('#breadcrumbs').textContent,visible:!document.querySelector('#canvas').hidden,pages:document.querySelector('#pages').textContent,documents:document.querySelectorAll('[data-document-id]').length,unopened:document.querySelectorAll('[data-embedded-file-id]').length,text:document.querySelector('#text-layer').textContent}));
 try{
  for(const rotation of [90,180,270]){
   await browser.open(join(directory,`rotated-${rotation}.pdf`));
   const bounds=await browser.evaluate(()=>{const layer=document.querySelector('#text-layer'),canvas=document.querySelector('#canvas');const box=e=>{const r=e.getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,height:r.height};};return {layer:box(layer),canvas:box(canvas),text:layer.textContent,spans:[...layer.querySelectorAll('span')].map(box)};});
   for(const key of ['x','y','width','height'])assert.ok(Math.abs(bounds.layer[key]-bounds.canvas[key])<2,`Rotation ${rotation}: ${key}`);
   assert.match(bounds.text,/Navigation test/);assert.ok(bounds.spans.some(s=>s.width>0&&s.height>0&&s.x>=bounds.canvas.x-2&&s.y>=bounds.canvas.y-2&&s.x+s.width<=bounds.canvas.x+bounds.canvas.width+2&&s.y+s.height<=bounds.canvas.y+bounds.canvas.height+2));
  }
  results.push({case:'rotated-selectable-text-alignment',status:'PASS'});
  const large=join(directory,'large.pdf');await browser.open(large);assert.equal((await state()).visible,true);assert.match((await state()).text,/Navigation test/);
  const expected='sha256:'+createHash('sha256').update(await readFile(large)).digest('hex');assert.equal(await browser.evaluate(()=>document.querySelector('[data-document-id]').dataset.documentId),expected);
  results.push({case:'40-MiB-local-file',status:'PASS'});
  await browser.open(join(directory,'large-embedded.pdf'));await browser.click('#files [data-view-file-id]');await browser.ready();assert.match((await state()).path,/large-child.pdf/);assert.equal((await state()).visible,true);await browser.click('#back');await browser.ready();results.push({case:'20-MiB-embedded-PDF',status:'PASS'});
  await browser.open(join(directory,'nested.pdf'));
  for(let i=1;i<=7;i++){await browser.click('#files [data-view-file-id]');await browser.ready();assert.match((await state()).path,new RegExp('level-'+i+'\\.pdf'));assert.equal((await state()).visible,true);if(browser.workerCount)assert.ok(await browser.workerCount()<=3);}
  for(let i=0;i<7;i++){await browser.click('#back');await browser.ready();assert.equal((await state()).visible,true);}
  assert.match((await state()).status,/nested.pdf.*Page 1/);results.push({case:'seven-annotation-only-PDF-levels-and-return',status:'PASS'});
  const collection=resolve('tests/attachments/source/pst_sample_1c.pdf');const before=createHash('sha256').update(await readFile(collection)).digest('hex');
  await browser.open(collection);assert.equal((await state()).documents,1);assert.equal((await state()).unopened,71);if(browser.workerCount){await browser.waitFor(()=>document.querySelector('#subject-index-progress').dataset.complete==='true');assert.equal(await browser.workerCount(1),1);}
  const target=await browser.evaluate(()=>{const b=[...document.querySelectorAll('[data-embedded-file-id]')].find(b=>b.textContent.startsWith('RR expansion contracts.'));return {file:b.dataset.embeddedFileId,stream:b.dataset.embeddedStream};});
  await expandFolders(browser);
 await browser.click(`[data-embedded-file-id=${JSON.stringify(target.file)}][data-embedded-stream=${JSON.stringify(target.stream)}]`);await browser.ready();assert.equal((await state()).visible,true);assert.equal((await state()).pages,'of 2');assert.match((await state()).path,/RR expansion contracts/);
  assert.equal(createHash('sha256').update(await readFile(collection)).digest('hex'),before);results.push({case:'71-message-real-PST-collection-lazy-open',status:'PASS',sourceSha256:before});
  return results;
 }finally{await rm(directory,{recursive:true,force:true});}
}
