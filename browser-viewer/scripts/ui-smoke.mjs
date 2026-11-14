import {checkReadingTools} from './reading-checks.mjs';
import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkTripAttachments } from './trip-attachment-checks.mjs';
import { checkScrolling } from './scrolling-checks.mjs';
import { checkNavigation } from './navigation-checks.mjs';
import { checkAttachments } from './attachment-checks.mjs';
import { checkInterface } from './ui-checks.mjs';
process.chdir(fileURLToPath(new URL('../', import.meta.url)));
const extension = resolve('dist/chromium');
const context = await chromium.launchPersistentContext('', {
  headless: true, acceptDownloads: true,
  ...(process.env.CHROMIUM_PATH ? {executablePath: process.env.CHROMIUM_PATH} : {channel:'chromium'}),
  args: [`--disable-extensions-except=${extension}`, `--load-extension=${extension}`],
});
try {
  const requests = [], errors = [];
  context.on('request', r => { if (/^https?:/.test(r.url())) requests.push(r.url()); });
  await context.setOffline(true);
  const service = context.serviceWorkers()[0] ?? await context.waitForEvent('serviceworker');
  let page = await context.newPage();
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(`chrome-extension://${new URL(service.url()).host}/viewer.html`);
  await page.waitForFunction(() => !document.querySelector('#file').disabled);
  const ready = () => page.waitForFunction(() => document.querySelector('#viewport').getAttribute('aria-busy') === 'false', null, { timeout: 60000 });
  const adapter = {
    evaluate: fn => page.evaluate(fn),
    workerCount: async (maximum=3) => {
      const deadline=Date.now()+10000;
      while(page.workers().length>maximum&&Date.now()<deadline)await new Promise(resolve=>setTimeout(resolve,20));
      return page.workers().length;
    },
    waitFor: predicate => page.waitForFunction(predicate,null,{timeout:60000}),
    ready,
    click: async selector => {if(selector.includes('data-file-id')||selector.includes('data-view-file-id'))await page.locator('#files details').evaluateAll(ds=>ds.forEach(d=>d.open=true));await page.locator(selector).first().click();},
    open: async path => { await page.locator('#file').setInputFiles(path); await ready(); assert.equal(await page.locator('#canvas').isVisible(),true,await page.locator('#status').textContent()); },
    download: async selector => { if(selector.includes('data-file-id'))await page.locator('#files details').evaluateAll(ds=>ds.forEach(d=>d.open=true));const event = page.waitForEvent('download'); await page.locator(selector).click(); let download;try{download=await event;}catch(error){throw new Error(`Download failed for ${selector}; ${await page.locator('#status').textContent()}; ${await page.locator('#breadcrumbs').textContent()}`,{cause:error});}return readFile(await download.path()); },
  };
  const results = await checkInterface(adapter);
    const reading=await checkReadingTools(adapter);await writeFile('test-results/reading-tools-chromium.json',JSON.stringify(reading,null,2)+'\n');
  // Exercise repeated downloads in the same tab throughout the full workflow.
  const attachments = await checkAttachments(adapter);
  const trip=await checkTripAttachments(adapter);
  await writeFile('test-results/trip-attachments-chromium.json',JSON.stringify(trip,null,2)+'\n');
  const scrolling = await checkScrolling(adapter);
  await adapter.open(resolve('tests/attachments/source/pst_first_message_legacy.pdf'));
  await page.locator('#zoom').selectOption('1');await ready();
  const viewport=await page.locator('#viewport').boundingBox();await page.mouse.move(viewport.x+viewport.width/2,viewport.y+viewport.height/2);await page.mouse.wheel(0,1100);
  await page.waitForFunction(()=>Number(document.querySelector('#page').value)>1&&document.querySelector('#canvas').closest('.pdf-page').dataset.rendered==='true');
  scrolling.checks.push('Native mouse wheel advances the visible page and counter');
  await page.screenshot({path:'test-results/continuous-scroll-pst.png',fullPage:true});
  await writeFile('test-results/scrolling-chromium.json',JSON.stringify(scrolling,null,2)+'\n');
  const navigation = await checkNavigation(adapter);
  await writeFile('test-results/navigation-chromium.json',JSON.stringify({status:'PASS',browser:context.browser()?.version(),navigation},null,2)+'\n');
  // Cancel while asynchronous inspection has started, then reopen immediately.
  await page.evaluate(() => {
    const data = new DataTransfer(); data.items.add(new File(['%PDF-broken'], 'cancelled.pdf'));
    document.querySelector('#file').files = data.files;
    document.querySelector('#file').dispatchEvent(new Event('change'));
    document.querySelector('#close').click();
  });
  await adapter.open(resolve('tests/fixtures/ordinary.pdf'));
  assert.match(await page.locator('#status').textContent(), /ordinary.pdf.*Page 1/);
  assert.deepEqual(requests, []); assert.deepEqual(errors, []);
  await mkdir('test-results', {recursive:true});
  await page.screenshot({path:'test-results/m5-ordinary.png',fullPage:true});
  await writeFile('test-results/beta-attachments-chromium.json',JSON.stringify({status:'PASS',browser:context.browser()?.version(),attachments},null,2)+'\n');
  await writeFile('test-results/m5-chromium.json', JSON.stringify({status:'PASS',browser:context.browser()?.version(),results,httpRequests:requests.length,pageErrors:errors.length,cancellation:'PASS'},null,2)+'\n');
  console.log(`PASS: ${results.length} UI cases; ${results.reduce((n,r)=>n+(r.messagesSelected??0),0)} message selections; exact saved bytes; offline; cancellation.`);
} finally { await context.close(); }
