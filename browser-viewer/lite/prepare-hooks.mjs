// Derive the generic hook subset from the authoritative, hash-pinned patches.
// Never apply 0003: Lite retains upstream xref storage and parsed-object caching.
import {readFile,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';

export async function prepareHooks(root,source,patches) {
 const patch = async prefix => {
  const name=patches.find(name=>name.split('/').at(-1).startsWith(prefix));
  assert.ok(name,`Missing selected hook patch ${prefix}`);
  return {name,text:await readFile(join(root,name),'utf8')};
 };
 const replace=async(name,before,after)=>{
  const path=join(source,name),text=await readFile(path,'utf8');
  assert.equal(text.split(before).length-1,1,`Review Lite adaptation: ${name}: ${before.slice(0,80)}`);
  await writeFile(path,text.replace(before,after));
 };
 for(const prefix of ['0001-','0002-']) {
  const {name}=await patch(prefix);
  execFileSync('git',['apply','--check',join(root,name)],{cwd:source});
  execFileSync('git',['apply',join(root,name)],{cwd:source});
 }
 await replace('src/core/document_assets.js','  inspect(options = {}) {','  async inspect(options = {}) {');
 await replace('src/core/catalog.js','const result = this._documentAssets.inspect(options);','const result = await this._documentAssets.inspect(options);');
 // These low-context upstream-facing patches were recorded after 0003/0004.
 // Match every old hunk exactly once; offsets may differ, content may not.
 for(const prefix of ['0007-']) {
  let {text}=await patch(prefix);
  if(prefix==='0007-')text=text.replaceAll(' import { yieldInspection } from "./inspection_yield.js";\n','').replace('     let pageIndices;\n','');
  for(const section of text.split(/(?=^--- )/m).filter(Boolean)) {
   const lines=section.split('\n'),old=lines.shift().slice(4),name=lines.shift().slice(6);
   assert.ok(name.startsWith('src/core/')&&!name.includes('..'));
   if(name==='src/core/document_assets.js'||name==='src/core/collection_presentation.js')continue;
   const path=join(source,name);let content=old==='/dev/null'?'':await readFile(path,'utf8');
   const hunks=lines.join('\n').split(/^@@[^\n]*\n/m).slice(1);
   for(const hunk of hunks) {
    const records=hunk.match(/[^\n]*\n|[^\n]+$/g)??[];
    const before=records.filter(line=>line.startsWith(' ')||line.startsWith('-')).map(line=>line.slice(1)).join('');
    const after=records.filter(line=>line.startsWith(' ')||line.startsWith('+')).map(line=>line.slice(1)).join('');
    assert.equal(before?content.split(before).length-1:content.length, before?1:0,`Review Lite hook context: ${name}`);
    content=before?content.replace(before,after):after;
   }
   if(name==='src/core/collection_navigation.js') {
    const marker='export async function folderThumbnail(image, xref) {';
    assert.equal(content.split(marker).length,2,'Review optional thumbnail extraction');
    content=content.slice(0,content.indexOf(marker));
    for(const line of ['import { PDFImage } from "./image.js";\n','import { PDFFunctionFactory } from "./function.js";\n','import { GlobalColorSpaceCache, LocalColorSpaceCache } from "./image_utils.js";\n','import { BaseStream } from "./base_stream.js";\n']) {
     assert.ok(content.includes(line));content=content.replace(line,'');
    }
   }
   await writeFile(path,content);
  }
 }
 // Preserve patch 0008's raw-XMP API without its private decoder reclamation.
 await replace('src/core/document_assets.js','    this.registered = new Map();','    this.registered = new Map();\n    this.metadataStreams = new Map();');
 await replace('src/core/document_assets.js','              metadata.set(mloc.path, item);','              metadata.set(mloc.path, item);\n              this.metadataStreams.set(mloc.path, rawMeta);');
 await replace('src/core/document_assets.js','read({ documentId, fileId, streamId, maxBytes })','read({ documentId, fileId, streamId, metadataId, maxBytes })');
 const metadata=(await patch('0008-')).text;
 const start=metadata.indexOf('+    if (metadataId !== undefined)'),end=metadata.indexOf('\n     const registered =',start);
 assert.ok(start>=0&&end>start);
 let block=metadata.slice(start,end).split('\n').map(line=>{assert.ok(line.startsWith('+'));return line.slice(1);}).join('\n');
 const cleanup='      try { return boundedBytes(stream, maxBytes); }\n      finally { if (raw instanceof Ref) discardStructuralStream(stream); }';
 assert.ok(block.includes(cleanup));block=block.replace(cleanup,'      return boundedBytes(stream, maxBytes);');
 await replace('src/core/document_assets.js','    const registered = this.registered.get(fileId + "|" + streamId);',block+'\n    const registered = this.registered.get(fileId + "|" + streamId);');
 const {name}=await patch('0009-');execFileSync('git',['apply','--check',join(root,name)],{cwd:source});execFileSync('git',['apply',join(root,name)],{cwd:source});
}
