// Upstream's official Node compatibility polyfills for browser bundles.
import 'pdfjs-dist/legacy/build/pdf.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile,mkdtemp,rm,access} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {build} from 'esbuild';
// The browser-targeted bundle expects the standard toHex API missing in Node 24.
const root=fileURLToPath(new URL('../',import.meta.url));
test('bundled workers preserve RTL ligatures for extraction and viewer search',async()=>{
 const temporary=await mkdtemp(join(tmpdir(),'eapdf-rtl-tests-'));
 try {
  const matcher=join(temporary,'matcher.mjs');await build({entryPoints:[join(root,'src/document-search.ts')],outfile:matcher,bundle:true,platform:'node',format:'esm'});
  const {pdfSearchText,textMatches}=await import(pathToFileURL(matcher));
  for(const profile of ['pdfjs-patched','pdfjs-lite']){
   const module=join(root,'vendor',profile,'pdf.mjs');try{await access(module);}catch{continue;}
   const {getDocument,GlobalWorkerOptions}=await import(pathToFileURL(module));GlobalWorkerOptions.workerSrc=pathToFileURL(join(root,'vendor',profile,'pdf.worker.mjs')).href;
   const task=getDocument({data:new Uint8Array(await readFile(join(root,'tests/fixtures/issue16880.pdf'))),disableFontFace:true,isEvalSupported:false,standardFontDataUrl:join(root,'node_modules/pdfjs-dist/standard_fonts/')});
   try{const pdf=await task.promise,page=await pdf.getPage(1);
    for(const disableNormalization of [false,true]){
     const content=await page.getTextContent({disableNormalization}),text=pdfSearchText(content.items);
     assert.equal(text.trim(),'سلام کلاس لاله',profile);assert.equal(textMatches(text,'سلام').length,1,profile);
    }
   }finally{await task.destroy();}
  }
 }finally{await rm(temporary,{recursive:true,force:true});}
});
