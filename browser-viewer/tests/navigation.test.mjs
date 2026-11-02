import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { messagePages, downloadName } from '../dist/chromium/navigation.js';
test('ambiguous message GUIDs never produce a body page destination', async () => {
  const record=JSON.parse(await readFile('tests/inspection/expected/m-duplicate-guid.json','utf8'));
  for(const message of record.messages) assert.deepEqual(messagePages(record,message),[]);
});
test('download names cannot carry paths or bidi controls; payloads are untouched', () => {
  assert.equal(downloadName('../a\\b\u202e.pdf'),'_a_b_.pdf');
  assert.equal(downloadName('...'),'embedded-file');
  assert.equal(downloadName('agenda.txt'),'agenda.txt');
});
