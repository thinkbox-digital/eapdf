import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';

test('Both manifests limit permissions to explicit opening and optional site access', async () => {
  for (const browser of ['chromium', 'firefox']) {
    const manifest = JSON.parse(await readFile(`dist/${browser}/manifest.json`, 'utf8'));
    assert.equal(manifest.manifest_version, 3);
    assert.deepEqual(manifest.permissions, ['contextMenus', 'activeTab']);
    assert.deepEqual(manifest.optional_host_permissions, ['http://*/*', 'https://*/*']);
    for (const name of ['host_permissions', 'content_scripts', 'web_accessible_resources']) assert.equal(manifest[name], undefined);
    assert.ok(manifest.content_security_policy.extension_pages.includes("connect-src 'self' http: https:"));
    assert.ok(!manifest.content_security_policy.extension_pages.includes("'unsafe-eval'"));
    assert.ok(manifest.content_security_policy.extension_pages.includes("object-src 'none'"));
    const wasm = await readdir(`dist/${browser}/vendor/pdfjs/wasm`);
    assert.ok(!wasm.some(name => /quickjs|sandbox/i.test(name)));
    for (const name of ['pdf.mjs', 'pdf.worker.mjs']) {
      const source = await readFile(`dist/${browser}/vendor/pdfjs/${name}`, 'utf8');
      assert.doesNotMatch(source, /\beval\s*\(|\bnew\s+Function\s*\(/);
    }
    if (browser === 'chromium') assert.equal(manifest.background.service_worker, 'background.js');
    else assert.deepEqual(manifest.background.scripts, ['background.js']);
  }
});

test('packaged assets match hashes and contain the reproducible patched worker', async () => {
  for (const browser of ['chromium', 'firefox']) {
    const base = `dist/${browser}`;
    const info = JSON.parse(await readFile(`${base}/build-info.json`, 'utf8'));
    for (const [name, hash] of Object.entries(info.files)) {
      assert.equal(createHash('sha256').update(await readFile(`${base}/${name}`)).digest('hex'), hash, name);
    }
    const patch = JSON.parse(await readFile('vendor/pdfjs-patches/manifest.json', 'utf8'));
    assert.equal(info.pdfjsPatch, patch.localInputs['vendor/pdfjs-patches/0001-document-parts.patch']);
    for (const file of ['pdf.mjs', 'pdf.worker.mjs']) {
      assert.deepEqual(await readFile(`${base}/vendor/pdfjs/${file}`), await readFile(`vendor/pdfjs-patched/${file}`));
    }
    assert.ok(info.files['license-sources/liberation-fonts-1.07.4.tar.gz']);
  }
});

test('ordinary fixture is deterministic and matches its recorded hash', async () => {
  const before = await readFile('tests/fixtures/ordinary.pdf');
  execFileSync(process.execPath, ['scripts/make-fixture.mjs']);
  assert.deepEqual(await readFile('tests/fixtures/ordinary.pdf'), before);
  const fixture = JSON.parse(await readFile('tests/fixtures/manifest.json', 'utf8'));
  assert.equal(createHash('sha256').update(before).digest('hex'), fixture.sha256);
});

test('dependency lock and redistribution notices match the pinned inventory', () => {
  execFileSync(process.execPath, ['scripts/licenses.mjs'], { stdio: 'inherit' });
});
