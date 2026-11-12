import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {resolve} from 'node:path';

const extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,
  executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',
  args:[`--load-extension=${extension}`,`--disable-extensions-except=${extension}`]});
try{
  const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker');
  const page=await context.newPage();
  await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
  await page.locator('#file').setInputFiles(resolve('tests/fulltext-index/mbox_sample_1m.pdf'));
  const ready=()=>page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false',null,{timeout:90000});
  await ready();
  const panel=page.locator('#outline-panel');
  assert.equal(await panel.isVisible(),true);
  assert.equal(await panel.getAttribute('open'),null);
  const caret=()=>panel.locator('summary').evaluate(el=>({content:getComputedStyle(el,'::before').content,rotation:getComputedStyle(el,'::before').transform}));
  const closedCaret=await caret();assert.notEqual(closedCaret.content,'none');
  await panel.locator('summary').click();
  await page.waitForTimeout(180);
  const openCaret=await caret();assert.notEqual(openCaret.rotation,closedCaret.rotation);
  const initial=await page.locator('#outline').innerText();
  assert.ok(initial.length>0);
  await page.evaluate(()=>{
    const panel=document.querySelector('#outline-panel'),list=document.querySelector('#outline'),file=document.querySelector('#file');
    window.bookmarkFlash=[];
    const check=()=>{if(panel.hidden)window.bookmarkFlash.push('bookmarks hidden');
      if(!panel.open)window.bookmarkFlash.push('bookmarks collapsed');
      if(!list.children.length)window.bookmarkFlash.push('bookmark list cleared');
      if(file.disabled)window.bookmarkFlash.push('Open PDF disabled');};
    new MutationObserver(check).observe(panel,{attributes:true,childList:true,subtree:true});
    new MutationObserver(check).observe(file,{attributes:true});
  });
  const messages=page.locator('#tree button[data-message-id]');
  assert.ok(await messages.count()>=2);
  await messages.first().click();await ready();
  await messages.last().click();await ready();
  await messages.last().focus();await page.keyboard.press('ArrowUp');await ready();
  assert.deepEqual(await page.evaluate(()=>window.bookmarkFlash),[]);
  assert.equal(await panel.getAttribute('open'),'');
  assert.equal(await page.locator('#outline').innerText(),initial);
  assert.equal(await page.locator('#file').isEnabled(),true);
  console.log('PASS: bookmarks and Open PDF stay stable across clicks and arrow navigation.');
}finally{await context.close();}
