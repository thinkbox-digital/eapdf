// Real display/worker boundary, offline extension, independent frozen M1 oracle.
import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

process.chdir(fileURLToPath(new URL('../', import.meta.url)));
const manifest = JSON.parse(await readFile('tests/inspection/manifest.json', 'utf8'));
const cases = [
  ['example-1s', 'present', []], ['example-1m', 'present', []],
  ['s-invalid-page-ref', 'partial', ['INVALID_PAGE_RANGE']],
  ['mi-reversed-range', 'partial', ['INVALID_PAGE_RANGE']],
  ['s-dpart-cycle', 'partial', ['REPEATED_DPART']], ['ordinary', 'missing', []],
];
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
function refPath(ref) { return ref ? `object:${ref.num}:${ref.gen}` : null; }
function compareParts(actual, expected) {
  const expectedId = id => id?.split('#')[1] ?? null;
  assert.equal(actual.parts.length, expected.parts.length);
  for (let i = 0; i < expected.parts.length; i++) {
    const a = actual.parts[i], e = expected.parts[i];
    assert.equal(a.locator.path, e.locator.path);
    assert.deepEqual(a.locator.ref, e.locator.objectNumber === null ? null : { num: e.locator.objectNumber, gen: e.locator.generation });
    assert.equal(a.parent?.path ?? null, expectedId(e.parentId));
    assert.deepEqual(a.children.map(c => c.path), e.childIds.map(expectedId));
    assert.equal(refPath(a.declaredParent.ref), expectedId(e.declaredParent.value?.[0]));
    assert.equal(refPath(a.start.ref), expectedId(e.startReference.value?.[0]));
    assert.equal(refPath(a.end.ref), expectedId(e.endReference.value?.[0]));
    assert.equal(a.pageRange.state, e.pageRange.state);
    if (e.pageRange.state === 'present') {
      const { state, ...range } = a.pageRange;
      assert.deepEqual(range, e.pageRange.value);
    } else {
      assert.equal(a.pageRange.start, null); assert.equal(a.pageRange.end, null);
    }
    const fields = Object.fromEntries(a.dpm.entries.map(item => [item.key, item.value]));
    assert.deepEqual(Object.keys(fields).sort(), Object.keys(e.dpm).sort());
    for (const [key, observation] of Object.entries(e.dpm)) {
      assert.equal(observation.state, 'present', 'selected parity fixtures use scalar DPM values');
      const value = fields[key];
      assert.equal(value.value, observation.value, key);
      assert.equal(`pdf-${value.kind}`, observation.raw[0].kind, key);
      // PDF.js name bytes exclude the lexical slash; the independent oracle includes it.
      assert.equal((value.kind === 'name' ? '2f' : '') + value.bytesHex, observation.raw[0].bytesHex, key);
    }
  }
}

const extension = resolve('dist/chromium');
const context = await chromium.launchPersistentContext('', {
  ...(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : { channel: 'chromium' }),
  headless: true, args: [`--disable-extensions-except=${extension}`, `--load-extension=${extension}`],
});
const requests = [], errors = [], checked = [];
try {
  context.setDefaultTimeout(30_000);
  context.on('request', request => { if (/^https?:/.test(request.url())) requests.push(request.url()); });
  await context.setOffline(true);
  const service = context.serviceWorkers()[0] ?? await context.waitForEvent('serviceworker');
  const page = await context.newPage();
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(`chrome-extension://${new URL(service.url()).host}/viewer.html`);
  await page.locator('#file').waitFor();
  const docIds = new Set();
  for (const [id, status, codes] of cases) {
    const fixture = manifest.fixtures.find(c => c.id === id);
    assert.ok(fixture, id);
    const data = await readFile(resolve('..', fixture.sourcePath));
    const expectedBytes = await readFile(resolve('tests/inspection', fixture.expected));
    assert.equal(digest(data), fixture.sha256, `${id} input changed`);
    assert.equal(digest(expectedBytes), fixture.expectedSha256, `${id} independent expectation changed`);
    const expected = JSON.parse(expectedBytes);
    const response = await page.evaluate(async base64 => {
      const { loadPdf } = await import('./pdfjs-adapter.js');
      const task = loadPdf(Uint8Array.from(atob(base64), c => c.charCodeAt(0)));
      try {
        const pdf = await task.promise;
        const result = await pdf.getDocumentParts();
        // Calling twice also verifies caller-owned records cannot mutate worker state.
        const snapshot = JSON.stringify(result);
        if (result.parts.length) result.parts[0].children.push({ ref: null, path: 'caller-only' });
        const again = await pdf.getDocumentParts();
        if (JSON.stringify(again) !== snapshot) throw new Error('Caller mutated worker data');
        const pageRefs = [];
        for (let index = 0; index < pdf.numPages; index++) {
          const page = await pdf.getPage(index + 1); // Public rendering API is one-based.
          pageRefs.push(page.ref);
        }
        return { result: again, pageCount: pdf.numPages, pageRefs, docId: task.docId };
      } finally {
        await task.destroy();
      }
    }, data.toString('base64'));
    const actual = response.result;
    assert.equal(actual.apiVersion, 1);
    assert.equal(actual.documentId, response.docId);
    assert.ok(!docIds.has(actual.documentId)); docIds.add(actual.documentId);
    assert.equal(response.pageCount, expected.pageCount);
    assert.equal(actual.status, status, id);
    assert.deepEqual([...new Set(actual.diagnostics.map(d => d.code))].sort(), codes, id);
    compareParts(actual, expected);
    for (const part of actual.parts) {
      if (part.pageRange.state !== 'present') continue;
      assert.deepEqual(response.pageRefs[part.pageRange.start], part.start.ref);
      assert.deepEqual(response.pageRefs[part.pageRange.end], part.end.kind === 'missing' ? part.start.ref : part.end.ref);
    }
    assert.deepEqual(JSON.parse(JSON.stringify(actual)), actual);
    checked.push({ id, inputSha256: fixture.sha256, expectedSha256: fixture.expectedSha256,
      parts: actual.parts.length, pages: response.pageCount, status: 'PASS', diagnosticCodes: codes });
  }
  assert.ok(page.workers().some(w => w.url().endsWith('/vendor/pdfjs/pdf.worker.mjs')), 'A real packaged worker is required');
  assert.deepEqual(requests, []); assert.deepEqual(errors, []);
  const report = { status: 'PASS', browser: context.browser().version(), offline: true,
    remoteRequests: requests.length, uncaughtErrors: errors.length, fixtures: checked };
  await mkdir('test-results', { recursive: true });
  await writeFile('test-results/document-parts-smoke.json', JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify(report, null, 2));
} finally {
  await context.close();
}
