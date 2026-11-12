import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {mkdtemp,writeFile,rm} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {tmpdir} from 'node:os';
import {portfolioFixture} from './portfolio-fixtures.mjs';
const work=await mkdtemp(join(tmpdir(),'eapdf-opening-zoom-')),extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,viewport:{width:600,height:850},
  executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',
  args:[`--load-extension=${extension}`,`--disable-extensions-except=${extension}`]});
try{
  const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage();
  await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
  const ready=()=>page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false');
  const open=async(name,options)=>{const path=join(work,name);await writeFile(path,portfolioFixture({collection:false,files:[],...options}));await page.locator('#file').setInputFiles(path);await page.waitForFunction(name=>document.querySelector('#header-filename').textContent===name&&document.querySelector('#viewport').getAttribute('aria-busy')==='false',name);};
  await open('default.pdf',{});assert.equal(await page.locator('#zoom').inputValue(),'fit-width');
  await page.locator('#zoom').selectOption('1.5');await ready();
  await page.setViewportSize({width:1440,height:960});await ready();assert.equal(await page.locator('#zoom').inputValue(),'1.5');
  await page.locator('#tree .root-document').click();await ready();assert.equal(await page.locator('#zoom').inputValue(),'1.5');
  await open('desktop-default.pdf',{});assert.equal(await page.locator('#zoom').inputValue(),'fit-page');
  await page.setViewportSize({width:600,height:850});
  await open('explicit.pdf',{openAction:'/OpenAction [{lastPage} /XYZ null null 1.25]'});
  assert.equal(await page.locator('#zoom').inputValue(),'1.25');
  console.log('PASS: narrow fit-width, desktop fit-page, chosen zoom retained and explicit opening zoom honored.');
}finally{await context.close();await rm(work,{recursive:true,force:true});}
