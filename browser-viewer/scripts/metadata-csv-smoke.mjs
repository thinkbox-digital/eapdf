import {createServer} from 'node:http';
import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
const extension=resolve('dist/chromium');
// HTTP mode exercises the built viewer when the cached browser is older than
// the extension manifest minimum. The default still tests the extension.
let server;
if(process.env.CSV_TEST_HTTP){
 server=createServer(async(req,res)=>{
  const path=resolve(extension,'.'+new URL(req.url,'http://localhost').pathname);
  if(!path.startsWith(extension+'/')){res.writeHead(403).end();return;}
  try{res.setHeader('Content-Type',path.endsWith('.js')||path.endsWith('.mjs')?'text/javascript':path.endsWith('.css')?'text/css':path.endsWith('.html')?'text/html':'application/octet-stream');res.end(await readFile(path));}
  catch{res.writeHead(404).end();}
 });
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
}
const context=await chromium.launchPersistentContext('',{headless:true,acceptDownloads:true,
 ...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{channel:'chromium'}),
 args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
try{
 let url;
 if(server)url=`http://127.0.0.1:${server.address().port}/viewer.html`;
 else{await context.setOffline(true);const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker');url=`chrome-extension://${new URL(worker.url()).host}/viewer.html`;}
 const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(url);
 const ready=()=>page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false',null,{timeout:60000});
 await page.waitForFunction(()=>!document.querySelector('#file').disabled);
 assert.equal(await page.locator('#export-csv').isDisabled(),true);
 for(const profile of ['1s','1mi','1c','1ci']){
  const stem='eml_Agenda_and_timeline_slides'+(profile==='1s'?'':'_archive')+'_'+profile;
  await page.locator('#file').setInputFiles(resolve('../examples/eml/'+stem+'.pdf'));await ready();
  const download=page.waitForEvent('download');await page.locator('#export-csv').click();await page.locator('#metadata-save').click();const saved=await download;await ready();
  const csv=await readFile(await saved.path(),'utf8');assert.match(csv,/"From","To","Cc","Bcc","Subject","Sent"/);
  assert.match(await page.locator('#status').textContent(),/^Exported finding aid for [1-9]\d* messages\.$/);
  assert.equal(saved.suggestedFilename(),stem+'-finding-aid.csv');
  assert.equal(await page.locator('#export-csv').isEnabled(),true);
  console.log(`PASS real ${profile} CSV download (${Buffer.byteLength(csv)} bytes)`);
 }
 const parsed=await page.evaluate(async()=>{
  const {parseXmp,NS}=await import('./domain/xmp.js');
  const subject='Long subject '.repeat(800);
  const xml=`<rdf:RDF xmlns:rdf="${NS.rdf}" xmlns:e="${NS.meta}" xmlns:foaf="http://xmlns.com/foaf/0.1/"><rdf:Description><e:email><rdf:Bag><rdf:li rdf:parseType="Resource"><e:Subject>${subject}</e:Subject><e:Bcc><rdf:Bag><rdf:li rdf:parseType="Resource"><foaf:name>Hidden</foaf:name><foaf:mbox rdf:resource="mailto:hidden@example.org"/></rdf:li></rdf:Bag></e:Bcc></rdf:li></rdf:Bag></e:email></rdf:Description></rdf:RDF>`;
  const result=parseXmp(xml,{documentId:'test',path:'xmp',objectNumber:null,generation:null},s=>new DOMParser().parseFromString(s,'application/xml'));
  return [...result.details.values()][0];
 });
 assert.equal(parsed.Subject,'Long subject '.repeat(800));assert.equal(parsed.Bcc,'Hidden <hidden@example.org>');
 await page.locator('#file').setInputFiles(resolve('tests/fixtures/ordinary.pdf'));await ready();
 assert.equal(await page.locator('#export-csv').isVisible(),false);
 const manifest=JSON.parse(await readFile('tests/inspection/manifest.json','utf8'));
 const corrupt=manifest.fixtures.find(f=>f.id==='ci-corrupt-child');
 await page.locator('#file').setInputFiles(resolve('..',corrupt.sourcePath));await ready();
 let downloaded=false;page.on('download',()=>{downloaded=true;});
 await page.locator('#export-csv').click();await page.locator('#metadata-save').click();await ready();assert.match(await page.locator('#status').textContent(),/^Metadata export failed:/);assert.equal(downloaded,false);
 assert.deepEqual(errors,[]);console.log('PASS full-length metadata, Bcc, ordinary PDF, and corrupt collection handling; no page errors.');
}finally{await context.close();if(server)await new Promise(r=>server.close(r));}
