import assert from 'node:assert/strict';
/** Exercise real foreground clicks while the separate metadata scan is active. */
export async function checkIndexingNavigation(browser,path,until) {
 await browser.evaluate(()=>document.querySelector('#close').click());await browser.upload(path);
 await until(()=>browser.evaluate(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false'&&!document.querySelector('#page-surface').hidden&&!document.querySelector('#subject-index-progress').hidden&&document.querySelector('#subject-index-progress').dataset.complete==='false'&&/Reading subjects/.test(document.querySelector('#subject-index-status').textContent)));
 const expected=await browser.evaluate(()=>Number(document.querySelector('#message-count').textContent));
 assert.ok(expected>32,'Use a real collection with more than 32 messages');
 await browser.evaluate(()=>{document.querySelectorAll('#tree details').forEach(node=>node.open=true);const row=document.querySelector('#tree button[data-embedded-file-id]:not(:disabled)');if(!row)throw Error('No enabled message during indexing');row.scrollIntoView({block:'center'});row.click();});
 await until(()=>browser.evaluate(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false'&&!document.querySelector('#back').disabled));
 const initial=await browser.evaluate(()=>document.querySelector('#breadcrumbs').textContent);
 assert.doesNotMatch(await browser.evaluate(()=>document.querySelector('#status').textContent),/^Unable/);
 await until(()=>browser.evaluate(()=>{const text=document.querySelector('#subject-index-status').textContent;return /Subjects (ready|partially)/.test(text)||Number(/Reading subjects · (\d+)/.exec(text)?.[1]??0)>40;}));
 const progress=await browser.evaluate(()=>document.querySelector('#subject-index-status').textContent);
 assert.doesNotMatch(progress,/partially|limit reached/i,'Metadata scans must release their per-child allowance');
 const scanning=await browser.evaluate(()=>document.querySelector('#subject-index-progress').dataset.complete==='false');
 assert.equal(scanning,true,'The second click must occur before indexing completes');
 assert.equal(await browser.evaluate(()=>document.querySelector('#breadcrumbs').textContent),initial,'Background updates preserve the chosen message');
 await browser.evaluate(()=>{const rows=[...document.querySelectorAll('#tree button[data-embedded-file-id]:not(:disabled)')];const row=rows[Math.min(35,rows.length-1)];if(!row)throw Error('No later message during indexing');row.scrollIntoView({block:'center'});row.click();});
 await until(()=>browser.evaluate(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false'&&!document.querySelector('#back').disabled));
 const selected=await browser.evaluate(()=>document.querySelector('#breadcrumbs').textContent);assert.notEqual(selected,initial);
 await until(()=>browser.evaluate(()=>document.querySelector('#subject-index-progress').dataset.complete==='true'||/partially|could not finish/.test(document.querySelector('#subject-index-status').textContent)));
 assert.equal(await browser.evaluate(()=>document.querySelector('#subject-index-progress').dataset.complete),'true');
 assert.equal(await browser.evaluate(()=>document.querySelector('#breadcrumbs').textContent),selected,'Index completion does not replace foreground navigation');
 assert.equal(await browser.evaluate(()=>Number(document.querySelector('#message-count').textContent)),expected,'Every source message remains indexed');
 assert.doesNotMatch(await browser.evaluate(()=>document.querySelector('#status').textContent),/^Unable/);
 await browser.evaluate(()=>document.querySelector('#back').click());await until(()=>browser.evaluate(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false'&&document.querySelector('#back').disabled));
 await browser.evaluate(()=>{const input=document.querySelector('#message-filter');input.value='approval';input.dispatchEvent(new Event('input',{bubbles:true}));});
 await until(()=>browser.evaluate(()=>/matching|matches|emails|No /.test(document.querySelector('#filter-status').textContent)&&!document.querySelector('#filter-status').textContent.startsWith('Reading')));
 assert.ok(await browser.evaluate(()=>document.querySelectorAll('#filter-results button').length)>0,'Metadata filtering still returns real message results');
 return [`${expected} messages indexed; clicks before/after 32 scans and completion retain selection; metadata filtering passes`];
}
