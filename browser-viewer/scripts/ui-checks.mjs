// Shared interaction checks against independently frozen M1 records.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { resolve } from 'node:path';
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
export async function expandFolders(browser) {
  while(await browser.evaluate(()=>!!document.querySelector('#tree details:not([open]) > summary')))await browser.click('#tree details:not([open]) > summary');
}
export async function checkInterface(browser) {
  const manifest = JSON.parse(await readFile('tests/inspection/manifest.json', 'utf8'));
  const results = [];
  await browser.evaluate(()=>{
    window.testInspectionDiagnostics=[];
    const debug=console.debug;
    console.debug=(...args)=>{if(args[0]==='[EA-PDF inspection]')window.testInspectionDiagnostics.push(args[2]);debug.apply(console,args);};
  });
  const snapshot = () => browser.evaluate(() => ({
    status: document.querySelector('#status').textContent,
    info: document.querySelector('#declared-profile').textContent,
    notes: JSON.stringify(window.testInspectionDiagnostics),
    tree: document.querySelector('#tree').textContent,
    visible: !document.querySelector('#canvas').hidden,
    page: Number(document.querySelector('#page').value),
    messages: [...document.querySelectorAll('[data-message-id]')].map(n => n.dataset.messageId),
  }));
  const messageSelector = id => `[data-message-id=${JSON.stringify(id)}]`;
  const docSelector = id => `[data-document-id=${JSON.stringify(id)}]`;
  for (const id of ['example-1s','example-1si','example-1m','example-1mi','example-1c','example-1ci',
    'c-nested-mixed','ci-corrupt-child','m-duplicate-guid','s-missing-stream','s-invalid-page-ref','c-folder-cycle']) {
    const fixture = manifest.fixtures.find(f => f.id === id);
    const source = resolve('..', fixture.sourcePath);
    assert.equal(hash(await readFile(source)), fixture.sha256);
    const expected = JSON.parse(await readFile(resolve('tests/inspection', fixture.expected), 'utf8'));
    await browser.evaluate(()=>{window.testInspectionDiagnostics=[];});
    await browser.open(source);
    assert.equal(await browser.evaluate(()=>document.querySelector('#reading-notes')),null,'Reading Notes panel removed');
    assert.equal(await browser.evaluate(()=>[...document.querySelectorAll('#tree details')].every(d=>!!d.querySelector('summary'))),true,'Folders retain native keyboard disclosures');
    let checks = 0, downloads = 0;
    async function inspect(record) {
      await browser.click(docSelector(record.documentId)); await browser.ready();
      assert.doesNotMatch((await snapshot()).info, /Conformance/);
      if (record.identification.declaredProfile.value) assert.ok((await snapshot()).info.includes('1'+record.identification.declaredProfile.value));
      // One nonempty saved payload per document; zero-byte files intentionally disable Download.
      const file = record.files.find(f => f.streams.some(s => s.extraction === 'present' && s.decodedSize.value !== 0));
      if (file) {
        const stream = file.streams.find(s => s.extraction === 'present' && s.decodedSize.value !== 0);
        const selector = `[data-file-id=${JSON.stringify(file.id)}][data-stream=${JSON.stringify(stream.locator.path)}]`;
        const saved = await browser.download(selector); await browser.ready();
        assert.equal(hash(saved), stream.sha256.value, id+' saved payload');
        assert.equal(saved.length, stream.decodedSize.value); downloads++;
      }
      for (const message of record.collection.value ? [] : record.messages) {
        await browser.click(messageSelector(message.id)); await browser.ready();
        const actual = await snapshot();
        assert.equal(await browser.evaluate(()=>document.querySelector('#metadata')),null);
        const parts = record.parts.filter(p => p.messageIds.value?.length === 1 && p.messageIds.value[0] === message.id &&
          p.dpm.Mail_ContentSetType?.value === 'BodyRendering' && p.pageRange.state === 'present');
        if (parts.length) {
          assert.equal(actual.page, Math.min(...parts.map(p => p.pageRange.value.start)) + 1, id+' message page');
          assert.equal(actual.visible, true);
        } else {
          assert.equal(actual.visible, true, id+' unmapped message keeps the document available');
          assert.match(actual.status, /mapping unavailable; showing document page/);
        }
        const visibleFiles = await browser.evaluate(() => [...document.querySelectorAll('[data-file-id]')].map(b => b.dataset.fileId));
        if(record.messages.length===1){
          assert.deepEqual([...new Set(visibleFiles)].sort(),record.files.filter(f=>f.streams.length).map(f=>f.id).sort(),'single-email row exposes all document files');
          assert.equal(await browser.evaluate(()=>document.querySelector('[aria-current]')?.hasAttribute('data-document-id')),true,'single email and PDF share one selected row');
        }else assert.ok(visibleFiles.every(fileId => record.files.find(f => f.id === fileId)?.owners.value?.includes(message.id)));
        checks++;
      }
      await expandFolders(browser);
      for (const child of record.collection.value?.children ?? []) {
        await browser.click(`[data-embedded-file-id=${JSON.stringify(child.fileId)}][data-embedded-stream=${JSON.stringify(child.stream.path)}]`); await browser.ready();
        if (child.inspection) await inspect(child.inspection);
        else assert.match((await snapshot()).status, /Unable to display PDF/);
      }
    }
    await inspect(expected);
    if (id === 'c-folder-cycle') assert.match((await snapshot()).notes, /FOLDER_CYCLE/);
    results.push({ id, messagesSelected: checks, verifiedDownloads: downloads, status: 'PASS' });
  }
  await browser.open(resolve('tests/fixtures/ordinary.pdf'));
  await browser.click('#open-search');
  await browser.evaluate(() => {
    document.querySelector('#search').value = 'page 2';
    document.querySelector('#search-form').requestSubmit();
  });
  await browser.ready();
  assert.equal((await snapshot()).page, 2);
  assert.ok(await browser.evaluate(() => document.querySelectorAll('#text-layer mark').length > 0));
  assert.ok(await browser.evaluate(() => { const range=document.createRange();range.selectNodeContents(document.querySelector('#text-layer'));const selection=getSelection();selection.removeAllRanges();selection.addRange(range);return selection.toString().includes('Ordinary PDF'); }));
  await browser.evaluate(() => {
    document.querySelector('#search').value = 'no such phrase';
    document.querySelector('#search-form').requestSubmit();
  });
  await browser.ready();
  assert.match(await browser.evaluate(() => document.querySelector('#search-status').textContent), /No matching text/);
  await browser.evaluate(() => {
    document.querySelector('#search').value = 'ordinary';
    document.querySelector('#search-form').requestSubmit();
    document.querySelector('#close').click();
  });
  assert.equal((await snapshot()).visible, false);
  assert.equal((await snapshot()).tree, '');
  await browser.open(resolve('tests/fixtures/ordinary.pdf'));
  assert.equal((await snapshot()).page, 1);
  results.push({ id: 'search-cancel-reopen', status: 'PASS' });
  return results;
}
