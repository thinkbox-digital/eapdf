import {checkTextFragments} from './text-fragment-checks.mjs';
import {chromium} from 'playwright';
import {resolve} from 'node:path';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
const extension=resolve('dist/chromium'),context=await chromium.launchPersistentContext('',{headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{channel:'chromium'}),args:[`--load-extension=${extension}`,`--disable-extensions-except=${extension}`]});
try{
 await context.setOffline(true);const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage();
 await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
 await checkTextFragments(fn=>page.evaluate(fn));
 await page.evaluate(()=>{const input=document.createElement('input');input.type='file';input.id='probe';document.body.append(input);});
 const fixtureDirectory=process.env.EAPDF_SIGNED_INDEX_FIXTURES??'tests/fulltext-index';
 const manifest=process.env.EAPDF_SIGNED_INDEX_FIXTURES?{files:Object.fromEntries(await Promise.all(['1m','1mi','1c','1ci'].map(async profile=>{const name=`mbox_sample_${profile}.pdf`;return [name,createHash('sha256').update(await readFile(resolve(fixtureDirectory,name))).digest('hex')];})))}:JSON.parse(await readFile('tests/fulltext-index/manifest.json','utf8')),reports=[];
 for(const [name,hash] of Object.entries(manifest.files)){
  const bytes=await readFile(resolve(fixtureDirectory,name));assert.equal(createHash('sha256').update(bytes).digest('hex'),hash);
  await page.locator('#probe').setInputFiles(resolve(fixtureDirectory,name));
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
  if(process.env.EAPDF_SIGNED_INDEX_FIXTURES){
   await page.locator('#probe').setInputFiles(resolve(fixtureDirectory,'revisions',name));
   const revised=await page.evaluate(async()=>{
    const {BrowserSession}=await import('./browser-session.js'),{searchCollection}=await import('./collection-search.js');
    const session=new BrowserSession({},{reuseWorker:true});
    try{const root=await session.open(document.querySelector('#probe').files[0],'revised.pdf'),result=await searchCollection(session,root,'Power VaR',()=>{},()=>{});
     return {source:result.source,notices:result.notices,incomplete:result.incomplete,pages:result.matches.length};
    }finally{session.close();}
   });
   assert.equal(revised.source,'scanned');assert.ok(revised.notices.some(n=>n.includes('changed')));assert.equal(revised.incomplete.length,0);
   assert.equal(revised.pages,result.find(r=>r.query==='Power VaR').pages);
  }
 }
 // Real older-engine PDFs still open and search through the existing scan path.
 if(!process.env.EAPDF_SIGNED_INDEX_FIXTURES){
  for(const [name,hash] of Object.entries(manifest.legacyFiles??{})){
   const bytes=await readFile(resolve(fixtureDirectory,name));assert.equal(createHash('sha256').update(bytes).digest('hex'),hash);
   await page.locator('#probe').setInputFiles(resolve(fixtureDirectory,name));
   const legacy=await page.evaluate(async()=>{
    const {BrowserSession}=await import('./browser-session.js'),{searchCollection}=await import('./collection-search.js');
    const session=new BrowserSession({},{reuseWorker:true});
    try{const root=await session.open(document.querySelector('#probe').files[0],'legacy.pdf');
     const result=await searchCollection(session,root,'Power VaR',()=>{},()=>{});
     return {source:result.source,incomplete:result.incomplete,notices:result.notices,count:result.matches.reduce((n,m)=>n+m.count,0)};
    }finally{session.close();}
   });
   assert.equal(legacy.source,'scanned');assert.deepEqual(legacy.incomplete,[]);assert.equal(legacy.count,3);
   assert.ok(legacy.notices.some(n=>n.includes('unsupported full-text format/engine')));
  }
 }
 // Filename navigation starts at page one and preserves the reader-selected zoom.
 for(const profile of ['1m','1mi','1c','1ci']){
  const name=`mbox_sample_${profile}.pdf`;await page.locator('#file').setInputFiles(resolve(fixtureDirectory,name));
  await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false');
  if(profile==='1c'||profile==='1ci'){
   await page.evaluate(()=>{for(const d of document.querySelectorAll('#tree details'))d.open=true;});await page.locator('#tree [data-embedded-file-id]').first().click();
  }else await page.locator('#tree [data-message-id]').last().click();
  await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false');
  await page.locator('#zoom').selectOption('1.5');await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false');
  await page.locator('#tree > li > button.root-document').click();
  await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false');
  assert.equal(await page.locator('#page').inputValue(),'1');assert.equal(await page.locator('#zoom').inputValue(),'1.5');
  assert.equal(await page.locator('#header-filename').innerText(),name);
  assert.ok(await page.locator('#viewport').evaluate(v=>v.scrollTop<=1));
  if(profile==='1c'||profile==='1ci')assert.equal(await page.locator('#canvas').isVisible(),true);
  else {
   await page.locator('#probe').setInputFiles(resolve(fixtureDirectory,name));
   const expectedWidth=await page.evaluate(async()=>{
    const {BrowserSession}=await import('./browser-session.js'),session=new BrowserSession({},{reuseWorker:true});
    try{const doc=await session.open(document.querySelector('#probe').files[0],'scale-check.pdf'),first=await doc.pdf.getPage(1);
     return Math.ceil(first.getViewport({scale:1.5*96/72}).width*Math.min(devicePixelRatio||1,2));
    }finally{session.close();}
   });
   assert.equal(await page.locator('#canvas').evaluate(c=>c.width),expectedWidth,'root navigation renders page one at 150%');
  }
 }
 // Exercise the existing UI, selected-page confirmation and actual text-layer highlights.
 await page.locator('#file').setInputFiles(resolve(fixtureDirectory,'mbox_sample_1ci.pdf'));
 await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false'&&document.querySelector('#subject-index-progress').dataset.complete==='true');
 await page.locator('#open-search').click();await page.locator('#search-scope').selectOption('collection');await page.locator('#search').fill('Power VaR');await page.locator('#find').click();
 await page.waitForFunction(()=>/^\d+ matching pages\.$/.test(document.querySelector('#collection-search-summary').textContent));
 await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false');
 assert.ok(await page.locator('#text-layer .current-match').count()>0,'verified first page must highlight');
 const totalMatches=Number((await page.locator('#search-status').innerText()).match(/of (\d+)/)[1]);assert.ok(totalMatches>=2);
 await page.locator('#find').click();
 await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false'&&document.querySelector('#breadcrumbs').textContent.includes('Power VaR'));
 assert.ok(await page.locator('#text-layer .current-match').count()>0,'verified child page must highlight');
 assert.equal(await page.locator('#search-status').innerText(),`Match 2 of ${totalMatches} in collection.`);
 await page.locator('#close').click();
 // Simulate extraction disagreement on selection, after the file/index was verified.
 await page.evaluate(async()=>{
  const {BrowserSession}=await import('./browser-session.js'),original=BrowserSession.prototype.child;let inject=true;window.disagreementInjected=false;
  BrowserSession.prototype.child=async function(...args){const doc=await original.apply(this,args);if(inject&&!this.options.reuseWorker){inject=false;window.disagreementSession=this;window.disagreementRoot=args[0];const getPage=doc.pdf.getPage.bind(doc.pdf);let change=true;
   doc.pdf.getPage=async(...params)=>{const page=await getPage(...params),getText=page.getTextContent.bind(page);page.getTextContent=async(...options)=>{const content=await getText(...options);if(change){change=false;window.disagreementInjected=true;return {...content,items:[{str:'simulated changed page'}]};}return content;};return page;};
  }return doc;};
 });
 await page.locator('#file').setInputFiles(resolve(fixtureDirectory,'mbox_sample_1ci.pdf'));
 await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false'&&document.querySelector('#subject-index-progress').dataset.complete==='true');
 await page.locator('#open-search').click();await page.locator('#search').fill('Power VaR');await page.locator('#find').click();
 await page.waitForFunction(()=>/^\d+ matching pages\.$/.test(document.querySelector('#collection-search-summary').textContent)&&document.querySelector('#viewport').getAttribute('aria-busy')==='false');
 if(!await page.evaluate(()=>window.disagreementInjected))await page.evaluate(()=>{const child=[...document.querySelectorAll('#collection-matches button[data-match-index]')].find(b=>b.closest('.collection-result')?.querySelector('.result-heading')?.textContent!=='Collection overview');if(!child)throw Error('No child match to verify');child.click();});
 await page.waitForFunction(async()=>{if(!window.disagreementInjected)return false;const {readFullTextIndex}=await import('./fulltext-index.js');const loaded=await readFullTextIndex(window.disagreementSession,window.disagreementRoot,()=>{});return loaded.diagnostic?.includes('disagrees')&&document.querySelector('#viewport').getAttribute('aria-busy')==='false'&&/^Match 1 of \d+ in collection\.$/.test(document.querySelector('#search-status').textContent);});
 assert.match(await page.locator('#search-status').innerText(),/^Match 1 of \d+ in collection\.$/);
 assert.match(await page.locator('#collection-search-summary').innerText(),/^\d+ matching pages\.$/);
 await page.locator('#close').click();
 await mkdir('test-results',{recursive:true});await writeFile(process.env.EAPDF_SIGNED_INDEX_FIXTURES?'test-results/fulltext-index-signed.json':'test-results/fulltext-index.json',JSON.stringify({status:'PASS',reports,highlighting:'PASS'},null,2)+'\n');console.log(JSON.stringify({status:'PASS',profiles:reports.length,queries:reports.reduce((n,r)=>n+r.queries.length,0),highlighting:'PASS'}));
}finally{await context.close();}
