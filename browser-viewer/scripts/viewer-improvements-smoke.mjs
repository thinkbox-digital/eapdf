import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {resolve} from 'node:path';
import {mkdir,writeFile} from 'node:fs/promises';
const baseline=process.env.EAPDF_VIEWER_BASELINE,extension=resolve(baseline??'dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--load-extension=${extension}`,`--disable-extensions-except=${extension}`]});
try{
 await context.setOffline(true);const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage();await page.setViewportSize({width:1500,height:900});
 await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
 await page.evaluate(async()=>{const {BrowserSession}=await import('./browser-session.js'),open=BrowserSession.prototype.open;BrowserSession.prototype.open=async function(...args){const doc=await open.apply(this,args);if(!this.options.reuseWorker&&!args[3])window.mainDoc=doc;return doc;};});
 const ready=()=>page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false',null,{timeout:180000});
 await page.locator('#file').setInputFiles(resolve('tests/fulltext-index/mbox_sample_1m.pdf'));await ready();
 await page.locator('#zoom').selectOption('1.5');await ready();
 await page.locator('#viewport').evaluate(e=>e.scrollTop=180);await page.locator('#message-filter').fill('Power');
 await page.waitForFunction(()=>document.querySelector('#filter-status').textContent.includes('matching emails'));await ready();
 const filterScroll=await page.locator('#viewport').evaluate(e=>e.scrollTop);if(!baseline)assert.equal(filterScroll,180);
 // A whole-document result must escape an email's restricted page range.
 await page.locator('#filter-results button').first().click();await ready();
 const target=await page.evaluate(async()=>{
  const doc=window.mainDoc,number=doc.pdf.numPages,p=await doc.pdf.getPage(number);
  const content=await p.getTextContent();let earlier='';for(let n=1;n<number;n++)earlier+=(await (await doc.pdf.getPage(n)).getTextContent()).items.map(x=>x.str??'').join(' ');
  return {number,query:content.items.map(x=>x.str??'').find(x=>x.trim().length>8&&!earlier.includes(x.trim()))?.trim()};
 });
 assert.ok(target.query,'fixture must have unique text on the last page');
 await page.locator('#open-search').click();await page.locator('#search-scope').selectOption('document');
 await page.locator('#search').fill(target.query);await page.locator('#search-submit').click();await ready();
 await page.waitForFunction(()=>document.querySelector('#search-status').textContent.startsWith('Match '));
 assert.equal(await page.locator('#message-filter').inputValue(),'');
 const reported=Number((await page.locator('#search-status').innerText()).match(/on page (\d+)/)[1]);
 assert.equal(reported,target.number);
 assert.match(await page.locator('#status').innerText(),new RegExp(`Page ${reported} of`));
 assert.ok(await page.locator('#text-layer .current-match').count()>0);
 const searches=await page.evaluate(async()=>{
  const {DocumentSearch}=await import('./document-search.js'),doc=window.mainDoc,search=new DocumentSearch(),getPage=doc.pdf.getPage.bind(doc.pdf),wrapped=new WeakSet();let reads=0;
  doc.pdf.getPage=async number=>{const p=await getPage(number);if(!wrapped.has(p)){wrapped.add(p);const original=p.getTextContent.bind(p);p.getTextContent=(...args)=>{reads++;return original(...args);};}return p;};
  const trials=[];
  for(let trial=0;trial<3;trial++){search.clear();reads=0;const start=performance.now();for(const query of ['Power','VaR','production','Power','VaR','production']){if(search.resetCursor)search.resetCursor();else search.clear();await search.find(doc,query,1,1,()=>{});}trials.push({milliseconds:performance.now()-start,pageExtractions:reads});}
  return trials;
 });
 if(!baseline)assert.ok(searches.every(t=>t.pageExtractions<=3));
 // Repeated logical members isolate cache retention using an unchanged real child PDF.
 // A file input keeps local sources offline and avoids serializing binary data through WebDriver.
 await page.evaluate(()=>{const input=document.createElement('input');input.id='improvement-probe';input.type='file';document.body.append(input);});
 await page.locator('#improvement-probe').setInputFiles(resolve('tests/metadata-index/mbox_sample_1ci.pdf'));
 const children=await page.evaluate(async()=>{
  const {BrowserSession}=await import('./browser-session.js'),session=new BrowserSession();let evictions=0;session.options.onEvict=()=>evictions++;
  try{
   const root=await session.open(document.querySelector('#improvement-probe').files[0],'collection.pdf');
   const file=root.inspection.files.find(f=>f.streams.some(s=>s.mimeType.value==='application/pdf')),stream=file.streams[0];
   const aliases=Array.from({length:64},(_,n)=>({...file,id:file.id+'-probe-'+n}));root.inspection={...root.inspection,files:[...root.inspection.files,...aliases]};
   let first;const checkpoints=[],start=performance.now();
   for(const [index,alias] of aliases.entries()){const doc=await session.child(root,alias,stream,false);first??=doc;if([16,32,64].includes(index+1))checkpoints.push({visited:index+1,retainedChildren:root.children.size});}
   const entries=session.entries.size,retainedChildren=root.children.size,elapsed=performance.now()-start;
   const metadataCharacters=[...session.entries].reduce((sum,[doc,entry])=>sum+JSON.stringify([doc.inspection,entry.assets,entry.parts,[...doc.details]]).length,0);
   const reopened=await session.child(root,aliases[0],stream,false),sameIdentity=reopened.inspection.documentId===first.inspection.documentId;
   const p=await reopened.pdf.getPage(1),text=await p.getTextContent();p.cleanup();
   return {entries,retainedChildren,metadataCharacters,evictions,checkpoints,reopened:sameIdentity&&text.items.length>0,milliseconds:elapsed};
  }finally{session.close();}
 });
 if(!baseline){assert.ok(children.entries<=19);assert.ok(children.evictions>=46);assert.ok(children.reopened);}
 if(!baseline){
  await page.locator('#improvement-probe').setInputFiles(resolve('tests/inspection/controls/c-nested-mixed.pdf'));
  const nested=await page.evaluate(async()=>{
   const {BrowserSession}=await import('./browser-session.js'),session=new BrowserSession({},{metadataCacheBytes:0});
   try{
    const root=await session.open(document.querySelector('#improvement-probe').files[0],'nested.pdf');
    const file=root.inspection.files.find(f=>f.filename.value==='nested.pdf'),parent=await session.child(root,file,file.streams[0],false);
    const childFile=parent.inspection.files.find(f=>f.streams.some(s=>s.mimeType.value==='application/pdf'));
    const aliases=Array.from({length:8},(_,n)=>({...childFile,id:childFile.id+'-nested-'+n}));parent.inspection={...parent.inspection,files:[...parent.inspection.files,...aliases]};
    for(const alias of aliases)await session.child(parent,alias,alias.streams[0],false);
    const alive=session.entries.has(root)&&session.entries.has(parent),reopened=await session.child(parent,aliases[0],aliases[0].streams[0],false);
    await session.ensure(root);await session.ensure(parent);
    return alive&&reopened.parent===parent&&parent.parent===root;
   }finally{session.close();}
  });assert.ok(nested,'nested parent chain remains usable after leaf eviction');
 }
 // Synthetic page repetition isolates result presentation, not creator or index performance.
 const count=Number(process.env.EAPDF_RESULT_COUNT??1000);
 const data=await page.evaluate(count=>{
  const text='BT /F1 12 Tf 40 700 Td (needle) Tj ET',objects=['','',`<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>`,`<< /Length ${text.length} >>\nstream\n${text}\nendstream`];
  const kids=[];for(let n=0;n<count;n++){kids.push(`${objects.length+1} 0 R`);objects.push('<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents 4 0 R >>');}
  objects[0]='<< /Type /Catalog /Pages 2 0 R >>';objects[1]=`<< /Type /Pages /Count ${count} /Kids [${kids.join(' ')}] >>`;
  let pdf='%PDF-1.7\n',offsets=[];objects.forEach((o,i)=>{offsets.push(pdf.length);pdf+=`${i+1} 0 obj\n${o}\nendobj\n`;});const offset=pdf.length;
  pdf+=`xref\n0 ${objects.length+1}\n0000000000 65535 f \n`+offsets.map(n=>String(n).padStart(10,'0')+' 00000 n \n').join('')+`trailer << /Size ${objects.length+1} /Root 1 0 R >>\nstartxref\n${offset}\n%%EOF`;
  return pdf;
 },count);
 await page.locator('#file').setInputFiles({name:'synthetic-search-pages.pdf',mimeType:'application/pdf',buffer:Buffer.from(data)});await ready();
 await page.evaluate(()=>{
  const original=document.createElement.bind(document);window.presentationStart=undefined;
  document.createElement=(...args)=>{if(args[0]==='li'&&document.querySelector('#collection-search-summary').textContent==='Searching collection…'&&!document.querySelector('#cancel-search').hidden)window.presentationStart??=performance.now();return original(...args);};
  new MutationObserver(()=>{if(window.presentationStart&&document.querySelector('#search-status').textContent.startsWith('Match 1 of'))window.presentationEnd??=performance.now();}).observe(document.querySelector('#search-status'),{subtree:true,childList:true});
 });
 await page.locator('#open-search').click();await page.locator('#search-scope').selectOption('collection');await page.locator('#search').fill('needle');await page.locator('#search-submit').click();await ready();
 await page.waitForFunction(()=>window.presentationEnd!==undefined,null,{timeout:180000});
 const results=await page.evaluate(()=>({rows:document.querySelectorAll('.collection-hit').length,milliseconds:window.presentationEnd-window.presentationStart,status:document.querySelector('#search-status').textContent}));
 assert.match(results.status,new RegExp(`Match 1 of ${count} in collection`));if(!baseline)assert.ok(results.rows<=72);
 await page.locator('#find-previous').click();await ready();assert.match(await page.locator('#search-status').innerText(),new RegExp(`Match ${count} of ${count}`));
 assert.equal(await page.locator('#collection-matches [aria-current]').count(),1);assert.ok(await page.locator('#text-layer .current-match').count()>0);
 if(!baseline){
  await page.locator('#collection-matches [aria-current]').focus();for(let n=0;n<80;n++){await page.keyboard.press('ArrowUp');await ready();}assert.equal(await page.evaluate(()=>document.activeElement.dataset.matchIndex),String(count-81));
 }
 await page.locator('#close').click();assert.equal(await page.locator('.collection-hit').count(),0);
 const report={status:'PASS',mode:baseline?'before':'after',filterScroll,searches,children,results,scope:'Three repeated-search trials; one result-presentation/retention trial. Synthetic repeated pages isolate DOM scaling; real PDF text/attachments used for cache and retention.'};
 await mkdir('test-results',{recursive:true});await writeFile(`test-results/viewer-improvements-${baseline?'before':'after'}.json`,JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
}finally{await context.close();}
