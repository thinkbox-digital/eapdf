// Documentation captures: actual extension, using fictional email.
import {chromium} from '../../browser-viewer/node_modules/playwright/index.mjs';
import {readFile,mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';
const root=resolve(import.meta.dirname,'../..'), work=process.env.MANUAL_WORK??'/tmp/eapdf-user-manual', screenshots=resolve(root,process.env.MANUAL_SCREENSHOTS??'docs/manual/screenshots');
const viewport=(process.env.MANUAL_VIEWPORT??'1200x850').match(/^(\d+)x(\d+)$/);
if(!viewport)throw new Error('MANUAL_VIEWPORT must be WIDTHxHEIGHT');
const [width,height]=viewport.slice(1).map(Number);
const extension=resolve(root,'browser-viewer/dist/chromium');
await mkdir(screenshots,{recursive:true});
if(process.env.MANUAL_SKIP_VIEWER!=='1') {
const context=await chromium.launchPersistentContext('',{headless:true,viewport:{width,height},executablePath:process.env.CHROMIUM_PATH,args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
try {
 await context.setOffline(true);
 const service=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker');
 const page=await context.newPage();await page.goto(`chrome-extension://${new URL(service.url()).host}/viewer.html`);
 await page.waitForFunction(()=>!document.querySelector('#file').disabled);
 const ready=()=>page.waitForFunction(()=>!document.querySelector('#canvas').hidden&&document.querySelector('#viewport').getAttribute('aria-busy')==='false');
 await page.locator('#file').setInputFiles(work+'/manual_1m.pdf');await ready();
 await page.locator('[data-message-id]').first().click();await ready();
 await page.locator('#zoom').selectOption('fit-width');await ready();
 await page.screenshot({path:resolve(screenshots,'viewer-email.png')});
 await page.locator('.file-card').filter({has:page.locator('.file-name',{hasText:'agenda.txt'})}).locator('[data-view-file-id]').click();
 await page.waitForSelector('#attachment-text:visible');
 await page.screenshot({path:resolve(screenshots,'viewer-attachment.png')});
 await page.keyboard.press('Escape');
 await page.locator('#file').setInputFiles(work+'/manual_1c.pdf');await ready();
 await page.screenshot({path:resolve(screenshots,'viewer-collection.png')});
 await page.locator('#export-csv').click();await page.locator('#metadata-dialog').waitFor({state:'visible'});
 const csv=page.waitForEvent('download');await page.locator('#metadata-save').click();const download=await csv;await download.saveAs(work+'/metadata.csv');
 assert.match(await readFile(work+'/metadata.csv','utf8'),/October planning meeting/);
} finally {await context.close();}
}
console.log('Captured reader screenshots.');
