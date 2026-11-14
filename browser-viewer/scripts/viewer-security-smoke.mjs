import {chromium} from 'playwright';
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
import {resolve,join} from 'node:path';
import {readFile,mkdtemp,rm,writeFile,mkdir} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {downloadScopeFixture} from './download-scope-fixture.mjs';
const extension=resolve(process.env.VIEWER_EXTENSION??'dist/chromium'),work=await mkdtemp(join(tmpdir(),'eapdf-viewer-security-'));
const reports=[];
const context=await chromium.launchPersistentContext('',{headless:true,acceptDownloads:true,permissions:['clipboard-read','clipboard-write'],viewport:{width:1280,height:800},executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--load-extension=${extension}`,`--disable-extensions-except=${extension}`]});
try{
 await context.setOffline(true);const errors=[];const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage();page.on('pageerror',e=>errors.push(String(e)));
 await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
 await page.evaluate(()=>{const input=document.createElement('input');input.type='file';input.id='security-reference';input.hidden=true;document.body.append(input);});
 const ready=()=>page.waitForFunction(()=>!document.querySelector('#file').disabled&&document.querySelector('#viewport').getAttribute('aria-busy')==='false');
 const inspect=async path=>{await page.locator('#security-reference').setInputFiles(path);return page.evaluate(async()=>{
  const {getDocument,GlobalWorkerOptions}=await import('./vendor/pdfjs/pdf.mjs');GlobalWorkerOptions.workerSrc=new URL('./vendor/pdfjs/pdf.worker.mjs',location.href).href;
  const task=getDocument({data:new Uint8Array(await document.querySelector('#security-reference').files[0].arrayBuffer()),isEvalSupported:false});
  try{const pdf=await task.promise,text=[];for(let n=1;n<=pdf.numPages;n++)text.push((await (await pdf.getPage(n)).getTextContent()).items.filter(i=>'str'in i).map(i=>i.str).join(''));
   return {pages:pdf.numPages,text,parts:await pdf.getDocumentParts()};
  }finally{await task.destroy();}
 });};
 for(const profile of ['m','mi']){
  const input=join(work,`partial_1${profile}.pdf`);await writeFile(input,downloadScopeFixture(profile));await page.locator('#file').setInputFiles(input);await ready();
  const original=await inspect(input);assert.equal(original.pages,2);assert.equal(original.parts.status,'partial');
  assert.equal(await page.locator('#download-message').isEnabled(),true);
  const pending=page.waitForEvent('download');await page.locator('#download-message').click();const download=await pending;const actual=await inspect(await download.path());
  assert.equal(actual.pages,1);assert.deepEqual(actual.text,['SELECTED BODY']);reports.push({profile,partialInspection:true,bodyOnly:true});
 }
 for(const profile of ['s','si']){
  const input=join(work,`standalone_1${profile}.pdf`),bytes=downloadScopeFixture(profile,{partial:false,standalone:true});await writeFile(input,bytes);await page.locator('#file').setInputFiles(input);await ready();
  const pending=page.waitForEvent('download');await page.locator('#download-message').click();const download=await pending;assert.deepEqual(await readFile(await download.path()),bytes);reports.push({profile,originalBytes:true});
 }
 const upstream=resolve('tests/fixtures/issue16880.pdf'),mixed=join(work,'mixed-rtl.pdf');
 execFileSync('python3',[resolve('scripts/make-rtl-mixed-fixture.py'),upstream,mixed]);
 for(const fixture of [upstream,mixed]){
  await page.locator('#file').setInputFiles(fixture);await ready();
  const extracted=await inspect(fixture);assert.ok(extracted.text.some(t=>t.includes('سلام کلاس لاله')));
  if(fixture===mixed)assert.ok(extracted.text.some(t=>t.includes('Mixed Latin 123')));
  await page.waitForFunction(()=>document.querySelector('.textLayer')?.textContent.includes('سلام'));
  await page.locator('#open-search').click();await page.locator('#search-scope').selectOption('document');await page.locator('#search').fill('سلام');await page.locator('#search-submit').click();await ready();
  await page.waitForFunction(()=>document.querySelector('.textLayer mark')?.textContent==='سلام');
  const selection=await page.locator('.textLayer').evaluate(el=>{const range=document.createRange();range.selectNodeContents(el);const selection=getSelection();selection.removeAllRanges();selection.addRange(range);return selection.toString();});assert.match(selection,/سلام/);
  await page.keyboard.press('Control+c');const copied=await page.evaluate(()=>navigator.clipboard.readText());assert.match(copied,/سلام/);assert.doesNotMatch(copied,/سالم/);
  if(fixture===mixed)assert.match(copied,/Mixed Latin 123/);
  reports.push({mixed:fixture===mixed,rtlExtraction:true,search:true,highlight:true,selection:true,clipboard:true});
 }
 assert.deepEqual(errors,[]);
 await mkdir('test-results',{recursive:true});await writeFile(resolve('test-results',process.env.SECURITY_REPORT??'viewer-security.json'),JSON.stringify(reports,null,2)+'\n');console.log(JSON.stringify(reports));
}finally{await context.close();await rm(work,{recursive:true,force:true});}
