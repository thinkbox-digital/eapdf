import {chromium} from 'playwright';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {mkdir,writeFile,readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
const extension=resolve('dist/chromium'),input=resolve(process.env.SEARCH_PDF??'../out/pst/pst_21_04+April+2003+Public+2/pst_21_04+April+2003+Public+2_1c.pdf'),baseline=process.env.SEARCH_BASELINE==='1';
const context=await chromium.launchPersistentContext('',{headless:true,executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
try{
 const service=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage();await context.setOffline(true);await page.goto(`chrome-extension://${new URL(service.url()).host}/viewer.html`);
 await page.evaluate(()=>{const i=document.createElement('input');i.id='benchmark-input';i.type='file';document.body.append(i);});await page.locator('#benchmark-input').setInputFiles(input);
 const reports=await page.evaluate(async baseline=>{
  const {BrowserSession}=await import('./browser-session.js'),{indexMessages}=await import('./message-index.js'),{searchCollection}=await import('./collection-search.js');const blob=document.querySelector('#benchmark-input').files[0],reports=[];
  for(const mode of ['metadata','text']){
   const session=new BrowserSession({},{reuseWorker:!baseline});let timer;
   try{
    const root=await session.open(blob,blob.name);let refreshes=0,children=0,released=0,progress=0,cancelled=false;
    const refresh=session.refresh.bind(session),child=session.child.bind(session),release=session.release.bind(session);
    session.refresh=doc=>{refreshes++;return refresh(doc);};session.child=async(...args)=>{const d=await child(...args);children++;return d;};session.release=async doc=>{await release(doc);released++;};
    const check=()=>{if(cancelled)throw Error('Baseline observation ended after 20 seconds');};
    const started=performance.now();if(baseline)timer=setTimeout(()=>{cancelled=true;},20000);
    let result,error;try{result=mode==='metadata'?await indexMessages(session,root,check,n=>{progress=n;}):await searchCollection(session,root,'medicaid',check,n=>{progress=n;});}catch(e){error=e.message;}
    reports.push({mode,elapsedMs:performance.now()-started,refreshes,children,released,progress,error,count:result?.messages?.length??result?.matches?.length,incomplete:result?.incomplete});
   }finally{clearTimeout(timer);session.close();}
  }
  return reports;
 },baseline);
 const result={input,sha256:createHash('sha256').update(await readFile(input)).digest('hex'),baseline,reports};await mkdir('test-results',{recursive:true});await writeFile(`test-results/search-${baseline?'before':'after'}.json`,JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
}finally{await context.close();}
