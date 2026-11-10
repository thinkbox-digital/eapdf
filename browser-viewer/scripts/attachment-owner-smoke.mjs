import {chromium} from 'playwright';import assert from 'node:assert/strict';
import {resolve} from 'node:path';import {access,readFile,writeFile,mkdir} from 'node:fs/promises';import {createHash} from 'node:crypto';
const extension=resolve('dist/chromium'),context=await chromium.launchPersistentContext('',{headless:true,executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',viewport:{width:1600,height:1000},args:[`--load-extension=${extension}`,`--disable-extensions-except=${extension}`]});
const results=[],hash=b=>createHash('sha256').update(b).digest('hex');
try{
 const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage();await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
 const ready=async()=>{await page.waitForFunction(()=>!document.querySelector('#file').disabled);await page.waitForTimeout(300);await page.waitForFunction(()=>!document.querySelector('#file').disabled);};
 for(const fixture of ['../examples/eml/eml_Agenda_and_timeline_slides_1s.pdf','../examples/msg/msg_TxtSampleEmailWithAttachment_1s.pdf','../examples/mbox/mbox_enron-sample_1s.pdf','../examples/pst/pst_quality-attached-message_1s.pdf','../examples/msg/msg_TxtSampleEmailWithAttachment_archive_1c.pdf']){
  const path=resolve(fixture),before=hash(await readFile(path));await ready();await page.locator('#file').setInputFiles(path);await ready();
  assert.equal(await page.locator('#files a, #files [role="link"], #files .file-message-link, #files .file-name-link').count(),0);
  assert.ok(await page.locator('#files strong.file-name').count()>0,'Filenames remain plain text');
  assert.ok(await page.locator('#files button[data-file-id]').count()>0,'Downloads remain available');
  assert.ok(await page.locator('#files button[data-view-file-id]').count()>0,'View controls remain available');
  assert.equal(await page.locator('#files .file-message-source, #files .file-message-owner button').count(),0);
  const preview=page.locator('#files button[data-view-file-id][title^="View "]');
  if(fixture.endsWith('_1s.pdf')&&await preview.count()){
   await preview.first().click();await ready();
   await page.locator('#attachment-viewer').waitFor({state:'visible'});
   await page.locator('#attachment-close').click();
  }
  assert.equal(hash(await readFile(path)),before);results.push({fixture,sha256:before,status:'PASS'});
 }
 for(const profile of ['1m','1mi']){
  const path=resolve(`../out/mbox/mbox_3p-dev_c216f5b5d7_${profile}.pdf`);try{await access(path);}catch{console.log('SKIP private regression:',path);continue;}
  const before=hash(await readFile(path));await ready();await page.locator('#file').setInputFiles(path);await ready();
  await page.evaluate(()=>{const input=document.createElement('input');input.type='file';input.id='owner-reference';input.hidden=true;document.body.append(input);});await page.locator('#owner-reference').setInputFiles(path);
  const reference=await page.evaluate(async()=>{
   const {BrowserSession}=await import('./browser-session.js'),session=new BrowserSession(),file=document.querySelector('#owner-reference').files[0];
   try{const doc=await session.open(file,file.name);await session.associateFiles(doc);
    return doc.inspection.files.filter(f=>!['Source','Supplement'].includes(f.afRelationship.value)&&f.owners.value?.length).flatMap(f=>f.owners.value.map(id=>{
     const message=doc.inspection.messages.find(m=>m.id===id),parts=doc.inspection.parts.filter(p=>message?.partIds.value?.includes(p.id)&&p.dpm.Mail_ContentSetType?.value==='BodyRendering'&&p.messageIds.value?.length===1&&p.messageIds.value[0]===id&&p.pageRange.state==='present');
     const sources=(message?.sourceAssetIds.value??[]).map(id=>doc.inspection.files.find(f=>f.id===id)).filter(Boolean).map(f=>f.unicodeFilename.value??f.filename.value);
     return {fileId:f.id,messageId:id,page:parts.length?Math.min(...parts.map(p=>p.pageRange.value.start))+1:null,names:sources.length?sources:[file.name]};
    }));
   }finally{session.close();document.querySelector('#owner-reference').remove();}
  });
  assert.equal(await page.locator('#files a, #files .file-message-link, #files .file-name-link, #files [role="link"]').count(),0);
  const owners=await page.locator('#files .file-message-owner').evaluateAll(nodes=>nodes.map(n=>({messageId:n.dataset.ownerMessageId,label:n.textContent,fileId:n.closest('.file-card').dataset.associatedFileId,buttons:n.querySelectorAll('button').length})));
  assert.ok(owners.length>=2,'Real archive must contain multiple attachment owners');
  for(const owner of owners){const expected=reference.find(r=>r.messageId===owner.messageId&&r.fileId===owner.fileId);assert.ok(expected);for(const name of expected.names)assert.ok(!owner.label.includes(name),'Owner source filename must not be displayed');assert.equal(owner.buttons,0);}
  assert.equal(hash(await readFile(path)),before);results.push({profile,sha256:before,owners:owners.length,status:'PASS'});
  await mkdir('test-results',{recursive:true});await page.screenshot({path:`test-results/attachment-owner-${profile}.png`});
 }
 await writeFile('test-results/attachment-owners.json',JSON.stringify(results,null,2)+'\n');console.log(JSON.stringify(results));
}finally{await context.close();}
