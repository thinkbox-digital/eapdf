import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
process.chdir(fileURLToPath(new URL('../', import.meta.url)));
const json = async p => JSON.parse(await readFile(p, 'utf8'));
const packageJson = await json('package.json');
const lock = await json('package-lock.json');
for (const field of ['dependencies', 'devDependencies']) {
  assert.deepEqual(lock.packages[''][field], packageJson[field]);
  for (const [name, version] of Object.entries(packageJson[field])) {
    assert.match(version, /^\d+\.\d+\.\d+$/);
    const installed = await json(`node_modules/${name}/package.json`);
    assert.equal(installed.version, version);
    assert.equal(installed.license, ['@xmldom/xmldom','esbuild','acorn'].includes(name)?'MIT':'Apache-2.0');
    assert.equal(lock.packages[`node_modules/${name}`].version, version);
  }
}
for (const [path, item] of Object.entries(lock.packages)) {
  if (!path) continue;
  assert.ok(item.license, `Missing license: ${path}`);
  assert.match(item.integrity, /^sha512-/);
  assert.ok(item.resolved.startsWith('https://registry.npmjs.org/'));
}
const inventory = await json('vendor/pdfjs-pin.json');
assert.equal(inventory.version, packageJson.dependencies['pdfjs-dist']);
for (const [path, expected] of Object.entries(inventory.files)) {
  const actual = createHash('sha256').update(await readFile(`node_modules/pdfjs-dist/${path}`)).digest('hex');
  assert.equal(actual, expected, path);
}
assert.equal(createHash('sha256').update(await readFile('vendor/sources/liberation-fonts-1.07.4.tar.gz')).digest('hex'),
  'ad98b7498dc2992f7f0868f79b65ce4a720a3acdb63ab3f1f1cb6881117a5406');
console.log(`Verified ${Object.keys(lock.packages).length - 1} locked packages and ${Object.keys(inventory.files).length} pinned PDF.js files; notices and font sources present.`);
