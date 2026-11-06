import {createServer} from 'node:http';
import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {readFile,mkdtemp,rm} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import {resolve,join} from 'node:path';
import {tmpdir} from 'node:os';
import {fileURLToPath} from 'node:url';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
const root=resolve('dist/chromium'),scratch=await mkdtemp(join(tmpdir(),'eapdf-folder-scroll-'));
const server=createServer(async(req,res)=>{
 const path=resolve(root,'.'+new URL(req.url,'http://localhost').pathname);if(!path.startsWith(root+'/')){res.writeHead(403).end();return;}
 try{res.setHeader('Content-Type',/\.m?js$/.test(path)?'text/javascript':path.endsWith('.css')?'text/css':path.endsWith('.html')?'text/html':'application/octet-stream');res.end(await readFile(path));}catch{res.writeHead(404).end();}
});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
let browser;
try{
 browser=await chromium.launch({headless:true,...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{channel:'chromium'})});
 const page=await browser.newPage({viewport:{width:1500,height:900}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(`http://127.0.0.1:${server.address().port}/viewer.html`);await page.waitForFunction(()=>!document.querySelector('#file').disabled);
 const ready=()=>page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false',null,{timeout:60000});
 const name=()=>page.locator('#breadcrumbs button').last().textContent();
 const open=async path=>{
  await page.locator('#file').setInputFiles(path);await ready();
  while(await page.locator('#tree details:not([open])').count())await page.locator('#tree details:not([open]) > summary').first().click();
 };
 const edge=async direction=>{
  await page.locator('#viewport').evaluate((v,d)=>{v.scrollTop=d>0?v.scrollHeight:0;},direction);
  await page.waitForTimeout(250);const box=await page.locator('#viewport').boundingBox();await page.mouse.move(box.x+box.width/2,box.y+box.height/2);
  await page.mouse.wheel(0,direction*180);await page.waitForTimeout(80);await ready();
 };
 for(const profile of ['1c','1ci']){
  await open(resolve(`tests/inspection/source-examples/eml/eml_sample_${profile}.pdf`));
  const names=await page.locator('#tree [data-embedded-file-id]').evaluateAll(nodes=>nodes.map(n=>n.textContent));assert.equal(names.length,2);
  await page.locator('#tree [data-embedded-file-id]').first().click();await ready();assert.equal(await name(),names[0]);
  await edge(1);assert.equal(await name(),names[1]);assert.equal(await page.locator('#page').inputValue(),'1');
  // Remaining momentum from the same gesture cannot move to another file.
  await page.locator('#viewport').dispatchEvent('wheel',{deltaY:5000});await ready();assert.equal(await name(),names[1]);
  await edge(1);assert.equal(await name(),names[1]);assert.match(await page.locator('#status').textContent(),/End of messages in this folder/);
  await edge(-1);assert.equal(await name(),names[0]);
  assert.equal(Number(await page.locator('#page').inputValue()),Number((await page.locator('#pages').textContent()).replace('of ','')));
  await edge(-1);assert.equal(await name(),names[0]);assert.match(await page.locator('#status').textContent(),/Beginning of messages in this folder/);
  console.log(`PASS ${profile}: forward/backward, first/last page, folder ends, momentum guard`);
 }
 // Filtering must preserve the results and use their order across files and pages.
 for(const profile of ['1c','1ci','1m','1mi']){
  await open(resolve(`tests/inspection/source-examples/eml/eml_sample_${profile}.pdf`));
  await page.locator('#message-filter').fill('@');
  await page.waitForFunction(()=>document.querySelectorAll('#filter-results button').length>=2);
  const results=page.locator('#filter-results button');
  await results.first().click();await ready();
  assert.equal(await page.locator('#message-filter').inputValue(),'@');
  assert.equal(await results.first().getAttribute('aria-current'),'true');
  await edge(1);assert.equal(await results.nth(1).getAttribute('aria-current'),'true');
  await edge(-1);assert.equal(await results.first().getAttribute('aria-current'),'true');
  await edge(-1);assert.match(await page.locator('#status').textContent(),/Beginning of filtered messages/);
  await results.last().click();await ready();await edge(1);
  assert.match(await page.locator('#status').textContent(),/End of filtered messages/);
  const subject=await results.first().locator('.message-subject').count()?await results.first().locator('.message-subject').textContent():null;
  if(subject){
   await page.locator('#message-filter').fill(subject);
   await page.waitForFunction(()=>document.querySelectorAll('#filter-results button').length===1);
   await results.first().click();await ready();await edge(1);
   assert.match(await page.locator('#status').textContent(),/End of filtered messages/);
  }
  await page.locator('#message-filter').fill('');await ready();
  assert.equal(await page.locator('#filter-results').isVisible(),false);
  console.log(`PASS ${profile}: retained filter, forward/backward result scrolling and result boundaries`);
 }
 // Nested collection children are an explicit boundary, not a folder to enter automatically.
 await open(resolve('tests/inspection/controls/c-nested-mixed.pdf'));
 await page.locator('#tree [data-embedded-file-id]').filter({hasText:'archive.pdf'}).click();await ready();const archive=await name();
 await edge(1);assert.equal(await name(),archive);assert.match(await page.locator('#status').textContent(),/nested collection/);
 // Keyboard navigation uses the same boundary rules when the page pane has focus.
 await open(resolve('tests/inspection/source-examples/eml/eml_sample_1ci.pdf'));
 await open(resolve('../examples/pst/pst_quality-attached-message_1s.pdf'));const standalone=await name();await edge(1);assert.equal(await name(),standalone);
 console.log('PASS nested collection stop and standalone file boundary');
 execFileSync('python3',['-c',`
import pikepdf,sys
with pikepdf.open(sys.argv[1]) as p:
 root=p.Root.Collection.Folders
 sibling=p.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name('/Folder'),ID=2,Name='Other folder',Parent=root))
 root.Child.Next=sibling
 tree=pikepdf.NameTree(p.Root.Names.EmbeddedFiles)
 key=list(tree.keys())[1];value=tree[key];del tree[key];tree['<2>'+key.split('>',1)[1]]=value
 p.save(sys.argv[2])
 `,resolve('tests/inspection/source-examples/eml/eml_sample_1ci.pdf'),join(scratch,'split.pdf')]);
 await open(join(scratch,'split.pdf'));
 await page.locator('#tree [data-embedded-file-id]').first().click();await ready();const first=await name();
 await edge(1);assert.equal(await name(),first);assert.match(await page.locator('#status').textContent(),/End of messages in this folder/);
 await edge(-1);assert.equal(await name(),first);assert.match(await page.locator('#status').textContent(),/Beginning of messages in this folder/);
 assert.deepEqual(errors,[]);console.log('PASS separate-folder boundary using a temporary copy of real message PDFs; no page errors');
}finally{await browser?.close();await new Promise(r=>server.close(r));await rm(scratch,{recursive:true,force:true});}
