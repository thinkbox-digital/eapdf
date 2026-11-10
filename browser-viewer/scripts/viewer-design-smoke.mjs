import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
const extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,viewport:{width:1600,height:1050},executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
try{
 const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage();await context.setOffline(true);const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
 const ready=()=>page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false',null,{timeout:120000});
 const fixture=resolve('../examples/pst/pst_quality-attached-message_1c.pdf');await page.locator('#file').setInputFiles(fixture);await ready();
 await page.waitForFunction(()=>document.querySelector('#subject-index-status').textContent.startsWith('Subjects ready'),null,{timeout:120000});
 const initialSubjects=await page.locator('#tree button[data-embedded-file-id] .message-subject').allTextContents();
 assert.ok(initialSubjects.includes('Feature Generators'),'subjects must appear before opening child PDFs');
 assert.ok(!initialSubjects.some(label=>label==='Feature Generators_000006_1s.pdf'));
 const dimensions=()=>page.evaluate(()=>{const box=id=>document.querySelector(id).getBoundingClientRect();return {workspace:box('#workspace').width,left:box('#navigation-panel').width,right:box('#details-panel').width,reader:box('.reader-pane').width,dividers:[...document.querySelectorAll('.pane-divider')].reduce((sum,e)=>sum+e.getBoundingClientRect().width,0),footer:box('footer').bottom,overflow:document.documentElement.scrollWidth>innerWidth};});
 const desktop=await dimensions();assert.equal(desktop.left,432);assert.equal(desktop.right,432);assert.equal(desktop.reader,desktop.workspace-desktop.left-desktop.right-desktop.dividers);assert.equal(desktop.overflow,false);assert.ok(desktop.footer<=1050);
 await page.locator('#open-search').click();await page.locator('#search-scope').selectOption('collection');await page.locator('#search').fill('email');await page.locator('#search-submit').click();await ready();
 assert.equal(await page.locator('#results-tab').getAttribute('aria-selected'),'true');assert.equal(await page.locator('#browse-panel').isVisible(),true);
 const groups=await page.locator('.result-heading').allTextContents();assert.ok(groups.length>1);assert.ok(groups.every(name=>!name.includes('pst_quality-attached-message_1c.pdf')));assert.ok(await page.locator('.match-excerpt mark').count()>0);
 // Real search results must stay below the form inside a single panel column.
 const searchGeometry=()=>page.evaluate(()=>{const box=id=>{const r=document.querySelector(id).getBoundingClientRect();return {left:r.left,right:r.right,top:r.top,bottom:r.bottom,height:r.height};};return {panel:box('#search-panel'),form:box('#search-form'),results:box('#collection-results'),panelScroll:document.querySelector('#search-panel').scrollTop};});
 for(const width of [1600,1100,390]){
  await page.setViewportSize({width,height:1050});
  const geometry=await searchGeometry();
  assert.ok(geometry.results.left>=geometry.panel.left&&geometry.results.right<=geometry.panel.right,'results stay inside the panel');
  assert.ok(geometry.results.top>=geometry.form.bottom&&geometry.results.height>0,'results stay below the stationary search form');
  const before=geometry.form;await page.locator('#collection-results').evaluate(e=>{e.scrollTop=e.scrollHeight;});
  assert.deepEqual((await searchGeometry()).form,before,'only results scroll');
  assert.equal((await searchGeometry()).panelScroll,0);
  await page.locator('#collection-results').evaluate(e=>{e.scrollTop=0;});
 }
 await page.setViewportSize({width:1600,height:1050});
 const count=await page.locator('.collection-hit').count();assert.ok(count>=groups.length);assert.equal(await page.locator('.result-heading .tree-icon').count(),groups.length);
 await page.locator('#find').click();await ready();assert.match(await page.locator('#search-status').innerText(),/^Match 2 of/);
 await page.locator('#search-submit').click();await ready();assert.match(await page.locator('#search-status').innerText(),/^Match 1 of/);
 await page.locator('.collection-hit').last().click();await ready();assert.equal(await page.locator('.collection-hit[aria-current]').count(),1);
 const selected=await page.locator('.collection-hit[aria-current]').innerText();assert.ok(selected.includes('Viewing'));
 const activeBox=await page.locator('.collection-hit[aria-current]').boundingBox(),panelBox=await page.locator('#search-panel').boundingBox();assert.ok(activeBox.y+activeBox.height<=panelBox.y+panelBox.height+1);
 await page.locator('#close-search').click();assert.ok(await page.locator('#message-filter').isVisible());
 await page.locator('#message-filter').fill('Feature');await page.locator('#filter-results button').first().waitFor();
 const filtered=await page.locator('#filter-results').innerText();assert.ok(!filtered.includes('pst_quality-attached-message_1c.pdf'));assert.ok(!filtered.includes(' / '));
 assert.equal(await page.locator('#filter-results small').count(),0,'single-message files should not repeat the subject');
 const destination=await page.locator('#filter-results button .message-subject').first().innerText();await page.locator('#filter-results button').first().click();await ready();assert.equal(await page.locator('#filter-results [aria-current] .message-subject').last().innerText(),destination);
 await page.locator('#open-search').click();
 await page.locator('.collection-hit').last().click();await ready();
 const categories=await page.locator('.file-category-heading>span:not(.badge)').allTextContents();assert.ok(categories.includes('Email sources'));assert.ok(categories.includes('Technical and supporting files'));
 assert.equal(categories[0],'Email sources');assert.equal(await page.locator('.file-owner').count(),0);
 assert.equal(await page.locator('#document-info').count(),0);assert.match(await page.locator('#declared-profile').innerText(),/EA-PDF 1s/);
 const premis=page.locator('.file-card').filter({has:page.locator('.file-name',{hasText:/premis\.xml$/i})});assert.equal(await premis.count(),0,'current examples disable optional PREMIS');
 assert.ok(await page.locator('.file-category details>ul .file-card').count()>0);
 assert.equal(await page.locator('.app-header #breadcrumbs').count(),0);
 assert.equal(await page.locator('.reader-pane #breadcrumbs').count(),1);
 assert.equal(await page.locator('.global-actions #export-csv').count(),1);
 assert.equal(await page.locator('.global-actions #close').count(),1);
 assert.equal(await page.locator('.file-category .file-role').count(),0);
 assert.ok(await page.locator('.file-actions button .tree-icon').count()>0);
 assert.equal(await page.locator('#back').getAttribute('aria-label'),'Return to parent PDF');

 await page.locator('#close-search').click();
 for(const [hide,show,panel] of [['toggle-navigation','show-navigation','navigation-panel'],['toggle-details','show-details','details-panel']]){
  await page.locator('#'+hide).click();assert.equal(await page.locator('#'+panel).isVisible(),false);assert.equal(await page.evaluate(()=>document.activeElement.id),show);
  await page.locator('#'+show).click();assert.ok(await page.locator('#'+panel).isVisible());assert.equal(await page.evaluate(()=>document.activeElement.id),hide);
 }
 await mkdir('test-results',{recursive:true});await page.screenshot({path:'test-results/viewer-design-desktop.png',fullPage:true});
 await page.setViewportSize({width:900,height:950});await page.waitForFunction(()=>document.querySelector('#workspace').dataset.layout==='medium');const medium=await dimensions();assert.equal(medium.left,medium.workspace*desktop.left/desktop.workspace);assert.equal(medium.right,0);assert.equal(medium.overflow,false);
 await page.setViewportSize({width:600,height:950});await page.waitForFunction(()=>document.querySelector('#workspace').dataset.layout==='compact');assert.equal((await dimensions()).overflow,false);await page.screenshot({path:'test-results/viewer-design-narrow.png',fullPage:true});
 await page.locator('#close').click();assert.equal(await page.locator('#results-tab').isDisabled(),true);assert.equal(await page.locator('#browse-tab').getAttribute('aria-selected'),'true');
 // Keep optional PREMIS grouping coverage independent of current conversion defaults.
 const legacy=JSON.parse(await readFile('tests/attachments/premis-legacy.json','utf8'));
 assert.equal(createHash('sha256').update(await readFile(legacy.sourcePath)).digest('hex'),legacy.sha256);
 await page.locator('#file').setInputFiles(resolve(legacy.sourcePath));await ready();
 assert.ok(await premis.count()>0);assert.equal(await premis.first().locator('xpath=../../..').locator('.file-category-heading>span:not(.badge)').innerText(),'Technical and supporting files');
 await page.locator('#close').click();
 assert.deepEqual(errors,[]);const report={status:'PASS',desktop,medium,groups,matchingPages:count,checks:['equal 27% sidebars','filename-only search groups and metadata results','filter destinations preserved','distinct file-category headers','highlighted excerpts','search returns to first match','search popover keeps message filtering available','pane focus and restore controls','no horizontal overflow','closing resets results','optional PREMIS remains grouped in legacy files']};await writeFile('test-results/viewer-design.json',JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
}finally{await context.close();}
