import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {readFile,writeFile,mkdir,mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {execFileSync} from 'node:child_process';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
const extension=resolve('dist/chromium'),evidence=resolve('test-results/metadata-export');
await mkdir(evidence,{recursive:true});
const temporary=await mkdtemp(resolve(tmpdir(),'eapdf-metadata-test-')),fixture=resolve(temporary,'metadata.pdf');
execFileSync(process.env.M1_PYTHON??'python3',['scripts/metadata-export-fixture.py',resolve('../examples/eml/eml_Agenda_and_timeline_slides_1s.pdf'),fixture,evidence+'/metadata-expected.json']);
const context=await chromium.launchPersistentContext('',{headless:true,acceptDownloads:true,executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
try{
 await context.setOffline(true);const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);await page.waitForFunction(()=>!document.querySelector('#file').disabled);
 const ready=()=>page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false',null,{timeout:180000});
 const open=async path=>{await page.locator('#file').setInputFiles(path);await ready();};
 await open(fixture);
 await page.locator('#export-csv').click();assert.equal(await page.locator('#metadata-dialog').isVisible(),true);
 await page.locator('#metadata-none').click();await page.locator('#metadata-save').click();assert.match(await page.locator('#metadata-state').textContent(),/at least one/);
 for(const field of ['Subject','From'])await page.locator(`#metadata-fields input[value="${field}"]`).check();
 let pending=page.waitForEvent('download');await page.locator('#metadata-save').click();let download=await pending;
 let csv=await readFile(await download.path(),'utf8');assert.equal(csv.replace(/^\uFEFF/,'').split('\r\n')[0],'"From","Subject"');await ready();
 await page.locator('#export-csv').click();await page.locator('#metadata-format').selectOption('xmp');await page.locator('#metadata-load').click();await page.waitForFunction(()=>!document.querySelector('#metadata-save').disabled);
 assert.ok(await page.locator('#metadata-packets input').count()>=3);assert.ok((await page.locator('#metadata-preview').inputValue()).length>0);
 await page.locator('#metadata-packets button').last().click();await page.locator('#metadata-next').click();assert.match(await page.locator('#metadata-preview').inputValue(),/END/);assert.equal(await page.locator('#metadata-previous').isEnabled(),true);await page.locator('#metadata-previous').click();
 await page.screenshot({path:evidence+'/metadata-dialog.png',fullPage:true});
 await page.setViewportSize({width:360,height:760});assert.ok(await page.locator('#metadata-dialog').evaluate(el=>el.getBoundingClientRect().width<=360));await page.locator('#metadata-save').scrollIntoViewIfNeeded();assert.equal(await page.locator('#metadata-save').isVisible(),true);await page.setViewportSize({width:1280,height:720});
 pending=page.waitForEvent('download');await page.locator('#metadata-save').click();download=await pending;await download.saveAs(evidence+'/metadata-streams.zip');
 const actual=JSON.parse(execFileSync('python3',['-c',`import zipfile,sys,json,hashlib; z=zipfile.ZipFile(sys.argv[1]); assert z.testzip() is None; m=json.loads(z.read('manifest.json')); print(json.dumps({'hashes':sorted(hashlib.sha256(z.read(s['file'])).hexdigest() for s in m['streams']),'manifest':m}))`,await download.path()],{encoding:'utf8'}));
 const expected=JSON.parse(await readFile(evidence+'/metadata-expected.json','utf8'));assert.deepEqual(actual.hashes,Object.values(expected).sort());assert.equal(actual.manifest.complete,true);assert.ok(actual.manifest.streams.every(s=>s.owners.length));
 await page.locator('#metadata-none').click();await page.locator('#metadata-save').click();assert.match(await page.locator('#metadata-state').textContent(),/Select at least/);
 await page.locator('#metadata-packets input').first().check();pending=page.waitForEvent('download');await page.locator('#metadata-save').click();download=await pending;
 const subset=JSON.parse(execFileSync('python3',['-c',"import zipfile,json,sys; print(zipfile.ZipFile(sys.argv[1]).read('manifest.json').decode())",await download.path()],{encoding:'utf8'}));assert.equal(subset.streams.length,1);assert.equal(subset.complete,false);
 pending=page.waitForEvent('download');await page.locator('#metadata-save-one').click();download=await pending;assert.match(download.suggestedFilename(),/\.xmp$/);
 await page.keyboard.press('Escape');assert.equal(await page.locator('#metadata-dialog').isVisible(),false);
 // Aggregate scope exports only the selected message's CSV row.
 await open(resolve('../examples/pst/pst_quality-attached-message_1m.pdf'));await page.locator('#tree button[data-message-id]').last().click();await ready();
 await page.locator('#export-csv').click();await page.locator('#metadata-all').click();await page.locator('#metadata-scope').selectOption('current');pending=page.waitForEvent('download');await page.locator('#metadata-save').click();download=await pending;csv=await readFile(await download.path(),'utf8');assert.equal(csv.trim().split('\r\n').length,2);await ready();
 // Entire collection includes child-PDF streams and keeps shared aliases deduplicated.
 await open(resolve('../examples/pst/pst_quality-attached-message_1c.pdf'));
 await page.locator('#export-csv').click();await page.locator('#metadata-format').selectOption('xmp');await page.locator('#metadata-load').click();await page.waitForFunction(()=>!document.querySelector('#metadata-save').disabled,null,{timeout:180000});
 assert.ok(await page.locator('#metadata-packets input').count()>2);pending=page.waitForEvent('download');await page.locator('#metadata-save').click();download=await pending;
 const collection=JSON.parse(execFileSync('python3',['-c',"import zipfile,json,sys; print(zipfile.ZipFile(sys.argv[1]).read('manifest.json').decode())",await download.path()],{encoding:'utf8'}));assert.ok(new Set(collection.streams.map(s=>s.document)).size>=3);assert.equal(collection.complete,true);
 // Worker rejects invented metadata locators and mismatched document IDs.
 await page.locator('#metadata-close').click();await page.evaluate(()=>{const i=document.createElement('input');i.type='file';i.id='metadata-reference';document.body.append(i);});await page.locator('#metadata-reference').setInputFiles(fixture);
 const rejected=await page.evaluate(async()=>{const {BrowserSession}=await import('./browser-session.js');const session=new BrowserSession();try{const doc=await session.open(document.querySelector('#metadata-reference').files[0],'test.pdf');const assets=await doc.pdf.getDocumentAssets(),results=[];for(const request of [{documentId:assets.documentId,metadataId:'invented',maxBytes:100},{documentId:'foreign',metadataId:assets.metadata[0].locator.path,maxBytes:100},{documentId:assets.documentId,metadataId:assets.metadata[0].locator.path,maxBytes:0}]){try{await doc.pdf.getAssociatedFileBytes(request);results.push(false);}catch{results.push(true);}}return results;}finally{session.close();}});assert.deepEqual(rejected,[true,true,true]);
 assert.deepEqual(errors,[]);await mkdir('test-results',{recursive:true});await writeFile('test-results/metadata-export.json',JSON.stringify({status:'PASS',streams:actual.hashes.length,collectionStreams:collection.streams.length,checks:['CSV fields','current-message scope','original XMP hashes match independent parser','object metadata','UTF-16 preserved','XML preview','selected ZIP manifest','single XMP','nested collection traversal','foreign/unknown/oversize requests rejected','Escape closes dialog']},null,2));console.log('PASS: metadata dialog and byte-exact extraction.');
}finally{await context.close();await rm(temporary,{recursive:true,force:true});}
