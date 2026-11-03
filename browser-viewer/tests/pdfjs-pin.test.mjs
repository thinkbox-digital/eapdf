import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { verifyCurrentMetadata, verifyPatchedPdfjs } from '../scripts/pdfjs-patch.mjs';
const read = async name => readFile(new URL(`../${name}`, import.meta.url), 'utf8');
const pin = JSON.parse(await read('vendor/pdfjs-pin.json'));
const pkg = JSON.parse(await read('package.json'));
const lock = JSON.parse(await read('package-lock.json'));
const documents = { readme: await read('README.md'), notices: await read('THIRD_PARTY_NOTICES.md'),
  maintenance: await read('docs/pdfjs-patch-maintenance.md') };
const bundles = { main: await read('vendor/pdfjs-patched/pdf.mjs'), worker: await read('vendor/pdfjs-patched/pdf.worker.mjs') };
test('current PDF.js pins, documentation, package lock and bundles agree', verifyPatchedPdfjs);
for (const key of Object.keys(documents)) test(`stale ${key} commit is rejected`, () => {
  const changed = { ...documents, [key]: documents[key].replace(pin.upstream_commit, 'd0991a0d53f3bb3a7307e3b83f279c1d117aafb3') };
  assert.throws(() => verifyCurrentMetadata(pin, pkg, lock, changed, bundles));
});
for (const key of Object.keys(bundles)) test(`stale ${key} version and commit headers are rejected`, () => {
  for (const [before, after] of [[pin.version, '6.4.299'], [pin.upstream_commit.slice(0, 9), 'd0991a0d5']])
    assert.throws(() => verifyCurrentMetadata(pin, pkg, lock, documents,
      { ...bundles, [key]: bundles[key].replace(before, after) }));
});
test('stale package and lock dependency versions are rejected', () => {
  const changedPackage = structuredClone(pkg); changedPackage.dependencies['pdfjs-dist'] = '6.4.299';
  assert.throws(() => verifyCurrentMetadata(pin, changedPackage, lock, documents, bundles));
  for (const name of ['', 'node_modules/pdfjs-dist']) {
    const changedLock = structuredClone(lock);
    if (name) changedLock.packages[name].version = '6.4.299';
    else changedLock.packages[name].dependencies['pdfjs-dist'] = '6.4.299';
    assert.throws(() => verifyCurrentMetadata(pin, pkg, changedLock, documents, bundles));
  }
});
