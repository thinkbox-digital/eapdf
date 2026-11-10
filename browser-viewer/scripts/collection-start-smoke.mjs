import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {resolve,basename} from 'node:path';
import {fileURLToPath} from 'node:url';
import {readFile,mkdir,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
const inputs=process.argv.slice(2).map(p=>resolve(p));
if(!inputs.length)for(const profile of ['c','ci'])inputs.push(resolve(`../examples/eml/eml_Agenda_and_timeline_slides_archive_1${profile}.pdf`));
const extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,viewport:{width:1600,height:1000},executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
try{
 await context.setOffline(true);const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage(),errors=[],reports=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);await page.waitForFunction(()=>!document.querySelector('#file').disabled);
 const ready=()=>page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false',null,{timeout:180000});
 for(const input of inputs){
  const before=createHash('sha256').update(await readFile(input)).digest('hex');
  await page.locator('#file').setInputFiles(input);await ready();
  assert.equal(await page.locator('#canvas').isVisible(),true);
  await page.waitForFunction(()=>document.querySelector('#page-surface').textContent.includes('Email Collection'));
  const text=await page.locator('#page-surface').textContent();assert.match(text,/Messages/);assert.match(text,/folder/i);
  assert.equal(await page.locator('#tree details[open]').count(),0,'Folders remain collapsed');
  assert.equal(await page.locator('#download-message').isDisabled(),true,'The collection page is not an email');
  assert.equal(await page.locator('#zoom').isEnabled(),true);assert.equal(await page.locator('#page').isEnabled(),true);
  const stats=await page.locator('#archive-stats').textContent();assert.match(stats,/messages.*folders.*attachments/);
  await page.locator('#zoom').selectOption('1');await ready();assert.equal(await page.locator('#canvas').isVisible(),true);
  await page.locator('#zoom').selectOption('fit-page');await ready();
  await mkdir('test-results',{recursive:true});await page.screenshot({path:resolve('test-results/collection-start-'+basename(input)+'.png'),fullPage:true});
  await page.locator('#tree details').evaluateAll(nodes=>nodes.forEach(node=>node.open=true));
  await page.locator('#tree button[data-embedded-file-id]').first().click();await ready();
  assert.equal(await page.locator('#download-message').isEnabled(),true);assert.equal(await page.locator('#archive-stats').textContent(),stats);
  await page.locator('#back').click();await ready();
  await page.waitForFunction(()=>document.querySelector('#page-surface').textContent.includes('Email Collection'));
  assert.equal(await page.locator('#download-message').isDisabled(),true);assert.equal(await page.locator('#canvas').isVisible(),true);
  assert.equal(createHash('sha256').update(await readFile(input)).digest('hex'),before);
  reports.push({file:input,sha256:before,status:'PASS',stats});
 }
 assert.deepEqual(errors,[]);await writeFile('test-results/collection-start.json',JSON.stringify(reports,null,2)+'\n');console.log(JSON.stringify(reports));
}finally{await context.close();}
