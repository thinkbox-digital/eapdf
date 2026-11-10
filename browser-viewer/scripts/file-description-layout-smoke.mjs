import assert from 'node:assert/strict';
import {chromium} from 'playwright';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
const extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,viewport:{width:1600,height:1000},executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
try {
 const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage();
 await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
 await page.waitForFunction(()=>!document.querySelector('#file').disabled);
 await page.locator('#file').setInputFiles(resolve(process.argv[2]??'../out/msg/msg_group_001_1c.pdf'));
 const ready=()=>page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false');
 await ready();
 await page.locator('#tree details').evaluateAll(nodes=>nodes.forEach(node=>node.open=true));
 await page.locator('#tree button[data-embedded-file-id]').first().click();await ready();
 assert.ok(await page.locator('.file-card .file-description').count()>=2,'Fixture exposes both stored description and subject');
 for(const width of [2048,1600,1100]) {
  await page.setViewportSize({width,height:1000});
  const rows=await page.locator('.file-card').evaluateAll(cards=>cards.map(card=>[...card.querySelectorAll('.file-heading,.file-description,.file-message-links')].map(node=>{const r=node.getBoundingClientRect();return {text:node.textContent,top:r.top,bottom:r.bottom};})));
  for(const row of rows)for(let i=1;i<row.length;i++)assert.ok(row[i].top>=row[i-1].bottom,JSON.stringify({width,row}));
 }
 console.log('PASS: real MSG source description and subject occupy separate rows at three viewport widths');
} finally {await context.close();}
