import {checkPortfolioPresentation} from './portfolio-presentation-checks.mjs';
import {checkAdditionalPortfolios} from './portfolio-additional-checks.mjs';
import {checkPortfolioNavigation} from './portfolio-navigation-checks.mjs';
import {checkPortfolio} from './portfolio-checks.mjs';
import {checkTextFragments} from './text-fragment-checks.mjs';
import {checkReadingTools} from './reading-checks.mjs';
// Development-only WebDriver harness. No driver/server is included in the extension.
import { spawn,execFileSync } from 'node:child_process';
import { createServer } from 'node:net';
import { once } from 'node:events';
import { mkdir, writeFile, mkdtemp, readdir, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { checkTripAttachments } from './trip-attachment-checks.mjs';
import { checkScrolling } from './scrolling-checks.mjs';
import { checkNavigation } from './navigation-checks.mjs';
import { checkAttachments } from './attachment-checks.mjs';
import { checkInterface } from './ui-checks.mjs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
process.chdir(fileURLToPath(new URL('../', import.meta.url)));
const portProbe = createServer();
portProbe.listen(0, '127.0.0.1');
await once(portProbe, 'listening');
const port = portProbe.address().port;
await new Promise(done => portProbe.close(done));
const driver = spawn(process.env.GECKODRIVER_PATH ?? 'geckodriver', ['--allow-system-access', '--host', '127.0.0.1', '--port', String(port), '--log', 'error'], { stdio: ['ignore', 'ignore', 'inherit'] });
let launchError;
driver.on('error', error => { launchError = error; });
let session;
const downloadDir = await mkdtemp(join(tmpdir(), 'eapdf-m5-downloads-'));
const endpoint = `http://127.0.0.1:${port}`;
async function request(path, body, method = 'POST') {
  if (launchError) throw launchError;
  const response = await fetch(endpoint + path, {
    method, headers: { 'content-type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    signal: AbortSignal.timeout(30_000),
  });
  const result = (await response.json()).value;
  if (!response.ok) throw new Error(JSON.stringify(result));
  return result;
}
const call = (path, body, method) => request(`/session/${session}${path}`, body, method);
const execute = (script, args = []) => call('/execute/sync', { script, args });
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(action) {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    if (await action()) return;
    await delay(100);
  }
  throw new Error('Firefox check timed out');
}
try {
  await until(async () => { try { return !!(await request('/status', undefined, 'GET')); } catch { if (launchError) throw launchError; return false; } });
  const result = await request('/session', { capabilities: { alwaysMatch: {
    browserName: 'firefox', 'moz:firefoxOptions': {
      binary: process.env.FIREFOX_PATH ?? '/usr/lib/firefox/firefox',
      args: ['-headless'],
      prefs: { 'browser.download.folderList': 2, 'browser.download.dir': downloadDir,
        'browser.download.useDownloadDir': true, 'browser.download.alwaysOpenPanel': false,
        'browser.helperApps.neverAsk.saveToDisk': 'application/octet-stream',
        'pdfjs.disabled': true },
    },
  } } });
  session = result.sessionId;
  const addon = await call('/moz/addon/install', { path: resolve('dist/firefox'), temporary: true });
  assert.equal(addon, 'eapdf-m0-viewer@thinkbox.local');
  await call('/moz/context', { context: 'chrome' });
  const uuid = await execute("Services.io.offline = true; return JSON.parse(Services.prefs.getCharPref('extensions.webextensions.uuids'))[arguments[0]];", [addon]);
  assert.ok(uuid);
  await call('/moz/context', { context: 'content' });
  await call('/url', { url: `moz-extension://${uuid}/viewer.html` });
  await until(() => execute("return document.querySelector('#file') && !document.querySelector('#file').disabled;"));
  await checkTextFragments(fn => execute('return (' + fn.toString() + ')();'));
  const file = await call('/element', { using: 'css selector', value: '#file' });
  const id = file['element-6066-11e4-a52e-4f735466cecf'];
  async function open(path, requireMarks = true) {
    await call(`/element/${id}/value`, { text: resolve(path) });
    await until(() => execute("return !document.querySelector('#canvas').hidden && document.querySelector('#viewport').getAttribute('aria-busy') === 'false';"));
    if (requireMarks) assert.ok(await execute("const c=document.querySelector('#canvas'); const d=c.getContext('2d').getImageData(0,0,c.width,c.height).data; let n=0; for(let i=0;i<d.length;i+=4) if(d[i]<220||d[i+1]<220||d[i+2]<220)n++; return n>1000;"));
  }
  await mkdir('test-results', { recursive: true });
  await open('tests/fixtures/ordinary.pdf');
  // Opening now defaults to Fit Page; make fixed-scale pixel assertions explicit.
  await execute("const z=document.querySelector('#zoom'); z.value='1'; z.dispatchEvent(new Event('change'));");
  await until(() => execute("return document.querySelector('#viewport').getAttribute('aria-busy') === 'false';"));
  // Real WebDriver key events must reach our picker. Cancel only the native
  // dialog's default action so the headless test does not need an OS file picker.
  await writeFile('test-results/firefox-ordinary.png', Buffer.from(await call('/screenshot', undefined, 'GET'), 'base64'));
  await execute("document.querySelector('#next').click();");
  await until(() => execute("return document.querySelector('#status').textContent.includes('Page 2 of 2');"));
  assert.equal(await execute("return document.querySelector('#canvas').width;"), Math.ceil(320 * (96/72)));
  await execute("const z=document.querySelector('#zoom'); z.value='1.5'; z.dispatchEvent(new Event('change'));");
  await until(() => execute("return document.querySelector('#viewport').getAttribute('aria-busy') === 'false';"));
  assert.equal(await execute("return document.querySelector('#canvas').width;"), Math.ceil(320 * (96/72) * 1.5));
  await open(process.env.EAPDF_SAMPLE ?? '../examples/eml/eml_Agenda_and_timeline_slides_1s.pdf');
  await writeFile('test-results/firefox-eapdf.png', Buffer.from(await call('/screenshot', undefined, 'GET'), 'base64'));
  await execute("return import('./browser-session.js').then(({BrowserSession})=>{const child=BrowserSession.prototype.child;window.indexChildOpens=0;BrowserSession.prototype.child=async function(...args){window.indexChildOpens++;return child.apply(this,args);};});");
  for (const profile of ['1c','1ci']) {
    await execute("document.querySelector('#close').click();window.indexChildOpens=0;");
    await open(`tests/metadata-index/mbox_sample_${profile}.pdf`);
    await until(() => execute("return document.querySelector('#subject-index-progress').dataset.complete==='true';"));
    assert.equal(await execute('return window.indexChildOpens;'),0);
    await execute("const f=document.querySelector('#message-filter');f.value='power';f.dispatchEvent(new Event('input'));");
    await until(() => execute("return document.querySelector('#filter-results button') && document.querySelector('#filter-status').textContent.includes('1 matching emails');"));
    assert.equal(await execute('return window.indexChildOpens;'),0);
    await execute("document.querySelector('#filter-results button').click();");
    await until(() => execute("return !document.querySelector('#back').disabled && document.querySelector('#viewport').getAttribute('aria-busy')==='false';"));
    assert.match(await execute("return document.querySelector('#breadcrumbs').textContent;"),/Power VaR/);
  }
  await execute("document.querySelector('#close').click();");
  await open(process.env.EAPDF_SAMPLE ?? '../examples/eml/eml_Agenda_and_timeline_slides_1s.pdf');
  for(const profile of ['1mi','1ci']){
    await execute("document.querySelector('#close').click();");
    await open(resolve(process.env.EAPDF_SIGNED_INDEX_FIXTURES??'tests/fulltext-index',`mbox_sample_${profile}.pdf`));
    await execute("document.querySelector('#open-search').click();document.querySelector('#search-scope').value='collection';document.querySelector('#search').value='Power VaR';document.querySelector('#find').click();");
    await until(()=>execute("return document.querySelector('#collection-search-summary').textContent.includes('Embedded PDFBox text index') && document.querySelector('#viewport').getAttribute('aria-busy')==='false';"));
    assert.ok(await execute("return document.querySelectorAll('#text-layer .current-match').length>0;"),'Indexed first page highlights in Firefox');
    await execute("document.querySelector('#collection-matches button[data-match-index=\"1\"]').click();");
    await until(()=>execute("return document.querySelector('#viewport').getAttribute('aria-busy')==='false' && document.querySelector('#search-status').textContent.includes('Match 2 of 3') && document.querySelectorAll('#text-layer .current-match').length>0;"));
    assert.ok(await execute("return document.querySelectorAll('#text-layer .current-match').length>0;"),'Indexed destination highlights in Firefox');
  }
  await execute("document.querySelector('#close').click();");
  await open(process.env.EAPDF_SAMPLE ?? '../examples/eml/eml_Agenda_and_timeline_slides_1s.pdf');
  await execute("document.querySelector('#search-scope').value='document';");
  let uiResults;
  if (process.env.M5_UI === '1') {
    const ready = () => until(() => execute("return document.querySelector('#viewport').getAttribute('aria-busy') === 'false';"));
    const click = async selector => {
      const found = await call('/element', {using:'css selector', value:selector});
      await call(`/element/${found['element-6066-11e4-a52e-4f735466cecf']}/click`, {});
    };
    const adapter = {
      evaluate: (fn,arg) => execute('return (' + fn.toString() + ')(' + JSON.stringify(arg??null) + ');'), ready, click,
      open: path => open(path, false), // Structural negative fixtures intentionally have blank pages.
      drag: async(selector,dx,dy)=>{
        const point=await execute('const r=document.querySelector('+JSON.stringify(selector)+').getBoundingClientRect();return {x:Math.round(r.x+r.width/2),y:Math.round(r.y+r.height/2)};');
        await call('/actions',{actions:[{type:'pointer',id:'portfolio-mouse',parameters:{pointerType:'mouse'},actions:[{type:'pointerMove',duration:0,origin:'viewport',x:point.x,y:point.y},{type:'pointerDown',button:0},{type:'pointerMove',duration:250,origin:'viewport',x:point.x+dx,y:point.y+dy},{type:'pointerUp',button:0}]}]});
      },
      download: async selector => {
        for (const file of await readdir(downloadDir)) await rm(join(downloadDir, file));
        await click(selector);
        let files;
        await until(async () => {
          files = await readdir(downloadDir);
          return files.length === 1 && !files[0].endsWith('.part');
        });
        return readFile(join(downloadDir, files[0]));
      },
    };
    const presentation=await checkPortfolioPresentation(adapter);await writeFile('test-results/portfolio-presentation-firefox.json',JSON.stringify(presentation,null,2)+'\n');
    const portfolioLinks=await checkPortfolioNavigation(adapter);
    await writeFile('test-results/portfolio-navigation-firefox.json',JSON.stringify({status:'PASS',checks:portfolioLinks},null,2)+'\n');
    const additional=await checkAdditionalPortfolios(adapter);await writeFile('test-results/portfolio-additional-firefox.json',JSON.stringify(additional,null,2)+'\n');
    const portfolios=await checkPortfolio(adapter);
    await writeFile('test-results/portfolio-firefox.json',JSON.stringify({status:'PASS',checks:portfolios},null,2)+'\n');
    uiResults = process.env.READING_ONLY ? [] : await checkInterface(adapter);
    const reading=await checkReadingTools(adapter);
    const encryptedDirectory=await mkdtemp(join(tmpdir(),'eapdf-firefox-password-'));
    try{
      const encrypted=join(encryptedDirectory,'password.pdf');
      execFileSync(process.env.M1_PYTHON??'python3',['-B','-c',"import pikepdf,sys\nwith pikepdf.Pdf.open(sys.argv[1]) as p:p.save(sys.argv[2],encryption=pikepdf.Encryption(owner='owner-test',user='reader-test',R=6))",resolve('tests/fixtures/ordinary.pdf'),encrypted]);
      await call(`/element/${id}/value`,{text:encrypted});
      await until(()=>execute("return !!document.querySelector('dialog[open] input[type=password]');"));
      await execute("document.querySelector('dialog[open] input').value='wrong';document.querySelector('dialog[open] form').requestSubmit();");
      await until(()=>execute("return document.querySelector('#pdf-password-title')?.textContent.includes('Incorrect');"));
      await execute("document.querySelector('dialog[open] input').value='reader-test';document.querySelector('dialog[open] form').requestSubmit();");await ready();
      assert.equal(await execute("return !!document.querySelector('.pdf-page[data-rendered]');"),true);
      reading.checks.push('encrypted PDF wrong-password retry and successful open');
    }finally{await rm(encryptedDirectory,{recursive:true,force:true});}
    await writeFile('test-results/reading-tools-firefox.json',JSON.stringify(reading,null,2)+'\n');
    if(!process.env.READING_ONLY){
    const attachments = await checkAttachments(adapter);
    const trip=await checkTripAttachments(adapter);
  await writeFile('test-results/trip-attachments-firefox.json',JSON.stringify(trip,null,2)+'\n');
  const scrolling = await checkScrolling(adapter);
    await writeFile('test-results/scrolling-firefox.json',JSON.stringify(scrolling,null,2)+'\n');
    const navigation = await checkNavigation(adapter);
    await writeFile('test-results/navigation-firefox.json',JSON.stringify({status:'PASS',browser:result.capabilities.browserVersion,navigation},null,2)+'\n');
    await writeFile('test-results/beta-attachments-firefox.json',JSON.stringify({status:'PASS',browser:result.capabilities.browserVersion,attachments},null,2)+'\n');
    await writeFile('test-results/m5-firefox.json', JSON.stringify({status:'PASS',browser:result.capabilities.browserVersion,driver:result.capabilities['moz:geckodriverVersion'],results:uiResults},null,2)+'\n');
  }
  }
  assert.deepEqual(await execute("return performance.getEntriesByType('resource').map(r=>r.name).filter(n=>/^https?:/.test(n));"), []);
  const report = { status: 'PASS', browser: result.capabilities.browserVersion,
    driver: result.capabilities['moz:geckodriverVersion'],
    checks: ['PDF.js fragment boundaries and highlight offsets across stream chunks', 'MV3 temporary add-on installation', 'ordinary PDF pixels and next-page navigation', '150% zoom', 'EA-PDF sample pixels', 'Firefox offline; no HTTP(S) page resources'],
    limits: 'Temporary installation only; no signing, store review, or older-version compatibility claim.' };
  await writeFile(process.env.EAPDF_SIGNED_INDEX_FIXTURES?'test-results/firefox-smoke-signed.json':'test-results/firefox-smoke.json', JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify(report, null, 2));
} finally {
  try { if (session) await request(`/session/${session}`, undefined, 'DELETE'); }
  finally { driver.kill(); await rm(downloadDir, {recursive:true,force:true}); }
}
