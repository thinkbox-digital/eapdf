// Differential checks against the exact upstream dependency, using synthetic PDFs.
import {chromium} from 'playwright';
import {createServer} from 'node:http';
import {readFile,mkdtemp,writeFile,rm,mkdir} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {resolve,join,extname} from 'node:path';
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
const temporary=await mkdtemp(join(tmpdir(),'eapdf-compat-'));
const original=await readFile('tests/fixtures/ordinary.pdf');
await writeFile(join(temporary,'classic.pdf'),original);
const previous=/startxref\s+(\d+)/.exec(original.toString())[1];
const update='8 0 obj\n<< /Title (Incremental revision) >>\nendobj\n';
await writeFile(join(temporary,'incremental.pdf'),Buffer.concat([original,Buffer.from(`${update}xref\n8 1\n${String(original.length).padStart(10,'0')} 00000 n \ntrailer\n<< /Size 9 /Root 1 0 R /Info 8 0 R /Prev ${previous} >>\nstartxref\n${original.length+Buffer.byteLength(update)}\n%%EOF\n`)]));
await writeFile(join(temporary,'recovery.pdf'),original.toString().replace(/startxref\s+\d+/,'startxref\n0'));
execFileSync(process.env.M1_PYTHON??'python3',['-B','-c',`import pikepdf,sys,pathlib
p=pathlib.Path(sys.argv[1])
with pikepdf.open(p/'classic.pdf') as pdf:
 pdf.save(p/'compressed.pdf',object_stream_mode=pikepdf.ObjectStreamMode.generate)
 pdf.save(p/'encrypted.pdf',object_stream_mode=pikepdf.ObjectStreamMode.generate,encryption=pikepdf.Encryption(owner='owner',user='reader',R=6))
`,temporary]);
const server=createServer(async(req,res)=>{
 try{
  const name=new URL(req.url,'http://localhost').pathname;
  if(name==='/'){res.setHeader('Content-Type','text/html');res.end('<!doctype html><title>PDF.js compatibility</title>');return;}
  const path=name.startsWith('/fixture/')?join(temporary,name.slice(9)):resolve('.'+name);
  if(!path.startsWith(temporary+'/')&&!path.startsWith(resolve('vendor')+'/')&&!path.startsWith(resolve('node_modules')+'/'))throw Error('path');
  res.setHeader('Content-Type',extname(path)==='.mjs'?'text/javascript':'application/octet-stream');res.end(await readFile(path));
 }catch{res.writeHead(404);res.end();}
});
await new Promise(done=>server.listen(0,'127.0.0.1',done));
let browser;
try{
 browser=await chromium.launch({headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{channel:'chromium'})});
 const page=await browser.newPage();await page.goto(`http://127.0.0.1:${server.address().port}`);
 const results=[];
 for(const fixture of ['classic','compressed','incremental','recovery','encrypted']){
  const actual=[];
  for(const patched of [false,true])actual.push(await page.evaluate(async({fixture,patched})=>{
   const base=patched?'/vendor/pdfjs-patched/':'/node_modules/pdfjs-dist/build/';
   const api=await import(base+'pdf.mjs');api.GlobalWorkerOptions.workerSrc=base+'pdf.worker.mjs';
   const task=api.getDocument({url:'/fixture/'+fixture+'.pdf',password:'reader',standardFontDataUrl:'/node_modules/pdfjs-dist/standard_fonts/',isEvalSupported:false});const doc=await task.promise;
   try{
    const rounds=[];
    for(let round=0;round<3;round++){
     const pages=[];
     for(const number of [2,1,2]){
      const p=await doc.getPage(number),text=(await p.getTextContent()).items.map(x=>x.str??'').join(' ');
      const canvas=document.createElement('canvas'),viewport=p.getViewport({scale:1});canvas.width=viewport.width;canvas.height=viewport.height;
      await p.render({canvas,viewport}).promise;
      const bytes=canvas.getContext('2d').getImageData(0,0,canvas.width,canvas.height).data;
      const hash=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),n=>n.toString(16).padStart(2,'0')).join('');
      pages.push({number,text,hash});p.cleanup();canvas.width=canvas.height=0;
     }
     rounds.push(pages);await doc.cleanup();
    }
    return {pages:doc.numPages,title:(await doc.getMetadata()).info.Title??null,rounds};
   }finally{await task.destroy();}
  },{fixture,patched}));
  assert.deepEqual(actual[1],actual[0],fixture+' differs from upstream');assert.equal(actual[1].pages,2);
  assert.deepEqual(actual[1].rounds[0],actual[1].rounds[2],fixture+' changed after cleanup');
  if(fixture==='incremental')assert.equal(actual[1].title,'Incremental revision');
  results.push({fixture,status:'PASS',rounds:3});
 }
 await mkdir('test-results',{recursive:true});await writeFile('test-results/pdfjs-compat.json',JSON.stringify({browser:browser.version(),results},null,2)+'\n');console.log(JSON.stringify(results));
}finally{await browser?.close();await new Promise(done=>server.close(done));await rm(temporary,{recursive:true,force:true});}
