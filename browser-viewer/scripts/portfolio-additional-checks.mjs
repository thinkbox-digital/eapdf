import assert from 'node:assert/strict';import {readdir,readFile} from 'node:fs/promises';import {resolve} from 'node:path';import {createHash} from 'node:crypto';import {execFileSync} from 'node:child_process';
// Discover new private samples automatically. The original three have dedicated
// feature assertions in portfolio-checks/portfolio-smoke.
export async function checkAdditionalPortfolios(browser){
 const root=resolve('../in/portfolio'),original=new Set(['Portfolio.pdf','Portfolio2.pdf','CheatSheetCollection.pdf']);let names;
 try{names=(await readdir(root)).filter(n=>/\.pdf$/i.test(n)&&!original.has(n)).sort();}catch{console.log('SKIP: optional private Portfolio directory unavailable.');return [];}
 const hash=b=>createHash('sha256').update(b).digest('hex'),results=[];
 const until=async fn=>{const end=Date.now()+90000;while(Date.now()<end){if(await fn())return;await new Promise(r=>setTimeout(r,100));}throw new Error('Additional Portfolio check timed out: '+await browser.evaluate(()=>document.querySelector('#status').textContent));};
 const ready=async()=>{await browser.ready();await new Promise(r=>setTimeout(r,200));await browser.ready();};
 for(const name of names){
  console.log('Additional Portfolio:',name);const path=resolve(root,name),before=hash(await readFile(path));
  const expected=JSON.parse(execFileSync(process.env.M1_PYTHON??'python3',['-B','scripts/portfolio-sample-inventory.py',path],{encoding:'utf8'}));
  await ready();if(await browser.evaluate(()=>document.querySelector('#attachment-viewer').open))await browser.click('#attachment-close');await browser.open(path);await until(async()=>await browser.evaluate(n=>document.querySelector('#header-filename').textContent===n&&document.querySelector('#viewport').getAttribute('aria-busy')==='false',name));
  assert.equal(await browser.evaluate(()=>document.body.dataset.documentKind),'portfolio');assert.equal(await browser.evaluate(()=>document.body.dataset.portfolio),expected.view);
  if(expected.split)assert.equal(await browser.evaluate(()=>document.body.dataset.portfolioSplit),expected.split);
  if(expected.position!==null&&expected.split!=='V')assert.equal(await browser.evaluate(()=>document.querySelector('#workspace').style.getPropertyValue('--portfolio-size')),expected.position+'%');
  if(expected.initial){const initial=expected.members.find(m=>m.key===expected.initial);if(initial)assert.ok((await browser.evaluate(()=>document.querySelector('#breadcrumbs').textContent)).includes(initial.name));}
  await browser.evaluate(()=>{const s=document.querySelector('select[aria-label="Portfolio layout"]');s.value='Tree';s.dispatchEvent(new Event('change'));});
  const rows=await browser.evaluate(()=>[...document.querySelectorAll('[data-portfolio-row]')].map(n=>n.textContent));
  assert.deepEqual(rows.sort(),[...expected.members.map(m=>m.name),...expected.folders.filter(f=>f.parent!==null).map(f=>f.name)].sort(),name+' complete file/folder inventory');
  const filter=async query=>{await ready();await browser.evaluate(q=>{const input=document.querySelector('input[aria-label="Filter portfolio files and metadata"]');input.value=q;input.dispatchEvent(new Event('input'));},query);await new Promise(r=>setTimeout(r,250));};
  const checks=['independent full member/folder inventory','stored initial view and split'];
  const zip=expected.members.find(m=>/\.zip$/i.test(m.name));if(zip){await filter(zip.name);assert.equal(await browser.evaluate(()=>document.querySelectorAll('[data-portfolio-row]').length),1);const bytes=await browser.download('.portfolio-card-actions button[aria-label="Download"]');assert.equal(hash(bytes),zip.sha256);checks.push('native ZIP download matches embedded bytes');}
  const pdfs=expected.members.filter(m=>/\.pdf$/i.test(m.name));
  const selected=new Map();for(const file of pdfs){const profile=/_(1s|1si|1m|1mi|1c|1ci)\.pdf$/i.exec(file.name)?.[1]??'ordinary';if(!selected.has(profile))selected.set(profile,file);}
  for(const [profile,file] of selected){
   await filter(file.name);await browser.evaluate(n=>[...document.querySelectorAll('[data-portfolio-row]')].find(b=>b.textContent===n).click(),file.name);await ready();
   await until(async()=>await browser.evaluate(()=>!!document.querySelector('.pdf-page[data-rendered="true"]')&&document.querySelector('#viewport').getAttribute('aria-busy')==='false'));
   assert.ok((await browser.evaluate(()=>document.querySelector('#breadcrumbs').textContent)).includes(file.name));assert.notEqual(await browser.evaluate(()=>document.body.dataset.portfolioSplit),'N');
   if(profile!=='ordinary'){
    await browser.click('#portfolio-toggle');await ready();assert.equal(await browser.evaluate(()=>document.body.dataset.documentKind),'eapdf');assert.equal(await browser.evaluate(()=>document.querySelector('#breadcrumbs button:last-child').textContent),file.name,'mode switch preserves newly selected member');assert.equal(await browser.evaluate(()=>document.querySelector('#export-csv').hidden),false);
    await browser.click('#portfolio-toggle');await ready();
    // Collection-profile members offer their own Portfolio; return to the outer
    // collection before selecting the next independent sample member.
    if(['1c','1ci'].includes(profile)){await browser.click('button[aria-label="Parent portfolio"]');await ready();}
   }
   checks.push('open '+profile+' PDF member');
  }
  assert.equal(hash(await readFile(path)),before);results.push({name,sha256:before,members:expected.members.length,folders:expected.folders.length,checks,status:'PASS'});
 }
 return results;
}
