import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {mkdir,writeFile} from 'node:fs/promises';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
const extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
try{
 const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage();await context.setOffline(true);const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
 await page.evaluate(async()=>{const {BrowserSession}=await import('./browser-session.js');const open=BrowserSession.prototype.open;window.collectionScans=0;window.scanDelay=0;window.forceCollectionScan=true;BrowserSession.prototype.open=async function(...args){if(this.options.reuseWorker&&!args[3]&&!document.querySelector('#cancel-search').hidden){window.collectionScans++;if(window.scanDelay)await new Promise(resolve=>setTimeout(resolve,window.scanDelay));}const doc=await open.apply(this,args);if(window.forceCollectionScan)doc.fullTextIndex=undefined;if(!this.options.reuseWorker)window.mainDocument=doc;return doc;};});
 const ready=()=>page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false',null,{timeout:180000});
 const fixture=resolve('../examples/pst/pst_quality-attached-message_1c.pdf');
 const load=async()=>{await page.locator('#file').setInputFiles(fixture);await ready();};
 const click=async id=>{await page.locator(id).click();await ready();};
 const status=()=>page.locator('#search-status').textContent(),scans=()=>page.evaluate(()=>window.collectionScans);
 await load();await page.locator('#tree details').evaluateAll(nodes=>nodes.forEach(n=>{n.open=true;}));await page.locator('#tree button[data-embedded-file-id]').first().click();await ready();
 await page.locator('#open-search').click();await page.locator('#search-scope').selectOption('collection');await page.locator('#search').fill('email');await click('#find');
 assert.equal(await scans(),1);assert.match(await status(),/^Match 1 of (\d+) in collection\.$/);assert.ok(await page.locator('#text-layer .current-match').count()>0,'first result must be actively highlighted');
 assert.equal(await page.locator('#breadcrumbs button').count(),1,'first match automatically returns from child to collection cover');
 const total=Number((await status()).match(/of (\d+)/)[1]);assert.ok(total>1);
 await click('#find');assert.match(await status(),/^Match 2 of/);await click('#find-previous');assert.match(await status(),/^Match 1 of/);
 await click('#find-previous');assert.equal(await status(),`Match ${total} of ${total} in collection.`);assert.ok(await page.locator('#text-layer .current-match').count()>0);
 await click('#find');assert.match(await status(),/^Match 1 of/);assert.equal(await scans(),1,'next/previous and wrap must not rescan');
 await page.locator('#collection-matches button').last().click();await ready();assert.equal(await page.locator('#collection-matches button[aria-current]').count(),1);assert.ok(await page.locator('#text-layer .current-match').count()>0);assert.equal(await scans(),1);
 await page.locator('#search').fill('zz-no-such-text-593284');await click('#find');assert.equal(await status(),'No matching text in this collection.');assert.equal(await scans(),2);await click('#find');assert.equal(await scans(),2,'empty results are cached too');
 await page.evaluate(()=>{window.scanDelay=1800;});await page.locator('#search').fill('subject');await page.locator('#find').click();await page.locator('#cancel-search').click();await ready();assert.equal(await status(),'Search cancelled.');assert.equal(await page.locator('#canvas').isVisible(),true);
 await page.evaluate(()=>{window.scanDelay=0;});await click('#find');assert.equal(await scans(),4);assert.match(await status(),/^Match 1 of/);
 await load();await page.locator('#open-search').click();await page.locator('#search-scope').selectOption('collection');await page.locator('#search').fill('subject');await click('#find');assert.equal(await scans(),5,'opening a document invalidates the old result cache');
 const checkCleared=async()=>{
  assert.equal(await page.locator('.collection-hit').count(),0);assert.equal(await status(),'');
  assert.equal(await page.locator('#text-layer mark').count(),0);assert.equal(await page.locator('#results-tab').isDisabled(),true);
  assert.equal(await page.locator('#browse-tab').getAttribute('aria-selected'),'true');
 };
 await page.locator('#search').fill('');await checkCleared();await page.locator('#search').fill('subject');await click('#search-submit');assert.equal(await scans(),6,'clearing invalidates completed results');
 await page.locator('#search').press('Escape');assert.equal(await page.locator('#search-panel').isVisible(),false);await page.locator('#open-search').click();await page.locator('#search').fill('');await checkCleared();
 // The first fixture deliberately exercises scan caching/cancellation, even when regenerated with an index.
 // Real multi-message archive: search must update ownership while Browse is hidden.
 await page.evaluate(()=>{window.forceCollectionScan=false;});
 await page.locator('#file').setInputFiles(resolve('../examples/mbox/mbox_enron_1m.pdf'));await ready();
 await page.locator('#open-search').click();await page.locator('#search-scope').selectOption('collection');await page.locator('#search').fill('sabo');await click('#search-submit');
 const checkFiles=async()=>{
  const expected=await page.evaluate(async()=>{
   const {messagePages,fileLabel}=await import('./navigation.js'),doc=window.mainDocument;
   const number=Number(document.querySelector('#page').value);
   const owners=doc.inspection.messages.filter(m=>messagePages(doc.inspection,m).includes(number));
   if(owners.length!==1)throw new Error('Expected one email on search page '+number);
   return {page:number,files:doc.inspection.files.filter(f=>f.owners.value?.includes(owners[0].id)).map(fileLabel).sort()};
  });
  assert.deepEqual((await page.locator('#files .file-name').allTextContents()).sort(),expected.files,'files must follow the matching email on page '+expected.page);
  assert.ok(expected.files.length>0);return expected;
 };
 const numberFits=await page.locator('#page').evaluate(input=>{const style=getComputedStyle(input),canvas=document.createElement('canvas'),ctx=canvas.getContext('2d');ctx.font=style.font;return input.clientWidth-parseFloat(style.paddingLeft)-parseFloat(style.paddingRight)>=ctx.measureText('1940').width;});assert.ok(numberFits,'four-digit page numbers fit without clipping');
 const ownedHits=await page.evaluate(async()=>{
  const {messagePages}=await import('./navigation.js'),owned=new Set(window.mainDocument.inspection.messages.flatMap(m=>messagePages(window.mainDocument.inspection,m)));
  return [...document.querySelectorAll('.collection-hit')].filter(b=>owned.has(Number(b.querySelector('.match-meta').textContent.match(/Page (\d+)/)[1]))).map(b=>b.dataset.matchIndex);
 });assert.ok(ownedHits.length>1);await page.locator(`[data-match-index="${ownedHits[0]}"]`).click();await ready();
 const firstEmail=await checkFiles();await page.locator('.collection-hit').last().click();await ready();const lastEmail=await checkFiles();assert.notDeepEqual(lastEmail.files,firstEmail.files);
 await click('#find-previous');await checkFiles();await click('#find');await checkFiles();
 assert.equal(await page.locator('#current-document').count(),0);assert.equal(await page.locator('.reader-heading').isVisible(),false);
 assert.equal(await page.locator('#breadcrumbs button').count(),1);
 await page.locator('#close-search').click();await page.locator('#toggle-details').click();assert.ok(await page.locator('#show-details').isVisible());await page.locator('#show-details').click();await checkFiles();
 assert.deepEqual(errors,[]);await mkdir('test-results',{recursive:true});const report={status:'PASS',totalEmailMatches:total,checks:['first match opens automatically from a child PDF','active highlight','next and previous reuse scan','occurrences and wraparound','result-list selection','empty-result reuse','changed query rescans','cancellation and retry','new document invalidates results','real Enron multi-message search refreshes owned files on click/next/previous','single document title and pane restoration','clearing input resets results; Escape closes the search panel']};await writeFile('test-results/collection-matches.json',JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
}finally{await context.close();}
