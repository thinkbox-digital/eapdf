import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {resolve} from 'node:path';

const extension=resolve('dist/chromium');
const context=await chromium.launchPersistentContext('',{headless:true,
  executablePath:process.env.CHROMIUM_PATH??'/snap/chromium/current/usr/lib/chromium-browser/chrome',
  args:[`--load-extension=${extension}`,`--disable-extensions-except=${extension}`]});
try{
  const worker=context.serviceWorkers()[0]??await context.waitForEvent('serviceworker'),page=await context.newPage();
  await page.goto(`chrome-extension://${new URL(worker.url()).host}/viewer.html`);
  const results=await page.evaluate(async()=>{
    const {parseXmp}=await import('./domain/xmp.js');
    const parse=xml=>new DOMParser().parseFromString(xml,'application/xml');
    const read=properties=>parseXmp(`<x:xmpmeta xmlns:x="adobe:ns:meta/" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:m="http://www.pdfa.org/eapdf/ns/meta/" xmlns:f="http://xmlns.com/foaf/0.1/"><rdf:RDF><rdf:Description><m:email><rdf:Bag><rdf:li rdf:parseType="Resource">${properties}</rdf:li></rdf:Bag></m:email></rdf:Description></rdf:RDF></x:xmpmeta>`,{documentId:'test',path:'XMP'},parse);
    const result=[];
    for(const key of ['From','To','Cc','Bcc'])for(const attributes of [false,true]){
      const agent=attributes?'<rdf:Description f:name="Zoë Example" f:mbox="mailto:zoe@example.invalid"/>':'<rdf:Description><f:name>Zoë Example</f:name><f:mbox rdf:resource="mailto:zoe@example.invalid"/></rdf:Description>';
      const r=read(`<m:${key}><rdf:Seq><rdf:li>${agent}</rdf:li><rdf:li f:name="李 Example" f:mbox="mailto:li@example.invalid"/></rdf:Seq></m:${key}>`);
      result.push({key,attributes,value:[...r.details.values()][0][key],diagnostics:r.diagnostics});
    }
    const conflict=read('<m:From><rdf:Seq><rdf:li><rdf:Description f:name="One" f:mbox="mailto:one@example.invalid"><f:name>Other</f:name></rdf:Description></rdf:li></rdf:Seq></m:From>');
    return {result,conflictValue:[...conflict.details.values()][0].From??null,conflictCodes:conflict.diagnostics.map(d=>d.code)};
  });
  for(const r of results.result){assert.equal(r.value,'Zoë Example <zoe@example.invalid>, 李 Example <li@example.invalid>');assert.deepEqual(r.diagnostics,[]);}
  assert.equal(results.conflictValue,null);assert.ok(results.conflictCodes.includes('XMP_DISPLAY_VALUE_AMBIGUOUS'));
  assert.ok(results.conflictCodes.includes('XMP_DISPLAY_VALUE_UNAVAILABLE'));
  console.log('PASS: From/To/Cc/Bcc attributes and elements, multiple Unicode recipients, conflicts reported.');
}finally{await context.close();}
