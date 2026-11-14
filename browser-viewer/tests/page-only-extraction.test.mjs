// Use upstream's official Node compatibility polyfills for its browser bundles.
import 'pdfjs-dist/legacy/build/pdf.mjs';
import test from 'node:test';import assert from 'node:assert/strict';
import {access} from 'node:fs/promises';import {fileURLToPath,pathToFileURL} from 'node:url';import {join} from 'node:path';
import {downloadScopeFixture} from '../scripts/download-scope-fixture.mjs';
const root=fileURLToPath(new URL('../',import.meta.url));
test('page-only extraction removes archive graphs while default extraction keeps its behavior',async()=>{
 for(const profile of ['pdfjs-patched','pdfjs-lite']){
  const module=join(root,'vendor',profile,'pdf.mjs');try{await access(module);}catch{continue;}
  const {getDocument,GlobalWorkerOptions}=await import(pathToFileURL(module));GlobalWorkerOptions.workerSrc=pathToFileURL(join(root,'vendor',profile,'pdf.worker.mjs')).href;
  const source=getDocument({data:new Uint8Array(downloadScopeFixture()),isEvalSupported:false,standardFontDataUrl:join(root,'node_modules/pdfjs-dist/standard_fonts/')});
  try{const pdf=await source.promise;assert.equal((await pdf.getDocumentAssets()).files.length,1);
   for(const pageOnly of [false,true]){
    const bytes=await pdf.extractPages([{document:null,includePages:[0]}],null,pageOnly?{pageOnly:true}:undefined);assert.ok(bytes?.length);
    const copy=getDocument({data:bytes,isEvalSupported:false,standardFontDataUrl:join(root,'node_modules/pdfjs-dist/standard_fonts/')});try{const result=await copy.promise;assert.equal(result.numPages,1);
     const assets=await result.getDocumentAssets();assert.equal(assets.files.length,pageOnly?0:1,profile);
     if(pageOnly){assert.equal(assets.catalogMetadata,null);assert.equal((await result.getDocumentParts()).status,'missing');assert.equal((await result.getMetadata()).info.Title,undefined);
      const page=await result.getPage(1);assert.deepEqual(page.view,[0,0,320,240]);assert.deepEqual((await page.getTextContent()).items.filter(i=>'str'in i).map(i=>i.str),['SELECTED BODY']);assert.deepEqual(await page.getAnnotations(),[]);
     }
    }finally{await copy.destroy();}
   }
  }finally{await source.destroy();}
 }
});
