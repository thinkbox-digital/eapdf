import {expandFolders} from './ui-checks.mjs';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {createHash} from 'node:crypto';
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
export async function checkTripAttachments(browser){
 const expected=JSON.parse(await readFile('tests/attachments/trip-info.json','utf8'));
 const source=resolve(expected.sourcePath);assert.equal(hash(await readFile(source)),expected.sourceSha256);
 await browser.open(source);
 const target=await browser.evaluate(()=>{const b=[...document.querySelectorAll('[data-embedded-file-id]')].find(b=>b.title.endsWith('TRIP INFO_000046_1s.pdf'));return {file:b.dataset.embeddedFileId,stream:b.dataset.embeddedStream};});
 await expandFolders(browser);
 await browser.click(`[data-embedded-file-id=${JSON.stringify(target.file)}][data-embedded-stream=${JSON.stringify(target.stream)}]`);await browser.ready();
 assert.equal(await browser.evaluate(()=>document.querySelector('#canvas').closest('.pdf-page').getAttribute('aria-label')),'Page 1 of 2');
 const cards=await browser.evaluate(()=>[...document.querySelectorAll('#files .file-card')].map(card=>{const b=card.querySelector('[data-file-id]');return {name:card.querySelector('.file-name').textContent,file:b?.dataset.fileId,stream:b?.dataset.stream,view:!!card.querySelector('[data-view-file-id]')};}));
 const results=[];
 for(const file of expected.attachments){
  const card=cards.find(c=>c.name===file.name);assert.ok(card,`${file.name} must appear immediately after opening the child`);assert.equal(card.view,false,'Office files use normal download handling');
  const data=await browser.download(`[data-file-id=${JSON.stringify(card.file)}][data-stream=${JSON.stringify(card.stream)}]`);await browser.ready();assert.equal(data.length,file.bytes);assert.equal(hash(data),file.sha256);results.push({...file,status:'PASS'});
 }
 assert.equal(hash(await readFile(source)),expected.sourceSha256);
 return {status:'PASS',sourceSha256:expected.sourceSha256,child:expected.childName,attachments:results};
}
