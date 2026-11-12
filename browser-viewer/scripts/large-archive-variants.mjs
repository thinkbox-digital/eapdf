// Experimental build helper. Only writes below an explicit temporary work directory.
import {readFile,writeFile,mkdir,cp} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {tmpdir} from 'node:os';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
const root=fileURLToPath(new URL('../',import.meta.url));
const work=resolve(process.env.PDFJS_COMPARISON_WORK??join(tmpdir(),'eapdf-pdfjs-comparison'));
assert.ok(work.startsWith(resolve(tmpdir())+'/'),'Comparison builds must be temporary');
const source=join(work,'source'),manifest=JSON.parse(await readFile(join(root,'vendor/pdfjs-patches/manifest.json'),'utf8'));
const hash=value=>createHash('sha256').update(value).digest('hex');
assert.equal(hash(await readFile(join(work,'source.tar.gz'))),manifest.sourceArchiveSha256);
const files=['xref.js','decode_stream.js','document_parts.js','document_assets.js','catalog.js'];
const full=Object.fromEntries(await Promise.all(files.map(async n=>[n,await readFile(join(work,'full-source',n),'utf8')])));
const prior=Object.fromEntries(await Promise.all(files.map(async n=>[n,await readFile(join(work,'before-large',n),'utf8')])));
function replace(s,a,b){assert.equal(s.split(a).length,2,'Unique source anchor: '+a.slice(0,80));return s.replace(a,b);}
function section(s,start,end,replacement){const a=s.indexOf(start),b=s.indexOf(end,a+start.length);assert.ok(a>=0&&b>a,start);return s.slice(0,a)+replacement+s.slice(b);}
function noCompact(v){v['xref.js']=replace(v['xref.js'],'class XRef {',`// Upstream array storage through the existing entry-access interface.
class ArrayXRefEntries {
 entries=[];get length(){return this.entries.length;}
 has(n){return this.entries[n]!==undefined;}get(n){return this.entries[n];}
 set(n,v){this.entries[n]=v;}clear(){this.entries.length=0;}
 *keys(){for(const n in this.entries)yield Number(n);}
}
class XRef {`);v['xref.js']=replace(v['xref.js'],'#entries = new CompactXRefEntries();','#entries = new ArrayXRefEntries();');}
function noCache(v){v['xref.js']=section(v['xref.js'],'  #cacheParsed(num, object) {','  #entries =',`  #cacheParsed(num, object) {this.#cacheMap.set(num,object);}
  #rememberObject(num,object) {return object;}

`);v['xref.js']=replace(v['xref.js'],'#parsedCacheOrder = new Array(100000);','#parsedCacheOrder = [];');}
function noReclaim(v){v['decode_stream.js']=section(v['decode_stream.js'],'function discardStructuralStream(stream) {','\nexport {','function discardStructuralStream(stream) { /* Experimental retention control. */ }\n');}
function noPageScan(v){v['document_parts.js']=section(v['document_parts.js'],'    // One page-tree traversal','    const index = await catalog.getPageIndex(raw);','');v['document_parts.js']=replace(v['document_parts.js'],'  let allPageIndices;\n','');
 v['catalog.js']=section(v['catalog.js'],'          if (!pageIndices) {','          origin.pageIndex = index;',`          const index = await this.getPageIndex(pageRef);
          const [, actual] = await this.getPageDict(index);
          if (!isRefsEqual(actual,pageRef))throw new Error("Detached page");
`);v['catalog.js']=replace(v['catalog.js'],'    let pageIndices;\n','');}
function control(v){
 // Restore pre-0003 XRef, including upstream eager compressed-object parsing.
 // Keep later zero-width-xref correction, explicit resource-error propagation,
 // and the optional cache-cleanup API required by later inspection features.
 v['xref.js']=replace(prior['xref.js'],'import { BaseStream } from "./base_stream.js";','import { BaseStream } from "./base_stream.js";\nimport {DecodeStreamLimitError} from "./decode_stream.js";');
 v['xref.js']=replace(v['xref.js'],'class XRef {','class XRef {\n  clearObjectStreamCache() {}');
 v['xref.js']=replace(v['xref.js'],'if (e instanceof MissingDataException) {','if (e instanceof MissingDataException || e instanceof DecodeStreamLimitError) {');
 noReclaim(v);noPageScan(v);
}
const variants=[['full',()=>{}],['control',control],['no-compact',noCompact],['no-parsed-cache',noCache],['no-reclaim',noReclaim],['no-page-scan',noPageScan]];
const report={upstream:manifest.upstreamCommit,archiveSha256:manifest.sourceArchiveSha256,variants:[],controlLimits:'Control restores pre-0003 XRef and disables decoder reclamation/page scans. Later metadata/extraction APIs, scoped metadata allowances, cooperative scheduling and asset traversal remain identical to full for feature equivalence. This is not a literal reversal of every line in 0003.'};
for(const [name,edit]of variants){
 const v={...full};edit(v);
 for(const file of files)await writeFile(join(source,'src/core',file),v[file]);
 if(name==='control')execFileSync('git',['apply',join(root,'vendor/pdfjs-patches/0005-zero-width-xref.patch')],{cwd:source,stdio:'inherit'});
 const destination=join(work,'variants',name);await mkdir(destination,{recursive:true});
 const snapshots={};for(const file of files){const bytes=await readFile(join(source,'src/core',file));await writeFile(join(destination,file+'.source'),bytes);snapshots[file]=hash(bytes);}
 const log=execFileSync(process.execPath,['node_modules/gulp/bin/gulp.js','document-parts-bundle'],{cwd:source,env:{...process.env,TESTING:'false'},maxBuffer:8*1024**2});await writeFile(join(destination,'build.log'),log);
 const bundles={};for(const file of ['pdf.mjs','pdf.worker.mjs']){const bytes=await readFile(join(source,'build/document-parts',file));bundles[file]=hash(bytes);await writeFile(join(destination,file),bytes);if(name==='full')assert.equal(bundles[file],manifest.outputs['vendor/pdfjs-patched/'+file],'Full reproduction differs');}
 report.variants.push({name,sources:snapshots,bundles});await writeFile(join(work,'builds.json'),JSON.stringify(report,null,2)+'\n');console.log('Built '+name);
}
for(const file of files)await writeFile(join(source,'src/core',file),full[file]);
