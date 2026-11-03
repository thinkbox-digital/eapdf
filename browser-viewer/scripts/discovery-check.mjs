// Compare benchmark observations with an independently extracted PDF inventory.
import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
const [reportPath,expectedPath,resultPath]=process.argv.slice(2);
if(!reportPath||!expectedPath||!resultPath)throw new Error('Usage: node scripts/discovery-check.mjs benchmark.json expected.json result.json');
const report=JSON.parse(await readFile(reportPath,'utf8')),expected=JSON.parse(await readFile(expectedPath,'utf8')),results=[];
for(const row of report.runs){
 assert.ok(!row.error,row.error);assert.deepEqual(row.errors,[]);
 const m=row.measurements,e=expected.find(x=>x.file===row.file);
 assert.ok(e,'independent inventory exists');
 assert.equal(report.files.find(x=>x.file===row.file).sha256,e.sha256,'same input bytes');
 assert.equal(m.coverage.associatedFiles,'present','file scan complete');
 assert.equal(m.coverage.documentParts,'present','document-part scan complete');
 if(m.readingNotesPresent!==undefined)assert.equal(m.readingNotesPresent,false);
 assert.ok(!(m.fileNotes??[]).some(t=>t.includes('could not be listed')));
 const actual=m.inventory.flatMap(f=>f.streams.map(s=>({name:f.name,bytes:s.declaredSize.value})));
 const key=f=>JSON.stringify([f.name,f.bytes]);
 assert.deepEqual(actual.map(key).sort(),e.files.map(key).sort(),'all embedded filenames and sizes, including duplicate names');
 const sources=m.inventory.filter(f=>f.relationship==='Source');
 for(const f of sources){assert.equal(f.owners.state,'present',f.name);assert.equal(f.owners.value.length,1,f.name);assert.ok(m.messageIds.includes(f.owners.value[0]),f.name);}
 let hashes=0;
 for(const f of m.inventory)for(const s of f.streams)if(s.sha256.state==='present'){
  assert.ok(e.files.some(x=>x.name===f.name&&x.sha256===s.sha256.value),f.name+' extracted bytes');hashes++;
 }
 const result={file:row.file,files:actual.length,sources:sources.length,computedHashesChecked:hashes,unassignedSupplements:m.inventory.filter(f=>f.relationship==='Supplement'&&!f.owners.value).length,status:'PASS'};
 results.push(result);console.log(JSON.stringify(result));
}
assert.equal(new Set(results.map(r=>r.file)).size,expected.length,'every reference file tested');
await writeFile(resultPath,JSON.stringify(results,null,2)+'\n');
