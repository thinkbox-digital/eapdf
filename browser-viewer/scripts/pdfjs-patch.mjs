import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
export const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
export async function verifyPatchedPdfjs() {
  const manifest = JSON.parse(await readFile(resolve(root, 'vendor/pdfjs-patches/manifest.json'), 'utf8'));
  const upstream = JSON.parse(await readFile(resolve(root, 'vendor/pdfjs-pin.json'), 'utf8'));
  assert.equal(manifest.upstreamCommit, upstream.upstream_commit);
  assert.equal(manifest.version, upstream.version);
  for (const [name, hash] of Object.entries({ ...manifest.localInputs, ...manifest.outputs })) {
    assert.equal(sha256(await readFile(resolve(root, name))), hash, `Patched PDF.js input/output changed: ${name}`);
  }
  return manifest;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const manifest = await verifyPatchedPdfjs();
  console.log(`Verified PDF.js ${manifest.version} source patch and both generated bundles.`);
}
