import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {resolve} from 'node:path';
import {mkdir,writeFile} from 'node:fs/promises';
const extension=resolve('dist/chromium'),context=await chromium.launchPersistentContext('',{headless:true,executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--load-extension=${extension}`,`--disable-extensions-except=${extension}`]});
try{
 await context.setOffline(true);const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage();await page.setViewportSize({width:1500,height:900});
 await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
 await page.locator('#file').setInputFiles(resolve('tests/fulltext-index/mbox_sample_1mi.pdf'));
 const ready=()=>page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false');await ready();
 await page.waitForFunction(()=>getComputedStyle(document.querySelector('#viewport')).overflowX==='hidden');
 assert.equal(await page.locator('#zoom').inputValue(),'fit-page');
 await page.locator('#zoom').selectOption('1.5');await ready();
 await page.waitForFunction(()=>getComputedStyle(document.querySelector('#viewport')).overflowX==='auto');
 assert.ok(await page.locator('#viewport').evaluate(e=>e.scrollWidth>e.clientWidth));
 await page.locator('#zoom').selectOption('fit-width');await ready();await page.waitForFunction(()=>getComputedStyle(document.querySelector('#viewport')).overflowX==='hidden');
 for(const selector of ['.reader-pane','#details-panel']){
  const ids=await page.locator(selector).evaluate(p=>[...p.querySelectorAll('button,summary,a[href]')].filter(e=>e.getClientRects().length&&!e.closest('[hidden],[inert]')&&!e.matches(':disabled')).slice(0,2).map((e,i)=>{e.dataset.keyboardProbe=i;return i;}));assert.equal(ids.length,2,selector);
  const a=page.locator(selector+' [data-keyboard-probe="0"]'),b=page.locator(selector+' [data-keyboard-probe="1"]');await a.focus();await page.keyboard.press('ArrowDown');assert.equal(await b.evaluate(e=>e===document.activeElement),true,selector);await page.keyboard.press('ArrowUp');assert.equal(await a.evaluate(e=>e===document.activeElement),true,selector);
 }
 await page.locator('#viewport').focus();const before=await page.locator('#viewport').evaluate(e=>e.scrollTop);await page.keyboard.press('ArrowDown');assert.ok(await page.locator('#viewport').evaluate(e=>e.scrollTop)>before);
 await page.locator('#open-search').click();await page.locator('#search-scope').selectOption('collection');await page.locator('#search').fill('Power VaR');await page.locator('#search-submit').click();await ready();
 await page.waitForFunction(()=>document.querySelector('#collection-matches button[data-match-index="1"]'));
 await page.locator('#search').focus();await page.keyboard.press('ArrowDown');await ready();assert.equal(await page.locator('#collection-matches button[data-match-index="0"]').evaluate(e=>e===document.activeElement),true);
 await page.keyboard.press('ArrowDown');await ready();assert.equal(await page.locator('#collection-matches button[data-match-index="1"]').evaluate(e=>e===document.activeElement),true);await page.keyboard.press('Enter');await ready();await page.waitForFunction(()=>document.querySelector('#search-status').textContent.includes('Match 2 of 3'));
 const sizes=await page.evaluate(()=>{const dialog=document.querySelector('#export-dialog'),message=document.querySelector('#export-message');dialog.showModal();const result=[];for(const text of ['Preparing…','very-long-name-'.repeat(1000),'Done']){message.textContent=text;result.push({height:dialog.getBoundingClientRect().height,messageHeight:message.getBoundingClientRect().height,overflow:getComputedStyle(message).overflowY,dialogOverflow:dialog.scrollHeight>dialog.clientHeight});}dialog.close();return result;});
 assert.equal(new Set(sizes.map(s=>s.height)).size,1);assert.equal(new Set(sizes.map(s=>s.messageHeight)).size,1);assert.ok(sizes.every(s=>s.overflow==='hidden'&&!s.dialogOverflow));
 await mkdir('test-results',{recursive:true});await writeFile('test-results/reader-polish.json',JSON.stringify({status:'PASS',checks:['fit-width without horizontal scrollbar','oversized page remains scrollable','up/down across all three panes','viewport scrolling','search-result focus and activation','stable export dialog without scrollbars']},null,2)+'\n');console.log('PASS: reader scrollbars, pane/result keys and stable CSV dialog.');
}finally{await context.close();}
