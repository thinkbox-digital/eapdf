import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile,readdir,mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {build} from 'esbuild';
import {fileURLToPath} from 'node:url';
import {join} from 'node:path';
import {sha256} from '../scripts/verify-hooks.mjs';
const root=fileURLToPath(new URL('../',import.meta.url));
test('source and bundle exclude search, filtering and metadata exports while keeping asset access',async()=>{
 const names=await readdir(join(root,'src'));
 for(const name of ['search-controller.ts','document-search.ts','collection-search.ts','message-index.ts','message-tree.ts','files-panel.ts','message-download.ts','metadata-csv.ts','metadata-dialog.ts','metadata-export.ts'])assert.ok(!names.includes(name),name);
 const html=await readFile(join(root,'src/viewer.html'),'utf8');
 assert.doesNotMatch(html,/type="search"|search-panel|message-filter|export-csv|metadata-dialog|download-message/);
 const inputs=JSON.parse(await readFile(join(root,'dist/web/bundle-inputs.json'),'utf8'));
 assert.ok(inputs.includes('src/domain/interpret.ts'));
 assert.ok(inputs.includes('src/downloads.ts'));assert.ok(inputs.includes('src/pane-resizer.ts'));assert.ok(inputs.includes('src/asset-panel.ts'));
 assert.ok(inputs.includes('src/viewer-icons.ts'));assert.ok(inputs.includes('src/attachment-viewer.ts'));assert.ok(inputs.includes('src/message-label.ts'));
 assert.ok(!inputs.some(name=>/(?:search|download|export|message-index)\.ts$/.test(name)));
});
test('physical source budget includes shared code, declarations, markup, styles and manifests',async()=>{
 const manifest=JSON.parse(await readFile(join(root,'export-manifest.json'),'utf8'));
 const names=Object.keys(manifest.files).filter(name=>name.startsWith('src/')&&/\.(?:ts|html|css|json)$/.test(name));
 let physical=0,nonblank=0;
 for(const name of names){const lines=(await readFile(join(root,name),'utf8')).split(/\r?\n/);if(lines.at(-1)==='')lines.pop();physical+=lines.length;nonblank+=lines.filter(line=>line.trim()).length;}
 assert.ok(physical<2000,`${physical} physical lines`);
 const info=JSON.parse(await readFile(join(root,'dist/web/build-info.json'),'utf8'));
 assert.deepEqual(info.sourceCounts,{files:names.length,physical,nonblank});
});
test('asset locations distinguish physical attachment pages from owning-message body pages',async()=>{
 const temporary=await mkdtemp(join(tmpdir(),'eapdf-asset-location-'));
 try{
  const output=join(temporary,'location.mjs');await build({entryPoints:[join(root,'src/asset-panel.ts')],outfile:output,bundle:true,format:'esm',platform:'node'});
  const {assetLocation,documentPath}=await import(output);
  const parent={name:'archive.pdf',parent:null,inspection:{collection:{value:{entries:[{fileId:'child',folderIds:{value:['folder']}}],folders:[{id:'folder',name:{value:'Inbox'},parentId:null}]}}}};
  const doc={name:'email.pdf',parent,parentFileId:'child',pdf:{numPages:4},inspection:{collection:{value:null},messages:[{id:'message'}]}};
  const file={id:'asset',filename:{value:'notes.txt'},unicodeFilename:{value:null},owners:{value:['message']},origins:[{kind:'name-tree',pageIndex:null}]};
  assert.equal(documentPath(doc),'archive.pdf / Inbox / email.pdf');
  let location=assetLocation(doc,file);assert.deepEqual(location.pages,[]);assert.equal(location.owners.length,1);
  location=assetLocation(doc,{...file,origins:[{kind:'annotation',pageIndex:2},{kind:'annotation',pageIndex:2},{kind:'annotation',pageIndex:99}]});
  assert.deepEqual(location.pages,[3]);
  assert.deepEqual(assetLocation(doc,{...file,origins:[],owners:{value:null}}),{pages:[],owners:[]});
 }finally{await rm(temporary,{recursive:true,force:true});}
});
test('standalone provenance verifies all exported source bytes',async()=>{
 const manifest=JSON.parse(await readFile(join(root,'export-manifest.json'),'utf8'));
 assert.equal(manifest.profile,'even-more-lite');
 for(const [name,digest]of Object.entries(manifest.files))assert.equal(sha256(await readFile(join(root,name))),digest,name);
});
test('browser packages have independent identity, local-only permissions and verified payloads',async()=>{
 for(const browser of ['web','chromium','firefox']){
  const directory=join(root,'dist',browser),info=JSON.parse(await readFile(join(directory,'build-info.json'),'utf8'));
  assert.equal(info.profile,'even-more-lite');assert.equal(info.pdfjsMinified,true);
  for(const [name,digest]of Object.entries(info.files))assert.equal(sha256(await readFile(join(directory,name))),digest,name);
  if(browser==='web')continue;
  const manifest=JSON.parse(await readFile(join(directory,'manifest.json'),'utf8'));
  assert.equal(manifest.name,'EA-PDF Even More Lite');assert.deepEqual(manifest.permissions,[]);
  assert.equal(manifest.optional_host_permissions,undefined);
  assert.doesNotMatch(manifest.content_security_policy.extension_pages,/https?:/);
  if(browser==='firefox')assert.equal(manifest.browser_specific_settings.gecko.id,'eapdf-viewer-even-more-lite@thinkbox.local');
 }
});
