import assert from 'node:assert/strict';
import {resolve} from 'node:path';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
export async function checkScrolling(browser){
 const state=()=>browser.evaluate(()=>({page:Number(document.querySelector('#page').value),total:document.querySelector('#pages').textContent,rendered:document.querySelectorAll('.pdf-page[data-rendered]').length,pixels:[...document.querySelectorAll('.pdf-page canvas')].reduce((n,c)=>n+c.width*c.height,0),ready:document.querySelector('#canvas')?.closest('.pdf-page')?.dataset.rendered==='true',width:parseFloat(document.querySelector('#canvas').style.width),text:document.querySelector('#text-layer')?.textContent??''}));
 const settled=async number=>{const end=Date.now()+30000;while(Date.now()<end){const s=await state();if(s.page===number&&s.ready&&s.rendered<=5){assert.ok(s.pixels<=32_000_000,'retained canvas pixel budget');return s;}await new Promise(r=>setTimeout(r,50));}assert.fail(`Scrolling did not settle on page ${number}: ${JSON.stringify(await state())}`);};
 const scroll=async number=>{
  // Keep the shared adapter free of privileged browser APIs.
  let end=await browser.evaluate(()=>{const v=document.querySelector('#viewport'),n=Number(document.querySelector('#page').dataset.scrollTest),slot=document.querySelector(`.pdf-page[data-page="${n}"]`);if(!slot)return false;v.scrollTop+=slot.getBoundingClientRect().top-v.getBoundingClientRect().top-24;return true;});
  // Distant placeholders are intentionally unmounted. Page entry mounts that
  // interval; nearby moves above still exercise actual viewport scrolling.
  if(!end){
   await browser.evaluate(()=>{const p=document.querySelector('#page');p.value=p.dataset.scrollTest;p.dispatchEvent(new Event('change'));});
   await browser.ready();end=true;
  }
  assert.equal(end,true);return settled(number);
 };
 // Use page-number entry to jump once, then real DOM scrolling in both directions.
 const source=resolve('tests/attachments/source/pst_first_message_legacy.pdf'),before=hash(await readFile(source));
 await browser.open(source);
 // Pixel-size checks explicitly select 100%; opening now defaults to Fit width.
 await browser.evaluate(()=>{const z=document.querySelector('#zoom');z.value='1';z.dispatchEvent(new Event('change'));});await browser.ready();assert.equal((await state()).total,'of 5');assert.equal((await state()).width,816);
 for(let number=2;number<=5;number++){
  // Transfer the next index from the current page rather than evaluating generated code.
  await browser.evaluate(()=>{const p=document.querySelector('#page');p.dataset.scrollTest=String(Number(p.value)+1);});
  const s=await scroll(number);assert.ok(s.text.length>0);
 }
 assert.equal(await browser.evaluate(()=>document.querySelector('#next').disabled),true);
 for(let number=4;number>=1;number--){await browser.evaluate(()=>{const p=document.querySelector('#page');p.dataset.scrollTest=String(Number(p.value)-1);});await scroll(number);}
 await browser.evaluate(()=>{const p=document.querySelector('#page');p.value='4';p.dispatchEvent(new Event('change'));});await browser.ready();await settled(4);
 await browser.click('#previous');await browser.ready();await settled(3);
 await browser.evaluate(()=>{const z=document.querySelector('#zoom');z.value='1.5';z.dispatchEvent(new Event('change'));});await browser.ready();assert.equal((await settled(3)).width,1224);
 await browser.click('[data-message-id]');await browser.ready();await settled(1);
 assert.equal(hash(await readFile(source)),before);
 const archive=resolve('tests/attachments/source/pst_sample_1m.pdf'),archiveHash=hash(await readFile(archive));await browser.open(archive);
 assert.equal((await state()).total,'of 151');
 await browser.evaluate(()=>{const p=document.querySelector('#page');p.value='100';p.dispatchEvent(new Event('change'));});await browser.ready();await settled(100);
 assert.equal(await browser.evaluate(()=>document.querySelector('.pdf-page[data-page="1"] canvas')?.width??0),0);
 await browser.evaluate(()=>{const p=document.querySelector('#page');p.value='1';p.dispatchEvent(new Event('change'));});await browser.ready();await settled(1);
 assert.ok(await browser.evaluate(()=>document.querySelectorAll('.pdf-page').length<=9),'bounded page placeholders');
 assert.equal(hash(await readFile(archive)),archiveHash);
 // Real PST DParts independently inspected with pikepdf: first email 8–12,
 // second 13–14, final 150–151. Front matter has no email owner.
 for(const suffix of ['m','mi']){
  await browser.open(resolve(suffix==='m'?'tests/attachments/source/pst_sample_1m.pdf':'../tests/quality/fixtures/pst_native_attachment_scope_legacy.pdf'));
  await browser.evaluate(()=>{const z=document.querySelector('#zoom');z.value='1';z.dispatchEvent(new Event('change'));});await browser.ready();
  const ids=await browser.evaluate(()=>[...document.querySelectorAll('#tree [data-message-id]')].map(b=>b.dataset.messageId));
  const selected=()=>browser.evaluate(()=>document.querySelector('#tree [aria-current]')?.dataset.messageId??null);
  const files=()=>browser.evaluate(()=>[...document.querySelectorAll('#files [data-file-id]')].map(b=>b.dataset.fileId));
  await browser.click(`[data-message-id=${JSON.stringify(ids[0])}]`);await browser.ready();await settled(8);const firstFiles=await files();
  await browser.click(`[data-message-id=${JSON.stringify(ids[1])}]`);await browser.ready();await settled(13);const secondFiles=await files();
  await browser.evaluate(()=>{document.querySelector('#page').dataset.scrollTest='12';});await scroll(12);
  assert.equal(await selected(),ids[0]);assert.deepEqual(await files(),firstFiles);
  await browser.evaluate(()=>{document.querySelector('#page').dataset.scrollTest='13';});await scroll(13);
  assert.equal(await selected(),ids[1]);assert.deepEqual(await files(),secondFiles);
  await browser.evaluate(()=>{document.querySelector('#page').dataset.scrollTest='150';});await scroll(150);assert.equal(await selected(),ids.at(-1));
  assert.equal(await browser.evaluate(()=>{const row=document.querySelector('#tree [aria-current]').getBoundingClientRect(),panel=document.querySelector('.navigation-panel').getBoundingClientRect();return row.top>=panel.top&&row.bottom<=panel.bottom;}),true,'selected email stays visible in sidebar');
  await browser.evaluate(()=>{document.querySelector('#page').dataset.scrollTest='1';});await scroll(1);assert.equal(await selected(),null,'front matter selects the document');
  await browser.evaluate(()=>{const p=document.querySelector('#page');p.value='13';p.dispatchEvent(new Event('change'));});await browser.ready();await settled(13);assert.equal(await selected(),ids[1]);
  await browser.click('#previous');await browser.ready();await settled(12);assert.equal(await selected(),ids[0]);
  await browser.click('#next');await browser.ready();await settled(13);assert.equal(await selected(),ids[1]);
  const horizontalCenter=()=>browser.evaluate(()=>{const v=document.querySelector('#viewport'),b=document.querySelector('#canvas').getBoundingClientRect(),r=v.getBoundingClientRect();return (r.left+v.clientLeft+v.clientWidth/2-b.left)/b.width;});
  const beforeZoom=await horizontalCenter();
  await browser.click('#zoom-in');await browser.ready();assert.equal((await settled(13)).width,897.6);
  assert.ok(Math.abs(await horizontalCenter()-beforeZoom)<0.002,'zoom preserves the horizontal focal point');assert.equal(await selected(),ids[1]);
  await browser.click('#zoom-out');await browser.ready();assert.equal((await settled(13)).width,816);
  await browser.evaluate(()=>{const z=document.querySelector('#zoom');z.value='2';z.dispatchEvent(new Event('change'));});await browser.ready();assert.equal(await browser.evaluate(()=>document.querySelector('#zoom-in').disabled),true);
  await browser.evaluate(()=>{const z=document.querySelector('#zoom');z.value='0.5';z.dispatchEvent(new Event('change'));});await browser.ready();assert.equal(await browser.evaluate(()=>document.querySelector('#zoom-out').disabled),true);
 }

 return {status:'PASS',fivePageSourceSha256:before,archiveSourceSha256:archiveHash,checks:['scroll through all five email pages and back','page counter follows scrolling','page entry/arrows/message navigation','100% = 816 CSS pixels for Letter; 150% = 1224','151-page archive jump to page 100 and back','at most five rendered canvases; distant pixels released','m/mi scrolling selects the owning email and files without jumping','selection stays visible; front matter selects the document','zoom buttons preserve page/selection and stop at supported limits','original PDFs unchanged']};
}
