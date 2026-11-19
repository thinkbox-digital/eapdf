// Compare both generated workers with their pre-backport bundles using Mozilla's fixture.
import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const root = fileURLToPath(new URL('../', import.meta.url));
const fixture = process.env.EAPDF_TRICKY_FONT_FIXTURE;
const baseline = process.env.EAPDF_TRICKY_FONT_BASELINE;
assert.ok(fixture && baseline, 'Set EAPDF_TRICKY_FONT_FIXTURE and EAPDF_TRICKY_FONT_BASELINE; see pdfjs-patch-maintenance.md');
const bytes = await readFile(fixture);
assert.equal(createHash('sha256').update(bytes).digest('hex'), '4fda75f5fba462f15b018ced3e7f158ad5ae481663541371d89bfde69bacb41c');
const server = createServer(async (request, response) => {
  try {
    const name = new URL(request.url, 'http://localhost').pathname;
    if (name === '/') { response.setHeader('Content-Type', 'text/html'); response.end('<!doctype html><title>Font compatibility</title>'); return; }
    if (name === '/fixture.pdf') { response.setHeader('Content-Type', 'application/pdf'); response.end(bytes); return; }
    const base = name.startsWith('/baseline/') ? resolve(baseline) : resolve(root, 'vendor');
    const relative = name.replace(/^\/(?:baseline|candidate)\//, '');
    const path = resolve(base, relative);
    assert.ok(path.startsWith(base + sep) && path.endsWith('.mjs'));
    response.setHeader('Content-Type', 'text/javascript'); response.end(await readFile(path));
  } catch { response.writeHead(404); response.end(); }
});
await new Promise(done => server.listen(0, '127.0.0.1', done));
let browser;
try {
  browser = await chromium.launch({ headless: true, ...(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : { channel: 'chromium' }) });
  const page = await browser.newPage();
  await page.goto(`http://127.0.0.1:${server.address().port}`);
  const results = [];
  for (const variant of ['pdfjs-patched', 'pdfjs-lite']) {
    const outputs = [];
    for (const build of ['baseline', 'candidate']) outputs.push(await page.evaluate(async ({ variant, build }) => {
      const base = `/${build}/${variant}/`;
      const api = await import(base + 'pdf.mjs'); api.GlobalWorkerOptions.workerSrc = base + 'pdf.worker.mjs';
      const task = api.getDocument({ url: '/fixture.pdf', fontExtraProperties: true, isEvalSupported: false });
      try {
        const doc = await task.promise, pdfPage = await doc.getPage(1), operators = await pdfPage.getOperatorList();
        const names = [...new Set(operators.fnArray.flatMap((op, i) => op === api.OPS.setFont ? [operators.argsArray[i][0]] : []))];
        const familyName = data => {
          const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
          for (let i = 0, n = view.getUint16(4); i < n; i++) {
            const entry = 12 + 16 * i;
            if (String.fromCharCode(...data.subarray(entry, entry + 4)) !== 'name') continue;
            const start = view.getUint32(entry + 8), strings = start + view.getUint16(start + 4);
            for (let j = 0, count = view.getUint16(start + 2); j < count; j++) {
              const record = start + 6 + 12 * j;
              if (view.getUint16(record) !== 1 || view.getUint16(record + 6) !== 1) continue;
              const offset = strings + view.getUint16(record + 10);
              return String.fromCharCode(...data.subarray(offset, offset + view.getUint16(record + 8)));
            }
          }
          return null;
        };
        const fonts = Object.fromEntries(names.map(name => { const font = pdfPage.commonObjs.get(name); return [font.name, familyName(font.data)]; }));
        const text = (await pdfPage.getTextContent()).items.map(({ str, transform, width, height, dir, hasEOL }) => ({ str, transform, width, height, dir, hasEOL }));
        const content = text.map(item => item.str).join(' '), needle = text.find(item => /[\u3400-\u9fff]/.test(item.str)).str;
        const matches = Array.from(content.matchAll(new RegExp(needle.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'g')), match => match.index);
        const viewport = pdfPage.getViewport({ scale: 1 }), canvas = document.createElement('canvas');
        canvas.width = viewport.width; canvas.height = viewport.height;
        await pdfPage.render({ canvas, viewport }).promise;
        const pixels = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
        const dark = pixels.reduce((count, value, i) => count + (i % 4 !== 3 && value < 220 ? 1 : 0), 0);
        const pixelHash = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', pixels)), value => value.toString(16).padStart(2, '0')).join('');
        return { fonts, text, matches, pixelHash, dark };
      } finally { await task.destroy(); }
    }, { variant, build }));
    const [before, after] = outputs;
    assert.equal(before.fonts['AAAAAD+___'], 'AAAAAD+___');
    assert.deepEqual(after.fonts, { ...before.fonts, 'AAAAAD+___': 'DFKai-SB' });
    assert.deepEqual(after.text, before.text, variant + ': extracted text and geometry changed');
    assert.deepEqual(after.matches, before.matches, variant + ': text search offsets changed');
    assert.ok(after.matches.length && after.dark > 10000, variant + ': missing searchable text or rendered content');
    results.push({ variant, restoredFamily: after.fonts['AAAAAD+___'], textItems: after.text.length, searchMatches: after.matches.length,
      beforePixelHash: before.pixelHash, afterPixelHash: after.pixelHash, samePixels: before.pixelHash === after.pixelHash });
  }
  await mkdir(resolve(root, 'test-results'), { recursive: true });
  const report = { browser: browser.version(), platform: process.platform, status: 'PASS', results,
    limitation: 'macOS Firefox rendering requires a separate platform check.' };
  await writeFile(resolve(root, 'test-results/tricky-fonts.json'), JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify(report, null, 2));
} finally { await browser?.close(); await new Promise(done => server.close(done)); }
