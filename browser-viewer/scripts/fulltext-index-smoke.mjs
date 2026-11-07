import {chromium} from 'playwright';
import {resolve} from 'node:path';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
const extension=resolve('dist/chromium'),context=await chromium.launchPersistentContext('',{headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{channel:'chromium'}),args:[`--load-extension=${extension}`,`--disable-extensions-except=${extension}`]});
try{
 await context.setOffline(true);const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage();
 await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
 // Real text-layer regression: adjacent font runs must not acquire a synthetic space.
 const fragmentCheck=await page.evaluate(async()=>{
  const {pdfjs}=await import('./pdfjs-adapter.js'),{PageText}=await import('./page-text.js'),{pdfSearchText,textMatches}=await import('./document-search.js');
  const commands='BT /F1 12 Tf 40 700 Td (con) Tj /F2 12 Tf (tract) Tj 0 -30 Td /F1 12 Tf (annual) Tj 0 -18 Td (report) Tj 0 -30 Td (con-) Tj 0 -18 Td (tract) Tj ET';
  const objects=['<< /Type /Catalog /Pages 2 0 R >>','<< /Type /Pages /Kids [3 0 R] /Count 1 >>','<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R /F2 5 0 R >> >> /Contents 6 0 R >>','<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>','<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>',`<< /Length ${commands.length} >>\nstream\n${commands}\nendstream`];
  let pdf='%PDF-1.7\n',offsets=[0];objects.forEach((o,i)=>{offsets.push(pdf.length);pdf+=`${i+1} 0 obj\n${o}\nendobj\n`;});const xref=pdf.length;pdf+='xref\n0 7\n0000000000 65535 f \n'+offsets.slice(1).map(n=>String(n).padStart(10,'0')+' 00000 n \n').join('')+`trailer << /Size 7 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`;
  const task=pdfjs.getDocument({data:new TextEncoder().encode(pdf)}),doc=await task.promise,p=await doc.getPage(1),container=document.createElement('div');container.className='textLayer';document.body.append(container);const layer=new PageText(container);
  try{
   const content=await p.getTextContent(),text=pdfSearchText(content.items);await layer.render(p,1);
   const counts=['contract','annual report'].map(query=>({query,scan:textMatches(text,query).length,highlight:layer.highlight(query),marks:[...container.querySelectorAll('mark')].map(m=>m.textContent)}));
   layer.clear();return {counts,cleared:container.childNodes.length===0};
  }finally{layer.clear();container.remove();p.cleanup();await task.destroy();}
 });
 assert.deepEqual(fragmentCheck.counts.map(({scan,highlight})=>({scan,highlight})),[{scan:2,highlight:2},{scan:1,highlight:1}]);assert.ok(fragmentCheck.cleared);
 assert.deepEqual(fragmentCheck.counts[0].marks,['con','tract','con-','tract']);
 await page.evaluate(()=>{const input=document.createElement('input');input.type='file';input.id='probe';document.body.append(input);});
 const manifest=JSON.parse(await readFile('tests/fulltext-index/manifest.json','utf8')),reports=[];
 for(const [name,hash] of Object.entries(manifest.files)){
  const bytes=await readFile('tests/fulltext-index/'+name);assert.equal(createHash('sha256').update(bytes).digest('hex'),hash);
  await page.locator('#probe').setInputFiles(resolve('tests/fulltext-index/'+name));
  const result=await page.evaluate(async()=>{
   const {BrowserSession}=await import('./browser-session.js'),{searchCollection}=await import('./collection-search.js'),{readFullTextIndex,forgetFullTextIndex}=await import('./fulltext-index.js');
   const file=document.querySelector('#probe').files[0],session=new BrowserSession({},{reuseWorker:true});let opened=0;const child=session.child.bind(session);session.child=async(...args)=>{opened++;return child(...args);};
   try{
    const root=await session.open(file,'sample.pdf'),first=await readFullTextIndex(session,root,()=>{});if(!first.index)throw Error(first.diagnostic);if(await readFullTextIndex(session,root,()=>{})!==first)throw Error('Index was not cached');
    const reports=[];
    for(const query of ['Power','Power VaR','production','schedule','PAGE','tim.belden','As of 12/28','no-match-zzzz','  Power  ']){
     const before=opened,fast=await searchCollection(session,root,query,()=>{},()=>{});if(fast.source!=='indexed'||opened!==before)throw Error('Query opened children');
     const slow=await searchCollection(session,root,query,()=>{},()=>{},true);
     const normalize=x=>x.matches.map(({indexed,excerpt,...m})=>m);
     if(JSON.stringify(normalize(fast))!==JSON.stringify(normalize(slow))||fast.incomplete.length||slow.incomplete.length)throw Error('Indexed results differ: '+query);
     reports.push({query,pages:fast.matches.length,count:fast.matches.reduce((n,m)=>n+m.count,0),indexedChildOpens:0});
    }
    forgetFullTextIndex(root);root.fullTextIndex='AAAA';const fallback=await searchCollection(session,root,'Power',()=>{},()=>{});if(fallback.source!=='scanned'||!fallback.notices.length)throw Error('Corrupt payload did not fall back');
    const stale=await session.open(new Blob([file,'\n']),'changed.pdf');const invalid=await readFullTextIndex(session,stale,()=>{});if(invalid.index||!invalid.diagnostic.includes('changed'))throw Error('Stale PDF accepted');
    await session.release(stale);session.close();return reports;
   }finally{session.close();}
  });reports.push({name,status:'PASS',queries:result});
 }
 // Filename navigation always starts at page one and recalculates fit width.
 for(const profile of ['1m','1mi','1c','1ci']){
  const name=`mbox_sample_${profile}.pdf`;await page.locator('#file').setInputFiles(resolve('tests/fulltext-index/'+name));
  await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false');
  if(profile==='1c'||profile==='1ci'){
   await page.evaluate(()=>{for(const d of document.querySelectorAll('#tree details'))d.open=true;});await page.locator('#tree [data-embedded-file-id]').first().click();
  }else await page.locator('#tree [data-message-id]').last().click();
  await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false');
  await page.locator('#zoom').selectOption('1.5');await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false');
  await page.locator('#tree > li > button.root-document').click();
  await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false');
  assert.equal(await page.locator('#page').inputValue(),'1');assert.equal(await page.locator('#zoom').inputValue(),'fit-width');
  assert.equal(await page.locator('#header-filename').innerText(),name);
  assert.ok(await page.locator('#viewport').evaluate(v=>v.scrollTop<=1));
  assert.ok(await page.evaluate(()=>{const v=document.querySelector('#viewport'),p=document.querySelector('.pdf-page[data-page="1"]'),s=getComputedStyle(v);return Math.abs(p.getBoundingClientRect().width-(v.clientWidth-parseFloat(s.paddingLeft)-parseFloat(s.paddingRight)-2))<2;}));
 }
 // Exercise the existing UI, selected-page confirmation and actual text-layer highlights.
 await page.locator('#file').setInputFiles(resolve('tests/fulltext-index/mbox_sample_1ci.pdf'));
 await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false'&&document.querySelector('#subject-index-progress').dataset.complete==='true');
 await page.locator('#open-search').click();await page.locator('#search-scope').selectOption('collection');await page.locator('#search').fill('Power VaR');await page.locator('#find').click();
 await page.waitForFunction(()=>document.querySelector('#collection-search-summary').textContent.includes('Embedded PDFBox text index'));
 await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false');
 assert.ok(await page.locator('#text-layer .current-match').count()>0,'verified first page must highlight');
 await page.locator('#collection-matches button[data-match-index="1"]').click();
 await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false'&&document.querySelector('#breadcrumbs').textContent.includes('Power VaR'));
 assert.ok(await page.locator('#text-layer .current-match').count()>0,'verified child page must highlight');
 assert.match(await page.locator('#search-status').innerText(),/Match 2 of 3/);
 await page.locator('#close').click();
 // Simulate extraction disagreement on selection, after the file/index was verified.
 await page.evaluate(async()=>{
  const {BrowserSession}=await import('./browser-session.js'),original=BrowserSession.prototype.child;let inject=true;
  BrowserSession.prototype.child=async function(...args){const doc=await original.apply(this,args);if(inject){inject=false;const getPage=doc.pdf.getPage.bind(doc.pdf);let change=true;
   doc.pdf.getPage=async(...params)=>{const page=await getPage(...params),getText=page.getTextContent.bind(page);page.getTextContent=async(...options)=>{const content=await getText(...options);if(change){change=false;return {...content,items:[{str:'simulated changed page'}]};}return content;};return page;};
  }return doc;};
 });
 await page.locator('#file').setInputFiles(resolve('tests/fulltext-index/mbox_sample_1ci.pdf'));
 await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false'&&document.querySelector('#subject-index-progress').dataset.complete==='true');
 await page.locator('#open-search').click();await page.locator('#search').fill('Power VaR');await page.locator('#find').click();
 await page.waitForFunction(()=>document.querySelector('#collection-search-summary').textContent.includes('Embedded PDFBox text index')&&document.querySelector('#viewport').getAttribute('aria-busy')==='false');
 await page.locator('#collection-matches button[data-match-index="1"]').click();
 await page.waitForFunction(()=>document.querySelector('#collection-search-summary').textContent.includes('All reachable collection PDFs were searched.')&&document.querySelector('#collection-search-summary').textContent.includes('disagrees'));
 await page.waitForFunction(()=>document.querySelector('#search-status').textContent.includes('Match 1 of 3')&&document.querySelector('#viewport').getAttribute('aria-busy')==='false');
 assert.match(await page.locator('#search-status').innerText(),/Match 1 of 3/);
 await page.locator('#close').click();
 await mkdir('test-results',{recursive:true});await writeFile('test-results/fulltext-index.json',JSON.stringify({status:'PASS',reports,highlighting:'PASS'},null,2)+'\n');console.log(JSON.stringify({status:'PASS',profiles:reports.length,queries:reports.reduce((n,r)=>n+r.queries.length,0),highlighting:'PASS'}));
}finally{await context.close();}
