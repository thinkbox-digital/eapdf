import test,{after} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,readdir,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {build} from 'esbuild';
const root=fileURLToPath(new URL('../',import.meta.url)),temporary=await mkdtemp(join(tmpdir(),'eapdf-lite-tests-'));
after(()=>rm(temporary,{recursive:true,force:true}));
const load=async name=>{const path=join(temporary,name.replaceAll('/','-')+'.mjs');await build({entryPoints:[join(root,'src',name+'.ts')],outfile:path,bundle:true,format:'esm',platform:'node'});return import(pathToFileURL(path));};
test('Lite omits optional source implementations and identifies the profile',async()=>{
 const names=await readdir(join(root,'src'));for(const name of ['thread-preview.ts','thread-graph.ts','thread-worker.ts','interpret-worker.ts','portfolio.ts','portfolio-view.ts','portfolio-scatter.ts','portfolio-splitter.ts','portfolio-thumbnails.ts'])assert.ok(!names.includes(name),name);
 const {largeArchives,embeddedIndexes}=await load('viewer-profile');assert.equal(largeArchives,false);assert.equal(embeddedIndexes,false);
 const manifest=JSON.parse(await readFile(join(root,'src/extension/manifest.json'),'utf8'));assert.equal(manifest.name,'EA-PDF Viewer Lite');
 const html=await readFile(join(root,'src/viewer.html'),'utf8'),css=await readFile(join(root,'src/viewer.css'),'utf8');
 assert.doesNotMatch(html,/portfolio-(?:toggle|show|panel)/);assert.doesNotMatch(css,/portfolio/i);
});
test('embedded payload readers never access or decompress document payloads',async()=>{
 const doc=new Proxy({},{get(){throw Error('Payload access is forbidden');}});let checks=0;
 assert.deepEqual(await (await load('embedded-index')).readEmbeddedIndex(doc,()=>checks++),{});
 assert.deepEqual(await (await load('fulltext-index')).readFullTextIndex({},doc,()=>checks++),{});assert.equal(checks,2);
 const {parseXmp}=await load('domain/xmp');const xmp=parseXmp(null,{documentId:'d',path:'metadata',objectNumber:null,generation:null},()=>{throw Error('No XML');});assert.equal(xmp.embeddedIndex,undefined);assert.equal(xmp.fullTextIndex,undefined);
});
test('message metadata filtering and ordinary whole-word search remain available',async()=>{
 const {metadataSearchText}=await load('metadata-search');assert.match(metadataSearchText({Subject:'Résumé',From:'Reader'}),/résumé reader/);
 const {textMatches}=await load('document-search');assert.equal(textMatches('mail mailbox mail','mail').length,2);
});
test('CSV export retains Unicode, spreadsheet protection and message fields',async()=>{
 const {metadataCsv,csvCell}=await load('metadata-csv');assert.equal(csvCell('=1+1'),'"\'=1+1"');
 const doc={name:'email.pdf',details:new Map([['email',{Subject:'Hello 東京',From:'Reader'}]]),inspection:{diagnostics:[],coverage:{metadata:'present',associatedFiles:'present'},metadataRecords:[],messages:[{id:'message',metadataRecordId:'email',internetMessageId:{value:'<id>'},mailGuid:{value:'uuid:1'}}],collection:{state:'missing',value:null},files:[]}};
 const result=await metadataCsv(doc,()=>{throw Error('Unexpected child');},()=>{},()=>{});assert.equal(result.rows,1);assert.match(await result.blob.text(),/Hello 東京/);
});
test('export provenance verifies every selected file byte',async()=>{
 const {sha256}=await import('../scripts/verify-hooks.mjs');const manifest=JSON.parse(await readFile(join(root,'export-manifest.json'),'utf8'));
 assert.equal(manifest.profile,'lite');assert.match(manifest.sourceCommit,/^[a-f0-9]{40}$/);
 for(const [name,digest]of Object.entries(manifest.files))assert.equal(sha256(await readFile(join(root,name))),digest,name);
});
