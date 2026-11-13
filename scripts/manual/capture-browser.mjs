// Documentation captures: actual extension, using fictional email.
import {chromium} from '../../browser-viewer/node_modules/playwright/index.mjs';
import {readFile,mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';
const root=resolve(import.meta.dirname,'../..'), work=process.env.MANUAL_WORK??'/tmp/eapdf-user-manual', screenshots=resolve(root,'docs/manual/screenshots');
const extension=resolve(root,'browser-viewer/dist/chromium');
await mkdir(screenshots,{recursive:true});
if(process.env.MANUAL_SKIP_VIEWER!=='1') {
const context=await chromium.launchPersistentContext('',{headless:true,viewport:{width:1200,height:850},executablePath:process.env.CHROMIUM_PATH,args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
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
 const csv=page.waitForEvent('download');await page.locator('#export-csv').click();const download=await csv;await download.saveAs(work+'/metadata.csv');
 assert.match(await readFile(work+'/metadata.csv','utf8'),/October planning meeting/);
} finally {await context.close();}
}
console.log('Captured reader screenshots.');
