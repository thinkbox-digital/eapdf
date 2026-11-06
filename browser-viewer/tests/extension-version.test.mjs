import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {extensionVersion} from '../scripts/extension-version.mjs';
test('browser versions follow package versions through multi-digit beta updates',()=>{
 assert.equal(extensionVersion('0.5.0-beta.9'),'0.5.0.9');
 assert.equal(extensionVersion('0.5.0-beta.10'),'0.5.0.10');
 assert.equal(extensionVersion('1.0.0'),'1.0.0');
 for(const version of ['0.5.0-beta.x','0.5.0-beta.65536','0.05.0-beta.9'])assert.throws(()=>extensionVersion(version));
});
test('both built manifests and viewer footers match the package version',async()=>{
 const {version}=JSON.parse(await readFile(new URL('../package.json',import.meta.url),'utf8'));
 for(const browser of ['chromium','firefox']){
  const base=new URL(`../dist/${browser}/`,import.meta.url);
  const manifest=JSON.parse(await readFile(new URL('manifest.json',base),'utf8'));
  assert.equal(manifest.version,extensionVersion(version),browser);
  assert.ok((await readFile(new URL('viewer.html',base),'utf8')).includes(version),browser);
 }
});
