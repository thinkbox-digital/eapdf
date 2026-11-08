// Deterministic inventory of shipped components, separate from development tools.
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
export async function makeSbom(root) {
 const read=path=>readFile(new URL(path,root));
 const pkg=JSON.parse(await read('package.json'));
 const pin=JSON.parse(await read('vendor/pdfjs-patches/manifest.json'));
 const materials=[
  ['@xmldom/xmldom',pkg.dependencies['@xmldom/xmldom'],'MIT','xmldom-LICENSE'],
  ['PDF.js',pin.version,'Apache-2.0','vendor/pdfjs/LICENSE'],
  ['Adobe character maps','bundled-'+pin.version,'BSD-style','vendor/pdfjs/cmaps/LICENSE'],
  ['Foxit PDFium standard fonts','bundled-'+pin.version,'BSD-3-Clause','vendor/pdfjs/standard_fonts/LICENSE_FOXIT'],
  ['Liberation Sans','1.07.4','GPL-2.0 with Liberation font exceptions','vendor/pdfjs/standard_fonts/LICENSE_LIBERATION'],
  ['OpenJPEG and PDF.js wrapper','bundled-'+pin.version,'BSD-style','vendor/pdfjs/wasm/LICENSE_OPENJPEG'],
  ['QCMS and PDF.js wrapper','bundled-'+pin.version,'MIT and BSD-style','vendor/pdfjs/wasm/LICENSE_QCMS'],
  ['JBIG2 and PDF.js wrapper','bundled-'+pin.version,'BSD and Apache-2.0','vendor/pdfjs/wasm/LICENSE_JBIG2'],
  ['ICC profiles','bundled-'+pin.version,'CC0-1.0','vendor/pdfjs/iccs/LICENSE'],
 ];
 return {bomFormat:'CycloneDX',specVersion:'1.6',version:1,
  metadata:{component:{type:'application',name:pkg.name,version:pkg.version,licenses:[{license:{id:'Apache-2.0'}}]}},
  components:materials.map(([name,version,license,path])=>({type:'library','bom-ref':name,name,version,licenses:[{license:{name:license}}],properties:[{name:'notice-path',value:path}]})),
  properties:[{name:'pdfjs-upstream-commit',value:pin.upstreamCommit},{name:'pdfjs-patches-manifest-sha256',value:createHash('sha256').update(await read('vendor/pdfjs-patches/manifest.json')).digest('hex')},
   {name:'inventory-note',value:'Bundled asset versions use the pinned PDF.js distribution where upstream does not expose a separate version. Development dependencies are excluded; package-lock.json records those.'}]};
}
