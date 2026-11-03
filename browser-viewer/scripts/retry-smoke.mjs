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
  const retry=page.locator('#retry');
  const ready=()=>page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false');
  assert.equal(await retry.isVisible(),false,'hidden before opening');
  // Fault injection is confined to this browser test, never shipped in the viewer.
  await page.evaluate(async()=>{
    const {BrowserSession}=await import('./browser-session.js');
    const open=BrowserSession.prototype.open;
    window.retryFault=2;
    BrowserSession.prototype.open=async function(...args){
      const doc=await open.apply(this,args),getPage=doc.pdf.getPage.bind(doc.pdf);
      const wrapped=new WeakSet();
      doc.pdf.getPage=async number=>{
        const p=await getPage(number);
        if(!wrapped.has(p)){
          wrapped.add(p);const render=p.render.bind(p);
          p.render=(...options)=>{if(window.retryFault===number)throw Error('Injected page-render failure');return render(...options);};
        }
        return p;
      };
      return doc;
    };
  });
  await page.locator('#file').setInputFiles(resolve('../examples/pst/pst_quality-attached-message_1m.pdf'));
  await ready();
  await page.waitForFunction(()=>document.querySelector('.pdf-page[data-page="1"][data-rendered]'));
  await page.waitForFunction(()=>document.querySelector('.pdf-page[data-page="2"]')?.textContent.includes('Injected page-render failure'));
  assert.equal(await retry.isVisible(),false,'a failed prefetched page must not show retry on a healthy page');
  await page.locator('#next').click();await ready();
  await retry.waitFor({state:'visible'});
  assert.equal(await retry.isEnabled(),true,'render failure exposes enabled retry');
  await page.evaluate(()=>{window.retryFault=0;document.querySelector('#retry').click();});
  await ready();await retry.waitFor({state:'hidden'});
  await page.waitForFunction(()=>document.querySelector('.pdf-page[data-page="2"][data-rendered]'));
  // Force another actual render, then leave the failed page.
  await page.evaluate(()=>{window.retryFault=2;});
  await page.locator('#zoom').selectOption('1.1');await ready();await retry.waitFor({state:'visible'});
  await page.locator('#previous').click();await ready();await retry.waitFor({state:'hidden'});
  await page.locator('#next').click();await ready();await retry.waitFor({state:'visible'});
  await page.locator('#close').click();await retry.waitFor({state:'hidden'});
  // An invalid file is an opening error, not a retryable rendered page.
  await page.locator('#file').setInputFiles({name:'invalid.pdf',mimeType:'application/pdf',buffer:Buffer.from('not a PDF')});
  await ready();assert.equal(await retry.isVisible(),false);
  console.log('PASS: retry hidden initially, on healthy/prefetched pages, after successful retry, navigation and close; shown only for failed current-page rendering.');
}finally{await context.close();}
