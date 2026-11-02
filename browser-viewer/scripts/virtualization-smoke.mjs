// Isolate browser layout limits without manufacturing a giant PDF.
import {createServer} from 'node:http';
import {readFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {chromium} from 'playwright';
import assert from 'node:assert/strict';
const root=resolve('dist/chromium');
const server=createServer(async(req,res)=>{
 const path=resolve(root,'.'+new URL(req.url,'http://localhost').pathname);
 if(!path.startsWith(root+'/')){res.writeHead(403).end();return;}
 try{res.setHeader('Content-Type',/\.m?js$/.test(path)?'text/javascript':path.endsWith('.html')?'text/html':path.endsWith('.css')?'text/css':'application/octet-stream');res.end(await readFile(path));}catch{res.writeHead(404).end();}
});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_PATH});
try{
 const page=await browser.newPage({viewport:{width:1500,height:900}});
 await page.goto(`http://127.0.0.1:${server.address().port}/viewer.html`);
 await page.waitForFunction(()=>!document.querySelector('#file').disabled);
 const result=await page.evaluate(async()=>{
  const {ScrollingPages}=await import('./scrolling-pages.js');
  const {VirtualList}=await import('./virtual-list.js');
  document.body.replaceChildren();
  const viewport=document.createElement('div'),surface=document.createElement('div');
  viewport.id='viewport';surface.id='page-surface';viewport.style.cssText='height:700px;width:900px;flex:none';viewport.append(surface);document.body.append(viewport);
  let active=1;const errors=[];
  const pages=new ScrollingPages(viewport,surface,n=>active=n,e=>errors.push(e));
  const fakePage={getViewport:({scale})=>({width:500*scale,height:1000*scale}),cleanup(){},render:()=>({promise:Promise.resolve(),cancel(){}})};
  const doc={pdf:{numPages:100000,getPage:async()=>fakePage},run:promise=>promise};
  const settle=()=>new Promise(r=>setTimeout(r,120));
  await pages.show(doc,1,100000,'');await settle();
  const last=Number(surface.querySelector('#canvas').closest('section').dataset.page);
  const height=viewport.scrollHeight;
  viewport.scrollTop=viewport.scrollHeight/2;await settle();const middle=active;
  await pages.show(doc,1,50000,'');await settle();
  viewport.scrollTop+=1030;await settle();const next=active;
  await pages.show(doc,2,100000,'');await settle();
  const zoomLast=Number(surface.querySelector('#canvas').closest('section').dataset.page);
  const count=surface.querySelectorAll('section').length;
  pages.clear();
  const list=document.createElement('ul');viewport.replaceChildren(list);viewport.scrollTop=0;
  const virtual=new VirtualList(list,viewport,Array.from({length:100000},(_,i)=>i),value=>{const row=document.createElement('li');row.style.height='40px';row.textContent=String(value);return row;},43);
  const lastRow=virtual.rowAt(99999);lastRow.scrollIntoView({block:'nearest'});await settle();
  const sidebarLast=[...list.children].some(row=>row.textContent==='99999');
  const rowCount=list.children.length;virtual.close();
  return {last,height,middle,next,zoomLast,count,sidebarLast,rowCount,errors};
 });
 assert.equal(result.last,100000);assert.equal(result.zoomLast,100000);
 assert.ok(result.height<8100000,JSON.stringify(result));assert.ok(result.middle>45000&&result.middle<55000,JSON.stringify(result));
 assert.ok(result.next>=50000&&result.next<=50002,JSON.stringify(result));assert.ok(result.count<=9);assert.ok(result.sidebarLast);assert.ok(result.rowCount<=74);assert.deepEqual(result.errors,[]);
 console.log('PASS 100,000-page jumps, scrollbar midpoint, local scrolling, zoom, and 100,000 sidebar entries:',JSON.stringify(result));
}finally{await browser.close();await new Promise(r=>server.close(r));}
