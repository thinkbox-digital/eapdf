import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {resolve} from 'node:path';
const hash=b=>createHash('sha256').update(b).digest('hex');
export async function checkAttachments(browser){
 const manifest=JSON.parse(await readFile('tests/attachments/manifest.json','utf8')),results=[];
 const parentState=()=>browser.evaluate(()=>({path:document.querySelector('#breadcrumbs').textContent,page:document.querySelector('#page').value}));
 const preview=()=>browser.evaluate(()=>{const c=document.querySelector('#attachment-canvas');return {status:document.querySelector('#attachment-status').textContent,text:document.querySelector('#attachment-text').textContent,visible:!c.hidden,width:c.width,height:c.height,pixel:c.hidden?null:[...c.getContext('2d').getImageData(0,0,1,1).data].slice(0,3),active:!!globalThis.attachmentExecuted,markup:document.querySelectorAll('#attachment-text script,#attachment-text img,#attachment-text svg').length};});
 for(const fixture of manifest.cases){
  const source=resolve(fixture.sourcePath);assert.equal(hash(await readFile(source)),fixture.sha256);await browser.open(source);
  for(const file of fixture.attachments){
   const parent=await parentState();
   const cards=await browser.evaluate(()=>[...document.querySelectorAll('#files .file-card')].map(li=>{const save=li.querySelector('[data-file-id]');return {name:li.querySelector('strong').textContent,file:save?.dataset.fileId??null,stream:save?.dataset.stream??null,view:!!li.querySelector('[data-view-file-id]'),text:li.textContent};}));
   const card=cards.find(c=>c.name===file.name);assert.ok(card,file.name);
   const selector=`[data-view-file-id=${JSON.stringify(card.file)}][data-view-stream=${JSON.stringify(card.stream)}]`;
   if(file.name==='empty.pdf'||file.name==='binary.exe'){
    assert.equal(card.view,false);if(file.name==='empty.pdf'){assert.match(card.text,/0 B/);assert.ok(card.file,'empty files retain a download action');}else assert.doesNotMatch(card.text,/Download only/);
   }else{
    assert.equal(card.view,true,file.name+' needs a viewing action');await browser.click(selector);await browser.ready();
    if(file.pages){
     assert.equal(await browser.evaluate(()=>document.querySelector('#page').value),'1');
     assert.equal(await browser.evaluate(()=>document.querySelector('#pages').textContent),`of ${file.pages}`);
     assert.ok((await parentState()).path.includes(file.name));
     if(file.pages>1){await browser.click('#next');await browser.ready();}
     await browser.evaluate(()=>{const z=document.querySelector('#zoom');z.value='1.5';z.dispatchEvent(new Event('change'));});await browser.ready();
     const width=await browser.evaluate(()=>document.querySelector('#canvas').width);
     assert.equal(width,Math.ceil(file.pageWidths[file.pages>1?1:0]*1.5*(96/72)));
     await browser.click('#back');await browser.ready();
     await browser.evaluate(()=>{const z=document.querySelector('#zoom');z.value='1';z.dispatchEvent(new Event('change'));});await browser.ready();
    }else if(file.name==='broken.pdf')assert.match(await browser.evaluate(()=>document.querySelector('#status').textContent),/Unable to display PDF/);
    else{
     assert.equal(await browser.evaluate(()=>document.querySelector('#attachment-close').closest('.attachment-heading')!==null),true,'Close is in the preview title bar');
     assert.match(await browser.evaluate(()=>document.querySelector('#attachment-close').textContent),/Close/);
     const view=await preview();assert.equal(view.active,false);assert.equal(view.markup,0);
     if(file.width){assert.equal(view.visible,true,view.status);assert.equal(view.width,file.width);assert.equal(view.height,file.height);assert.ok(view.pixel.every((n,i)=>Math.abs(n-file.pixel[i])<=3),file.name+' pixels');}
     else if(file.name==='huge.png')assert.match(view.status,/pixel.*limit/);
     else if(file.name==='large.txt'){assert.match(view.status,/first 2 MiB/);assert.equal(view.text.length,2*1024*1024);}
     else{assert.ok(view.text.length>0);assert.match(view.status,/plain text/);}
     assert.equal(hash(await browser.download('#attachment-download')),file.sha256);await browser.click('#attachment-close');
     assert.equal(await browser.evaluate(()=>document.activeElement?.hasAttribute('data-view-file-id')),true,'closing returns focus to the View control');
    }
   }
   assert.deepEqual(await parentState(),parent,'Parent document and page must be preserved');
   const downloaded=await browser.download(`[data-file-id=${JSON.stringify(card.file)}][data-stream=${JSON.stringify(card.stream)}]`);await browser.ready();assert.equal(hash(downloaded),file.sha256);
   results.push({sourcePath:fixture.sourcePath,name:file.name,status:'PASS',sha256:file.sha256});
  }
 }
 return results;
}
