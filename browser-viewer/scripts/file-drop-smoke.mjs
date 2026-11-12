import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {resolve,join} from 'node:path';
import {mkdtemp,copyFile,readFile,writeFile,mkdir,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {createHash} from 'node:crypto';
const source=resolve('../examples/eml/eml_Agenda_and_timeline_slides_1s.pdf');
const before=createHash('sha256').update(await readFile(source)).digest('hex');
const work=await mkdtemp(join(tmpdir(),'eapdf-file-drop-'));
const extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',viewport:{width:1600,height:1000},args:[`--load-extension=${extension}`,`--disable-extensions-except=${extension}`]});
const checks=[];
try {
 const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker');
 const page=await context.newPage(),url=`chrome-extension://${new URL(worker.url()).host}/viewer.html`;
 await page.goto(url);await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false');
 const cdp=await context.newCDPSession(page);
 for(const [name,selector] of [['left-empty','#navigation-panel'],['left-filter','#message-filter'],['left-message','#tree button'],['left-consuming-control','#message-filter'],['center','#viewport'],['right','#files'],['toolbar','.app-header']]){
  if(name==='left-consuming-control')await page.locator(selector).evaluate(node=>{for(const type of ['dragenter','dragover','drop'])node.addEventListener(type,event=>event.stopPropagation());});
  const path=join(work,name+'.pdf');await copyFile(source,path);
  const target=page.locator(selector).first();await target.scrollIntoViewIfNeeded();
  const rect=await target.boundingBox();assert.ok(rect,selector);
  const x=rect.x+Math.min(rect.width/2,40),y=rect.y+Math.min(rect.height/2,30);
  const data={items:[],files:[path],dragOperationsMask:1};
  for(const type of ['dragEnter','dragOver','drop'])await cdp.send('Input.dispatchDragEvent',{type,x,y,data});
  await page.waitForFunction(name=>document.querySelector('#header-filename')?.textContent===name&&document.querySelector('#viewport').getAttribute('aria-busy')==='false',name+'.pdf',{timeout:30000});
  assert.equal(page.url(),url,'Drop must not navigate to the built-in PDF viewer');
  checks.push({target:name,status:'PASS'});console.log('PASS drop:',name);
 }
 assert.equal(createHash('sha256').update(await readFile(source)).digest('hex'),before);
 await mkdir('test-results',{recursive:true});await writeFile('test-results/file-drop-chromium.json',JSON.stringify({status:'PASS',sha256:before,checks},null,2)+'\n');
}finally{await context.close();await rm(work,{recursive:true,force:true});}
