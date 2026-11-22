import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {createServer} from 'node:http';
import {readFile, mkdir, rm} from 'node:fs/promises';
import {basename, dirname, extname, join, resolve, sep} from 'node:path';
import {setTimeout as delay} from 'node:timers/promises';
import {chromium} from 'playwright';

const [browser, directory, manifest] = process.argv.slice(2);
const {documents, subjects, attachment} = JSON.parse(await readFile(manifest, 'utf8'));
const expectedBytes = await readFile(join(dirname(manifest), attachment));
const input = '#file';
const childRows = '#tree button[data-embedded-file-id], #tree button[data-file-id]';
const messageRows = '#tree button[data-message-id]';
const download = `button[aria-label="Download ${attachment}"]`;

async function until(check, description) {
  const deadline = Date.now() + 20000;
  while (Date.now() < deadline) {
    if (await check()) return;
    await delay(100);
  }
  throw new Error(`Timed out waiting for ${description}`);
}

async function listen(server) {
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  return `http://127.0.0.1:${server.address().port}`;
}

const close = server => new Promise(resolve => server.close(resolve));

async function exercise(reader) {
  const evaluate = reader.evaluate;
  const ready = () => evaluate(() => {
    const viewport = document.querySelector('#viewport');
    return viewport.getAttribute('aria-busy') === 'false'
      && [...viewport.querySelectorAll('canvas')].some(canvas => canvas.width > 0 && canvas.getClientRects().length);
  });
  const expandFolders = () => evaluate(() => {
    for (const folder of document.querySelectorAll('#tree details')) folder.open = true;
  });
  const body = subject => evaluate(value => [...document.querySelectorAll('#page-surface .textLayer')]
    .some(layer => layer.textContent.includes(`${value} body`)), subject);
  await until(() => evaluate(selector => !document.querySelector(selector)?.disabled
    && Boolean(document.querySelector(selector)), input), 'reader startup');

  for (const [profile, {path}] of Object.entries(documents)) {
    console.log(`Checking ${profile}`);
    if (await evaluate(() => !document.querySelector('#close')?.disabled
        && !document.querySelector('#close')?.hidden && !!document.querySelector('#close'))) {
      await reader.click('#close');
      await until(() => evaluate(() => document.querySelector('#page-surface').hidden), 'previous PDF to close');
    }
    await reader.open(path);
    await until(() => evaluate(name => document.querySelector('#header-filename').textContent.trim() === name,
      basename(path)), `${profile} filename`);
    await until(ready, `${profile} rendering`);
    if (await evaluate(() => {
      const button = document.querySelector('#show-navigation');
      return button && !button.hidden && !button.disabled && button.getClientRects().length > 0;
    })) await reader.click('#show-navigation');
    if (profile.startsWith('1c')) {
      await expandFolders();
      await until(() => evaluate(selector => document.querySelectorAll(selector).length === 2, childRows),
        `${profile} collection children`);
      await reader.click(childRows);
      await until(() => evaluate(() => !document.querySelector('#back').disabled), `${profile} child opening`);
      await until(ready, `${profile} child rendering`);
      await until(async () => await body(subjects[0]) || await body(subjects[1]), `${profile} child message body`);
      await reader.click('#back');
      await until(() => evaluate(() => document.querySelector('#back').disabled), `${profile} parent return`);
      await until(ready, `${profile} parent rendering`);
    } else {
      await until(() => evaluate(selector => document.querySelectorAll(selector).length > 0, messageRows),
        `${profile} message rows`);
      const subject = profile.startsWith('1m') ? subjects[1] : subjects[0];
      const index = await evaluate(([selector, name]) => [...document.querySelectorAll(selector)]
        .findIndex(row => row.textContent.includes(name)), [messageRows, subject]);
      assert.ok(index >= 0, `${profile}: expected message ${subject}`);
      await reader.click(messageRows, index);
      await until(() => evaluate(([selector, name]) => [...document.querySelectorAll(selector)]
        .some(row => row.getAttribute('aria-current') === 'true' && row.textContent.includes(name)),
      [messageRows, subject]), `${profile} active message`);
      await until(() => body(subject), `${profile} selected message body`);
    }
    if (profile === '1s') {
      await until(() => evaluate(selector => !!document.querySelector(selector), download), 'native attachment control');
      assert.deepEqual(await reader.download(download), expectedBytes, 'Downloaded native attachment bytes differ');
    }
  }
}

async function chromiumReader() {
  let server;
  let context;
  try {
    let url;
    if (browser === 'web') {
      const types = {'.html':'text/html', '.js':'text/javascript', '.mjs':'text/javascript', '.wasm':'application/wasm',
        '.css':'text/css', '.json':'application/json'};
      server = createServer(async (request, response) => {
        const path = resolve(directory, '.' + new URL(request.url, 'http://localhost').pathname);
        if (!path.startsWith(directory + sep)) return response.writeHead(403).end();
        try {
          const bytes = await readFile(path);
          response.setHeader('Content-Type', types[extname(path)] ?? 'application/octet-stream');
          response.end(bytes);
        } catch {
          response.writeHead(404).end();
        }
      });
      url = await listen(server) + '/viewer.html';
    }
    context = await chromium.launchPersistentContext('', {
      headless: true, acceptDownloads: true, viewport: {width: 1440, height: 1000},
      executablePath: process.env.CHROMIUM_PATH ?? chromium.executablePath(),
      args: browser === 'chromium'
        ? [`--disable-extensions-except=${directory}`, `--load-extension=${directory}`] : [],
    });
    if (browser === 'chromium') {
      await context.setOffline(true);
      const worker = context.serviceWorkers()[0] ?? await context.waitForEvent('serviceworker', {timeout: 20000});
      url = `chrome-extension://${new URL(worker.url()).host}/viewer.html`;
    }
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(url);
    await exercise({
      evaluate: (fn, arg) => page.evaluate(fn, arg),
      open: path => page.locator(input).setInputFiles(path),
      click: (selector, index = 0) => page.locator(selector).nth(index).click({timeout: 10000}),
      download: async selector => {
        const [file] = await Promise.all([page.waitForEvent('download', {timeout: 10000}), page.locator(selector).first().click()]);
        assert.equal(file.suggestedFilename(), attachment);
        return readFile(await file.path());
      },
    });
    assert.deepEqual(errors, [], 'Reader page errors');
  } finally {
    await context?.close();
    if (server) await close(server);
  }
}

async function firefoxReader() {
  const probe = createServer();
  const address = await listen(probe);
  await close(probe);
  const downloads = directory + '-downloads';
  await mkdir(downloads);
  const driver = spawn(process.env.GECKODRIVER_PATH ?? 'geckodriver',
    ['--allow-system-access', '--host', '127.0.0.1', '--port', new URL(address).port],
    {stdio: 'inherit', env: {...process.env, MOZ_REMOTE_ALLOW_SYSTEM_ACCESS: '1'}});
  let launchError;
  driver.on('error', error => { launchError = error; });
  const request = async (path, body, method = 'POST') => {
    const response = await fetch(address + path, {method, signal: AbortSignal.timeout(30000),
      headers: {'content-type': 'application/json'}, body: JSON.stringify(body)});
    const {value} = await response.json();
    if (!response.ok) throw new Error(JSON.stringify(value));
    return value;
  };
  let session;
  const call = (path, body, method) => request(`/session/${session}${path}`, body, method);
  const evaluate = (fn, arg) => call('/execute/sync', {script: `return (${fn})(arguments[0]);`, args: [arg ?? null]});
  const elementPath = async (selector, index = 0) => {
    const elements = await call('/elements', {using: 'css selector', value: selector});
    assert.ok(elements[index], `Missing element ${selector} at index ${index}`);
    return '/element/' + elements[index]['element-6066-11e4-a52e-4f735466cecf'];
  };
  const click = (selector, index) => until(async () => {
    try {
      await call(await elementPath(selector, index) + '/click', {});
      return true;
    } catch (error) {
      if (!/element not interactable|stale element reference|element click intercepted/.test(error.message)) throw error;
      return false;
    }
  }, `Firefox click on ${selector}`);
  try {
    await until(async () => {
      if (launchError) throw launchError;
      try { return !!await request('/status', undefined, 'GET'); }
      catch { return false; }
    }, 'geckodriver startup');
    const result = await request('/session', {capabilities: {alwaysMatch: {browserName: 'firefox',
      'moz:firefoxOptions': {binary: process.env.FIREFOX_PATH, args: ['-headless'], prefs: {
        'browser.download.folderList': 2, 'browser.download.dir': downloads, 'browser.download.useDownloadDir': true,
        'browser.helperApps.neverAsk.saveToDisk': 'application/octet-stream,text/plain',
      }},
    }}});
    session = result.sessionId;
    await call('/window/rect', {width: 1440, height: 1000});
    const addon = await call('/moz/addon/install', {path: directory, temporary: true});
    await call('/moz/context', {context: 'chrome'});
    const id = await evaluate(addonId => {
      Services.io.offline = true;
      return JSON.parse(Services.prefs.getCharPref('extensions.webextensions.uuids'))[addonId];
    }, addon);
    await call('/moz/context', {context: 'content'});
    await call('/url', {url: `moz-extension://${id}/viewer.html`});
    await exercise({
      evaluate, click,
      open: async path => call(await elementPath(input) + '/value', {text: path}),
      download: async selector => {
        const path = join(downloads, attachment);
        await rm(path, {force: true});
        await click(selector);
        let bytes;
        await until(async () => {
          try { bytes = await readFile(path); return bytes.length >= expectedBytes.length; }
          catch (error) { if (error.code === 'ENOENT') return false; throw error; }
        }, 'native attachment download');
        return bytes;
      },
    });
  } finally {
    try { if (session) await call('', undefined, 'DELETE'); }
    finally { driver.kill(); }
  }
}

if (browser === 'firefox') await firefoxReader();
else await chromiumReader();
console.log(`PASS: ${browser} opened all six validated PDFs, navigated messages and children, and downloaded exact bytes.`);
