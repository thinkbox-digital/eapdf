import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {resolve} from 'node:path';
import {mkdir} from 'node:fs/promises';
const extension=resolve(process.env.EAPDF_VIEWER_PATH??'dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--load-extension=${extension}`,`--disable-extensions-except=${extension}`]});
try{
 const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage();
 await page.setViewportSize({width:1600,height:1000});await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
 await page.locator('#file').setInputFiles(resolve('tests/attachments/viewing.pdf'));
 await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false');
 const open=page.getByRole('button',{name:'View plain.txt',exact:true});
 await open.click();await page.locator('#attachment-viewer[aria-busy="false"]').waitFor();
 const close=page.getByRole('button',{name:'Close attachment preview',exact:true});
 assert.match(await close.innerText(),/Close/);assert.equal(await close.evaluate(e=>e===document.activeElement),true);
 // A search panel behind the modal must not intercept Escape.
 await page.evaluate(()=>document.querySelector('#search-panel').hidden=false);
 await page.keyboard.press('Escape');await page.locator('#attachment-viewer').waitFor({state:'hidden'});
 assert.equal(await open.evaluate(e=>e===document.activeElement),true);
 assert.equal(await page.locator('#search-panel').isVisible(),true);await page.locator('#close-search').click();
 await open.click();await page.locator('#attachment-viewer[aria-busy="false"]').waitFor();
 // Long filenames and long content must not move the Close control offscreen.
 await page.evaluate(()=>{document.querySelector('#attachment-title').textContent='Long attachment filename '.repeat(50);document.querySelector('#attachment-text').textContent='Email attachment content\n'.repeat(10000);});
 await mkdir('test-results',{recursive:true});
 for(const width of [1600,390]){
  await page.setViewportSize({width,height:800});
  await page.locator('.attachment-content').evaluate(e=>e.scrollTop=e.scrollHeight);
  const box=await close.boundingBox(),dialog=await page.locator('#attachment-viewer').boundingBox();
  assert.ok(box&&dialog&&box.x>=dialog.x&&box.x+box.width<=dialog.x+dialog.width&&box.y>=dialog.y&&box.y+box.height<dialog.y+80,'Close remains in the visible top-right title bar');
  assert.equal(await page.locator('#attachment-viewer').evaluate(e=>e.scrollHeight<=e.clientHeight+1),true,'only the content pane scrolls');
  await page.screenshot({path:`test-results/attachment-window-${width}.png`});
 }
 await close.click();await page.locator('#attachment-viewer').waitFor({state:'hidden'});
 console.log('PASS: top-right Close button, Escape with search behind, focus return, fixed title bar and narrow/long-content layout.');
}finally{await context.close();}
