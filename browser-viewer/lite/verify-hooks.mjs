import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {join} from 'node:path';
import assert from 'node:assert/strict';
export const sha256=bytes=>createHash('sha256').update(bytes).digest('hex');
export async function verifyHooks(root=fileURLToPath(new URL('../',import.meta.url))) {
 const manifest=JSON.parse(await readFile(join(root,'vendor/pdfjs-patches/manifest.json'),'utf8'));
 for(const [name,digest]of Object.entries({...manifest.localInputs,...manifest.outputs}))assert.equal(sha256(await readFile(join(root,name))),digest,`Lite hook input/output changed: ${name}`);
 const pkg=JSON.parse(await readFile(join(root,'package.json'),'utf8'));
 assert.equal(pkg.dependencies['pdfjs-dist'],manifest.version);
 assert.ok(manifest.patches.every(name=>!name.includes('0003-')&&!name.includes('0006-')));
 const workerName=Object.keys(manifest.outputs).find(name=>name.endsWith('pdf.worker.mjs'));
 const worker=await readFile(join(root,workerName),'utf8');
 for(const name of ['function collectionPresentation','function folderThumbnail'])assert.ok(!worker.includes(name),`Excluded worker feature: ${name}`);
 return manifest;
}
if(process.argv[1]===fileURLToPath(import.meta.url)){await verifyHooks();console.log('Verified Lite hook source and bundle hashes.');}
