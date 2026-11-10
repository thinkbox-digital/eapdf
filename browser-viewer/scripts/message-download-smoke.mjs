import {chromium} from 'playwright';import assert from 'node:assert/strict';
import {resolve} from 'node:path';import {readFile,writeFile,mkdir} from 'node:fs/promises';import {createHash} from 'node:crypto';
const extension=resolve('dist/chromium'),reports=[];
const context=await chromium.launchPersistentContext('',{headless:true,acceptDownloads:true,viewport:{width:1600,height:1000},executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--load-extension=${extension}`,`--disable-extensions-except=${extension}`]});
try{
 await context.setOffline(true);const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage();
 await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
 await page.evaluate(()=>{const input=document.createElement('input');input.type='file';input.id='download-reference';input.hidden=true;document.body.append(input);});
 const ready=()=>page.waitForFunction(()=>!document.querySelector('#file').disabled&&document.querySelector('#viewport').getAttribute('aria-busy')==='false');
 assert.equal(await page.locator('#download-message').isDisabled(),true);
 for(const profile of ['s','c','ci','m','mi']){
  const path=resolve(profile==='s'?'../examples/eml/eml_Agenda_and_timeline_slides_1s.pdf':`tests/fulltext-index/mbox_sample_1${profile}.pdf`);
  await page.locator('#file').setInputFiles(path);await ready();
  let fileId,messageId;
  if(profile==='c'||profile==='ci'){
   assert.equal(await page.locator('#download-message').isDisabled(),true);
   await page.locator('#tree details').evaluateAll(nodes=>nodes.forEach(n=>n.open=true));
   const item=page.locator('#tree button[data-embedded-file-id]').first();fileId=await item.getAttribute('data-embedded-file-id');await item.click();await ready();
  }else if(profile!=='s'){
   const item=page.locator('#tree button[data-message-id]').last();messageId=await item.getAttribute('data-message-id');await item.click();await ready();
  }
  await page.locator('#download-reference').setInputFiles(path);
  const expected=await page.evaluate(async({fileId,messageId})=>{
   const {BrowserSession}=await import('./browser-session.js'),{messagePages}=await import('./navigation.js'),{pdfSearchText}=await import('./document-search.js');
   const session=new BrowserSession();try{
    const source=document.querySelector('#download-reference').files[0];let doc=await session.open(source,source.name);
    if(fileId){const f=doc.inspection.files.find(f=>f.id===fileId);doc=await session.child(doc,f,f.streams[0]);}
    const numbers=messageId?messagePages(doc.inspection,doc.inspection.messages.find(m=>m.id===messageId)):Array.from({length:doc.pdf.numPages},(_,i)=>i+1),texts=[];
    for(const n of numbers)texts.push(pdfSearchText((await (await doc.pdf.getPage(n)).getTextContent()).items));
    const hash=messageId?null:[...new Uint8Array(await crypto.subtle.digest('SHA-256',await (await session.sourceBlob(doc)).arrayBuffer()))].map(x=>x.toString(16).padStart(2,'0')).join('');
    return {texts,hash,pages:numbers};
   }finally{session.close();}
  },{fileId,messageId});
  const pending=page.waitForEvent('download');await page.locator('#download-message').click();const download=await pending;assert.match(download.suggestedFilename(),/\.pdf$/i);const saved=await download.path();
  await mkdir('test-results',{recursive:true});await download.saveAs(resolve(`test-results/message-download-${profile}.pdf`));
  if(expected.hash)assert.equal(createHash('sha256').update(await readFile(saved)).digest('hex'),expected.hash,'Complete message PDF bytes stay unchanged');
  await page.locator('#download-reference').setInputFiles(saved);
  const actual=await page.evaluate(async()=>{
   const {loadManagedPdf}=await import('./pdfjs-adapter.js'),{pdfSearchText}=await import('./document-search.js'),managed=loadManagedPdf(document.querySelector('#download-reference').files[0]);
   try{const pdf=await managed.task.promise,texts=[];for(let n=1;n<=pdf.numPages;n++)texts.push(pdfSearchText((await (await pdf.getPage(n)).getTextContent()).items));return texts;}finally{await managed.close();}
  });assert.deepEqual(actual,expected.texts);reports.push({profile,pages:actual.length,exactBytes:!!expected.hash,status:'PASS'});
 }
 await page.locator('#close').click();assert.equal(await page.locator('#download-message').isDisabled(),true);
 await mkdir('test-results',{recursive:true});await writeFile('test-results/message-download.json',JSON.stringify(reports,null,2)+'\n');console.log(JSON.stringify(reports));
}finally{await context.close();}
