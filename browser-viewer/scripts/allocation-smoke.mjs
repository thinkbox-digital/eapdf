import {chromium} from 'playwright';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {writeFile,mkdir,readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
const input=resolve('../out/pst/pst_21_04+April+2003+Public+2/pst_21_04+April+2003+Public+2_1c.pdf');
const extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
try{
 const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker');const page=await context.newPage();await context.setOffline(true);
 await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
 const ready=()=>page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false',null,{timeout:240000});
 await page.waitForFunction(()=>!document.querySelector('#file').disabled);
 await page.locator('#file').setInputFiles(input);await ready();console.log('Opened: '+await page.locator('#status').textContent());
 await page.locator('#tree details').evaluateAll(items=>items.forEach(item=>{item.open=true;}));
 for(const label of ['(no subject)_000009_1s.pdf','(no subject)_000013_1s.pdf']){
  const button=page.locator('#tree button').filter({hasText:label}).first();await button.click();await ready();
  const status=await page.locator('#status').textContent();console.log(status);assert.doesNotMatch(status,/Unable|allocation limit|failed/i);assert.match(status,/Page 1 of/);
 }
 await mkdir('test-results',{recursive:true});await writeFile('test-results/allocation.json',JSON.stringify({status:'PASS',input,sha256:createHash('sha256').update(await readFile(input)).digest('hex'),checks:['April archive opens','two screenshot-adjacent child emails render']},null,2)+'\n');
}finally{await context.close();}
