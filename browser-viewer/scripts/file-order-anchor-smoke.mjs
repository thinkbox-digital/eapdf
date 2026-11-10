import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {resolve} from 'node:path';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {createHash} from 'node:crypto';
const input=process.argv[2]??process.env.FILE_ORDER_PDF,extension=resolve('dist/chromium'),before=createHash('sha256').update(await readFile(input)).digest('hex');
const context=await chromium.launchPersistentContext('',{headless:true,viewport:{width:1500,height:900},executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--load-extension=${extension}`,`--disable-extensions-except=${extension}`]});
try{
 await context.setOffline(true);const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);await page.waitForFunction(()=>!document.querySelector('#file').disabled);
 const ready=()=>page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false',null,{timeout:180000});await page.evaluate(()=>document.addEventListener('change',event=>{if(event.target.id==='file'&&event.target.files[0])window.orderBlob=event.target.files[0];},true));await page.locator('#file').setInputFiles(input);await ready();
 await page.waitForFunction(()=>document.querySelector('#subject-index-progress').dataset.complete==='true',null,{timeout:180000});
 const order=await page.evaluate(async()=>{
  const {BrowserSession}=await import('./browser-session.js'),{indexMessages}=await import('./message-index.js');const session=new BrowserSession({},{reuseWorker:true});
  try{const doc=await session.open(window.orderBlob,'order.pdf'),indexed=await indexMessages(session,doc,()=>{},()=>{}),dates=new Map(),guids=new Map();
   const remember=(map,id,date)=>map.set(id,Math.min(map.get(id)??Infinity,date));
   for(const m of indexed.messages){const date=Date.parse(m.sent??'');if(!Number.isFinite(date))continue;if(m.path[0])remember(dates,m.path[0].fileId,date);if(m.mailGuid)remember(guids,m.mailGuid,date);}
   const owners=new Map(doc.inspection.messages.map(m=>[m.id,guids.get(m.mailGuid.value)]));
   for(const file of doc.inspection.files)for(const owner of file.owners.value??[]){const date=owners.get(owner);if(date!==undefined)remember(dates,file.id,date);}
   const groupOrders=[...document.querySelectorAll('#files .file-category')].map(group=>[...group.querySelectorAll('[data-associated-file-id]')].map(r=>dates.get(r.dataset.associatedFileId)??Infinity));
   return {members:indexed.messages.length,groupOrders};
  }finally{session.close();}
 });assert.ok(order.members>0);for(const dates of order.groupOrders)for(let i=1;i<dates.length;i++)assert.ok((dates[i]??Infinity)>=(dates[i-1]??Infinity),'Each file group is chronological, undated last');
 await page.locator('#thread-preview-enable').click();await page.waitForFunction(()=>document.querySelector('#thread-preview').dataset.ready==='true',null,{timeout:180000});
 await page.locator('#thread-preview-rows .conversation-summary').first().click();await ready();
 // Choose a later mounted heading, scrolling first so the anchor isn't at the list top.
 let id=await page.locator('#thread-preview-rows [data-thread-root]').last().getAttribute('data-thread-root');const root=page.locator(`#thread-preview-rows [data-thread-root="${id}"]`);await root.scrollIntoViewIfNeeded();await page.waitForTimeout(150);
 const state=await page.evaluate(id=>{const row=document.querySelector(`[data-thread-root="${id}"]`);window.savedThreadTree=document.querySelector('#thread-conversation .conversation-tree');return {top:row.getBoundingClientRect().top,left:document.querySelector('#navigation-panel').scrollTop,view:document.querySelector('#viewport').scrollTop,page:document.querySelector('#page').value};},id);
 await root.locator('.conversation-expand').click();await page.waitForTimeout(200);
 const after=await page.evaluate(id=>({top:document.querySelector(`[data-thread-root="${id}"]`).getBoundingClientRect().top,left:document.querySelector('#navigation-panel').scrollTop,view:document.querySelector('#viewport').scrollTop,page:document.querySelector('#page').value,sameTree:window.savedThreadTree===document.querySelector('#thread-conversation .conversation-tree')}),id);
 assert.ok(state.left>0);assert.ok(Math.abs(after.top-state.top)<5,JSON.stringify({state,after}));assert.equal(after.page,state.page);assert.equal(after.view,state.view);assert.equal(after.sameTree,true);
 await root.locator('.conversation-expand').click();await page.waitForTimeout(200);assert.ok(Math.abs((await root.boundingBox()).y-state.top)<5);assert.deepEqual(errors,[]);assert.equal(createHash('sha256').update(await readFile(input)).digest('hex'),before);
 await mkdir('test-results',{recursive:true});await writeFile('test-results/file-order-anchor.json',JSON.stringify({status:'PASS',members:order.members,state,after,inputSha256:before},null,2));console.log('PASS: per-group file order and stable expansion anchor.');
}finally{await context.close();}
