import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
const extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,acceptDownloads:true,executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
try{
  await context.setOffline(true);
  const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage();
  const received=[],errors=[];page.on('pageerror',e=>errors.push(e.message));page.on('download',d=>received.push(d));
  await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
  await page.evaluate(async()=>{
    const {saveBlob}=await import('./downloads.js');let count=0;
    const button=document.createElement('button');button.id='download-probe';button.textContent='Download test';
    button.onclick=()=>{const n=count++;void saveBlob(new Blob([`payload ${n}`]),`probe-${n}.txt`);};document.body.prepend(button);
  });
  for(let n=0;n<15;n++)await page.locator('#download-probe').click();
  const deadline=Date.now()+30000;while(received.length<15&&Date.now()<deadline)await new Promise(resolve=>setTimeout(resolve,100));
  assert.equal(received.length,15,'Every rapid user request must reach the browser');
  for(let n=0;n<15;n++){assert.equal(received[n].suggestedFilename(),`probe-${n}.txt`);assert.equal(await readFile(await received[n].path(),'utf8'),`payload ${n}`);}
  assert.equal(await page.evaluate(async()=>{
    const {saveBlob}=await import('./downloads.js');
    try{await saveBlob(new Blob(['cancelled']),'cancelled.txt',()=>{throw new Error('cancelled');});}catch(error){return error.message;}
  }),'cancelled');
  // A rejected request must not poison the queue.
  await page.locator('#download-probe').click();
  const until=Date.now()+5000;while(received.length<16&&Date.now()<until)await new Promise(resolve=>setTimeout(resolve,100));
  assert.equal(received.length,16);assert.equal(await readFile(await received[15].path(),'utf8'),'payload 15');assert.deepEqual(errors,[]);
  const result={status:'PASS',requested:16,received:16,exactBytes:true,cancelledRequest:'not downloaded',queueRecovery:'PASS'};
  await writeFile('test-results/download-queue-chromium.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
}finally{await context.close();}
