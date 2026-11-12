import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
import {mkdtemp,writeFile,readFile,rm} from 'node:fs/promises';import {join} from 'node:path';import {tmpdir} from 'node:os';
import {portfolioFixture} from './portfolio-fixtures.mjs';
// Shared assertions run against the installed Chromium and Firefox packages.
export async function checkPortfolioNavigation(browser){
 const ready=async()=>{await browser.ready();await new Promise(r=>setTimeout(r,200));await browser.ready();};
 const work=await mkdtemp(join(tmpdir(),'eapdf-portfolio-links-'));const results=[];
 const until=async fn=>{const end=Date.now()+45000;while(Date.now()<end){if(await fn())return;await new Promise(r=>setTimeout(r,100));}throw new Error('Portfolio navigation check timed out: '+await browser.evaluate(()=>document.querySelector('#status').textContent));};
 const open=async(name,options)=>{console.log('Portfolio navigation:',name);await ready();const path=join(work,name);await writeFile(path,portfolioFixture(options));await browser.open(path);await until(async()=>await browser.evaluate(n=>document.querySelector('#header-filename').textContent===n&&!document.querySelector('#file').disabled,name));};
 const linked=async(index=0,wait=true)=>{await ready();await until(async()=>await browser.evaluate(i=>!!document.querySelectorAll('.internal-links a:not(.file-attachment-link)')[i],index));await browser.evaluate(i=>document.querySelectorAll('.internal-links a:not(.file-attachment-link)')[i].click(),index);if(wait)await ready();};
 const status=()=>browser.evaluate(()=>document.querySelector('#status').textContent);
 try{
  const back=portfolioFixture({collection:false,pages:2,files:[],links:[{target:'<< /R /P >>',dest:'[0 /Fit]'}]});
  await open('named.pdf',{files:[['child.pdf',back,1]],links:[{target:'<< /R /C /N (child.pdf) >>'}]});
  assert.equal(await browser.evaluate(()=>document.body.dataset.portfolioSplit),'H','initial Details view follows the PDF default');
  let resized;for(const layout of ['FilmStrip','D']){
   await browser.evaluate(value=>{const select=document.querySelector('select[aria-label="Portfolio layout"]');select.value=value;select.dispatchEvent(new Event('change'));},layout);await ready();
   assert.equal(await browser.evaluate(()=>document.body.dataset.portfolioSplit),'V');
   assert.equal(await browser.evaluate(()=>{const pane=document.querySelector('#portfolio-panel').getBoundingClientRect(),reader=document.querySelector('.reader-pane').getBoundingClientRect();return pane.right<=reader.left+1&&Math.abs(pane.top-reader.top)<1;}),true,layout+' stays to the left of the reader');
   if(layout==='FilmStrip')resized=await browser.evaluate(()=>{document.querySelector('#portfolio-splitter').dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowRight',bubbles:true}));return document.querySelector('#workspace').style.getPropertyValue('--portfolio-size');});
  }
  assert.equal(await browser.evaluate(()=>document.querySelector('#workspace').style.getPropertyValue('--portfolio-size')),resized,'switching views preserves sidebar width');
  await browser.click('button[aria-label="Hide portfolio pane"]');await browser.click('#portfolio-show');await ready();
  assert.equal(await browser.evaluate(()=>document.body.dataset.portfolioSplit),'V');assert.equal(await browser.evaluate(()=>document.querySelector('#workspace').style.getPropertyValue('--portfolio-size')),resized);
  results.push('initial split respected; Details/Filmstrip stay left and retain width across switching and hiding');
  await linked();assert.match(await status(),/child.pdf.*Page 2 of 2/);results.push('named destination in child');
  await browser.evaluate(()=>{const p=document.querySelector('#page');p.value='1';p.dispatchEvent(new Event('change'));});await ready();await linked();assert.match(await status(),/named.pdf.*Page 1 of 1/);results.push('return to parent');
  await browser.click('#portfolio-outline-panel summary');await browser.click('#portfolio-outline button');await ready();assert.match(await status(),/child.pdf.*Page 2 of 2/);results.push('outline embedded action');
  const nested=portfolioFixture({files:[['leaf.pdf',back,1]]});
  await open('nested.pdf',{files:[['inner.pdf',nested,1]],links:[{target:'<< /R /C /N (inner.pdf) /T << /R /C /N (leaf.pdf) >> >>'}]});await linked();assert.match(await status(),/leaf.pdf.*Page 2 of 2/);results.push('nested collection path');
  await open('annotation.pdf',{files:[['child.pdf',back,1]],attachments:[{fileIndex:0,name:'attached'}],links:[{target:'<< /R /C /P 0 /A 0 >>'},{target:'<< /R /C /P (chapter) /A (attached) >>'}]});await linked(1);assert.match(await status(),/child.pdf.*Page 2 of 2/);results.push('named annotation target');
  await open('annotation-index.pdf',{files:[['child.pdf',back,1]],attachments:[{fileIndex:0,name:'attached'}],links:[{target:'<< /R /C /P 0 /A 0 >>'}]});await linked();assert.match(await status(),/child.pdf.*Page 2 of 2/);results.push('annotation index target');
  await open('broken.pdf',{links:[{target:'<< /R /C /N (missing.pdf) >>'}]});await linked();assert.match(await status(),/missing or ambiguous/);results.push('broken target stays in source');
  const plain=join(work,'plain.pdf'),encrypted=join(work,'encrypted.pdf');await writeFile(plain,back);
  execFileSync(process.env.M1_PYTHON??'python3',['-B','-c',"import pikepdf,sys\nwith pikepdf.Pdf.open(sys.argv[1]) as p:p.save(sys.argv[2],encryption=pikepdf.Encryption(owner='owner-test',user='reader-test',R=6))",plain,encrypted]);
  await open('encrypted-member.pdf',{files:[['protected.pdf',await readFile(encrypted),1]],links:[{target:'<< /R /C /N (protected.pdf) >>'}]});
  await linked(0,false);await until(async()=>await browser.evaluate(()=>!!document.querySelector('dialog[open] input[type=password]')));
  await browser.evaluate(()=>{document.querySelector('dialog[open] input').value='wrong';document.querySelector('dialog[open] form').requestSubmit();});await until(async()=>await browser.evaluate(()=>document.querySelector('#pdf-password-title')?.textContent.includes('Incorrect')));
  await browser.evaluate(()=>[...document.querySelectorAll('dialog[open] button')].find(b=>b.textContent==='Cancel').click());await ready();assert.equal(await browser.evaluate(()=>document.querySelector('#breadcrumbs button:last-child').textContent),'encrypted-member.pdf');assert.equal(await browser.evaluate(()=>!!document.querySelector('dialog[open] input[type=password]')),false);
  await linked(0,false);await until(async()=>await browser.evaluate(()=>!!document.querySelector('dialog[open] input[type=password]')));await browser.evaluate(()=>{document.querySelector('dialog[open] input').value='reader-test';document.querySelector('dialog[open] form').requestSubmit();});await ready();assert.match(await status(),/protected.pdf.*Page 2 of 2/);results.push('encrypted member: incorrect password, cancellation, retry and named destination');
  await open('folder-thumbnail.pdf',{view:'T',folderThumbnail:true});await until(async()=>await browser.evaluate(()=>!!document.querySelector('.stored-thumbnail img')));results.push('stored folder thumbnail');
  const before=await browser.evaluate(()=>document.querySelector('#portfolio-panel').getBoundingClientRect().width);await browser.evaluate(()=>document.querySelector('#portfolio-splitter').dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowRight',bubbles:true})));assert.ok(await browser.evaluate(()=>document.querySelector('#portfolio-panel').getBoundingClientRect().width)>before);results.push('adjustable split');
  await browser.evaluate(()=>[...document.querySelectorAll('[data-portfolio-row]')].find(b=>b.textContent==='Illustrated folder').click());assert.match(await browser.evaluate(()=>document.querySelector('.portfolio-breadcrumbs').textContent),/Illustrated folder/);
  await browser.click('button[aria-label="Previous folder"]');assert.equal(await browser.evaluate(()=>document.querySelectorAll('[data-portfolio-row]').length),3);await browser.click('button[aria-label="Next folder"]');assert.equal(await browser.evaluate(()=>document.querySelectorAll('[data-portfolio-row]').length),0);await browser.click('button[aria-label="Up one folder"]');results.push('folder breadcrumbs, history and Up');
  await open('keyboard.pdf',{view:'T',files:[['first.pdf',back,2],['second.pdf',portfolioFixture({collection:false,files:[]}),1]]});
  await browser.evaluate(()=>document.querySelector('[data-portfolio-row]').click());await ready();assert.match(await status(),/first.pdf/);
  await browser.evaluate(()=>document.activeElement.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowDown',bubbles:true})));await ready();assert.match(await status(),/second.pdf/);assert.equal(await browser.evaluate(()=>document.activeElement.hasAttribute('data-portfolio-row')),true);
  await browser.click('button[aria-label="Previous file"]');await ready();assert.match(await status(),/first.pdf/);results.push('mouse focus, arrow-key opening and Previous file');
  await open('large-collection.pdf',{files:Array.from({length:1200},(_,i)=>[`member-${String(i).padStart(4,'0')}.txt`,'payload',i])});assert.equal(await browser.evaluate(()=>document.querySelectorAll('[data-portfolio-row]').length),100);await browser.evaluate(()=>{const q=document.querySelector('input[aria-label="Filter portfolio files and metadata"]');q.value='member-1199';q.dispatchEvent(new Event('input'));});await until(async()=>await browser.evaluate(()=>document.querySelectorAll('[data-portfolio-row]').length===1));results.push('1200-member bounded listing and filtering');
  const replacement=[...portfolioFixture({collection:false,files:[]})];
  const wasBusy=await browser.evaluate(bytes=>{const zoom=document.querySelector('#zoom');zoom.value='2';zoom.dispatchEvent(new Event('change'));const busy=document.querySelector('#viewport').getAttribute('aria-busy')==='true';const transfer=new DataTransfer();transfer.items.add(new File([new Uint8Array(bytes)],'selected-during-render.pdf',{type:'application/pdf'}));const input=document.querySelector('#file');input.files=transfer.files;input.dispatchEvent(new Event('change'));return busy;},replacement);
  assert.equal(wasBusy,true);await until(async()=>await browser.evaluate(()=>document.querySelector('#header-filename').textContent==='selected-during-render.pdf'&&document.querySelector('#viewport').getAttribute('aria-busy')==='false'));results.push('file selection during an active render is queued');
  return results;
 }finally{await rm(work,{recursive:true,force:true});}
}
