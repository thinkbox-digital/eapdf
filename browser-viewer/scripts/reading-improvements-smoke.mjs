import {checkReadingTools} from './reading-checks.mjs';
import {execFileSync} from 'node:child_process';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {readFile,mkdir,writeFile,mkdtemp,rm} from 'node:fs/promises';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
const extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
const checks=[],errors=[],requests=[];
const temporary=await mkdtemp(join(tmpdir(),'eapdf-reading-test-'));
const passwordFixture=process.env.PASSWORD_FIXTURE??join(temporary,'password.pdf');
try{
 if(!process.env.PASSWORD_FIXTURE)execFileSync(process.env.M1_PYTHON??'python3',['-B','-c',"import pikepdf,sys\nwith pikepdf.Pdf.open(sys.argv[1]) as p:p.save(sys.argv[2],encryption=pikepdf.Encryption(owner='owner-test',user='reader-test',R=6))",'tests/fixtures/ordinary.pdf',passwordFixture]);
 context.on('request',r=>{if(/^https?:/.test(r.url()))requests.push(r.url());});await context.setOffline(true);
 const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
 await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
 const ready=()=>page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false');
 const open=async path=>{await page.locator('#file').setInputFiles(resolve(path));await ready();await page.waitForFunction(()=>document.querySelector('.pdf-page[data-rendered]'));};
 const shared=await checkReadingTools({evaluate:fn=>page.evaluate(fn),ready,click:selector=>page.locator(selector).first().click(),open,download:async selector=>{const pending=page.waitForEvent('download');await page.locator(selector).click();const downloaded=await pending;return readFile(await downloaded.path());}});checks.push(...shared.checks);
 await open('tests/fixtures/ordinary.pdf');
 const ordinary=(await readFile('tests/fixtures/ordinary.pdf')).toString('base64');
 const restored=await page.evaluate(async base64=>{
   const {BrowserSession}=await import('./browser-session.js');const session=new BrowserSession();
   try{const doc=await session.open(new Blob([Uint8Array.from(atob(base64),c=>c.charCodeAt(0))]),'recovery.pdf');let timedOut=false;try{await doc.run(new Promise(()=>{}),10);}catch(e){timedOut=/timed out/.test(String(e));}await session.ensure(doc);const p=await doc.run(doc.pdf.getPage(1));p.cleanup();return timedOut&&doc.active;}finally{session.close();}
 },ordinary);assert.equal(restored,true);checks.push('same document reopens after timeout');
 await page.locator('#zoom').selectOption('fit-width');await ready();
 assert.ok(await page.evaluate(()=>document.querySelector('#canvas').getBoundingClientRect().width<=document.querySelector('#viewport').clientWidth));
 assert.ok(await page.locator('#text-layer').textContent());checks.push('fit width and selectable text');
 await open('tests/inspection/controls/s-bad-checksum.pdf');
 await page.locator('[data-file-id]').first().click();await ready();assert.equal(await page.locator('#diagnostics').count(),0);assert.match(await page.locator('#status').textContent(),/checksum mismatch/);checks.push('extraction refreshes checksum warning');
 await open('../examples/pst/pst_quality-attached-message_1m.pdf');
 assert.equal(await page.getByRole('button',{name:'Find email sources',exact:true}).count(),0);
 const linked=await page.evaluate(()=>[...document.querySelectorAll('[data-message-id]')].map(b=>b.dataset.messageId));assert.ok(linked.length>1);
 let matched=0,headers=0;for(const id of linked){await page.locator(`[data-message-id=${JSON.stringify(id)}]`).first().click();await ready();if(await page.getByRole('button',{name:'Download email (.eml)',exact:true}).count())matched++;headers+=await page.locator('#files .file-name').filter({hasText:/^transport-headers/}).count();}
 assert.equal(matched,7,'all seven source emails must be associated automatically');assert.equal(headers,6,'all six transport-header files must appear with their email');checks.push(`${matched} real PST sources and ${headers} transport-header files associated automatically`);
 await page.locator('#message-filter').fill('Feature');await page.locator('#filter-results button').first().waitFor();await page.locator('#filter-results button').first().click();await ready();assert.equal(await page.locator('#message-details').count(),0);checks.push('email metadata filtering without duplicate details pane');
 await page.locator('#search').fill('the');await page.locator('#find').click();await ready();assert.match(await page.locator('#search-status').textContent(),/Match \d+ of \d+/);await page.locator('#find-previous').click();await ready();checks.push('occurrence search and previous match');
 assert.equal(await page.locator('#restart').isVisible(),false);checks.push('recovery hidden during normal use');
 await open('tests/inspection/controls/c-nested-mixed.pdf');await page.locator('#search-scope').selectOption('collection');await page.locator('#search').fill('not-present');await page.locator('#find').click();await ready();assert.match(await page.locator('#collection-search-summary').textContent(),/matching pages/);checks.push('nested collection search with coverage summary');
 {
  await page.locator('#file').setInputFiles(passwordFixture);await page.locator('dialog input[type=password]').fill('wrong');await page.locator('dialog button[type=submit]').click();await page.getByRole('heading',{name:'Incorrect password. Try again.'}).waitFor();await page.locator('dialog input[type=password]').fill('reader-test');await page.locator('dialog button[type=submit]').click();await ready();await page.waitForFunction(()=>document.querySelector('.pdf-page[data-rendered]'));checks.push('encrypted PDF wrong-password retry and successful open');
 }
 assert.deepEqual(errors,[]);assert.deepEqual(requests,[]);
 await mkdir('test-results',{recursive:true});await page.screenshot({path:'test-results/reading-improvements.png',fullPage:true});await writeFile('test-results/reading-improvements.json',JSON.stringify({status:'PASS',browser:context.browser()?.version(),checks,errors,requests},null,2)+'\n');console.log(JSON.stringify({status:'PASS',checks}));
}finally{await context.close();await rm(temporary,{recursive:true,force:true});}
