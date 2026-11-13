// Rebuild or verify Lite hooks from the exact upstream archive in disposable /tmp.
import {appendFile,cp,mkdir,mkdtemp,readFile,rm,writeFile,access} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {tmpdir} from 'node:os';
import {fileURLToPath} from 'node:url';
import {join,resolve} from 'node:path';
import assert from 'node:assert/strict';
import {prepareHooks} from './prepare-hooks.mjs';
const root=fileURLToPath(new URL('../',import.meta.url));
let manifestPath=join(root,'lite/hooks-manifest.json');
try{await access(manifestPath);}catch{manifestPath=join(root,'vendor/pdfjs-patches/manifest.json');}
const manifest=JSON.parse(await readFile(manifestPath,'utf8'));
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
for(const [name,digest]of Object.entries(manifest.localInputs))assert.equal(hash(await readFile(join(root,name))),digest,name);
assert.equal(process.versions.node,manifest.node,'Use the pinned Node version');
const args=process.argv.slice(2),index=args.indexOf('--archive'),write=args.includes('--write');
if(index>=0&&!args[index+1])throw Error('--archive requires a path');
const temporary=await mkdtemp(join(tmpdir(),'eapdf-lite-hooks-'));
const run=(cmd,args,options={})=>execFileSync(cmd,args,{cwd:temporary,stdio:'inherit',env:{...process.env,PUPPETEER_SKIP_DOWNLOAD:'true'},...options});
try {
 const archive=index>=0?resolve(args[index+1]):join(temporary,'source.tar.gz');
 if(index<0)run('curl',['-fL','--retry','2',manifest.sourceArchiveUrl,'-o',archive]);
 assert.equal(hash(await readFile(archive)),manifest.sourceArchiveSha256,'Upstream archive');
 run('tar',['-xzf',archive,'--strip-components=1','-C',temporary]);
 for(const [name,digest]of Object.entries(manifest.upstreamFiles))assert.equal(hash(await readFile(join(temporary,name))),digest,name);
 await prepareHooks(root,temporary,manifest.patches);
 // xref is deliberately byte-for-byte upstream. DecodeStream retains only 0002's ordinary defensive caps.
 assert.equal(hash(await readFile(join(temporary,'src/core/xref.js'))),manifest.upstreamFiles['src/core/xref.js']);
 run('npm',['ci','--ignore-scripts','--no-audit','--no-fund']);
 assert.equal(hash(await readFile(join(temporary,'package-lock.json'))),manifest.upstreamFiles['package-lock.json']);
 await mkdir(join(temporary,'build'),{recursive:true});
 await writeFile(join(temporary,'build/version.json'),JSON.stringify({version:manifest.version,build:Number(manifest.version.split('.').at(-1)),commit:manifest.upstreamCommit.slice(0,9)})+'\n');
 await appendFile(join(temporary,'gulpfile.mjs'),'\n'+await readFile(join(root,manifest.buildTask),'utf8'));
 run(process.execPath,['node_modules/gulp/bin/gulp.js','document-parts-bundle']);
 run(process.execPath,['--test',...manifest.tests.map(name=>join(root,name))],{env:{...process.env,PDFJS_SOURCE:temporary}});
 for(const [name,expected]of Object.entries(manifest.outputs)) {
  const artifact=join(temporary,'build/document-parts',name.split('/').at(-1)),digest=hash(await readFile(artifact));
  if(write){await mkdir(join(root,name,'..'),{recursive:true});await cp(artifact,join(root,name));manifest.outputs[name]=digest;}
  else assert.equal(digest,expected,`Rebuilt ${name}`);
 }
 if(write)await writeFile(manifestPath,JSON.stringify(manifest,null,2)+'\n');
 console.log(write?'Wrote Lite hook bundles; review updated hashes.':'Lite hook bundles reproduce byte-for-byte.');
} finally {await rm(temporary,{recursive:true,force:true});}
