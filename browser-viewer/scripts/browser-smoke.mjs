import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import { access, mkdir, readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

process.chdir(fileURLToPath(new URL('../', import.meta.url)));
const extension = resolve('dist/chromium');
const executablePath = process.env.CHROMIUM_PATH;
const context = await chromium.launchPersistentContext('', {
  ...(executablePath ? { executablePath } : { channel: 'chromium' }),
  headless: true,
  args: [`--disable-extensions-except=${extension}`, `--load-extension=${extension}`],
});
const requests = [];
const errors = [];
const checks = [];
const report = { browser: context.browser()?.version(), executable: executablePath ?? 'Playwright Chromium', checks };
try {
  context.setDefaultTimeout(30_000);
  context.on('request', request => {
    if (/^https?:/.test(request.url())) requests.push(request.url());
  });
  await context.setOffline(true);
  const worker = context.serviceWorkers()[0] ?? await context.waitForEvent('serviceworker');
  const id = new URL(worker.url()).host;
  const pagePromise = context.waitForEvent('page');
  await worker.evaluate(() => chrome.tabs.create({ url: chrome.runtime.getURL('viewer.html') }));
  const page = await pagePromise;
  page.on('pageerror', error => errors.push(error.message));
  await page.waitForURL(`chrome-extension://${id}/viewer.html`);
  await page.locator('#file').waitFor();
  await page.waitForFunction(() => !document.querySelector('#file').disabled);
  checks.push('MV3 extension installed unpacked; background opened the viewer tab');
  const ready = async pattern => {
    await page.waitForFunction(source => new RegExp(source).test(document.querySelector('#status').textContent), pattern);
    await page.waitForFunction(() => document.querySelector('#viewport').getAttribute('aria-busy') === 'false');
    assert.equal(await page.locator('#canvas').isVisible(), true);
  };
  const pixels = () => page.locator('#canvas').evaluate(canvas => {
    const image = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
    let dark = 0;
    for (let i = 0; i < image.length; i += 4) if (image[i] < 220 || image[i + 1] < 220 || image[i + 2] < 220) dark++;
    return { dark, width: canvas.width, height: canvas.height };
  });
  await page.locator('#file').setInputFiles('tests/fixtures/ordinary.pdf');
  await ready('ordinary.pdf.*Page 1 of 2');
  const first = await pixels();
  assert.ok(first.dark > 5000, 'ordinary page must contain rendered marks');
  const blue = await page.locator('#canvas').evaluate(c => [...c.getContext('2d').getImageData(80, 80, 1, 1).data]);
  assert.ok(blue[2] > blue[0] + 90, 'first page must show the blue rectangle');
  assert.equal(await page.locator('#previous').isDisabled(), true);
  assert.ok(page.workers().some(w => w.url().endsWith('/vendor/pdfjs/pdf.worker.mjs')), 'real packaged PDF.js worker required');
  await mkdir('test-results', { recursive: true });
  await page.screenshot({ path: 'test-results/ordinary-page-1.png', fullPage: true });
  await page.locator('#next').click();
  await ready('ordinary.pdf.*Page 2 of 2');
  const red = await page.locator('#canvas').evaluate(c => [...c.getContext('2d').getImageData(80, 80, 1, 1).data]);
  assert.ok(red[0] > red[2] + 90, 'navigation must change the actual rendered page');
  assert.equal(await page.locator('#next').isDisabled(), true);
  await page.locator('#zoom').selectOption('1.5');
  await ready('ordinary.pdf.*Page 2 of 2');
  assert.equal((await pixels()).width, Math.ceil(320 * (96/72) * 1.5));
  await page.locator('#page').fill('1');
  await page.locator('#page').press('Tab');
  await ready('ordinary.pdf.*Page 1 of 2');
  await page.locator('#page').fill('999');
  await page.locator('#page').press('Tab');
  await page.waitForFunction(() => document.querySelector('#status').textContent.includes('Enter a whole page number'));
  assert.equal(await page.locator('#page').inputValue(), '1');
  checks.push('ordinary PDF: real worker, visible page pixels, next, direct page entry, invalid page rejection and 150% zoom');

  await page.locator('#file').setInputFiles({ name: 'broken.pdf', mimeType: 'application/pdf', buffer: Buffer.from('not a PDF') });
  await page.waitForFunction(() => document.querySelector('#status').textContent.startsWith('Unable to display PDF:'));
  assert.equal(await page.locator('#canvas').isVisible(), false);
  assert.equal(await page.locator('#next').isDisabled(), true);
  await page.locator('#file').setInputFiles('tests/fixtures/ordinary.pdf');
  await ready('ordinary.pdf.*Page 1 of 2');
  checks.push('invalid PDF produces a visible error; reopening recovers with no stale page');

  const sample = resolve(process.env.EAPDF_SAMPLE ?? '../examples/eml/eml_Agenda_and_timeline_slides_1s.pdf');
  let exists = false;
  try { await access(sample); exists = true; } catch { /* Standalone fixture suite may omit private repository samples. */ }
  if (!exists && process.env.EAPDF_SAMPLE) throw new Error(`Requested EA-PDF sample not found: ${sample}`);
  if (exists) {
    await page.locator('#file').setInputFiles(sample);
    await ready('Page 1 of [1-9]');
    assert.ok((await pixels()).dark > 1000);
    await page.screenshot({ path: 'test-results/eapdf-page-1.png', fullPage: true });
    if (!(await page.locator('#next').isDisabled())) {
      await page.locator('#next').click();
      await ready('Page 2 of [2-9]');
      assert.ok((await pixels()).dark > 1000);
      await page.locator('#previous').click();
      await ready('Page 1 of [2-9]');
    }
    report.eaPdf = { path: sample, sha256: createHash('sha256').update(await readFile(sample)).digest('hex') };
    checks.push('existing EA-PDF sample: visible page pixels and page navigation; no semantic classification or validation');
  } else {
    report.eaPdf = { status: 'NOT TESTED: set EAPDF_SAMPLE to meet the M0 acceptance gate' };
  }
  assert.deepEqual(requests, [], 'no HTTP(S) request may be attempted');
  assert.deepEqual(errors, [], 'no uncaught page errors');
  checks.push('all file operations succeeded offline; zero HTTP(S) requests and zero uncaught page errors');
  report.status = 'PASS';
  await writeFile('test-results/chromium-smoke.json', JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify(report, null, 2));
} finally {
  await context.close();
}
