import {chromium} from 'playwright';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {mkdir,writeFile,stat} from 'node:fs/promises';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
if(!process.env.EAPDF_LARGE_PDF)throw new Error('Set EAPDF_LARGE_PDF to a real generated archive.');
const input=resolve(process.env.EAPDF_LARGE_PDF),extension=resolve(process.env.EAPDF_VIEWER_EXTENSION??'dist/chromium'),report={file:input,bytes:(await stat(input)).size,events:[],errors:[]};
const context=await chromium.launchPersistentContext('',{headless:true,executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
const start=Date.now();let page;
try{
 await context.setOffline(true);const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker');page=await context.newPage();page.on('pageerror',e=>report.errors.push(e.message));page.on('crash',()=>report.errors.push('Browser tab crashed'));page.on('console',m=>{if(/XREF|XRef|Indexing all|Warning:/.test(m.text()))console.log('PDFJS: '+m.text());});
 await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
 page.on('console',m=>{if(m.type()==='info'&&m.text().startsWith('LOAD:')){const event={ms:Date.now()-start,text:m.text().slice(5)};report.events.push(event);console.log(JSON.stringify(event));}});
 await page.evaluate(()=>{let last='';new MutationObserver(()=>{const text=document.querySelector('#status').textContent;if(text!==last){last=text;console.info('LOAD:'+text);}}).observe(document.querySelector('#status'),{childList:true,subtree:true,characterData:true});});
 await page.evaluate(async()=>{const {BrowserSession}=await import('./browser-session.js');window.archiveMetrics={reads:0,restarts:0,reactivations:0};for(const [method,key]of [['readBytes','reads'],['restartExtractionWorker','restarts']]){const original=BrowserSession.prototype[method];BrowserSession.prototype[method]=async function(...args){window.archiveMetrics[key]++;if(key==='restarts'||window.archiveMetrics.reads%1000===0)console.info('LOAD:'+JSON.stringify(window.archiveMetrics));return original.apply(this,args);};}const ensure=BrowserSession.prototype.ensure;BrowserSession.prototype.ensure=async function(doc){if(!doc.active)window.archiveMetrics.reactivations++;return ensure.call(this,doc);};const associate=BrowserSession.prototype.associateFiles;BrowserSession.prototype.associateFiles=async function(doc){const result=await associate.call(this,doc);window.associatedSummary={documentId:doc.inspection.documentId,messages:doc.inspection.messages.length,files:doc.inspection.files.length,coverage:doc.inspection.coverage,diagnostics:doc.inspection.diagnostics.map(d=>d.code)};return result;};const open=BrowserSession.prototype.open;BrowserSession.prototype.open=async function(...args){try{const doc=await open.apply(this,args);window.openedSummary={documentId:doc.inspection.documentId,messages:doc.inspection.messages.length,files:doc.inspection.files.length,coverage:doc.inspection.coverage,diagnostics:doc.inspection.diagnostics.map(d=>d.code)};return doc;}catch(e){window.openError={message:e.message,name:e.name,stack:e.stack,details:e.details};throw e;}};});
 await page.locator('#file').setInputFiles(input);
 await page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false',null,{timeout:Number(process.env.EAPDF_OPEN_TIMEOUT_MS??600000)});
 report.openError=await page.evaluate(()=>window.openError);
 report.inspection=await page.evaluate(()=>window.associatedSummary??window.openedSummary);
 report.metrics=await page.evaluate(()=>window.archiveMetrics);
 report.display=await page.evaluate(()=>({status:document.querySelector('#status').textContent,dialog:document.querySelector('#loading-dialog').open,canvasVisible:!document.querySelector('#canvas').hidden,controlsEnabled:!document.querySelector('#file').disabled,indexing:document.querySelector('#subject-index-status').textContent}));
 report.status=report.display.canvasVisible&&!report.display.dialog&&!report.display.status.startsWith('Unable')?'PASS':'FAIL';
 if(process.env.EAPDF_EXPECTED_MESSAGES&&(report.inspection?.messages!==Number(process.env.EAPDF_EXPECTED_MESSAGES)||report.inspection?.coverage.metadata!=='present'))report.status='FAIL';
 if(await page.locator('#subject-index-cancel').isVisible())await page.locator('#subject-index-cancel').click();
 if(await page.locator('#loading-dialog').isVisible())await page.locator('#loading-cancel').click();else await page.locator('#close').click();
}catch(error){report.status='FAIL';report.errors.push(String(error));if(page)try{Object.assign(report,await Promise.race([page.evaluate(()=>({openError:window.openError,inspection:window.associatedSummary??window.openedSummary,metrics:window.archiveMetrics})),new Promise(resolve=>setTimeout(()=>resolve({}),5000))]));}catch{}}
finally{report.elapsedMs=Date.now()-start;await context.close();await mkdir('test-results',{recursive:true});await writeFile('test-results/large-opening.json',JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));}
if(report.status!=='PASS')process.exitCode=1;
