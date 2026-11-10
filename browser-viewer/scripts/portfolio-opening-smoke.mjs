import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {mkdtemp,rm,writeFile,mkdir,readFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {resolve,join} from 'node:path';
import {portfolioFixture} from './portfolio-fixtures.mjs';
const work=await mkdtemp(join(tmpdir(),'eapdf-opening-')),extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--load-extension=${extension}`,`--disable-extensions-except=${extension}`]});
try{
 await context.setOffline(true);const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);await page.waitForFunction(()=>!document.querySelector('#file').disabled);
 const open=async(name,options)=>{const path=join(work,name);await writeFile(path,portfolioFixture(options));await page.locator('#file').setInputFiles(path);await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false',null,{timeout:90000});};
 await open('xyz.pdf',{pages:3,openAction:'/OpenAction [{lastPage} /XYZ 20 200 1.5]'});assert.equal(await page.locator('#page').inputValue(),'3');assert.equal(await page.locator('#zoom').inputValue(),'1.5');
 await page.locator('#page').fill('1');await page.locator('#page').press('Enter');await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false');assert.equal(await page.locator('#page').inputValue(),'1','opening action runs only once');
 await open('named.pdf',{pages:3,openAction:'/OpenAction << /S /GoTo /D (chapter) >>'});assert.equal(await page.locator('#page').inputValue(),'3');assert.equal(await page.locator('#zoom').inputValue(),'fit-page');
 await open('bad-target.pdf',{initial:'absent.pdf'});assert.match(await page.locator('#document-notice').textContent(),/initial document is missing/);assert.equal(await page.locator('#attachment-viewer').isVisible(),false);
 const child=portfolioFixture({collection:false,pages:3,openAction:'/OpenAction [{lastPage} /FitH null]'});
 await open('child.pdf',{initial:'message.pdf',files:[['message.pdf',child,1]]});assert.equal(await page.locator('#page').inputValue(),'3');assert.equal(await page.locator('#zoom').inputValue(),'fit-width');
 await open('bad-destination.pdf',{openAction:'/OpenAction [999 0 R /Fit]'});assert.equal(await page.locator('#document-notice').isVisible(),true);assert.equal(await page.locator('#canvas').isVisible(),true);
 assert.deepEqual(errors,[]);await mkdir('test-results',{recursive:true});await writeFile('test-results/portfolio-opening.json',JSON.stringify({status:'PASS',checks:['explicit last page and zoom','named destination','manual navigation retained','invalid initial member warning','embedded PDF opening page','invalid destination warning']},null,2));console.log('PASS: Portfolio opening targets, pages, zoom and invalid-target warnings.');
}finally{await context.close();await rm(work,{recursive:true,force:true});}
