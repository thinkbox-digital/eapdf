// Prepare a disposable, hash-verified upstream build tree for the experiment.
import {readFile,writeFile,mkdir,cp,appendFile,access} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {tmpdir} from 'node:os';
import {fileURLToPath} from 'node:url';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
const root=fileURLToPath(new URL('../',import.meta.url));
const work=resolve(process.env.PDFJS_COMPARISON_WORK??join(tmpdir(),'eapdf-pdfjs-comparison'));
assert.ok(work.startsWith(resolve(tmpdir())+'/'),'Use a temporary work directory');
const manifest=JSON.parse(await readFile(join(root,'vendor/pdfjs-patches/manifest.json'),'utf8'));
assert.equal(process.versions.node,manifest.node);
await mkdir(work,{recursive:true});const archive=join(work,'source.tar.gz');
try{await access(archive);}catch{execFileSync('curl',['-fL','--retry','2',manifest.sourceArchiveUrl,'-o',archive],{stdio:'inherit'});}
const hash=b=>createHash('sha256').update(b).digest('hex');
assert.equal(hash(await readFile(archive)),manifest.sourceArchiveSha256);
for(const [file,expected]of Object.entries(manifest.localInputs))assert.equal(hash(await readFile(join(root,file))),expected,file);
const source=join(work,'source');await mkdir(source); // Refuse to overwrite an existing experiment.
execFileSync('tar',['-xzf',archive,'--strip-components=1','-C',source]);
for(const [file,expected]of Object.entries(manifest.upstreamFiles))assert.equal(hash(await readFile(join(source,file))),expected,file);
const files=['xref.js','decode_stream.js','document_parts.js','document_assets.js','catalog.js'];
for(let i=0;i<manifest.patches.length;i++){
 if(i===2){await mkdir(join(work,'before-large'));for(const file of files)await cp(join(source,'src/core',file),join(work,'before-large',file));}
 const patch=join(root,manifest.patches[i]);execFileSync('git',['apply','--check',patch],{cwd:source});execFileSync('git',['apply',patch],{cwd:source});
}
await mkdir(join(work,'full-source'));for(const file of files)await cp(join(source,'src/core',file),join(work,'full-source',file));
execFileSync('npm',['ci','--ignore-scripts','--no-audit','--no-fund'],{cwd:source,stdio:'inherit',env:{...process.env,PUPPETEER_SKIP_DOWNLOAD:'true'}});
assert.equal(hash(await readFile(join(source,'package-lock.json'))),manifest.upstreamFiles['package-lock.json']);
await mkdir(join(source,'build'),{recursive:true});await writeFile(join(source,'build/version.json'),JSON.stringify({version:manifest.version,build:Number(manifest.version.split('.').at(-1)),commit:manifest.upstreamCommit.slice(0,9)})+'\n');
await appendFile(join(source,'gulpfile.mjs'),'\n'+await readFile(join(root,'vendor/pdfjs-patches/build-task.mjs'),'utf8'));
await writeFile(join(work,'preparation.json'),JSON.stringify({archiveSha256:manifest.sourceArchiveSha256,upstream:manifest.upstreamCommit,patchInputs:manifest.localInputs,node:process.versions.node},null,2)+'\n');
console.log('Prepared verified temporary source tree.');
