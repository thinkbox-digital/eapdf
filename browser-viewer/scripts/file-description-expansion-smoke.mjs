import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {resolve} from 'node:path';

const extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,
  executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',
  args:[`--load-extension=${extension}`,`--disable-extensions-except=${extension}`]});
try{
  const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker');
  const page=await context.newPage();
  await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
  const descriptions=[
    'Exported RFC 822 conversion source. MBOX preprocessing: extracted email.\nComplete provenance and checksum: abc123.',
    'A long attachment description. '.repeat(30)+'\nContents omitted by policy. 日本語 <script>not executable</script>',
    'Original email source',
    'A moderately long description that fits a wide panel but needs expansion in a narrow panel. '.repeat(2)
  ];
  await page.evaluate(async descriptions=>{
    const {FilesPanel}=await import('./files-panel.js');
    const files=descriptions.map((_,i)=>({id:String(i),filename:{value:`file-${i}.eml`},unicodeFilename:{value:null},
      afRelationship:{value:i?'Data':'Source'},owners:{value:[]},locator:{path:String(i)},streams:[]}));
    const doc={parent:null,details:new Map(),inspection:{files,messages:[],collection:{value:null},
      diagnostics:[],identification:{declaredProfile:{value:'s'}},coverage:{associatedFiles:'present'}}};
    const panel=new FilesPanel({files:document.querySelector('#files'),count:document.querySelector('#file-count'),
      title:document.querySelector('#files-title'),details:document.querySelector('#selection-details')},
      {busy:()=>false,conversationsEnabled:()=>false,index:()=>[],singleMessage:()=>undefined,download:()=>{},view:()=>{}});
    document.querySelector('#files').style.width='900px';
    panel.setDescriptions(doc,new Map(descriptions.map((value,i)=>[String(i),value])));panel.show(doc);window.descriptionPanel=panel;window.descriptionDoc=doc;
  },descriptions);
  const disclosures=page.locator('.file-description-disclosure');
  assert.equal(await disclosures.count(),4);
  assert.match(await page.locator('.file-description-block').first().locator('.file-description').innerText(),/Email source extracted from MBOX/);
  await disclosures.nth(1).waitFor({state:'visible'});
  assert.equal(await disclosures.nth(2).isVisible(),false);
  assert.equal(await disclosures.nth(3).isVisible(),false);
  for(let i=0;i<2;i++){
    const item=disclosures.nth(i),summary=item.locator('summary'),full=item.locator('.file-description-full');
    assert.equal(await full.isVisible(),false);
    await summary.focus();await page.keyboard.press('Enter');
    assert.equal(await full.isVisible(),true);
    assert.equal(await full.textContent(),descriptions[i]);
    assert.equal(await item.locator('script').count(),0);
    assert.equal(await full.evaluate(el=>el.scrollHeight<=el.clientHeight+1),true,'Expanded description is not clipped');
    assert.equal(await item.locator('.description-less').isVisible(),true);
    await summary.click();assert.equal(await full.isVisible(),false);
  }
  await page.locator('#files').evaluate(el=>el.style.width='180px');
  await disclosures.nth(3).waitFor({state:'visible'});
  await disclosures.nth(3).locator('summary').click();
  assert.equal(await disclosures.nth(3).locator('.file-description-full').textContent(),descriptions[3]);
  await page.locator('#files').evaluate(el=>el.style.width='900px');
  await disclosures.nth(3).locator('summary').click();
  await disclosures.nth(3).waitFor({state:'hidden'});
  await page.evaluate(()=>{
    const doc=window.descriptionDoc,prototype=doc.inspection.files[1];
    doc.inspection.files=Array.from({length:300},(_,i)=>({...prototype,id:String(i),filename:{value:`virtual-${i}.eml`},locator:{path:String(i)}}));
    window.descriptionPanel.setDescriptions(doc,new Map(doc.inspection.files.map(f=>[f.id,'Long description content that needs disclosure. '.repeat(40)])));
    window.descriptionPanel.show(doc);
    document.querySelector('#details-panel').scrollTop=document.querySelector('#details-panel').scrollHeight;
  });
  await page.waitForFunction(()=>[...document.querySelectorAll('.file-card')].some(row=>
    Number(row.dataset.associatedFileId)>100&&row.querySelector('.file-description-disclosure:not([hidden])')));
  assert.ok(await page.locator('.file-description-disclosure:not([hidden])').count()>0);
  console.log('PASS: full stored descriptions, simplified previews, Unicode, inert text, keyboard expansion and collapse.');
}finally{await context.close();}
