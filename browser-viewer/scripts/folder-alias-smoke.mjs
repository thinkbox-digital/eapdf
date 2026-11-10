import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {resolve} from 'node:path';
import {access,readFile,writeFile,mkdir} from 'node:fs/promises';
import {createHash} from 'node:crypto';
const extension=resolve('dist/chromium'),reports=[];
const context=await chromium.launchPersistentContext('',{headless:true,viewport:{width:1600,height:1000},executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--load-extension=${extension}`,`--disable-extensions-except=${extension}`]});
try{
 const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage();
 await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
 await page.evaluate(async()=>{
  const {BrowserSession}=await import('./browser-session.js'),open=BrowserSession.prototype.open,child=BrowserSession.prototype.child;
  window.aliasProbe={mode:'real',children:[]};
  BrowserSession.prototype.open=async function(...args){
   const doc=await open.apply(this,args);
   if(!doc.parent&&!this.options.reuseWorker){
    window.aliasProbe.root=doc;window.aliasProbe.session=this;
    if(window.aliasProbe.mode==='virtual'){
     const source=doc.inspection.files.find(f=>f.streams.some(s=>s.mimeType.value==='application/pdf'));
     const observed=value=>({state:'present',value});
     const folders=[{id:'aliases',parentId:null,name:observed('Collection')},{id:'left',parentId:'aliases',name:observed('Label A')},{id:'right',parentId:'aliases',name:observed('Label B')}];
     const files=Array.from({length:300},(_,i)=>({...source,id:'alias-file-'+i,filename:observed(`Message ${i}.pdf`),unicodeFilename:observed(`Message ${i}.pdf`)}));
     doc.inspection={...doc.inspection,files,collection:observed({...doc.inspection.collection.value,folders,entries:files.flatMap(f=>['left','right'].map(id=>({fileId:f.id,folderIds:observed([id]),nameTreeKey:observed(`<${id}>${f.filename.value}`)})))})};
     this.rememberMessageIndex(doc,{messages:[],incomplete:[],source:'embedded'});
    }
   }
   return doc;
  };
  BrowserSession.prototype.child=async function(...args){const doc=await child.apply(this,args);window.aliasProbe.children.push(doc);return doc;};
 });
 const ready=async()=>{await page.waitForFunction(()=>!document.querySelector('#file').disabled&&document.querySelector('#viewport').getAttribute('aria-busy')==='false');};
 const openFolders=()=>page.locator('#tree details').evaluateAll(nodes=>nodes.forEach(n=>n.open=true));
 const positions=async title=>page.locator('#tree button').evaluateAll((nodes,title)=>nodes.filter(n=>n.title===title).map(n=>({folder:n.closest('details')?.querySelector('summary')?.dataset.folderId,current:n.getAttribute('aria-current')})),title);
 const clickIn=async(title,folder)=>{
  await page.locator(`#tree summary[data-folder-id="${folder}"]`).locator('..').locator('button').evaluateAll((nodes,title)=>nodes.find(n=>n.title===title).click(),title);await ready();
  const rows=await positions(title);assert.equal(rows.filter(r=>r.current==='true').length,1);assert.equal(rows.find(r=>r.current==='true').folder,folder);
  assert.equal(await page.locator('#page-surface').isVisible(),true);
 };
 const path=resolve('../out/gmail_ALL_MAIL_1c.pdf');
 try{await access(path);
  const hash=()=>readFile(path).then(b=>createHash('sha256').update(b).digest('hex')),before=await hash();
  await page.locator('#file').setInputFiles(path);await ready();
  await page.waitForFunction(()=>document.querySelector('#subject-index-progress').dataset.complete==='true');await openFolders();
  const memberships=await page.evaluate(()=>{const c=aliasProbe.root.inspection.collection.value,m=new Map();for(const e of c.entries){if(!aliasProbe.root.inspection.files.some(f=>f.id===e.fileId&&f.streams.some(s=>s.mimeType.value==='application/pdf')))continue;const ids=m.get(e.fileId)??new Set();for(const id of e.folderIds.value??[])ids.add(id);m.set(e.fileId,ids);}return [...m].map(([fileId,ids])=>({fileId,folders:[...ids]}));});
  for(const member of memberships){const actual=await page.locator('#tree button[data-embedded-file-id]').evaluateAll((nodes,id)=>nodes.filter(n=>n.dataset.embeddedFileId===id).map(n=>n.closest('details')?.querySelector('summary')?.dataset.folderId),member.fileId);assert.deepEqual(actual.sort(),member.folders.sort());}
  const member=memberships.find(m=>m.folders.length===2);
  const title=await page.locator('#tree button[data-embedded-file-id]').evaluateAll((nodes,id)=>nodes.find(n=>n.dataset.embeddedFileId===id).title,member.fileId);
  for(const folder of [...member.folders,...member.folders]){await clickIn(title,folder);assert.equal((await positions(title)).length,2);}
  assert.equal(await page.evaluate(()=>new Set(aliasProbe.children).size),1,'Both aliases reuse one cached document');
  assert.equal(await hash(),before);reports.push({fixture:'Gmail ALL_MAIL',messages:memberships.length,multipleMemberships:memberships.filter(m=>m.folders.length>1).length,sha256:before,status:'PASS'});
 }catch(error){if(error.code!=='ENOENT')throw error;console.log('SKIP private Gmail fixture');}
 await page.locator('#file').setInputFiles(resolve('tests/fulltext-index/mbox_sample_1c.pdf'));await ready();
 for(const [query,expected] of [['Pow',false],['Power',true],['wer',false]]){
  await page.locator('#message-filter').fill(query);await page.waitForTimeout(250);
  await page.waitForFunction(()=>/^\d+ matching emails\./.test(document.querySelector('#filter-status').textContent));
  assert.equal(await page.locator('#filter-results button').count()>0,expected,query);
 }
 await page.locator('#close').click();await page.evaluate(()=>{aliasProbe.mode='virtual';aliasProbe.children=[];});
 await page.locator('#file').setInputFiles(resolve('tests/fulltext-index/mbox_sample_1c.pdf'));await ready();await openFolders();
 for(const folder of ['left','right','left']){await clickIn('Message 0.pdf',folder);assert.equal((await positions('Message 0.pdf')).length,2);}
 await page.locator('#navigation-panel').evaluate(n=>{n.scrollTop=n.scrollHeight;});await page.waitForTimeout(300);
 await page.locator('#navigation-panel').evaluate(n=>{n.scrollTop=0;});await page.waitForTimeout(300);
 await clickIn('Message 0.pdf','left');
 assert.equal(await page.evaluate(()=>new Set(aliasProbe.children.filter(d=>d.parentFileId==='alias-file-0')).size),1);
 await page.evaluate(()=>aliasProbe.session.options.onEvict(aliasProbe.children.find(d=>d.parentFileId==='alias-file-0')));
 await clickIn('Message 0.pdf','left');
 reports.push({fixture:'synthetic virtual aliases',messages:300,folders:2,status:'PASS'});
 await mkdir('test-results',{recursive:true});await writeFile('test-results/folder-aliases.json',JSON.stringify(reports,null,2)+'\n');console.log(JSON.stringify(reports));
}finally{await context.close();}
