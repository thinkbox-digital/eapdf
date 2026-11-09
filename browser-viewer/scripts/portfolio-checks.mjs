import assert from 'node:assert/strict';import {mkdtemp,writeFile,rm,access,readFile} from 'node:fs/promises';import {tmpdir} from 'node:os';import {join,resolve,basename} from 'node:path';import {createHash} from 'node:crypto';import {portfolioFixture} from './portfolio-fixtures.mjs';
export async function checkPortfolio(browser){
 const work=await mkdtemp(join(tmpdir(),'eapdf-portfolio-cross-browser-')),results=[];
 const until=async(fn)=>{const start=Date.now();while(Date.now()-start<60000){if(await fn())return;await new Promise(r=>setTimeout(r,100));}throw new Error('Portfolio acceptance timed out');};
 const open=async path=>{console.log('Firefox Portfolio opening:',basename(path));await until(()=>browser.evaluate(()=>!document.querySelector('#file').disabled));if(await browser.evaluate(()=>document.querySelector('#attachment-viewer').open))await browser.click('#attachment-close');await browser.open(path);try{await until(async()=>await browser.evaluate(()=>document.querySelector('#header-filename').textContent)===basename(path)&&await browser.evaluate(()=>!document.querySelector('#file').disabled));}catch(error){console.error(await browser.evaluate(()=>({filename:document.querySelector('#header-filename').textContent,status:document.querySelector('#status').textContent,loading:document.querySelector('#loading-message').textContent,busy:document.querySelector('#file').disabled})));throw error;}};
 try{
  const fixture=join(work,'portfolio-control.pdf');await writeFile(fixture,portfolioFixture());await open(fixture);
  assert.equal(await browser.evaluate(()=>document.body.dataset.documentKind),'portfolio');assert.deepEqual(await browser.evaluate(()=>[...document.querySelectorAll('[data-portfolio-row]')].map(n=>n.textContent)),['zeta.txt','alpha.txt']);
  await browser.evaluate(()=>{const input=[...document.querySelectorAll('.portfolio-columns label')].find(n=>n.textContent==='Score').querySelector('input');input.checked=true;input.dispatchEvent(new Event('change'));});
  await browser.evaluate(()=>[...document.querySelectorAll('#portfolio-panel th button')].find(b=>b.textContent==='Score').click());
  assert.deepEqual(await browser.evaluate(()=>[...document.querySelectorAll('[data-portfolio-row]')].map(n=>n.textContent)),['alpha.txt','zeta.txt']);
  await browser.evaluate(()=>{const s=document.querySelector('select[aria-label="Portfolio layout"]');s.value='H';s.dispatchEvent(new Event('change'));});assert.equal(await browser.evaluate(()=>document.querySelector('#portfolio-panel').hidden),true);await browser.click('#portfolio-show');assert.equal(await browser.evaluate(()=>document.querySelector('#portfolio-panel').hidden),false);
  await browser.click('#portfolio-toggle');await browser.ready();assert.equal(await browser.evaluate(()=>!!document.body.dataset.portfolio),false);await browser.click('#portfolio-toggle');await browser.ready();assert.equal(await browser.evaluate(()=>!!document.body.dataset.portfolio),true);
  results.push('Generic Portfolio classification, numeric sort, hidden view and view switching');
  for(const name of ['Portfolio.pdf','Portfolio2.pdf','CheatSheetCollection.pdf']){
   const path=resolve('../in/portfolio',name);try{await access(path);}catch{continue;}const before=createHash('sha256').update(await readFile(path)).digest('hex');await open(path);assert.equal(await browser.evaluate(()=>document.body.dataset.documentKind),'portfolio');
   if(await browser.evaluate(()=>document.querySelector('#attachment-viewer').open))await browser.click('#attachment-close');
   if(name==='CheatSheetCollection.pdf'){
    await browser.evaluate(()=>{const q=document.querySelector('input[aria-label="Filter portfolio files and metadata"]');q.value='LogicalStructureObjects+Tags.pdf';q.dispatchEvent(new Event('input'));});
    await until(async()=>await browser.evaluate(()=>[...document.querySelectorAll('[data-portfolio-row]')].some(n=>n.textContent==='LogicalStructureObjects+Tags.pdf')));
    await browser.evaluate(()=>[...document.querySelectorAll('[data-portfolio-row]')].find(n=>n.textContent==='LogicalStructureObjects+Tags.pdf').click());await browser.ready();
    assert.equal(await browser.evaluate(()=>document.querySelector('#breadcrumbs').textContent),'CheatSheetCollection.pdf›Tagged PDF and Logical Structure›LogicalStructureObjects+Tags.pdf');
    results.push('Collection document breadcrumb includes the recorded member folder');
    await browser.evaluate(()=>{const q=document.querySelector('input[aria-label="Filter portfolio files and metadata"]');q.value='fragment identifiers';q.dispatchEvent(new Event('input'));});await until(async()=>JSON.stringify(await browser.evaluate(()=>[...document.querySelectorAll('[data-portfolio-row]')].map(n=>n.textContent)))==='["Color.pdf"]');
   }
   assert.equal(createHash('sha256').update(await readFile(path)).digest('hex'),before);results.push(name+' PASS; original SHA-256 unchanged');
  }
  await open(resolve('tests/fulltext-index/mbox_sample_1c.pdf'));assert.equal(await browser.evaluate(()=>document.body.dataset.documentKind),'eapdf');assert.equal(await browser.evaluate(()=>document.querySelector('#export-csv').hidden),false);assert.equal(await browser.evaluate(()=>!!document.body.dataset.portfolio),false);results.push('EA-PDF default interface restored after Portfolio files');
  return results;
 }finally{await rm(work,{recursive:true,force:true});}
}
