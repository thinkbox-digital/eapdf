// Reproduce the two patched bundles in a fresh temporary upstream source tree.
import { appendFile, cp, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { sha256 } from './pdfjs-patch.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const manifestPath = join(root, 'vendor/pdfjs-patches/manifest.json');
const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
const args = process.argv.slice(2);
const archiveIndex = args.indexOf('--archive');
const suppliedArchive = archiveIndex < 0 ? null : args[archiveIndex + 1];
if (archiveIndex >= 0 && !suppliedArchive) throw new Error('--archive requires a path');
const write = args.includes('--write');
assert.equal(process.versions.node, manifest.node, 'Use the pinned Node version');
const temporary = await mkdtemp(join(tmpdir(), 'eapdf-pdfjs-source-'));
const run = (command, arguments_, options = {}) => execFileSync(command, arguments_, {
  cwd: temporary, stdio: 'inherit', env: { ...process.env, PUPPETEER_SKIP_DOWNLOAD: 'true', TESTING: 'false' }, ...options,
});
try {
  for (const [name, hash] of Object.entries(manifest.localInputs)) {
    assert.equal(sha256(await readFile(join(root, name))), hash, name);
  }
  const archive = suppliedArchive ? resolve(suppliedArchive) : join(temporary, 'source.tar.gz');
  if (!suppliedArchive) run('curl', ['-fL', '--retry', '2', manifest.sourceArchiveUrl, '-o', archive]);
  assert.equal(sha256(await readFile(archive)), manifest.sourceArchiveSha256, 'Upstream archive hash mismatch');
  run('tar', ['-xzf', archive, '--strip-components=1', '-C', temporary]);
  for (const [name, hash] of Object.entries(manifest.upstreamFiles)) {
    assert.equal(sha256(await readFile(join(temporary, name))), hash, name);
  }
  for (const name of manifest.patches ?? ['vendor/pdfjs-patches/0001-document-parts.patch']) {
    const patch = join(root, name);
    run('git', ['apply', '--check', patch]);
    run('git', ['apply', patch]);
  }
  console.log('Source patch applied cleanly to the hash-verified upstream archive.');
  run('npm', ['ci', '--ignore-scripts', '--no-audit', '--no-fund']);
  assert.equal(sha256(await readFile(join(temporary, 'package-lock.json'))), manifest.upstreamFiles['package-lock.json']);
  await mkdir(join(temporary, 'build'), { recursive: true });
  await writeFile(join(temporary, 'build/version.json'), JSON.stringify({ version: manifest.version,
    build: Number(manifest.version.split(".").at(-1)), commit: manifest.upstreamCommit.slice(0, 9) }) + '\n');
  await appendFile(join(temporary, 'gulpfile.mjs'), '\n' + await readFile(join(root, 'vendor/pdfjs-patches/build-task.mjs'), 'utf8'));
  run(process.execPath, ['node_modules/gulp/bin/gulp.js', 'document-parts-bundle']);
  run(process.execPath, ['--test', join(root, 'vendor/pdfjs-patches/core.test.mjs'), join(root, 'vendor/pdfjs-patches/assets.test.mjs')], {
    env: { ...process.env, PDFJS_SOURCE: temporary },
  });
  // Source tests need the same upstream core-js Map support as the legacy build on Node 24.
  await writeFile(join(temporary, 'm2-upstream-tests.mjs'), `
import 'core-js/actual/map/get-or-insert-computed.js';
import Jasmine from 'jasmine';
const runner = new Jasmine();
runner.loadConfig({ spec_dir: '.', spec_files: ['test/unit/catalog_spec.js', 'test/unit/primitives_spec.js', 'test/unit/message_handler_spec.js', 'test/unit/stream_spec.js', 'test/unit/parser_spec.js', 'test/unit/evaluator_spec.js', 'test/unit/xml_parser_spec.js'], helpers: ['test/unit/clitests_helper.js'] });
runner.env.configure({ random: false });
await runner.execute();
`);
  run(process.execPath, ['m2-upstream-tests.mjs']);
  for (const filename of ['pdf.mjs', 'pdf.worker.mjs']) {
    const name = `vendor/pdfjs-patched/${filename}`;
    const artifact = join(temporary, 'build/document-parts', filename);
    const hash = sha256(await readFile(artifact));
    if (write) {
      await mkdir(join(root, 'vendor/pdfjs-patched'), { recursive: true });
      await cp(artifact, join(root, name));
      manifest.outputs[name] = hash;
    } else assert.equal(hash, manifest.outputs[name], `Rebuilt ${filename} differs`);
  }
  if (write) await writeFile(manifestPath, JSON.stringify(manifest, null, 2) + '\n');
  console.log(write ? 'Wrote generated bundles and output hashes; review before accepting.' : 'Both rebuilt bundles are byte-identical to the pinned artifacts.');
} finally {
  await rm(temporary, { recursive: true, force: true });
}
