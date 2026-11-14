// Export a standalone repository from shared sources; never fork implementation edits.
import {readFile,writeFile,mkdir,mkdtemp,cp,access,rename,rm,readdir,symlink} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {resolve,join,dirname,relative} from 'node:path';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
import {selectHookTests} from './select-hook-tests.mjs';
import {liteStyles} from './styles.mjs';
const root=fileURLToPath(new URL('../',import.meta.url));
const args=process.argv.slice(2),index=args.indexOf('--output');
if(index<0||!args[index+1])throw Error('Use --output /tmp/eapdf-viewer-lite');
const destination=resolve(args[index+1]);
assert.ok(destination!==root&&!root.startsWith(destination+'/')&&!destination.startsWith(root+'src/'),'Unsafe export destination');
try{await access(destination);throw Error('Export destination exists. Use a fresh directory; review updates before replacing a maintained repository.');}catch(error){if(error.code!=='ENOENT')throw error;}
const sha=bytes=>createHash('sha256').update(bytes).digest('hex');
const hooks=JSON.parse(await readFile(join(root,'lite/hooks-manifest.json'),'utf8'));
for(const [name,digest]of Object.entries({...hooks.localInputs,...hooks.outputs}))assert.equal(sha(await readFile(join(root,name))),digest,`Review changed Lite hook input/output: ${name}`);
for(const kind of ['core','assets'])assert.equal(await readFile(join(root,`lite/${kind}.test.mjs`),'utf8'),selectHookTests(await readFile(join(root,`vendor/pdfjs-patches/${kind}.test.mjs`),'utf8'),kind),'Refresh shared hook regressions explicitly');
await mkdir(dirname(destination),{recursive:true});const stage=await mkdtemp(join(dirname(destination),'.eapdf-lite-export-'));
const inputs={},files={};
const put=async(name,data,origin)=>{await mkdir(dirname(join(stage,name)),{recursive:true});await writeFile(join(stage,name),data);files[name]=sha(data);if(origin)inputs[origin]=sha(await readFile(join(root,origin)));};
const copy=async(from,to=from)=>put(to,await readFile(join(root,from)),from);
try {
 // Let the compiler resolve the module graph, including type-only imports.
 // Apply Lite overrides before discovery so omitted implementations stay omitted.
 await cp(join(root,'src'),join(stage,'src'),{recursive:true});
 await cp(join(root,'lite/overrides'),join(stage,'src'),{recursive:true});
 await symlink(join(root,'node_modules'),join(stage,'node_modules'),'dir');
 const config=JSON.parse(await readFile(join(root,'tsconfig.json'),'utf8'));
 delete config.include;
 config.files=['src/viewer.ts','src/extension/background.ts','src/pdfjs-assets.d.ts','src/pdfjs-document-parts.d.ts'];
 await writeFile(join(stage,'tsconfig.json'),JSON.stringify(config));
 const discovered=execFileSync(process.execPath,[join(root,'node_modules/typescript/bin/tsc'),'--project',join(stage,'tsconfig.json'),'--listFilesOnly','--pretty','false'],{encoding:'utf8'});
 const seen=new Set(discovered.split(/\r?\n/).map(name=>relative(stage,name)).filter(name=>name.startsWith('src/')));
 assert.ok(seen.has('src/viewer.ts'),'Compiler did not identify the Lite entry point');
 await rm(join(stage,'src'),{recursive:true});
 await rm(join(stage,'node_modules'));
 await rm(join(stage,'tsconfig.json'));
 for(const name of seen) {
  const override='lite/overrides/'+relative('src',name);let origin=name;
  try{await access(join(root,override));origin=override;}catch{}
  await put(name,await readFile(join(root,origin)),origin);
  if(origin!==name)inputs[name]=sha(await readFile(join(root,name)));
 }
 for(const name of ['src/pdfjs-assets.d.ts','src/pdfjs-document-parts.d.ts'])if(!seen.has(name))await copy(name);
 const html=(await readFile(join(root,'src/viewer.html'),'utf8')).replaceAll('EA-PDF viewer','EA-PDF Viewer Lite').replace('viewer __VIEWER_VERSION__','Lite __VIEWER_VERSION__')
  .replace(/<button id="portfolio-(?:toggle|show)"[^>]*>[\s\S]*?<\/button>/g,'')
  .replace(/<section id="portfolio-panel"[^>]*><\/section>/,'');
 assert.ok(!html.includes('portfolio-'),'Review optional collection HTML');
 await put('src/viewer.html',html,'src/viewer.html');await put('src/viewer.css',liteStyles(await readFile(join(root,'src/viewer.css'),'utf8')),'src/viewer.css');
 inputs['lite/styles.mjs']=sha(await readFile(join(root,'lite/styles.mjs')));
 const extension=JSON.parse(await readFile(join(root,'src/extension/manifest.json'),'utf8'));extension.name='EA-PDF Viewer Lite';extension.action.default_title='Open EA-PDF Viewer Lite';
 await put('src/extension/manifest.json',JSON.stringify(extension,null,2)+'\n','src/extension/manifest.json');
 const background='src/extension/background.ts';await put(background,(await readFile(join(stage,background),'utf8')).replaceAll('EA-PDF viewer','EA-PDF Viewer Lite'),'src/extension/background.ts');
 for(const dir of ['src/icons','vendor/sources'])for(const name of await readdir(join(root,dir)))await copy(dir+'/'+name);
 await put('assets/thinkbox_logo.jpg',await readFile(new URL('../../assets/thinkbox_logo.jpg',import.meta.url)),'../assets/thinkbox_logo.jpg');
 const pkg=JSON.parse(await readFile(join(root,'package.json'),'utf8'));pkg.name='eapdf-viewer-lite';
 pkg.scripts={check:'tsc --noEmit && node scripts/verify-hooks.mjs',build:'node scripts/build.mjs',test:'node --test tests/lite.test.mjs tests/message-download.test.mjs tests/rtl-ligatures.test.mjs tests/page-only-extraction.test.mjs','test:browser':'node scripts/browser-smoke.mjs','build:pdfjs':'node scripts/rebuild-pdfjs.mjs',package:'node scripts/package.mjs','verify:licenses':'node scripts/licenses.mjs'};
 await put('package.json',JSON.stringify(pkg,null,2)+'\n','package.json');
 const lock=JSON.parse(await readFile(join(root,'package-lock.json'),'utf8'));lock.name=pkg.name;lock.packages[''].name=pkg.name;await put('package-lock.json',JSON.stringify(lock,null,2)+'\n','package-lock.json');
 await copy('tests/fixtures/ordinary.pdf');
 await copy('tests/fixtures/issue16880.pdf');
 await copy('tests/fixtures/RTL-LIGATURES.md');
 for(const name of ['tsconfig.json','LICENSE','vendor/pdfjs-pin.json'])await copy(name);
 let notices=await readFile(join(root,'THIRD_PARTY_NOTICES.md'),'utf8');notices=notices.split('\n## XML interpretation worker')[0]+'\n\nLite omits the XML interpretation worker and does not bundle xmldom. The source lock retains the shared development dependency set.\n';await put('THIRD_PARTY_NOTICES.md',notices,'THIRD_PARTY_NOTICES.md');
 await put('PRIVACY.md',await readFile(join(root,'../docs/site/privacy.md')),'../docs/site/privacy.md');
 const mapping={'lite/prepare-hooks.mjs':'scripts/prepare-hooks.mjs','lite/rebuild-hooks.mjs':'scripts/rebuild-pdfjs.mjs','lite/verify-hooks.mjs':'scripts/verify-hooks.mjs','lite/build.mjs':'scripts/build.mjs','lite/package.mjs':'scripts/package.mjs','lite/core.test.mjs':'tests/core-hooks.test.mjs','lite/assets.test.mjs':'tests/assets-hooks.test.mjs','lite/metadata-hooks.test.mjs':'tests/metadata-hooks.test.mjs','lite/lite.test.mjs':'tests/lite.test.mjs','tests/message-download.test.mjs':'tests/message-download.test.mjs','tests/rtl-ligatures.test.mjs':'tests/rtl-ligatures.test.mjs','tests/page-only-extraction.test.mjs':'tests/page-only-extraction.test.mjs','scripts/download-scope-fixture.mjs':'scripts/download-scope-fixture.mjs','lite/browser-smoke.mjs':'scripts/browser-smoke.mjs','lite/indexing-checks.mjs':'scripts/indexing-checks.mjs','lite/README.md':'README.md','lite/check.yml':'.github/workflows/check.yml','lite/INSTALL.txt':'INSTALL.txt','scripts/sbom.mjs':'scripts/sbom.mjs','scripts/licenses.mjs':'scripts/licenses.mjs'};
 for(const [from,to]of Object.entries(mapping))await copy(from,to);
 const exportedHooks=structuredClone(hooks);exportedHooks.localInputs={};exportedHooks.outputs={};
 for(const [name,digest]of Object.entries(hooks.localInputs)){const to=mapping[name]??name;if(!files[to])await copy(name,to);exportedHooks.localInputs[to]=digest;}
 for(const [name,digest]of Object.entries(hooks.outputs)){const to=name.replace('vendor/pdfjs-lite/','vendor/pdfjs-patched/');await copy(name,to);exportedHooks.outputs[to]=digest;}
 exportedHooks.tests=hooks.tests.map(name=>mapping[name]??name);
 await put('vendor/pdfjs-patches/manifest.json',JSON.stringify(exportedHooks,null,2)+'\n','lite/hooks-manifest.json');
 await put('.gitignore','node_modules/\ndist/\nreleases/\ntest-results/\n');
 const sourceCommit=execFileSync('git',['rev-parse','HEAD'],{cwd:root,encoding:'utf8'}).trim();
 const dirty=execFileSync('git',['status','--porcelain','--','browser-viewer'],{cwd:resolve(root,'..'),encoding:'utf8'}).trim().length>0;
 await put('export-manifest.json',JSON.stringify({schemaVersion:1,name:'EA-PDF Viewer Lite',sourceRepository:'https://github.com/thinkbox-digital/eapdf',sourceCommit,sourceViewerDirty:dirty,profile:'lite',inputs,files},null,2)+'\n');
 await rename(stage,destination);console.log(`Exported ${seen.size} shared source modules to ${destination}`);
} catch(error){await rm(stage,{recursive:true,force:true});throw error;}
