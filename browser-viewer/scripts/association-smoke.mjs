import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
const extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
try{
  await context.setOffline(true);
  const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker');
  const page=await context.newPage();
  await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
  await page.evaluate(()=>{const input=document.createElement('input');input.type='file';input.id='test-source';document.body.append(input);});
  await page.locator('#test-source').setInputFiles(resolve('../examples/mbox/mbox_enron_1m.pdf'));
  const results=await page.evaluate(async()=>{
    const {BrowserSession}=await import('./browser-session.js');
    const file=document.querySelector('#test-source').files[0],results=[];
    for(const fail of [false,true]){
      const variants=[];
      for(const eager of [false,true]){
        const session=new BrowserSession();
        try{
          const doc=await session.open(file,file.name);
          // Force fallback ownership resolution even when full DParts resolve it.
          // Limit the eager reference to four real sources; reparsing the full
          // archive after all 1,433 reads is the regression under investigation.
          doc.inspection={...doc.inspection,files:doc.inspection.files.filter(f=>['Source','Supplement'].includes(f.afRelationship.value??'')).slice(0,4).map(f=>({...f,owners:{...f.owners,value:null,state:'unavailable'}}))};
          let reads=0,parses=0;
          const parse=DOMParser.prototype.parseFromString;
          DOMParser.prototype.parseFromString=function(...args){parses++;return parse.apply(this,args);};
          const read=session.readBytes.bind(session);
          session.readBytes=async(...args)=>{reads++;if(fail&&reads===1)throw new Error('Injected extraction failure');if(eager)args[3]=true;return read(...args);};
          try{await session.associateFiles(doc);}finally{DOMParser.prototype.parseFromString=parse;}
          variants.push({reads,parses,inspection:doc.inspection,details:[...doc.details]});
        }finally{session.close();}
      }
      results.push({fail,variants});
    }
    return results;
  });
  for(const {fail,variants:[batched,eager]}of results){
    assert.ok(batched.reads>1,'real fixture must exercise multiple source-email reads');
    assert.deepEqual(batched.inspection,eager.inspection,'identical associations, hashes and diagnostics');
    assert.deepEqual(batched.details,eager.details,'identical metadata details');
    assert.ok(batched.parses<eager.parses,'batch avoids repeated XML parsing');
    if(fail)assert.ok(batched.inspection.files.some(f=>f.streams.some(s=>s.sha256.state==='unavailable')),'failed extraction remains unavailable');
    console.log(`PASS: ${fail?'failed extraction':'successful extraction'}; ${batched.reads} reads, XML parses ${eager.parses} -> ${batched.parses}; identical inspection and details.`);
  }
}finally{await context.close();}
