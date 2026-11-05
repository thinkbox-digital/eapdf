import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
const extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
try{
  await context.setOffline(true);
  const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker');
  const page=await context.newPage();
  await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  const cancelledPreparation=await page.evaluate(async()=>{
    const {BrowserSession}=await import('./browser-session.js');const session=new BrowserSession();
    const blob=new Blob([new Uint8Array(16*1024**2)]);let release;
    blob.arrayBuffer=()=>new Promise(resolve=>{release=resolve;});
    const opening=session.open(blob,'cancel-before-transfer.pdf');session.close();release(new ArrayBuffer(0));
    try{await opening;return false;}catch(error){return /cancelled/i.test(error.message)&&session.pending.size===0;}
  });
  assert.equal(cancelledPreparation,true,'cancellation during buffer preparation must not start a PDF worker');
  await page.evaluate(async()=>{
    const {BrowserSession}=await import('./browser-session.js');
    const original=BrowserSession.prototype.open;
    window.openDelay=1600;
    BrowserSession.prototype.open=async function(...args){await new Promise(resolve=>setTimeout(resolve,window.openDelay));return original.apply(this,args);};
  });
  const dialog=page.locator('#loading-dialog');
  const input=page.locator('#file');
  const fixture=resolve('../examples/pst/pst_quality-attached-message_1m.pdf');
  await input.setInputFiles(fixture);
  assert.equal(await dialog.isVisible(),false,'no immediate dialog flash');
  await dialog.waitFor({state:'visible'});
  assert.equal(await page.locator('#tree').isVisible(),false);
  await dialog.waitFor({state:'hidden',timeout:60000});
  assert.equal(await page.locator('#tree').isVisible(),true);
  await page.locator('#close').click();
  await input.setInputFiles(fixture);await dialog.waitFor({state:'visible'});
  await page.locator('#loading-cancel').click();
  assert.equal(await dialog.isVisible(),false);
  assert.equal(await input.isEnabled(),true);
  await input.setInputFiles(fixture);await dialog.waitFor({state:'visible'});
  await page.keyboard.press('Escape');
  assert.equal(await dialog.isVisible(),false);
  await input.setInputFiles({name:'invalid.pdf',mimeType:'application/pdf',buffer:Buffer.from('not a PDF')});
  await page.waitForFunction(()=>document.querySelector('#loading-message').textContent.startsWith('Unable to open PDF:'));
  assert.equal(await dialog.isVisible(),true);
  assert.equal(await page.locator('#loading-progress').isVisible(),false);
  await page.locator('#loading-cancel').click();
  await page.evaluate(()=>{window.openDelay=0;});
  await input.setInputFiles(fixture);
  await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false');
  assert.equal(await dialog.isVisible(),false);
  assert.deepEqual(errors,[]);
  console.log('PASS: delayed dialog, hidden tree, completion, Cancel, Escape, replacement load, failure and recovery.');
}finally{await context.close();}
