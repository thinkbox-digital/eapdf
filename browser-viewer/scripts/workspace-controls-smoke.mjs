import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {resolve} from 'node:path';
import {mkdir,writeFile} from 'node:fs/promises';
const extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,viewport:{width:1600,height:1000},executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--load-extension=${extension}`,`--disable-extensions-except=${extension}`]});
try{
 const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage();
 await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
 const ready=async()=>{await page.waitForFunction(()=>!document.querySelector('#file').disabled);await page.waitForTimeout(250);await page.waitForFunction(()=>!document.querySelector('#file').disabled);};
 await page.locator('#file').setInputFiles(resolve('../out/eml/eml_group_002_1c.pdf'));await ready();
 assert.equal(await page.locator('#canvas').isVisible(),true);
 assert.equal(await page.locator('#page-surface').isVisible(),true);
 assert.equal(await page.locator('#tree details[open]').count(),0,'Folders start collapsed');
 await page.waitForFunction(()=>!document.querySelector('#archive-stats').hidden);
 const stats=await page.locator('#archive-stats').textContent();assert.match(stats,/15 messages.*folders.*attachments/);
 const names=await page.locator('#tree summary[data-folder-id]').evaluateAll(nodes=>nodes.map(n=>[...n.childNodes].filter(c=>c.nodeType===3).map(c=>c.textContent).join('').trim()));
 const numbers=names.filter(n=>/^\d+$/.test(n)).map(Number);assert.ok(numbers.length>2);assert.deepEqual(numbers,[...numbers].sort((a,b)=>a-b));
 const left=page.locator('#navigation-panel'),before=(await left.boundingBox()).width,bar=await page.locator('#resize-navigation').boundingBox();
 await page.mouse.move(bar.x+4,bar.y+120);await page.mouse.down();await page.mouse.move(bar.x+104,bar.y+120,{steps:5});await page.mouse.up();await ready();
 assert.ok((await left.boundingBox()).width>before+80);
 await page.locator('#resize-details').focus();const rightBefore=(await page.locator('#details-panel').boundingBox()).width;await page.keyboard.press('ArrowLeft');await ready();assert.ok((await page.locator('#details-panel').boundingBox()).width>rightBefore);
 await page.locator('#tree details').evaluateAll(nodes=>nodes.forEach(n=>n.open=true));
 await page.locator('#tree button[data-embedded-file-id]').first().click();await ready();
 assert.equal(await page.locator('#collection-start').count(),0);assert.equal(await page.locator('#page-surface').isVisible(),true);assert.equal(await page.locator('#archive-stats').textContent(),stats);
 await page.setViewportSize({width:390,height:844});await ready();assert.equal(await page.locator('#resize-navigation').isVisible(),false);assert.equal(await page.locator('#resize-details').isVisible(),false);
 await page.setViewportSize({width:1600,height:1000});
 for(const profile of ['c','ci','m','mi']){
  await page.locator('#file').setInputFiles(resolve(`../output/viewer-updates-20261110/${profile}/eml_viewer_regression_1${profile}.pdf`));await ready();
  await page.waitForFunction(()=>!document.querySelector('#archive-stats').hidden);
  assert.match(await page.locator('#archive-stats').textContent(),/^2 messages · 1 folders · \d+ attachments$/);
 }
 await mkdir('test-results',{recursive:true});await writeFile('test-results/workspace-controls.json',JSON.stringify({status:'PASS',stats,folderOrder:numbers},null,2)+'\n');console.log({status:'PASS',stats,folderOrder:numbers});
}finally{await context.close();}
