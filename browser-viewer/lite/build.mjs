// Standalone Lite builder: no parent project, Java, Python, or network needed.
import {cp,mkdir,mkdtemp,readFile,readdir,rm,writeFile} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {build,transform} from 'esbuild';
import {verifyHooks,sha256} from './verify-hooks.mjs';
import {makeSbom} from './sbom.mjs';
const root=fileURLToPath(new URL('../',import.meta.url));process.chdir(root);
const manifest=await verifyHooks(root),pkg=JSON.parse(await readFile('package.json','utf8'));
execFileSync(process.execPath,['scripts/licenses.mjs'],{stdio:'inherit'});
execFileSync(process.execPath,['node_modules/typescript/bin/tsc','--noEmit'],{stdio:'inherit'});
const temp=await mkdtemp(join(tmpdir(),'eapdf-lite-build-'));
const hashes=async(directory,prefix='')=>{
 const result={};for(const item of (await readdir(directory,{withFileTypes:true})).sort((a,b)=>a.name.localeCompare(b.name,'en'))) {
  const name=prefix+item.name;if(item.isDirectory())Object.assign(result,await hashes(join(directory,item.name),name+'/'));
  else result[name]=sha256(await readFile(join(directory,item.name)));
 }return result;
};
try {
 await build({entryPoints:['src/viewer.ts'],outfile:join(temp,'viewer.js'),bundle:true,format:'esm',platform:'browser',target:'es2022',minifySyntax:true,legalComments:'inline',metafile:true}).then(async result=>{
  // A second syntax pass folds enum constants after cross-module inlining.
  const optimized=await transform(await readFile(join(temp,'viewer.js'),'utf8'),{format:'esm',target:'es2022',minifySyntax:true,legalComments:'inline'});
  for(const forbidden of ['interpret-worker.js','batch.used','eapdf-search-index','DecompressionStream','PortfolioView','PortfolioModel','PortfolioThumbnails'])if(optimized.code.includes(forbidden))throw Error(`Excluded implementation reached Lite: ${forbidden}`);
  await writeFile(join(temp,'viewer.js'),optimized.code);
  await writeFile(join(temp,'bundle-inputs.json'),JSON.stringify(Object.keys(result.metafile.inputs).sort(),null,2)+'\n');
  for(const name of Object.keys(result.metafile.inputs))if(/(?:thread-(?:preview|graph|worker)|portfolio(?:-view|-scatter|-splitter|-thumbnails)?)\.ts$/.test(name))throw Error(`Excluded presentation reached the Lite bundle: ${name}`);
 });
 await build({entryPoints:['src/extension/background.ts'],outfile:join(temp,'background.js'),bundle:true,format:'esm',platform:'browser',target:'es2022'});
 for(const browser of ['chromium','firefox','web']) {
  const directory=join('dist',browser);await rm(directory,{recursive:true,force:true});await mkdir(join(directory,'vendor/pdfjs'),{recursive:true});
  await cp(temp,directory,{recursive:true});
  for(const name of ['pdf.mjs','pdf.worker.mjs'])await cp(join('vendor/pdfjs-patched',name),join(directory,'vendor/pdfjs',name));
  for(const name of ['cmaps','standard_fonts','wasm','iccs','LICENSE'])await cp(join('node_modules/pdfjs-dist',name),join(directory,'vendor/pdfjs',name),{recursive:true});
  await cp('node_modules/pdfjs-dist/web/pdf_viewer.mjs',join(directory,'vendor/pdfjs/pdf_viewer.mjs'));
  for(const name of ['quickjs-eval.js','quickjs-eval.wasm','LICENSE_QUICKJS','LICENSE_PDFJS_QUICKJS'])await rm(join(directory,'vendor/pdfjs/wasm',name),{force:true});
  for(const name of ['LICENSE','THIRD_PARTY_NOTICES.md','INSTALL.txt'])await cp(name,join(directory,name));
  await cp('vendor/sources',join(directory,'license-sources'),{recursive:true});
  await cp('src/icons',join(directory,'icons'),{recursive:true});await cp('assets',join(directory,'assets'),{recursive:true});
  await cp('src/viewer.css',join(directory,'viewer.css'));
  await writeFile(join(directory,'viewer.html'),(await readFile('src/viewer.html','utf8')).replaceAll('__VIEWER_VERSION__',pkg.version));
  if(browser!=='web') {
   const extension=JSON.parse(await readFile('src/extension/manifest.json','utf8'));extension.version=pkg.version.replace('-beta.','.');
   if(browser==='chromium'){extension.background={service_worker:'background.js',type:'module'};extension.minimum_chrome_version='156';}
   else {extension.background={scripts:['background.js'],type:'module'};extension.browser_specific_settings={gecko:{id:'eapdf-viewer-lite@thinkbox.local',strict_min_version:'158.0',data_collection_permissions:{required:['none']}}};}
   await writeFile(join(directory,'manifest.json'),JSON.stringify(extension,null,2)+'\n');
  }else await rm(join(directory,'background.js'));
  const sbom=await makeSbom(new URL('../',import.meta.url));
  sbom.components=sbom.components.filter(component=>component.name!=='@xmldom/xmldom');
  await writeFile(join(directory,'SBOM.cdx.json'),JSON.stringify(sbom,null,2)+'\n');
  await writeFile(join(directory,'build-info.json'),JSON.stringify({project:pkg.version,name:'EA-PDF Viewer Lite',profile:'lite',pdfjs:manifest.version,pdfjsCommit:manifest.upstreamCommit,hookManifestSha256:sha256(await readFile('vendor/pdfjs-patches/manifest.json')),provenanceSha256:sha256(await readFile('export-manifest.json')),files:await hashes(directory)},null,2)+'\n');
  console.log(`Built EA-PDF Viewer Lite: ${directory}`);
 }
} finally {await rm(temp,{recursive:true,force:true});}
