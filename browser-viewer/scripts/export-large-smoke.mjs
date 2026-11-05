import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {readFile,writeFile,mkdir,stat} from 'node:fs/promises';
import {createHash} from 'node:crypto';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
const input=resolve(process.env.EXPORT_PDF??'../out/pst/pst_14_05+May+2003+Public+2/pst_14_05+May+2003+Public+2_1c.pdf');
const extension=resolve(process.env.EXPORT_EXTENSION??'dist/chromium'),baseline=process.env.EXPORT_BASELINE==='1';
const context=await chromium.launchPersistentContext('',{headless:true,acceptDownloads:true,executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
try{
 await context.setOffline(true);const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker');const page=await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
 await page.waitForFunction(()=>!document.querySelector('#file').disabled);
 const openStart=Date.now();await page.locator('#file').setInputFiles(input);await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false',null,{timeout:600000});
 assert.equal(await page.locator('#export-csv').isEnabled(),true);const openMs=Date.now()-openStart;console.log(JSON.stringify({phase:"opened",openMs}));
 const download=Promise.race([page.waitForEvent('download',{timeout:600000}),page.waitForFunction(()=>document.querySelector('#status').textContent.startsWith('Metadata export failed:'),null,{timeout:600000}).then(async()=>{throw Error(await page.locator('#status').textContent());})]);const started=Date.now();await page.locator('#export-csv').click();
 let dialogMs=null;
 if(!baseline){await page.locator('#export-dialog').waitFor({state:'visible',timeout:2000});dialogMs=Date.now()-started;await mkdir('test-results',{recursive:true});await page.screenshot({path:'test-results/export-large-dialog.png'});}
 const timer=setInterval(()=>{void page.locator('#status').textContent().then(status=>console.log(JSON.stringify({phase:'export',elapsedMs:Date.now()-started,status}))).catch(()=>{});},30000);
 let saved;try{saved=await download;}finally{clearInterval(timer);}const elapsedMs=Date.now()-started;const data=await readFile(await saved.path());
 await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false',null,{timeout:60000});
 const status=await page.locator('#status').textContent();assert.match(status,/^Exported finding aid for \d+ messages\.$/);assert.deepEqual(errors,[]);
 const result={status:'PASS',input,sourceBytes:(await stat(input)).size,sourceSha256:createHash('sha256').update(await readFile(input)).digest('hex'),baseline,openMs,dialogMs,exportMs:elapsedMs,csvBytes:data.length,csvSha256:createHash('sha256').update(data).digest('hex'),messages:Number(status.match(/for (\d+)/)[1])};
 await mkdir('test-results',{recursive:true});await writeFile(`test-results/export-large-${baseline?'before':'after'}.json`,JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
}finally{await context.close();}
