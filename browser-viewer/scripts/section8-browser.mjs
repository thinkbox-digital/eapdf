import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {mkdtemp,rm,writeFile,readFile,mkdir} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {resolve} from 'node:path';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
const temporary=await mkdtemp(resolve(tmpdir(),'eapdf-section8-'));
execFileSync(process.env.M1_PYTHON??'python3',['scripts/section8-fixtures.py',temporary]);
const extension=resolve('dist/chromium'),checks=[];
const context=await chromium.launchPersistentContext('',{headless:true,viewport:{width:1000,height:900},executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',acceptDownloads:true,args:[`--load-extension=${extension}`,`--disable-extensions-except=${extension}`]});
try{
 await context.setOffline(true);const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);await page.waitForFunction(()=>!document.querySelector('#file').disabled);
 const ready=()=>page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false',null,{timeout:180000});
 const open=async name=>{await page.locator('#file').setInputFiles(resolve(temporary,name));await ready();};
 const hash=async name=>createHash('sha256').update(await readFile(resolve(temporary,name))).digest('hex');
 const before=await hash('attachments.pdf');await open('attachments.pdf');
 assert.equal(await page.locator('#details-panel').isVisible(),true);checks.push('S8-INITIAL: UseAttachments on medium window');
 assert.match(await page.locator('#files').textContent(),/Section 8 description — 日本語/);checks.push('S8-DESC: stored Unicode file descriptions');
 assert.match(await page.locator('#declared-profile').textContent(),/EA-PDF 1s/);checks.push('S8-CLAIM: declared profile');
 await page.locator('#page').fill('2');await page.locator('#page').press('Enter');await ready();
 const annotation=page.locator('.internal-links a[title="Section 8 attachment comment — 日本語"]');await annotation.waitFor();
 const pending=page.waitForEvent('download');await annotation.click();assert.match((await pending).suggestedFilename(),/\.pptx$/);await ready();checks.push('S8-ANNOTATION: comment and native attachment interaction');
 assert.equal(await hash('attachments.pdf'),before);checks.push('S8-READONLY: original unchanged after interaction');
 await open('outlines.pdf');assert.equal(await page.locator('#navigation-panel').isVisible(),true);assert.equal(await page.locator('#outline-panel').isVisible(),true);assert.equal(await page.locator('#outline-panel').getAttribute('open'),null);await page.locator('#outline-panel summary').click();assert.equal(await page.locator('#outline-panel').getAttribute('open'),'');
 await page.locator('#outline button').first().click();await ready();checks.push('S8-OUTLINES: collapsed default and bookmark activation');
 await open('invalid-name.pdf');assert.match(await page.locator('#loading-message').textContent(),/Invalid.*filename/);await page.locator('#loading-cancel').click();checks.push('S8-NAMES: invalid collection filename rejected');
 await open('unsupported-child.pdf');if(await page.locator('#show-navigation').isVisible())await page.locator('#show-navigation').click();await page.locator('#tree details').evaluateAll(nodes=>nodes.forEach(n=>n.open=true));await page.locator('#tree button[data-embedded-file-id]').first().click();await ready();assert.equal(await page.locator('#document-notice').isVisible(),true);assert.match(await page.locator('#document-notice').textContent(),/unsupported/);checks.push('S8-VERSION: unsupported child warning');
 if(process.env.SECTION8_EXTERNAL_SAMPLE){await page.locator('#file').setInputFiles(process.env.SECTION8_EXTERNAL_SAMPLE);await ready();assert.equal(await page.locator('#canvas').isVisible(),true);checks.push('S8-NAMES: external sample unnamed root opens');}
 assert.deepEqual(errors,[]);await mkdir('test-results',{recursive:true});await writeFile('test-results/section8-browser.json',JSON.stringify({status:'PASS',checks},null,2));console.log(JSON.stringify(checks));
}finally{await context.close();await rm(temporary,{recursive:true,force:true});}
