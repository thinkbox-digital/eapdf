import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
export const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
export function verifyCurrentMetadata(pin, pkg, lock, documents, bundles) {
  assert.equal(pkg.dependencies['pdfjs-dist'], pin.version, 'package.json PDF.js version');
  assert.equal(lock.packages[''].dependencies['pdfjs-dist'], pin.version, 'lock root PDF.js version');
  assert.equal(lock.packages['node_modules/pdfjs-dist'].version, pin.version, 'lock resolved PDF.js version');
  assert.equal(lock.packages['node_modules/pdfjs-dist'].integrity, pin.npm_integrity, 'lock PDF.js integrity');
  assert.equal(documents.readme.match(/PDF\.js upstream commit: `([a-f0-9]+)`/)?.[1], pin.upstream_commit, 'README upstream commit');
  assert.equal(documents.readme.match(/and PDF\.js \*\*([\d.]+)\*\*/)?.[1], pin.version, 'README PDF.js version');
  assert.equal(documents.notices.match(/pinned source README\]\(https:\/\/github\.com\/mozilla\/pdf\.js\/blob\/([a-f0-9]+)\//)?.[1], pin.upstream_commit, 'notices source link');
  assert.equal(documents.maintenance.match(/pinned to \*\*([\d.]+)\*\*/)?.[1], pin.version, 'maintenance version');
  assert.equal(documents.maintenance.match(/commit\s+`([a-f0-9]+)`/)?.[1], pin.upstream_commit, 'maintenance commit');
  for (const [name, text] of Object.entries(bundles)) {
    assert.equal(text.match(/pdfjsVersion = ([\d.]+)/)?.[1], pin.version, `${name} version header`);
    assert.equal(text.match(/pdfjsBuild = ([a-f0-9]+)/)?.[1], pin.upstream_commit.slice(0, 9), `${name} commit header`);
  }
}

export async function verifyPatchedPdfjs() {
  const manifest = JSON.parse(await readFile(resolve(root, 'vendor/pdfjs-patches/manifest.json'), 'utf8'));
  const upstream = JSON.parse(await readFile(resolve(root, 'vendor/pdfjs-pin.json'), 'utf8'));
  assert.equal(manifest.upstreamCommit, upstream.upstream_commit);
  assert.equal(manifest.version, upstream.version);
  for (const [name, hash] of Object.entries({ ...manifest.localInputs, ...manifest.outputs })) {
    assert.equal(sha256(await readFile(resolve(root, name))), hash, `Patched PDF.js input/output changed: ${name}`);
  }
  const read = name => readFile(resolve(root, name), 'utf8');
  verifyCurrentMetadata(upstream, JSON.parse(await read('package.json')), JSON.parse(await read('package-lock.json')),
    { readme: await read('README.md'), notices: await read('THIRD_PARTY_NOTICES.md'),
      maintenance: await read('docs/pdfjs-patch-maintenance.md') },
    Object.fromEntries(await Promise.all(Object.keys(manifest.outputs).map(async name => [name, await read(name)]))));
  return manifest;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const manifest = await verifyPatchedPdfjs();
  console.log(`Verified PDF.js ${manifest.version} source patch and both generated bundles.`);
}
