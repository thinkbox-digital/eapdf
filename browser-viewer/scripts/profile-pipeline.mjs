// HTTP viewer performance checks against freshly generated private PDFs.
import {createServer} from 'node:http';
import {chromium} from 'playwright';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {resolve,dirname,basename} from 'node:path';
import {performance} from 'node:perf_hooks';
const root=resolve(process.env.EAPDF_VIEWER_ROOT||'browser-viewer/dist/chromium');
const resultPath=resolve(process.argv[2]);
const files=process.argv.slice(3);
const server=createServer(async(req,res)=>{
 const path=resolve(root,'.'+new URL(req.url,'http://localhost').pathname);
 if(!path.startsWith(root+'/')){res.writeHead(403).end();return;}
 try{res.setHeader('Content-Type',/\.m?js$/.test(path)?'text/javascript':path.endsWith('.css')?'text/css':path.endsWith('.html')?'text/html':'application/octet-stream');res.end(await readFile(path));}catch{res.writeHead(404).end();}
});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const results=[];
async function processRss(pid){
 try{
  const status=await readFile(`/proc/${pid}/status`,'utf8');
  const own=Number(status.match(/^VmRSS:\s+(\d+)/m)?.[1]||0)*1024;
  const childText=await readFile(`/proc/${pid}/task/${pid}/children`,'utf8');
  const children=childText.trim().split(/\s+/).filter(Boolean);
  return own+(await Promise.all(children.map(processRss))).reduce((a,b)=>a+b,0);
 }catch{return 0;}
}
try{
 for(const file of files){
  const row={file,mode:'Chromium headless HTTP; fresh browser process per file'};
  const browser=await chromium.launch({headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{channel:'chromium'})});
  let peakRss=0,sampling=false;
  const sampler=setInterval(async()=>{if(sampling)return;sampling=true;try{peakRss=Math.max(peakRss,await processRss(process.pid));}finally{sampling=false;}},250);
  let page;
  try{
   row.browserVersion=browser.version();
   page=await browser.newPage({viewport:{width:1500,height:900}});
   const errors=[];page.on('pageerror',e=>errors.push(e.message));
   await page.route('**/*',route=>new URL(route.request().url()).hostname==='127.0.0.1'?route.continue():route.abort());
   await page.goto(`http://127.0.0.1:${server.address().port}/viewer.html`);
   await page.waitForFunction(()=>!document.querySelector('#file').disabled);
   const cdp=await page.context().newCDPSession(page);await cdp.send('Performance.enable');
   const ready=async()=>{
    await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false' && (Number(document.querySelector('#pages').textContent.replace('of ',''))>0||document.querySelector('#status').textContent.startsWith('Unable')),null,{timeout:180000});
    const failure=await page.locator('#status').textContent();if(failure.startsWith('Unable'))throw new Error(failure);
   };
   let start=performance.now();
   await page.locator('#file').setInputFiles(file);await ready();
   row.openMs=performance.now()-start;
   const screenshots=resolve(dirname(resultPath),'viewer-screenshots');await mkdir(screenshots,{recursive:true});
   row.screenshot=resolve(screenshots,basename(file)+'.png');
   await page.screenshot({path:row.screenshot});
   row.pages=Number((await page.locator('#pages').textContent()).replace('of ',''));
   row.navigation=[];
   for(const target of [...new Set([Math.ceil(row.pages/2),row.pages,1])]){
    start=performance.now();await page.locator('#page').fill(String(target));await page.locator('#page').dispatchEvent('change');await ready();
    await page.waitForFunction(n=>Number(document.querySelector('#page').value)===n,target,{timeout:60000});
    await page.locator(`.pdf-page[data-page="${target}"][data-rendered="true"]`).waitFor({state:'visible',timeout:60000});
    row.navigation.push({target,ms:performance.now()-start});
    if(file.includes('gmail')&&target===Math.ceil(row.pages/2))await page.screenshot({path:resolve(screenshots,basename(file)+'.middle.png')});
   }
   await page.waitForTimeout(1000);
   row.canvas=await page.locator('canvas').evaluateAll(nodes=>({count:nodes.length,pixels:nodes.reduce((s,n)=>s+n.width*n.height,0)}));
   await page.locator('#zoom').selectOption('2');await ready();await page.waitForTimeout(1000);
   row.zoom2Canvas=await page.locator('canvas').evaluateAll(nodes=>({count:nodes.length,pixels:nodes.reduce((s,n)=>s+n.width*n.height,0)}));
   row.domNodes=await page.locator('*').count();
   row.domBreakdown=await page.evaluate(()=>Object.fromEntries(['tree','files','diagnostics','page-surface'].map(id=>[id,document.getElementById(id).querySelectorAll('*').length])));
   row.performance=(await cdp.send('Performance.getMetrics')).metrics.filter(x=>['JSHeapUsedSize','JSHeapTotalSize','TaskDuration','LayoutDuration','ScriptDuration'].includes(x.name));
   row.errors=errors;row.status=errors.length?'PAGE_ERRORS':'PASS';
  }catch(e){row.status='FAILED';row.error=String(e);row.viewerStatus=await page?.locator('#status').textContent().catch(()=>null);}finally{clearInterval(sampler);row.sampledPeakProcessTreeRssBytes=peakRss;await browser.close();}
  results.push(row);await writeFile(resultPath,JSON.stringify(results,null,2)+'\n');console.log(`${file}: ${row.status}, open ${Math.round(row.openMs||0)} ms, ${row.pages||0} pages`);
 }
}finally{await new Promise(r=>server.close(r));}

if(results.some(row=>row.status!=='PASS'))process.exitCode=1;
