import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
import {resolve} from 'node:path';
export async function checkReadingTools(browser){
 const checks=[];
 for(const profile of ['c','ci']){
   await browser.open(resolve(`../examples/pst/pst_quality-attached-message_1${profile}.pdf`));
   const csv=await browser.download('#export-csv');
   const rows=JSON.parse(execFileSync(process.env.M1_PYTHON??'python3',['-B','-c','import csv,json,sys; print(json.dumps(list(csv.DictReader(sys.stdin))))'],{input:csv.toString('utf8').replace(/^\uFEFF/,''),encoding:'utf8'}));
   assert.equal(rows.length,7);assert.equal(rows.some(r=>'Mail GUID' in r),false);
   assert.ok(rows.every(r=>r['Folder paths']==='/Collection/Début du fichier de données Outlook'));
   assert.equal(rows.filter(r=>r['Attachment count']==='1'&&r['Attachment names']==='First email.eml').length,1);
   assert.equal(rows.filter(r=>r['Attachment count']==='0'&&r['Attachment names']==='').length,6);
   // The CSV uses its own session; none of these child PDFs is open in the viewer.
   await browser.evaluate(()=>{const input=document.querySelector('#message-filter');input.value='Feature';input.dispatchEvent(new Event('input'));});
   for(let attempt=0;attempt<200;attempt++){if(await browser.evaluate(()=>document.querySelector('#filter-results button')!==null))break;await new Promise(resolve=>setTimeout(resolve,100));}
   assert.ok(await browser.evaluate(()=>document.querySelector('#filter-results button')!==null),'unopened collection email must be found');
   assert.match(await browser.evaluate(()=>document.querySelector('#filter-status').textContent),/All reachable email metadata searched/);
   await browser.click('#filter-results button');await browser.ready();
   assert.ok(await browser.evaluate(()=>document.querySelector('#breadcrumbs').textContent.includes(' / ')||document.querySelectorAll('#breadcrumbs button').length>1));
   checks.push(`${profile}: find and open an email in a previously unopened child PDF`);
   checks.push(`${profile} CSV finding aid: folder paths, seven emails, attachment count and filename`);
 }

 await browser.open(resolve('tests/fixtures/ordinary.pdf'));
 await browser.open(resolve('tests/fixtures/ordinary.pdf'));
 await browser.evaluate(()=>{const z=document.querySelector('#zoom');z.value='fit-width';z.dispatchEvent(new Event('change'));});await browser.ready();
 assert.ok(await browser.evaluate(()=>document.querySelector('#canvas').getBoundingClientRect().width<=document.querySelector('#viewport').clientWidth));
 assert.ok(await browser.evaluate(()=>document.querySelector('#text-layer').textContent.includes('Ordinary PDF')));assert.equal(await browser.evaluate(()=>!!document.querySelector('#print,#rotate')),false);checks.push('fit width and selectable text; no print or rotation controls');
 await browser.click('#open-search');await browser.evaluate(()=>{document.querySelector('#search').value='PDF';});await browser.click('#find');await browser.ready();assert.match(await browser.evaluate(()=>document.querySelector('#search-status').textContent),/Match 1 of 1 on page 1/);
 await browser.click('#find');await browser.ready();assert.match(await browser.evaluate(()=>document.querySelector('#search-status').textContent),/page 2/);await browser.click('#find-previous');await browser.ready();assert.match(await browser.evaluate(()=>document.querySelector('#search-status').textContent),/page 1/);checks.push('current-page-first search, next and previous');
 await browser.open(resolve('../examples/pst/pst_quality-attached-message_1m.pdf'));
 assert.equal(await browser.evaluate(()=>document.querySelector('#files').textContent.includes('Find email sources')),false);
 const first=await browser.evaluate(()=>document.querySelector('[data-message-id]').dataset.messageId);await browser.click(`[data-message-id=${JSON.stringify(first)}]`);await browser.ready();
 assert.equal(await browser.evaluate(()=>document.activeElement===document.querySelector('#tree button[aria-current]')),true,'mouse-opened message retains focus');
 await browser.evaluate(()=>document.activeElement.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowDown',bubbles:true})));await browser.ready();
 assert.notEqual(await browser.evaluate(()=>document.querySelector('#tree button[aria-current]').dataset.messageId),first);
 await browser.evaluate(()=>document.activeElement.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowUp',bubbles:true})));await browser.ready();
 assert.equal(await browser.evaluate(()=>document.querySelector('#tree button[aria-current]').dataset.messageId),first);
 checks.push('mouse-opened flat message retains focus for Up/Down activation');
 assert.ok(await browser.evaluate(()=>[...document.querySelectorAll('#files button')].some(b=>/^Download .*\.eml$/i.test(b.getAttribute('aria-label')??''))));assert.equal(await browser.evaluate(()=>!!document.querySelector('#message-details')),false);checks.push('automatic source association without duplicate email details pane');
 assert.equal(await browser.evaluate(()=>document.querySelector('#text-layer').dataset.structure==='unavailable'),false);checks.push('upstream structure layer loads without errors');
 assert.equal(await browser.evaluate(()=>document.querySelector('#restart').hidden),true);checks.push('recovery control hidden during normal reading');
 await browser.open(resolve('tests/metadata-index/mbox_sample_1ci.pdf'));
 await browser.click('#tree button[data-embedded-file-id]');await browser.ready();
 const childId=await browser.evaluate(()=>document.querySelector('#tree button[aria-current]').dataset.documentId);
 assert.equal(await browser.evaluate(()=>document.activeElement===document.querySelector('#tree button[aria-current]')),true,'replacement child row receives focus');
 await browser.evaluate(()=>document.activeElement.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowDown',bubbles:true})));await browser.ready();
 assert.notEqual(await browser.evaluate(()=>document.querySelector('#tree button[aria-current]').dataset.documentId),childId);
 await browser.evaluate(()=>document.activeElement.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowUp',bubbles:true})));await browser.ready();
 assert.equal(await browser.evaluate(()=>document.querySelector('#tree button[aria-current]').dataset.documentId),childId);
 checks.push('mouse-opened collection row retains focus and opens next/previous message');
 return {status:'PASS',checks};
}
