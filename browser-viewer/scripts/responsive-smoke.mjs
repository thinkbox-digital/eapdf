import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {mkdir,writeFile} from 'node:fs/promises';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
const extension=resolve('dist/chromium'),touch=process.env.EAPDF_TOUCH==='1',suffix=touch?'-touch':'';
const context=await chromium.launchPersistentContext('',{headless:true,hasTouch:touch,viewport:{width:1600,height:1000},executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
const results=[];
try{
 await context.setOffline(true);const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage(),errors=[];
 page.on('pageerror',error=>errors.push(error.message));await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
 assert.equal(await page.locator('#empty-state h2').innerText(),'Your email—complete, authentic, accessible, structured.');
 await page.locator('#file').setInputFiles(resolve('../examples/pst/pst_quality-attached-message_1m.pdf'));
 const fitted=()=>page.waitForFunction(()=>{
  const v=document.querySelector('#viewport'),c=document.querySelector('#canvas'),s=getComputedStyle(v);
  if(v.getAttribute('aria-busy')!=='false'||!c||c.hidden)return false;
  const r=c.getBoundingClientRect(),w=v.clientWidth-parseFloat(s.paddingLeft)-parseFloat(s.paddingRight)-2,h=v.clientHeight-parseFloat(s.paddingTop)-parseFloat(s.paddingBottom)-2;
  return document.querySelector('#zoom').value==='fit-page'?r.width<=w+3&&r.height<=h+3&&(Math.abs(r.width-w)<3||Math.abs(r.height-h)<3):Math.abs(r.width-w)<3;
 },null,{timeout:60000});
 await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false');assert.equal(await page.locator('#zoom').inputValue(),'fit-page');
 assert.ok(await page.locator('#canvas').evaluate(c=>{const v=document.querySelector('#viewport');return c.getBoundingClientRect().height<=v.clientHeight&&c.getBoundingClientRect().width<=v.clientWidth;}),'Fit Page fits both dimensions');
 const fittedWidth=await page.locator('#canvas').evaluate(e=>e.getBoundingClientRect().width);
 await page.locator('#zoom-in').click();await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false');
 assert.ok((await page.locator('#canvas').evaluate(e=>e.getBoundingClientRect().width))>fittedWidth,'plus must enlarge the fitted page');
 await page.locator('#zoom').selectOption('fit-width');await fitted();
 await mkdir('test-results',{recursive:true});
 for(const [width,height]of [[1600,1000],[1366,768],[1024,768],[768,1024],[390,844],[320,568],[844,390]]){
  await page.setViewportSize({width,height});await fitted();
  const result=await page.evaluate(()=>{const b=id=>document.getElementById(id).getBoundingClientRect();return {width:innerWidth,height:innerHeight,mode:document.querySelector('#workspace').dataset.layout,left:b('navigation-panel').width,right:b('details-panel').width,viewport:b('viewport').height,overflow:document.documentElement.scrollWidth>innerWidth,footer:document.querySelector('footer').getBoundingClientRect().bottom};});
  assert.equal(result.overflow,false,JSON.stringify(result));assert.ok(result.viewport>100,JSON.stringify(result));assert.ok(result.footer<=height+1,JSON.stringify(result));
  if(result.mode==='wide'){assert.ok(Math.abs(result.left-width*.27)<1);assert.ok(Math.abs(result.right-width*.27)<1);}
  if(result.mode==='medium'){assert.equal(result.left,270);assert.equal(result.right,0);}
  if(result.mode==='compact'){assert.equal(result.left,0);assert.equal(result.right,0);}
  results.push(result);await page.screenshot({path:`test-results/responsive-${width}x${height}${suffix}.png`});
 }
 await page.setViewportSize({width:390,height:844});await fitted();
 await page.locator('#show-navigation').click();assert.equal(await page.locator('#navigation-panel').isVisible(),true);assert.equal(await page.locator('#details-panel').isVisible(),false);
 assert.ok((await page.locator('#navigation-panel').boundingBox()).width<=390);
 await page.locator('#tree button').first().click();await fitted();assert.equal(await page.locator('#navigation-panel').isVisible(),false);
 await page.locator('#show-details').click();assert.equal(await page.locator('#details-panel').isVisible(),true);assert.equal(await page.locator('#navigation-panel').isVisible(),false);
 assert.equal(await page.locator('.reader-pane').evaluate(e=>e.inert),true);
 await page.keyboard.press('Escape');await fitted();assert.equal(await page.locator('.reader-pane').evaluate(e=>e.inert),false);assert.equal(await page.evaluate(()=>document.activeElement.id),'show-details');
 await page.setViewportSize({width:1024,height:768});await fitted();await page.locator('#show-details').click();await fitted();
 assert.equal(await page.locator('#navigation-panel').isVisible(),false);assert.equal(await page.locator('#details-panel').isVisible(),true);
 await page.locator('#show-navigation').click();await fitted();assert.equal(await page.locator('#details-panel').isVisible(),false);
 await page.locator('#zoom').selectOption('1');await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false');
 await page.setViewportSize({width:800,height:700});await page.waitForTimeout(250);assert.equal(await page.locator('#zoom').inputValue(),'1','manual zoom survives resizing');
 await page.setViewportSize({width:1600,height:1000});await page.locator('#zoom').selectOption('fit-width');await fitted();
 assert.equal(await page.locator('#navigation-panel').isVisible(),true);assert.equal(await page.locator('#details-panel').isVisible(),true);
 assert.deepEqual(errors,[]);await writeFile(`test-results/responsive${suffix}.json`,JSON.stringify({status:'PASS',results},null,2)+'\n');console.log(JSON.stringify({status:'PASS',results}));
}finally{await context.close();}
