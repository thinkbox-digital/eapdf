// Explicit update step: review changed shared hooks/tests, then rebuild with --write.
import {readFile,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {join} from 'node:path';
import {selectHookTests} from './select-hook-tests.mjs';
const root=fileURLToPath(new URL('../',import.meta.url));
const full=JSON.parse(await readFile(join(root,'vendor/pdfjs-patches/manifest.json'),'utf8'));
for(const kind of ['core','assets'])await writeFile(join(root,`lite/${kind}.test.mjs`),selectHookTests(await readFile(join(root,`vendor/pdfjs-patches/${kind}.test.mjs`),'utf8'),kind));
const patches=full.patches.filter(name=>/\/(0001|0002|0007|0008|0009|0010)-/.test(name));
const names=[...patches,'vendor/pdfjs-patches/build-task.mjs','lite/prepare-hooks.mjs','lite/rebuild-hooks.mjs','lite/core.test.mjs','lite/assets.test.mjs','lite/metadata-hooks.test.mjs'];
const localInputs={};for(const name of names)localInputs[name]=createHash('sha256').update(await readFile(join(root,name))).digest('hex');
let outputs={};try{outputs=JSON.parse(await readFile(join(root,'lite/hooks-manifest.json'),'utf8')).outputs;}catch{for(const name of ['pdf.mjs','pdf.worker.mjs'])outputs['vendor/pdfjs-lite/'+name]=createHash('sha256').update(await readFile(join(root,'vendor/pdfjs-lite',name))).digest('hex');}
await writeFile(join(root,'lite/hooks-manifest.json'),JSON.stringify({profile:'lite',version:full.version,upstreamCommit:full.upstreamCommit,node:full.node,sourceArchiveUrl:full.sourceArchiveUrl,sourceArchiveSha256:full.sourceArchiveSha256,upstreamFiles:full.upstreamFiles,patches,buildTask:'vendor/pdfjs-patches/build-task.mjs',tests:['lite/core.test.mjs','lite/assets.test.mjs','lite/metadata-hooks.test.mjs'],localInputs,outputs},null,2)+'\n');
console.log('Updated Lite hook input expectations; review and rebuild before accepting.');
