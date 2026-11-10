import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {mkdtemp,rm,mkdir,readFile,writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
const work=await mkdtemp(join(tmpdir(),'eapdf-thread-preview-test-'));execFileSync(process.env.M1_PYTHON??'python3',['scripts/thread-preview-fixture.py',work]);
const extension=resolve('dist/chromium'),context=await chromium.launchPersistentContext('',{headless:true,acceptDownloads:true,executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--load-extension=${extension}`,`--disable-extensions-except=${extension}`]});
try{
 await context.setOffline(true);const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage(),errors=[],reports=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);await page.waitForFunction(()=>!document.querySelector('#file').disabled);
 await page.evaluate(async()=>{const {BrowserSession}=await import('./browser-session.js');const open=BrowserSession.prototype.open;window.previewReads=0;const post=Worker.prototype.postMessage;Worker.prototype.postMessage=function(data,...args){if(data?.messages){window.previewMessages=data.messages;if(!this.previewObserved){this.previewObserved=true;this.addEventListener('message',event=>{if(event.data.graph)window.previewGraph=event.data.graph;});}}return post.call(this,data,...args);};BrowserSession.prototype.open=async function(...args){window.previewReads++;return open.apply(this,args);};});
 const ready=()=>page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false',null,{timeout:180000});
 for(const profile of ['s','si','m','mi','c','ci',...(process.env.THREAD_PREVIEW_REAL?['real']:[])]){
  const path=profile==='real'?process.env.THREAD_PREVIEW_REAL:['s','si'].includes(profile)?resolve(`../examples/eml/eml_Agenda_and_timeline_slides_1${profile}.pdf`):join(work,`preview_1${profile}.pdf`),before=createHash('sha256').update(await readFile(path)).digest('hex');
  await page.locator('#file').setInputFiles(path);await ready();assert.equal(await page.locator('#thread-preview').isVisible(),false);
  if(await page.locator('#files details').count())assert.ok(await page.locator('#files details[open]').count()>0,'Normal file sections start open');
  const started=performance.now();await page.locator('#thread-preview-enable').click();await page.waitForFunction(()=>document.querySelector('#thread-preview').dataset.ready==='true',null,{timeout:180000});
  const status=(await page.locator('#thread-preview-status').textContent())+' · '+(await page.locator('#thread-preview-status').getAttribute('title'));assert.match(status,/conversations.*messages/);
  if(!['s','si','real'].includes(profile)){assert.match(status,/^1 conversations/);assert.match(status,/[1-9]\d* Confirmed links/);assert.match(status,/0 missing parents/);}
  const disclosure=page.locator('#thread-preview-rows button[aria-expanded]').first();if(await disclosure.count())await disclosure.click();
  const summary=page.locator('#thread-preview-rows .conversation-summary');const threaded=await summary.count()>0;const button=threaded?summary.first():page.locator('#thread-preview-rows button[data-thread-message]').first();await button.click();await ready();assert.equal(await page.locator('#canvas').isVisible(),true);
  if(threaded){
  assert.equal(await page.locator('#thread-conversation').isVisible(),true);assert.equal(await page.locator('#files details[open]').count(),0);assert.equal(await page.locator('#thread-details-disclosure').getAttribute('open'),null);assert.equal(await page.locator('#message-details-disclosure').getAttribute('open'),null);assert.match(await page.locator('#thread-conversation').textContent(),/Conversation.*Thread details.*Root Message-ID.*Participants/s);
  const right=page.locator('#thread-conversation button[data-thread-message]');assert.ok(await right.count()>0);await right.first().click();await ready();assert.equal(await page.locator('#thread-conversation [aria-current=true]').count(),1);
  const selected=()=>page.locator('#thread-conversation [aria-current=true]').getAttribute('data-thread-message');
  const firstId=await selected();await page.locator('#thread-conversation [aria-current=true]').focus();await page.keyboard.press('ArrowDown');
  await page.waitForFunction(id=>document.querySelector('#thread-conversation [aria-current=true]')?.getAttribute('data-thread-message')!==id,firstId);await ready();await page.waitForTimeout(80);const nextId=await selected();assert.notEqual(nextId,firstId);
  const leftSelected=page.locator('#thread-preview-rows button[aria-current=true]');assert.equal(await leftSelected.count(),1);assert.equal(await leftSelected.evaluate(el=>{const r=el.getBoundingClientRect(),p=document.querySelector('#navigation-panel').getBoundingClientRect();return r.top>=p.top&&r.bottom<=p.bottom;}),true,'Selected message stays visible on the left');
  await page.keyboard.press('ArrowUp');await page.waitForFunction(id=>document.querySelector('#thread-conversation [aria-current=true]')?.getAttribute('data-thread-message')===id,firstId);await ready();await page.waitForTimeout(80);
  const wheel=async direction=>{await page.waitForTimeout(240);await page.locator('#viewport').evaluate((el,d)=>{el.scrollTop=d>0?el.scrollHeight:0;el.dispatchEvent(new WheelEvent('wheel',{deltaY:d*120,bubbles:true,cancelable:true}));},direction);};
  await wheel(1);await page.waitForFunction(id=>document.querySelector('#thread-conversation [aria-current=true]')?.getAttribute('data-thread-message')===id,nextId);await ready();await page.waitForTimeout(80);
  await wheel(-1);await page.waitForFunction(id=>document.querySelector('#thread-conversation [aria-current=true]')?.getAttribute('data-thread-message')===id,firstId);await ready();await page.waitForTimeout(80);
  await page.locator('#thread-conversation button[data-thread-message]').last().click();await ready();await page.waitForTimeout(80);const endId=await selected();await page.locator('#thread-conversation [aria-current=true]').focus();await page.keyboard.press('ArrowDown');await wheel(1);await page.waitForTimeout(100);assert.equal(await selected(),endId,'Navigation stays within the current thread');



  }else{assert.equal(await page.locator('#thread-conversation').isVisible(),false);assert.equal(await page.locator('#thread-preview-rows button[aria-expanded]').count(),0);}
  const singleton=page.locator('#thread-preview-rows [data-thread-standalone=true] button[data-thread-message]').first();if(await singleton.count()){await singleton.click();await ready();assert.equal(await page.locator('#thread-conversation').isVisible(),false);}
  if(profile==='real'){
   const confirmed=await page.evaluate(()=>window.previewGraph.nodes.filter(n=>n.evidence==='Confirmed').map(n=>[n.id,n.parent]));
   await page.getByRole('checkbox',{name:'Infer from matching subjects (experimental)',exact:true}).check();
   await page.waitForFunction(()=>document.querySelector('#thread-preview').dataset.ready==='true',null,{timeout:180000});
   const grouped=await page.evaluate(()=>{
    const g=window.previewGraph,messages=window.previewMessages,groups=g.nodes.filter(n=>n.subjectGroup);
    for(const group of groups){if(group.message!==null||group.mid||group.children.length<2)throw Error('Invalid conversation heading');let previous=-Infinity;for(const id of group.children){const child=g.nodes[id],date=Date.parse(messages[child.message].sent??'');if(child.evidence!=='Inferred'||child.parent!==group.id)throw Error('Invalid inferred component');const value=Number.isFinite(date)?date:Infinity;if(value<previous)throw Error('Components out of chronological order');previous=value;}}
    const affected=groups.find(group=>{const dates=group.children.map(id=>messages[g.nodes[id].message].sent??'');return dates.some(d=>d.startsWith('2026-02-04'))&&dates.some(d=>d.startsWith('2026-02-08'));});
    if(!affected)throw Error('Expected dated regression conversation not found');
    return {count:groups.length,id:affected.id,children:affected.children,label:affected.label.replace(/^Conversation: /,''),confirmed:g.nodes.filter(n=>n.evidence==='Confirmed').map(n=>[n.id,n.parent])};
   });
   assert.ok(grouped.count>0);assert.deepEqual(grouped.confirmed,confirmed,'Inference preserves every Confirmed edge');
   await page.getByRole('searchbox',{name:'Filter conversations',exact:true}).fill(grouped.label);
   await page.locator('#thread-preview-rows .conversation-summary').first().click();await ready();
   const displayed=await page.locator('#thread-conversation .thread-message').evaluateAll((rows,group)=>rows.filter(row=>group.children.includes(Number(row.dataset.threadNode))).map(row=>Number(row.dataset.threadNode)),grouped);
   assert.deepEqual(displayed,grouped.children,'Right pane keeps inferred components in chronological order');
   assert.equal(await page.locator(`#thread-conversation [data-thread-node="${grouped.id}"].missing`).count(),0,'Conversation heading is not a missing parent');
   await page.locator('#thread-conversation [aria-current=true]').focus();const selected=await page.locator('#thread-conversation [aria-current=true]').getAttribute('data-thread-message');await page.keyboard.press('ArrowDown');await page.waitForFunction(id=>document.querySelector('#thread-conversation [aria-current=true]')?.getAttribute('data-thread-message')!==id,selected);await ready();
   await page.getByRole('checkbox',{name:'Infer from matching subjects (experimental)',exact:true}).uncheck();await page.waitForFunction(()=>document.querySelector('#thread-preview').dataset.ready==='true');
  }
  await page.getByRole('searchbox',{name:'Filter conversations',exact:true}).fill('Preview');await page.getByRole('searchbox',{name:'Filter conversations',exact:true}).fill('');
  if(profile==='ci'||profile==='real'){await mkdir('test-results',{recursive:true});await page.screenshot({path:`test-results/thread-preview-${profile}.png`,fullPage:true});}
  await page.locator('#thread-preview-disable').click();assert.equal(await page.locator('#browse-panel').isVisible(),true);assert.equal(await page.locator('#thread-preview').isVisible(),false);assert.equal(await page.locator('#thread-conversation').isVisible(),false);
  if(await page.locator('#files details').count())assert.ok(await page.locator('#files details[open]').count()>0,'Returning to Messages restores file sections');
  const reads=await page.evaluate(()=>window.previewReads);await page.locator('#thread-preview-enable').click();await page.waitForFunction(()=>document.querySelector('#thread-preview').dataset.ready==='true');assert.equal(await page.evaluate(()=>window.previewReads),reads,'Cached Conversations must not reopen or reindex XMP');assert.doesNotMatch(await page.locator('#thread-preview-status').textContent(),/Reading XMP/);assert.equal(await page.locator('#files details[open]').count(),0);await page.locator('#thread-preview-disable').click();
  assert.equal(createHash('sha256').update(await readFile(path)).digest('hex'),before);reports.push({profile,status,milliseconds:Math.round(performance.now()-started),unchanged:true});
 }
 // Closing while indexing must terminate the preview and permit ordinary reopening.
 await page.locator('#thread-preview-enable').click();await page.locator('#thread-preview-disable').click();assert.equal(await page.locator('#browse-panel').isVisible(),true);
 assert.deepEqual(errors,[]);await mkdir('test-results',{recursive:true});await writeFile('test-results/thread-preview.json',JSON.stringify(reports,null,2)+'\n');await page.screenshot({path:'test-results/thread-preview.png',fullPage:true});console.log(JSON.stringify(reports));
}finally{await context.close();await rm(work,{recursive:true,force:true});}
