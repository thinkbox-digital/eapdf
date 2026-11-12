import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,rm,writeFile} from 'node:fs/promises';
import {join,resolve} from 'node:path';
import {tmpdir} from 'node:os';
import {portfolioFixture} from './portfolio-fixtures.mjs';

const work=await mkdtemp(join(tmpdir(),'eapdf-portfolio-download-'));
const extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,acceptDownloads:true,
  executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',
  args:[`--load-extension=${extension}`,`--disable-extensions-except=${extension}`]});
try{
  const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker');
  const page=await context.newPage();
  await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
  const ready=()=>page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false',null,{timeout:90000});
  const member=portfolioFixture({collection:false,files:[]});
  const path=join(work,'collection.pdf');
  await writeFile(path,portfolioFixture({initial:'active.pdf',files:[['active.pdf',member,1]]}));
  await page.locator('#file').setInputFiles(path);
  await page.waitForFunction(()=>document.querySelector('#status').textContent.includes('active.pdf')&&
    document.querySelector('#viewport').getAttribute('aria-busy')==='false',null,{timeout:90000});
  const control=page.locator('#download-message');
  assert.equal(await control.isEnabled(),true);
  assert.match(await control.innerText(),/Download active file/);
  const pending=page.waitForEvent('download');await control.click();
  const download=await pending;
  assert.equal(download.suggestedFilename(),'active.pdf');
  assert.deepEqual(await readFile(await download.path()),member);
  await ready();
  await page.getByRole('searchbox',{name:'Filter portfolio files and metadata'}).fill('active');
  await page.waitForTimeout(250);
  await page.locator('#back').click();await ready();
  assert.equal(await page.getByRole('searchbox',{name:'Filter portfolio files and metadata'}).inputValue(),'');
  assert.equal(await page.locator('[data-portfolio-row][aria-current]').count(),0);
  assert.equal(await page.locator('.portfolio-scroll').evaluate(el=>el.scrollTop),0);
  assert.equal(await page.locator('#back').isDisabled(),true);
  await page.locator('#file').setInputFiles(resolve('tests/fulltext-index/mbox_sample_1c.pdf'));
  await ready();
  await page.locator('#tree details').evaluateAll(nodes=>nodes.forEach(node=>node.open=true));
  await page.locator('#tree button[data-embedded-file-id]').first().click();await ready();
  await page.evaluate(()=>document.querySelector('#navigation-panel').scrollTop=1000);
  await page.locator('#back').click();await ready();
  assert.equal(await page.locator('#navigation-panel').evaluate(el=>el.scrollTop),0);
  assert.equal(await page.locator('#back').isDisabled(),true);
  assert.equal(await page.locator('#page').inputValue(),'1');
  console.log('PASS: active PDF bytes and filename; Portfolio and EA-PDF Parent navigation.');
}finally{await context.close();await rm(work,{recursive:true,force:true});}
