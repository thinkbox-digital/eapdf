import assert from 'node:assert/strict';
import {access,mkdtemp,writeFile,rm,readFile} from 'node:fs/promises';
import {resolve,join} from 'node:path';import {tmpdir} from 'node:os';import {createHash} from 'node:crypto';
import {portfolioFixture} from './portfolio-fixtures.mjs';
export async function checkPortfolioPresentation(browser){
 const work=await mkdtemp(join(tmpdir(),'eapdf-portfolio-presentation-'));
 const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
 const ready=async()=>{await browser.ready();await new Promise(r=>setTimeout(r,250));await browser.ready();};
 const select=async value=>{await ready();await browser.evaluate(layout=>{const select=document.querySelector('select[aria-label="Portfolio layout"]');select.value=layout;select.dispatchEvent(new Event('change'));},value);await ready();};
 const width=()=>browser.evaluate(()=>document.querySelector('#portfolio-panel').getBoundingClientRect().width);
 try{
  let path=resolve('../in/portfolio/CheatSheetCollection.pdf');try{await access(path);}catch{path=join(work,'presentation.pdf');await writeFile(path,portfolioFixture({view:'T'}));}
  const before=hash(await readFile(path));await browser.evaluate(()=>localStorage.removeItem('eapdf.portfolio.width'));await ready();await browser.open(path);await ready();assert.equal(await browser.evaluate(()=>document.querySelector('#zoom').value),'fit-page');
  await select('D');assert.ok(await width()>=400&&await width()<=450,'Default sidebar width should be about 425px');
  const compact=await browser.evaluate(()=>({headers:[...document.querySelectorAll('.portfolio-items th button')].map(n=>n.textContent),overflow:document.querySelector('.portfolio-scroll').scrollWidth>document.querySelector('.portfolio-scroll').clientWidth,heights:[...document.querySelectorAll('.portfolio-items tbody tr')].map(n=>n.getBoundingClientRect().height)}));
  assert.deepEqual(compact.headers,['File name','Size'].map((n,i)=>i===0&&path.endsWith('CheatSheetCollection.pdf')?'Name':n));assert.equal(compact.overflow,false);assert.ok(compact.heights.every(n=>n<=58),'Compact rows should not wrap');
  if(path.endsWith('CheatSheetCollection.pdf')){
   await browser.evaluate(()=>[...document.querySelectorAll('[data-portfolio-row]')].find(n=>n.textContent==='OperatorsAndOperands.pdf').click());await ready();
   assert.equal(await browser.evaluate(()=>document.querySelector('.portfolio-selection').open),true);assert.match(await browser.evaluate(()=>document.querySelector('.portfolio-selection').textContent),/Content stream operators and operands/);
   const selected=await browser.evaluate(()=>{const b=document.querySelector('[data-portfolio-row][aria-current]');b.focus();return {name:b.getAttribute('aria-label'),tip:getComputedStyle(b,'::after').content};});assert.equal(selected.name,'OperatorsAndOperands.pdf');assert.ok(selected.tip.includes(selected.name),'Keyboard focus exposes the full filename');
   await browser.screenshot?.('details-selected');await browser.click('.portfolio-selection>summary');
   await browser.evaluate(()=>{const input=[...document.querySelectorAll('.portfolio-columns label')].find(n=>n.textContent==='Topics').querySelector('input');input.checked=true;input.dispatchEvent(new Event('change'));});assert.ok(await browser.evaluate(()=>[...document.querySelectorAll('.portfolio-items th')].some(n=>n.textContent==='Topics')));
   await browser.evaluate(()=>{const input=[...document.querySelectorAll('.portfolio-columns label')].find(n=>n.textContent==='Topics').querySelector('input');input.checked=false;input.dispatchEvent(new Event('change'));});
  }
  await browser.screenshot?.('details-narrow');
  await select('FilmStrip');await browser.evaluate(()=>{const bar=document.querySelector('#portfolio-splitter');for(let i=0;i<2;i++)bar.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowRight',shiftKey:true,bubbles:true}));});await ready();
  const film=await browser.evaluate(()=>({height:document.querySelector('.portfolio-card').getBoundingClientRect().height,metadata:document.querySelectorAll('.portfolio-card dl').length}));
  assert.equal(film.metadata,0);assert.ok(film.height<=90,`Filmstrip card should be compact: ${film.height}px`);
  await browser.screenshot?.('filmstrip');
  await select('Linear');const linear=await browser.evaluate(()=>{const metadata=document.querySelector('.portfolio-selection'),reader=document.querySelector('.reader-pane').getBoundingClientRect();return {height:metadata.getBoundingClientRect().height,sideBySide:metadata.getBoundingClientRect().right<=reader.left+1,fields:metadata.querySelectorAll('dt').length,perFileMetadata:document.querySelectorAll('.portfolio-items dl').length};});
  assert.equal(linear.perFileMetadata,0,'Linear must not repeat metadata for every file');assert.ok(linear.sideBySide,'Selected metadata sits beside the large reader preview');
  if(path.endsWith('CheatSheetCollection.pdf')){
   assert.ok(linear.fields>0);assert.match(await browser.evaluate(()=>document.querySelector('.portfolio-selection summary').textContent),/OperatorsAndOperands/);
   await browser.click('[aria-label="Basics.pdf"][data-portfolio-row]');await ready();assert.match(await browser.evaluate(()=>document.querySelector('.portfolio-selection').textContent),/Basic information about PDF/);assert.match(await browser.evaluate(()=>document.querySelector('#status').textContent),/Basics.pdf/);
   await browser.evaluate(()=>document.activeElement.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowDown',bubbles:true})));await ready();assert.match(await browser.evaluate(()=>document.querySelector('.portfolio-selection summary').textContent),/Color.pdf/);assert.match(await browser.evaluate(()=>document.querySelector('#status').textContent),/Color.pdf/);
   await browser.click('[aria-label="Cover page"]');await ready();assert.equal(await browser.evaluate(()=>document.querySelector('.portfolio-selection').hidden),true,'Cover must not retain metadata for a different file');
   await browser.click('[aria-label="Basics.pdf"][data-portfolio-row]');await ready();
  }
  await browser.screenshot?.('preview-details');
  await select('FreeForm');
  const scatter=()=>browser.evaluate(()=>{const board=document.querySelector('.portfolio-scatter'),bounds=board.getBoundingClientRect();return {width:bounds.width,height:bounds.height,cards:[...board.querySelectorAll('.portfolio-card')].map(c=>{const r=c.getBoundingClientRect();return {id:c.dataset.member,x:r.left-bounds.left,y:r.top-bounds.top,width:r.width,height:r.height};})};});
  const checkScatter=state=>{assert.ok(state.cards.length>0);for(const r of state.cards){assert.ok(r.x>=-1&&r.y>=-1&&r.x+r.width<=state.width+1&&r.y+r.height<=state.height+1,'Cards stay within the board: '+JSON.stringify({r,width:state.width,height:state.height}));for(const other of state.cards)if(r.id!==other.id)assert.ok(r.x+r.width<=other.x+1||other.x+other.width<=r.x+1||r.y+r.height<=other.y+1||other.y+other.height<=r.y+1,'Thumbnails must not overlap');}};
  const scattered=await scatter();checkScatter(scattered);assert.ok(new Set(scattered.cards.map(c=>Math.round(c.x))).size>1,'Freeform has varied positions');
  if(path.endsWith('CheatSheetCollection.pdf')){await browser.click('.portfolio-thumbnail-button[aria-label="View Color.pdf"]');await ready();assert.match(await browser.evaluate(()=>document.querySelector('#status').textContent),/Color.pdf/);const after=(await scatter()).cards;assert.ok(after.every((c,i)=>c.id===scattered.cards[i].id&&Math.abs(c.x-scattered.cards[i].x)<1&&Math.abs(c.y-scattered.cards[i].y)<1),'Selection must not reshuffle thumbnails');}
  await browser.drag('#portfolio-splitter',-130,0);await ready();checkScatter(await scatter());await browser.screenshot?.('freeform');
  await select('D');const table=await browser.evaluate(()=>{const lines=element=>{const range=document.createRange();range.selectNodeContents(element);return range.getClientRects().length;};return {headers:[...document.querySelectorAll('.portfolio-items th button')].map(lines),dates:[...document.querySelectorAll('td[data-field-type=ModDate],td[data-field-type=CreationDate],td[data-field-type=Size]')].filter(n=>n.textContent).map(lines),scroll:document.querySelector('.portfolio-scroll').scrollWidth>document.querySelector('.portfolio-scroll').clientWidth};});
  assert.ok(table.headers.every(n=>n===1),'Headers must stay on one line');assert.ok(table.dates.every(n=>n===1),'Dates and sizes must stay on one line');assert.equal(table.scroll,false,'Default Details fits the pane');
  await browser.screenshot?.('details');
  const start=await width();await browser.drag('#portfolio-splitter',100,0);await ready();const dragged=await width();assert.ok(dragged>start+85,'Dragging the separator must widen the pane');
  await browser.evaluate(()=>{const bar=document.querySelector('#portfolio-splitter');bar.focus();bar.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowLeft',bubbles:true}));});await ready();assert.ok(await width()<dragged-10,'ArrowLeft must resize the focused separator');
  const retained=await width();await select('FilmStrip');assert.ok(Math.abs(await width()-retained)<2,'Layout switch must preserve dragged width');
  assert.equal(await browser.evaluate(()=>document.querySelector('#portfolio-splitter').getAttribute('aria-orientation')),'vertical');
  await browser.click('button[aria-label="Hide portfolio pane"]');assert.equal(await browser.evaluate(()=>document.querySelector('#portfolio-splitter').hidden),true);await browser.click('#portfolio-show');await ready();assert.ok(Math.abs(await width()-retained)<2);
  await browser.click('#portfolio-toggle');await ready();assert.equal(await browser.evaluate(()=>document.querySelectorAll('#portfolio-splitter').length),0,'Closing Portfolio removes its separator');
  await browser.open(path);await ready();await select('D');assert.ok(Math.abs(await width()-retained)<2,'Reopening restores the chosen width');
  assert.equal(hash(await readFile(path)),before);
  const horizontal=join(work,'horizontal.pdf');await writeFile(horizontal,portfolioFixture({view:'D'}));await browser.open(horizontal);await ready();assert.equal(await browser.evaluate(()=>document.querySelector('#portfolio-splitter').getAttribute('aria-orientation')),'horizontal');
  const height=await browser.evaluate(()=>document.querySelector('#portfolio-panel').getBoundingClientRect().height);await browser.drag('#portfolio-splitter',0,-50);await ready();assert.ok(await browser.evaluate(()=>document.querySelector('#portfolio-panel').getBoundingClientRect().height)<height-35);
  return {status:'PASS',filmstrip_height:film.height,selected_metadata_height:linear.height,checks:['distinct compact presentations','selected metadata follows mouse, keyboard and cover navigation','stable non-overlapping Freeform thumbnails with native resize and click opening','narrow Name/Size defaults and compact rows','optional columns and selected-file metadata','full filename on keyboard focus','width persisted across reopening','native horizontal and vertical divider dragging','keyboard resizing','width retention and separator cleanup'],source_sha256:before};
 }finally{await rm(work,{recursive:true,force:true});}
}
