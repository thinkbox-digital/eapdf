import {chromium} from 'playwright';import assert from 'node:assert/strict';
import {resolve} from 'node:path';import {access,readFile,writeFile,mkdir} from 'node:fs/promises';import {createHash} from 'node:crypto';
const extension=resolve('dist/chromium'),context=await chromium.launchPersistentContext('',{headless:true,executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',viewport:{width:1600,height:1000},args:[`--load-extension=${extension}`,`--disable-extensions-except=${extension}`]});
const results=[],hash=b=>createHash('sha256').update(b).digest('hex');
try{
 const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage();await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
 const ready=async()=>{await page.waitForFunction(()=>!document.querySelector('#file').disabled);await page.waitForTimeout(300);await page.waitForFunction(()=>!document.querySelector('#file').disabled);};
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
  const links=await page.locator('#files .file-message-link').evaluateAll(nodes=>nodes.map(n=>({messageId:n.dataset.ownerMessageId,label:n.textContent,fileId:n.closest('.file-card').dataset.associatedFileId,disabled:n.disabled})));
  assert.ok(links.length>=2,'Real archive must contain multiple attachment owners');
  for(const link of links){const expected=reference.find(r=>r.messageId===link.messageId&&r.fileId===link.fileId);assert.ok(expected,JSON.stringify({link,reference:reference.slice(0,2)}));for(const name of expected.names)assert.ok(link.label.includes(name));assert.equal(link.disabled,!expected.page);}
  const target=reference.find(r=>r.page>1&&links.some(l=>l.messageId===r.messageId));assert.ok(target);
  await page.locator('#files .file-message-link').filter({hasText:target.names[0]}).evaluateAll((buttons,id)=>buttons.find(b=>b.dataset.ownerMessageId===id).click(),target.messageId);await ready();
  assert.equal(Number(await page.locator('#page').inputValue()),target.page);assert.equal(await page.locator(`#tree button[data-message-id="${target.messageId}"]`).getAttribute('aria-current'),'true');
  assert.equal(await page.locator('#files-title').textContent(),'Files in this email');
  assert.equal(hash(await readFile(path)),before);results.push({profile,sha256:before,links:links.length,destination_page:target.page,status:'PASS'});
  await mkdir('test-results',{recursive:true});await page.screenshot({path:`test-results/attachment-owner-${profile}.png`});
 }
 await writeFile('test-results/attachment-owners.json',JSON.stringify(results,null,2)+'\n');console.log(JSON.stringify(results));
}finally{await context.close();}
