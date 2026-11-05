// Inspect metadata and absence messages without claiming to validate EA-PDF.
import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {mkdir,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
process.chdir(fileURLToPath(new URL('../',import.meta.url)));
const extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,
  ...(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{channel:'chromium'}),
  args:[`--disable-extensions-except=${extension}`,`--load-extension=${extension}`]});
const checks=[],requests=[],errors=[];
try{
  context.on('request',r=>{if(/^https?:/.test(r.url()))requests.push(r.url());});await context.setOffline(true);
  const service=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker');
  const page=await context.newPage();page.on('pageerror',error=>errors.push(error.message));
  const url=`chrome-extension://${new URL(service.url()).host}/viewer.html`;
  await page.goto(url);await page.waitForFunction(()=>!document.querySelector('#file').disabled);
  assert.match(await page.locator('#empty-state').textContent(),/Read email preserved in PDF/);
  assert.doesNotMatch(await page.locator('#empty-state').textContent(),/complete|authentic/i);
  assert.doesNotMatch(await page.locator('footer').textContent(),/Conformance not evaluated/);
  const results=await page.evaluate(async()=>{
    const {parseXmp,NS}=await import('./domain/xmp.js');
    const {metadataCsv}=await import('./metadata-csv.js');
    const cases=[
      ['full-text','<m:Subject>  Subject &amp; value\nsecond line  </m:Subject><m:Sent>2019-03-05T16:08:13</m:Sent>',{Subject:'  Subject & value\nsecond line  ',Sent:'2019-03-05T16:08:13'}],
      ['missing-date','<m:Subject>Undated draft</m:Subject>',{Subject:'Undated draft'}],
      ['empty-text','<m:Subject></m:Subject><m:Sent></m:Sent>',{Subject:'',Sent:''}],
      ['identical-repeat','<m:Subject>same</m:Subject><m:Subject>same</m:Subject>',{Subject:'same'}],
      ['qualified-resource','<m:Subject rdf:parseType="Resource"><rdf:value>Qualified subject</rdf:value><q:source>not part of subject</q:source></m:Subject>',{Subject:'Qualified subject'}],
      ['qualified-description','<m:Sent><rdf:Description><rdf:value>2019-03-05T16:08:13</rdf:value><q:source>not part of date</q:source></rdf:Description></m:Sent>',{Sent:'2019-03-05T16:08:13'}],
      ['conflicting-date','<m:Sent>2001-01-01T00:00:00</m:Sent><m:Sent>2002-01-01T00:00:00</m:Sent>',{},'XMP_DISPLAY_VALUE_AMBIGUOUS'],
      ['array-subject','<m:Subject><rdf:Bag><rdf:li>one</rdf:li><rdf:li>two</rdf:li></rdf:Bag></m:Subject>',{},'XMP_DISPLAY_VALUE_UNAVAILABLE'],
      ['uri-date','<m:Sent rdf:resource="https://example.invalid/date"/>',{},'XMP_DISPLAY_VALUE_UNAVAILABLE'],
      ['unreadable-raw-address','<m:Raw-To rdf:parseType="Resource"><q:value>unknown</q:value></m:Raw-To>',{},'XMP_DISPLAY_VALUE_UNAVAILABLE'],
    ];
    const results=[];
    for(const [name,fields,expected,diagnostic] of cases){
      const xml=`<rdf:RDF xmlns:rdf="${NS.rdf}" xmlns:m="${NS.meta}" xmlns:q="urn:qualifier"><rdf:Description><m:email><rdf:Bag><rdf:li rdf:parseType="Resource"><m:Mail_GUID>id</m:Mail_GUID>${fields}</rdf:li></rdf:Bag></m:email></rdf:Description></rdf:RDF>`;
      const parsed=parseXmp(xml,{documentId:'test',objectNumber:7,generation:0,path:'catalog/Metadata'},x=>new DOMParser().parseFromString(x,'application/xml'));
      const record=parsed.records[0],details=parsed.details.get(record.id);
      const doc={name:name+'.pdf',details:parsed.details,inspection:{diagnostics:parsed.diagnostics,coverage:{metadata:parsed.state,associatedFiles:'present'},collection:{state:'missing',value:null},files:[],messages:[{metadataRecordId:record.id,mailGuid:{value:'id'},internetMessageId:{value:null}}]}};
      let csv='',error='';
      try{csv=await(await metadataCsv(doc,()=>{throw Error('Unexpected child');},()=>{},()=>{})).blob.text();}catch(e){error=String(e);}
      results.push({name,expected,diagnostic,details,diagnostics:parsed.diagnostics,csv,error});
    }
    return results;
  });
  for(const result of results){
    assert.deepEqual(result.details,result.expected,result.name);
    if(result.diagnostic){
      assert.ok(result.diagnostics.some(d=>d.code===result.diagnostic),result.name);
      assert.ok(result.diagnostics.every(d=>d.state!=='invalid'),result.name+' is an inspection limit, not a conformance verdict');
      assert.ok(result.diagnostics.every(d=>d.evidence[0].objectNumber===7&&d.evidence[0].path.startsWith('catalog/Metadata/email[0]/')));
      assert.match(result.error,/could not be read unambiguously/);
    }else{assert.deepEqual(result.diagnostics,[]);assert.equal(result.error,'');}
    if(result.name==='missing-date')assert.match(result.csv,/,"Undated draft","","","id"\r\n/);
    if(result.name.startsWith('qualified'))assert.doesNotMatch(result.csv,/not part of/);
    checks.push(result.name);
  }
  const ready=()=>page.waitForFunction(()=>document.querySelector('#viewport').getAttribute('aria-busy')==='false'&&!document.querySelector('#canvas').hidden);
  await page.locator('#file').setInputFiles(resolve('tests/inspection/source-examples/eml/eml_sample_1si.pdf'));await ready();
  assert.match(await page.locator('#files').textContent(),/zero bytes/);
  assert.doesNotMatch(await page.locator('#files').textContent(),/are omitted in isolated/);
  assert.doesNotMatch(await page.locator('footer').textContent(),/Conformance not evaluated/);
  checks.push('isolated-placeholder-does-not-assert-source-absence');
  // Exercise the real PDF.js object limit, then check the existing UI's empty list.
  await page.evaluate(async()=>{
    const {BrowserSession}=await import('./browser-session.js');const open=BrowserSession.prototype.open;
    BrowserSession.prototype.open=function(...args){this.limits.maxObjects=1;return open.apply(this,args);};
  });
  await page.locator('#file').setInputFiles(resolve('tests/inspection/source-examples/eml/eml_sample_1s.pdf'));await ready();
  assert.match(await page.locator('#files').textContent(),/could not be fully inspected/);
  assert.doesNotMatch(await page.locator('#files').textContent(),/No embedded files/);
  checks.push('incomplete-inspection-is-not-an-empty-inventory');
  assert.deepEqual(requests,[]);assert.deepEqual(errors,[]);
  await mkdir('test-results',{recursive:true});
  await writeFile('test-results/rule-alignment.json',JSON.stringify({status:'PASS',checks,httpRequests:requests,pageErrors:errors},null,2)+'\n');
  console.log(`PASS: ${checks.length} metadata/inspection checks; offline; conformance remains not evaluated.`);
}finally{await context.close();}
