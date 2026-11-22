import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { publishViewerBuilds } from '../scripts/publish-viewer-builds.mjs';

async function build(root, browser, contents) {
  const output = join(root, browser);
  await mkdir(output, { recursive: true });
  await writeFile(join(output, 'viewer.js'), contents);
  const files = { 'viewer.js': createHash('sha256').update(contents).digest('hex') };
  await writeFile(join(output, 'build-info.json'), JSON.stringify({ files }));
}

test('verified build replacement retains changes and unrelated files', async t => {
  const work = await mkdtemp(join(tmpdir(), 'eapdf-build-publish-'));
  t.after(() => rm(work, { recursive: true, force: true }));
  const dist = join(work, 'dist');
  const stage = join(work, 'stage');
  await build(dist, 'chromium', 'installed');
  await build(dist, 'firefox', 'managed and unchanged');
  await writeFile(join(dist, 'chromium', 'viewer.js'), 'modified');
  await writeFile(join(dist, 'chromium', '__proto__'), 'unrelated');
  await writeFile(join(dist, 'notes.txt'), 'user notes');
  await build(stage, 'chromium', 'new chromium');
  await build(stage, 'firefox', 'new firefox');
  await publishViewerBuilds(stage, dist, ['chromium', 'firefox']);
  assert.equal(await readFile(join(dist, 'chromium', 'viewer.js'), 'utf8'), 'new chromium');
  const saved = (await readdir(dist)).find(name => name.startsWith('.preserved-'));
  assert.deepEqual(await readdir(join(dist, saved)), ['chromium']);
  assert.equal(await readFile(join(dist, saved, 'chromium', 'viewer.js'), 'utf8'), 'modified');
  assert.equal(await readFile(join(dist, saved, 'chromium', '__proto__'), 'utf8'), 'unrelated');
  assert.equal(await readFile(join(dist, 'notes.txt'), 'utf8'), 'user notes');
});

test('a later destination conflict restores the previous build', async t => {
  const work = await mkdtemp(join(tmpdir(), 'eapdf-build-rollback-'));
  t.after(() => rm(work, { recursive: true, force: true }));
  const dist = join(work, 'dist');
  const stage = join(work, 'stage');
  await build(dist, 'chromium', 'installed');
  await writeFile(join(dist, 'firefox'), 'operator file');
  await build(stage, 'chromium', 'new chromium');
  await build(stage, 'firefox', 'new firefox');
  await assert.rejects(publishViewerBuilds(stage, dist, ['chromium', 'firefox']), /non-directory/);
  assert.equal(await readFile(join(dist, 'chromium', 'viewer.js'), 'utf8'), 'installed');
  assert.equal(await readFile(join(dist, 'firefox'), 'utf8'), 'operator file');
  assert.deepEqual((await readdir(dist)).sort(), ['chromium', 'firefox']);
});
