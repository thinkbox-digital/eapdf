import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {resolve,join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {mkdtemp,mkdir,readFile,writeFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {existsSync} from 'node:fs';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
const temporary=await mkdtemp(join(tmpdir(),'eapdf-redesign-')),extension=resolve('dist/chromium'),screens=resolve('test-results/redesign/after');
const source=resolve('../examples/eml/eml_Agenda_and_timeline_slides_1s.pdf'),single=join(temporary,'single-empty.pdf');
// Isolate the zero-byte edge case in a copy of a real email; preserve originals.
execFileSync('python3',['-c',`import pikepdf,sys
with pikepdf.open(sys.argv[1]) as p:
 for o in p.objects:
  if isinstance(o,pikepdf.Dictionary) and o.get('/Type')==pikepdf.Name('/Filespec') and str(o.get('/F','')).endswith('.pptx'):
   for s in o['/EF'].values():
    s.write(b'');s['/Params']['/Size']=0
 p.save(sys.argv[2])`,source,single]);
const shapes=[['single',single,1],['mbox',resolve('../examples/mbox/mbox_enron-sample_1m.pdf'),2],['pst',resolve('../examples/pst/pst_quality-attached-message_1c.pdf'),7]];
const originals=[source,...shapes.slice(1).map(s=>s[1])],hash=async p=>createHash('sha256').update(await readFile(p)).digest('hex'),hashes=await Promise.all(originals.map(hash));
const context=await chromium.launchPersistentContext('',{headless:true,acceptDownloads:true,viewport:{width:1440,height:900},executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
const report={status:'PASS',screens:[],checks:[],sourceHashes:Object.fromEntries(originals.map((p,i)=>[p,hashes[i]]))};
try{
 await context.setOffline(true);const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage(),errors=[];
 page.on('pageerror',e=>errors.push(e.message));await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);await mkdir(screens,{recursive:true});
 const ready=()=>page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false',null,{timeout:90000});
 async function audit(){
  const issues=await page.evaluate(()=>{
   const issues=[],visible=e=>e.getClientRects().length&&!e.closest('[hidden]')&&getComputedStyle(e).visibility!=='hidden';
   for(const e of document.querySelectorAll('button,input,select,summary,a')){
    if(!visible(e)||e.closest('#page-surface'))continue;
    const name=e.getAttribute('aria-label')||e.getAttribute('aria-labelledby')||e.textContent?.trim()||e.labels?.[0]?.textContent?.trim();
    if(!name)issues.push(`Unnamed control: ${e.id||e.outerHTML.slice(0,80)}`);
    const r=e.getBoundingClientRect(),tree=!!e.closest('#tree,#filter-results');
    if(e.matches('button,input,select,summary,a')&&(r.height<(tree?39.5:43.5)||(!tree&&r.width<43.5)))issues.push(`Small target ${e.id||e.tagName}: ${r.width}x${r.height}`);
    if(e.classList.contains('icon-button')&&(!e.getAttribute('aria-label')||!e.title))issues.push(`Icon label/tooltip missing: ${e.id}`);
   }
   const rgb=c=>c.match(/[\d.]+/g)?.slice(0,3).map(Number),lum=c=>{const v=c.map(x=>{x/=255;return x<=.04045?x/12.92:((x+.055)/1.055)**2.4;});return .2126*v[0]+.7152*v[1]+.0722*v[2];};
   for(const e of document.querySelectorAll('header *,aside *,footer *,#search-panel *,.page-toolbar *')){
    if(!visible(e)||!Array.from(e.childNodes).some(n=>n.nodeType===3&&n.textContent.trim())||e.closest('button:disabled'))continue;
    const s=getComputedStyle(e);let parent=e,bg;
    while(parent){const value=getComputedStyle(parent).backgroundColor;if(value!=='rgba(0, 0, 0, 0)'&&value!=='transparent'){bg=rgb(value);break;}parent=parent.parentElement;}
    const fg=rgb(s.color);if(!fg)continue;const a=lum(fg),b=lum(bg??[255,255,255]),ratio=(Math.max(a,b)+.05)/(Math.min(a,b)+.05);
    if(ratio<4.5)issues.push(`Contrast ${ratio.toFixed(2)}: ${e.id||e.className}`);
   }
   if(document.documentElement.scrollWidth>innerWidth)issues.push('Horizontal page overflow');
   for(const id of ['navigation-panel','details-panel']){const e=document.getElementById(id);if(visible(e)&&e.scrollWidth>e.clientWidth+1)issues.push(`Pane overflow: ${id}`);}
   return issues;
  });assert.deepEqual(issues,[]);
 }
 for(const [shape,path,count] of shapes){
  await page.setViewportSize({width:1440,height:900});await page.locator('#file').setInputFiles(path);await ready();
  if(shape==='pst')await page.waitForFunction(()=>document.querySelector('#subject-index-cancel').hidden,{timeout:90000});
  assert.equal(await page.locator('#message-count').textContent(),String(count));assert.equal(await page.locator('#search-panel').isVisible(),false);
  await page.keyboard.press('Tab');await page.locator('#open-search').focus();assert.deepEqual(await page.locator('#open-search').evaluate(e=>{const s=getComputedStyle(e);return [s.outlineWidth,s.outlineOffset,s.outlineColor];}),['2px','2px','rgb(31, 78, 140)']);
  if(shape==='single'){const empty=page.locator('.file-card').filter({hasText:'This file is empty.'});assert.equal(await empty.count(),1);assert.equal(await empty.locator('[data-file-id]').isDisabled(),true);assert.equal(await empty.locator('[role=img][aria-label=Warning]').count(),1);}
  for(const width of [1440,1100]){
   await page.setViewportSize({width,height:900});await page.waitForTimeout(350);await ready();await audit();
   const filename=`${shape}-${width}.png`;await page.screenshot({path:join(screens,filename)});report.screens.push(filename);
   if(width===1100){await page.locator('#show-details').click();await ready();await audit();await page.locator('#show-navigation').click();await ready();}
  }
  await page.setViewportSize({width:1440,height:900});await page.waitForTimeout(350);await ready();
  const csvEvent=page.waitForEvent('download');await page.locator('#export-csv').click();const csv=await readFile(await(await csvEvent).path(),'utf8');assert.match(csv,/"PDF","Folder paths","From","To","Cc","Bcc","Subject","Sent","Message-ID","Attachment count","Attachment names"/);await ready();
  if(shape==='mbox'){const collapse=page.locator('#tree .document-disclosure').first();await collapse.focus();await page.keyboard.press('Enter');assert.equal(await collapse.getAttribute('aria-expanded'),'false');await page.keyboard.press('Enter');assert.equal(await collapse.getAttribute('aria-expanded'),'true');const root=page.locator('#tree > li > button[data-document-id]').first();await root.focus();await page.keyboard.press('ArrowDown');assert.equal(await root.evaluate(e=>document.activeElement===e),false);assert.equal(await page.locator('#pages').textContent(),'of 3');await page.locator('#next').click();await ready();assert.equal(await page.locator('#page').inputValue(),'2');await page.locator('#previous').click();await ready();}
  await page.keyboard.press('Control+f');assert.equal(await page.evaluate(()=>document.activeElement.id),'search');assert.equal(await page.locator('#open-search').getAttribute('aria-expanded'),'true');await audit();
  const word=await page.locator('#text-layer').evaluate(e=>e.textContent.match(/[A-Za-z]{5,}/)?.[0]??'email');await page.locator('#search').fill(word);await page.keyboard.press('Enter');await ready();assert.match(await page.locator('#search-status').textContent(),/Match \d+ of \d+/);await page.keyboard.press('Shift+Enter');await ready();await page.keyboard.press('Escape');assert.equal(await page.locator('#search-panel').isVisible(),false);assert.equal(await page.evaluate(()=>document.activeElement.id),'open-search');
  const download=page.locator('#files [data-file-id]:not(:disabled)').first();const event=page.waitForEvent('download');await download.click();assert.ok((await readFile(await(await event).path())).length>0);await ready();
  const view=page.locator('#files [data-view-file-id]').first();await view.click();await ready();await page.locator('#attachment-viewer').waitFor({state:'visible'});await page.locator('#attachment-close').click();
  if(shape==='pst'){
   await page.locator('#open-search').click();await page.locator('#search-scope').selectOption('collection');await page.locator('#search').fill('Feature');await page.keyboard.press('Enter');await ready();assert.ok(await page.locator('#collection-matches .collection-hit').count());await page.screenshot({path:join(screens,'search-panel.png')});await page.locator('#find').click();await ready();await page.locator('#close-search').click();
   await page.locator('#message-filter').fill('Feature');await page.locator('#filter-results button').first().waitFor();await page.locator('#filter-results button').first().click();await ready();assert.match(await page.locator('#files-title').textContent(),/email/);assert.ok(await page.locator('#selection-details').textContent());
  }
  report.checks.push(`${shape}: opening, responsive layout, target sizes, names, contrast, keyboard search, CSV, view/download`);
 }
 await page.locator('#file').setInputFiles(resolve('tests/inspection/controls/s-two-streams.pdf'));await ready();await page.locator('#files details').evaluateAll(ds=>ds.forEach(d=>d.open=true));
 const multiple=JSON.parse(await readFile('tests/inspection/expected/s-two-streams.json','utf8')).files.find(f=>f.streams.length>1);assert.ok(multiple);
 for(const stream of multiple.streams){const control=page.locator(`[data-file-id=${JSON.stringify(multiple.id)}][data-stream=${JSON.stringify(stream.locator.path)}]`),event=page.waitForEvent('download');await control.click();const bytes=await readFile(await(await event).path());assert.equal(createHash('sha256').update(bytes).digest('hex'),stream.sha256.value);await ready();}
 report.checks.push('Multiple EF streams retain independently accessible, exact-byte downloads');
 assert.deepEqual(errors,[]);assert.deepEqual(await Promise.all(originals.map(hash)),hashes);await writeFile('test-results/redesign/report.json',JSON.stringify(report,null,2)+'\n');const gallery=shapes.map(([shape])=>[1440,1100].map(width=>{const name=`${shape}-${width}.png`;const before=existsSync(resolve('test-results/redesign/before',name));return `<section><h2>${shape.toUpperCase()} · ${width} × 900</h2><div class=pair>${before?`<figure><figcaption>Before</figcaption><a href=before/${name}><img loading=lazy src=before/${name} alt="Before: ${shape}, ${width} pixels"></a></figure>`:''}<figure><figcaption>After</figcaption><a href=after/${name}><img loading=lazy src=after/${name} alt="After: ${shape}, ${width} pixels"></a></figure></div></section>`;}).join('')).join('');
 await writeFile('test-results/redesign/index.html',`<!doctype html><html lang=en><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1"><title>EA-PDF viewer — before and after</title><style>body{font:16px system-ui;margin:24px;background:#eceff4;color:#1d2733}h1{color:#1f4e8c}h2{font-size:18px}.pair{display:grid;grid-template-columns:1fr 1fr;gap:16px}figure{margin:0}figcaption{font-weight:600;margin-bottom:8px}img{width:100%;border:1px solid #d5dce5}section{margin:32px 0}@media(max-width:800px){.pair{grid-template-columns:1fr}}</style><h1>EA-PDF viewer: before and after</h1><p><a href=after/search-panel.png>Search panel</a></p><p>Three document shapes at both requested widths. Click a screenshot for full size. The single-email case uses a copy of a real EML-derived PDF with one attachment made empty; original files are unchanged.</p><p>PDF page content, parsing, extraction and CSV logic are outside this presentation redesign. The parent-navigation icon is retained; medium windows use one sidebar at a time.</p>${gallery}</html>`);console.log(JSON.stringify(report));
}finally{await context.close();await rm(temporary,{recursive:true,force:true});}
