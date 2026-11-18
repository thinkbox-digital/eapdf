// Exercise packaged bytes locally, including minified PDF.js and offline resources.
import {chromium} from 'playwright';
import {createServer} from 'node:http';
import {once} from 'node:events';
import {mkdtemp,readFile,writeFile,mkdir,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join,resolve,extname,sep} from 'node:path';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {makeAssetFixture} from './asset-fixture.mjs';
const root=resolve(process.env.EAPDF_MINIMAL_ROOT??fileURLToPath(new URL('../',import.meta.url)));
const evidence=resolve(process.env.EAPDF_MINIMAL_EVIDENCE??join(root,'test-results'));
const corpus=process.env.EAPDF_CORPUS;
await mkdir(evidence,{recursive:true});
const checks=[],errors=[],requests=[];
async function exercise(page,label){
 page.on('pageerror',error=>errors.push(error.message));
 await page.waitForFunction(()=>document.querySelector('#file')&&!document.querySelector('#file').disabled);
 assert.equal(await page.title(),'EA-PDF Even More Lite');
 assert.equal(await page.locator('[type="search"],#export-csv,#download-message,#metadata-dialog').count(),0);
 const idle=()=>page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false');
 const open=async path=>{
  await page.locator('#file').setInputFiles(path);
  await page.waitForFunction(()=>!document.querySelector('#page-surface').hidden&&document.querySelector('#viewport').getAttribute('aria-busy')==='false');
  await page.waitForFunction(()=>!!document.querySelector('.pdf-page[data-rendered]'));
  const pixels=await page.locator('#canvas').evaluate(canvas=>{
   const pixels=canvas.getContext('2d').getImageData(0,0,canvas.width,canvas.height).data;let dark=0;
   for(let n=0;n<pixels.length;n+=4)if(pixels[n]<220||pixels[n+1]<220||pixels[n+2]<220)dark++;return dark;
  });assert.ok(pixels>100,'page contains rendered marks');
 };
 await open(join(root,'tests/fixtures/ordinary.pdf'));
 await page.locator('#next').click();await idle();assert.equal(await page.locator('#page').inputValue(),'2');
 await page.locator('#previous').click();await idle();assert.equal(await page.locator('#page').inputValue(),'1');
 await page.locator('#page').fill('2');await page.locator('#page').press('Enter');await idle();assert.equal(await page.locator('#page').inputValue(),'2');
 await page.locator('#page').fill('99999');await page.locator('#page').press('Enter');assert.equal(await page.locator('#page').inputValue(),'2');
 await page.locator('#zoom').selectOption('fit-width');await idle();
 assert.ok(await page.locator('.textLayer span').count()>0,'text remains selectable');
 checks.push(label+': ordinary rendering, page controls, invalid page recovery, zoom, text selection');
 await page.screenshot({path:join(evidence,label+'-even-more-lite.png')});
 if(corpus)for(const profile of ['1s','1si','1m','1mi','1c','1ci']){
  await open(join(corpus,`eml_sample_${profile}.pdf`));
  assert.ok(await page.locator('#tree').getByRole('button').count()>0,'EA-PDF navigation available');
  if(['1c','1ci'].includes(profile)){
   assert.equal(await page.locator('#tree').getByText(/page unavailable/).count(),0);
   assert.equal(await page.locator('#tree > button').count(),0,'collection metadata must not create duplicate non-navigable rows');
   const member=page.locator('#tree button').filter({hasText:/\.pdf$/i}).first();assert.ok(await member.count());
   await member.click();await idle();assert.equal(await page.locator('#back').isEnabled(),true);
   await page.locator('#back').click();await idle();assert.equal(await page.locator('#back').isEnabled(),false);
  }else{
   if(['1m','1mi'].includes(profile)&&await page.locator('#tree button[data-message-id]').count()>1){
    const messages=page.locator('#tree button[data-message-id]');await messages.first().click();await idle();
    const expected=await messages.last().getAttribute('data-message-id');await page.locator('#viewport').focus();
    await page.locator('#viewport').evaluate(el=>{el.scrollTop=el.scrollHeight;});
    await page.waitForFunction(id=>document.querySelector('#tree button[aria-current]')?.dataset.messageId===id,expected);
    const activePage=await page.locator('#page').inputValue();await page.evaluate(()=>new Promise(done=>requestAnimationFrame(()=>requestAnimationFrame(done))));
    assert.equal(await page.locator('#page').inputValue(),activePage);assert.equal(await page.evaluate(()=>document.activeElement.id),'viewport');
    checks.push(label+': scrolling an archive selects the corresponding left-pane message without moving the reading position');
   }
   const message=page.locator('#tree button').first();await message.click();await idle();
  }
  checks.push(label+': '+profile+' rendering and message/collection navigation');
 }
 await open(fixture.path);
 const row=name=>page.locator('.asset').filter({has:page.getByRole('heading',{name,exact:true})});
 const layout=await page.evaluate(()=>['navigation-panel','viewport','details-panel'].map(id=>{const r=document.getElementById(id).getBoundingClientRect();return {x:r.x,width:r.width};}));
 assert.ok(layout.every(pane=>pane.width>150));assert.ok(layout[0].x<layout[1].x&&layout[1].x<layout[2].x);
 await page.locator('#resize-navigation').focus();await page.keyboard.press('ArrowRight');
 await page.waitForFunction(width=>document.querySelector('#navigation-panel').getBoundingClientRect().width>width+5,layout[0].width);
 const divider=await page.locator('#resize-details').boundingBox(),old=await page.locator('#details-panel').evaluate(el=>el.clientWidth);
 await page.mouse.move(divider.x+divider.width/2,divider.y+30);await page.mouse.down();await page.mouse.move(divider.x-30,divider.y+30);await page.mouse.up();
 assert.ok(await page.locator('#details-panel').evaluate(el=>el.clientWidth)>old+10);
 await page.locator('#toggle-navigation').click();assert.equal(await page.locator('#navigation-panel').isVisible(),false);
 await page.locator('#toggle-navigation').click();await page.locator('#toggle-details').click();assert.equal(await page.locator('#details-panel').isVisible(),false);await page.locator('#toggle-details').click();
 const save=async(action,expected)=>{const pending=page.waitForEvent('download');await action();const file=await pending;const bytes=await readFile(await file.path());assert.deepEqual(bytes,expected);await idle();return file;};
 const previewSave=async(action,expected)=>{
  await action();await page.locator('#attachment-viewer').waitFor({state:'visible'});await page.waitForFunction(()=>document.querySelector('#attachment-viewer').getAttribute('aria-busy')==='false');
  assert.match(await page.locator('#attachment-text').textContent(),/東京/);
  await save(()=>page.locator('#attachment-download').click(),expected);await page.locator('#attachment-close').click();
 };
 for(const name of ['notes.txt','archive.bin','two-pages.pdf','empty.bin']){
  const file=await save(()=>row(name).getByRole('button',{name:'Download '+name,exact:true}).click(),fixture.payloads[name]);assert.equal(file.suggestedFilename(),name);
 }
 assert.equal(await row('archive.bin').getByRole('button',{name:/Go to page/}).count(),0);
 await row('notes.txt').getByRole('button',{name:'Go to page 2',exact:true}).click();await idle();assert.equal(await page.locator('#page').inputValue(),'2');
 assert.doesNotMatch(await page.locator('#assets').textContent(),/File object:|PDF relationship:|name-tree|catalog-af|Stream \/|object:\d/);
 await previewSave(()=>page.locator('.pdf-page[data-page="2"] .file-attachment-link').click(),fixture.payloads['notes.txt']);
 assert.equal(await row('notes.txt').getAttribute('aria-current'),'true');
 await page.locator('#page').fill('1');await page.locator('#page').press('Enter');await idle();
 await previewSave(()=>page.locator('#page-surface a[aria-label="Open linked attachment"]').nth(0).click(),fixture.payloads['notes.txt']);
 await page.locator('#page-surface a[aria-label="Open linked attachment"]').nth(1).click();await idle();assert.equal(await page.locator('#back').isEnabled(),true);
 assert.match(await page.locator('#breadcrumbs').textContent(),/attachment-control\.pdf \/ two-pages\.pdf/);
 await page.locator('#back').click();await idle();assert.equal(await page.locator('#page').inputValue(),'1');
 await page.locator('#page-surface a[aria-label="Open linked attachment"]').nth(2).click();await idle();assert.match(await page.locator('#status').textContent(),/missing or ambiguous/);
 await row('over-limit.bin').getByRole('button',{name:'Download over-limit.bin',exact:true}).click();await idle();assert.match(await page.locator('#status').textContent(),/16 MiB/);
 await save(()=>row('broken.bin').getByRole('button',{name:'Download broken.bin',exact:true}).click(),fixture.payloads['broken.bin']);assert.match(await page.locator('#status').textContent(),/Warning:.*checksum/);
 const variants=row('variants.bin').getByRole('button',{name:'Download variants.bin',exact:true});assert.equal(await variants.count(),2);
 await save(()=>variants.nth(0).click(),fixture.payloads['variant-f.bin']);await save(()=>variants.nth(1).click(),fixture.payloads['variant-uf.bin']);
 await row('two-pages.pdf').getByRole('button',{name:'View two-pages.pdf',exact:true}).click();await idle();await page.locator('#back').click();await idle();
 await page.locator('#page-surface .file-attachment-link').first().click();await idle();assert.equal(await page.locator('#back').isEnabled(),true);await page.locator('#back').click();await idle();
 await page.screenshot({path:join(evidence,label+'-three-panes.png')});
 checks.push(label+': three adjustable/collapsible panes, asset locations, exact downloads, both embedded stream variants, annotation/GoToE links and error recovery');
 await open(join(root,'tests/attachments/viewing.pdf'));
 await row('blue.png').getByRole('button',{name:'View blue.png',exact:true}).click();await page.locator('#attachment-viewer').waitFor({state:'visible'});await page.waitForFunction(()=>document.querySelector('#attachment-viewer').getAttribute('aria-busy')==='false');
 assert.ok(await page.locator('#attachment-canvas').evaluate(canvas=>canvas.width>0&&canvas.height>0));await page.locator('#attachment-close').click();
 await row('markup.html').getByRole('button',{name:'View markup.html',exact:true}).click();await page.locator('#attachment-viewer').waitFor({state:'visible'});await page.waitForFunction(()=>document.querySelector('#attachment-viewer').getAttribute('aria-busy')==='false');
 assert.match(await page.locator('#attachment-text').textContent(),/<script>/);assert.equal(await page.evaluate(()=>globalThis.attachmentExecuted),undefined);await page.locator('#attachment-close').click();
 assert.equal(await row('blue.png').locator('button[aria-label="Download blue.png"] svg').count(),1);assert.equal(await row('blue.png').locator('button[aria-label="View blue.png"] svg').count(),1);
 checks.push(label+': shared Full viewer icons, text/image previews and inert markup');
 if(process.env.EAPDF_COLLECTION_SAMPLE){
  await open(process.env.EAPDF_COLLECTION_SAMPLE);assert.equal(await page.locator('#tree > button').count(),0);assert.equal(await page.getByText('Email PDFs (7)',{exact:true}).count(),1);
  const members=await page.locator('#tree button[data-file-id]').evaluateAll(buttons=>buttons.map(button=>button.dataset.fileId));
  assert.equal(await page.locator('#assets summary').filter({hasText:/^Location$/}).count(),0);
  await page.screenshot({path:join(evidence,label+'-collection-labels.png')});
  await page.locator('#tree button[data-file-id]').first().click();await idle();assert.equal(await page.locator('#back').isEnabled(),true);
  assert.equal(await page.locator('#tree button[aria-current]').getAttribute('data-file-id'),members[0]);
  const lastPage=(await page.locator('#pages').textContent()).match(/\d+/)[0];
  await page.locator('#viewport').evaluate(el=>{el.scrollTop=el.scrollHeight;});await page.locator('#viewport').hover();await page.mouse.wheel(0,600);
  await page.waitForFunction(id=>document.querySelector('#tree button[aria-current]')?.dataset.fileId===id&&document.querySelector('#viewport').getAttribute('aria-busy')==='false',members[1]);
  assert.equal(await page.locator('#tree button[data-file-id]').count(),members.length);
  await page.waitForTimeout(250);await page.locator('#viewport').evaluate(el=>{el.scrollTop=0;});await page.mouse.wheel(0,-600);
  await page.waitForFunction(id=>document.querySelector('#tree button[aria-current]')?.dataset.fileId===id&&document.querySelector('#viewport').getAttribute('aria-busy')==='false',members[0]);
  assert.equal(await page.locator('#page').inputValue(),lastPage);
  await page.locator('#back').click();await idle();assert.ok(await page.locator('#assets .file-message-subject').count()>0);
  assert.equal(await page.locator('#assets summary').filter({hasText:/^Location$/}).count(),0);
  checks.push(label+': collection boundary scrolling keeps the folder tree, selects next/previous emails and displays known subjects inline');
  checks.push(label+': real collection cover, grouped email PDFs and child opening without unavailable-page rows');
 }
 if(corpus){
  await open(join(corpus,'eml_sample_1m.pdf'));
  const expected=JSON.parse(await readFile(process.env.EAPDF_ATTACHMENT_MANIFEST??join(root,'tests/attachments/manifest.json'),'utf8')).cases.find(item=>item.sourcePath.endsWith('eml_sample_1m.pdf')).attachments.find(item=>item.name==='attachment.txt');
  await page.locator('#all-files').evaluate(el=>{if(!el.hidden)el.click();});
  const pending=page.waitForEvent('download');await row('attachment.txt').getByRole('button',{name:'Download attachment.txt',exact:true}).click();const file=await pending;
  assert.equal(createHash('sha256').update(await readFile(await file.path())).digest('hex'),expected.sha256);await idle();
  checks.push(label+': real-source attachment download matches its independent SHA-256');
 }
 await page.setViewportSize({width:400,height:700});await idle();
 assert.ok(await page.locator('#viewport').evaluate(el=>el.clientWidth)>100);
 await page.setViewportSize({width:1280,height:800});await idle();
 await page.locator('#file').setInputFiles({name:'oversized.pdf',mimeType:'application/pdf',buffer:Buffer.alloc(32*1024**2+1)});
 await page.waitForFunction(()=>document.querySelector('#status').textContent.includes('32 MiB'));
 await open(join(root,'tests/fixtures/ordinary.pdf'));
 await page.locator('#file').setInputFiles({name:'invalid.pdf',mimeType:'application/pdf',buffer:Buffer.from('invalid PDF')});
 await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false'&&document.querySelector('#status').textContent.includes('Invalid PDF'));
 await open(join(root,'tests/fixtures/ordinary.pdf'));
 const bytes=Array.from(await readFile(join(root,'tests/fixtures/ordinary.pdf')));
 await page.evaluate(bytes=>{
  const input=document.querySelector('#file');
  const select=name=>{const files=new DataTransfer();files.items.add(new File([new Uint8Array(bytes)],name,{type:'application/pdf'}));input.files=files.files;input.dispatchEvent(new Event('change'));};
  select('cancelled.pdf');document.querySelector('#close').click();select('reopened.pdf');
 },bytes);
 await page.waitForFunction(()=>!document.querySelector('#page-surface').hidden&&document.querySelector('#viewport').getAttribute('aria-busy')==='false');
 assert.equal(await page.locator('#status').textContent(),'reopened.pdf');
 await page.locator('#close').click();assert.equal(await page.locator('#empty-state').isVisible(),true);
 checks.push(label+': narrow layout, input limit, invalid PDF, cancellation and close/reopen recovery');
}
const temporary=await mkdtemp(join(tmpdir(),'eapdf-minimal-browser-'));
const fixture=await makeAssetFixture(root,temporary);
let server,context;
try{
 context=await chromium.launchPersistentContext(join(temporary,'extension'),{headless:true,viewport:{width:1280,height:800},
  executablePath:process.env.CHROMIUM_PATH??chromium.executablePath(),
  args:[`--disable-extensions-except=${join(root,'dist/chromium')}`,`--load-extension=${join(root,'dist/chromium')}`]});
 context.on('request',request=>{if(/^https?:/.test(request.url()))requests.push(request.url());});
 await context.setOffline(true);
 const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker');
 const page=await context.newPage();await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);await exercise(page,'chromium');
 await context.close();context=undefined;
 const directory=join(root,'dist/web');
 server=createServer(async(req,res)=>{
  const path=resolve(directory,'.'+new URL(req.url,'http://localhost').pathname);
  if(!path.startsWith(directory+sep)){res.writeHead(403).end();return;}
  try{const bytes=await readFile(path);res.setHeader('content-type',({'.js':'text/javascript','.mjs':'text/javascript','.css':'text/css','.html':'text/html','.wasm':'application/wasm'})[extname(path)]??'application/octet-stream');res.end(bytes);}
  catch{res.writeHead(404).end();}
 });server.listen(0,'127.0.0.1');await once(server,'listening');
 const address=`http://127.0.0.1:${server.address().port}`;
 context=await chromium.launchPersistentContext(join(temporary,'web'),{headless:true,viewport:{width:1280,height:800},...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{})});
 context.on('request',request=>{if(!request.url().startsWith(address)&&/^https?:/.test(request.url()))requests.push(request.url());});
 const web=await context.newPage();await web.goto(address+'/viewer.html');await exercise(web,'web');
 assert.deepEqual(errors,[]);assert.deepEqual(requests,[]);
 await writeFile(join(evidence,'browser-checks.json'),JSON.stringify({status:'PASS',browser:context.browser()?.version(),checks,errors,externalRequests:requests},null,2)+'\n');
 console.log(JSON.stringify({status:'PASS',checks}));
}finally{await context?.close();if(server)await new Promise(done=>server.close(done));await rm(temporary,{recursive:true,force:true});}
