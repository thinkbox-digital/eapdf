// Development-only tests of the actual patched upstream module.
import test from 'node:test';
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';
const source = process.env.PDFJS_SOURCE;
if (!source) throw new Error('PDFJS_SOURCE must identify the clean, patched source checkout.');
const upstream = name => import(pathToFileURL(resolve(source, name)).href);
const { Dict, Name, Ref } = await upstream('src/core/primitives.js');
const { MissingDataException } = await upstream('src/core/core_utils.js');
const { getDocumentParts } = await upstream('src/core/document_parts.js');

function fixture() {
  const objects = new Map();
  const rootRef = Ref.get(10, 2), partRef = Ref.get(11, 3), pageRef = Ref.get(20, 1);
  const dict = entries => { const d = new Dict(); for (const [k, v] of Object.entries(entries)) d.set(k, v); return d; };
  const root = dict({ DPartRootNode: partRef });
  const part = dict({ Parent: rootRef, Start: pageRef });
  objects.set(rootRef, root); objects.set(partRef, part);
  const catalog = { xref: { fetch: r => { if (!objects.has(r)) throw new Error('Bad ref'); return objects.get(r); } },
    numPages: 2, getPageIndex: async () => 0, getPageDict: async () => [null, pageRef] };
  return { objects, rootRef, partRef, pageRef, root, part, dict, catalog,
    inspect: () => getDocumentParts(catalog, rootRef, 'loaded-document-1') };
}

test('preserves nonzero generations, decoded strings/names, bytes and scalar types', async () => {
  const f = fixture();
  const indirect = Ref.get(22, 4); f.objects.set(indirect, '\xfe\xff\x00A');
  f.part.set('DPM', f.dict({ Text: indirect, Name: Name.get('Example'), Count: 42, Flag: true, Nothing: null }));
  const r = await f.inspect();
  assert.equal(r.status, 'present');
  assert.equal(r.documentId, 'loaded-document-1');
  assert.deepEqual(r.parts[0].locator.ref, { num: 11, gen: 3 });
  assert.deepEqual(r.parts[0].declaredParent.ref, { num: 10, gen: 2 });
  const fields = Object.fromEntries(r.parts[0].dpm.entries.map(e => [e.key, e.value]));
  assert.deepEqual(fields.Text, { kind: 'string', ref: { num: 22, gen: 4 }, value: 'A', bytesHex: 'feff0041' });
  assert.deepEqual(fields.Name, { kind: 'name', ref: null, value: 'Example', bytesHex: '4578616d706c65' });
  assert.equal(fields.Count.value, 42); assert.equal(fields.Flag.value, true); assert.equal(fields.Nothing.kind, 'null');
  assert.deepEqual(r.parts[0].pageRange, { state: 'present', start: 0, end: 0, indexBase: 0, endInclusive: true });
  assert.deepEqual(structuredClone(r), r);
  r.parts[0].dpm.entries[0].key = 'changed by caller';
  assert.notDeepEqual(await f.inspect(), r, 'returned records cannot mutate parser state');
});

test('ordinary/missing root and malformed root have distinct states', async () => {
  const f = fixture();
  assert.equal((await getDocumentParts(f.catalog, undefined, 'd')).status, 'missing');
  assert.equal((await getDocumentParts(f.catalog, 17, 'd')).status, 'invalid');
});

test('missing parents and repeated DPart references terminate with diagnostics', async () => {
  const f = fixture();
  f.objects.set(f.partRef, f.dict({ Start: f.pageRef, DParts: [f.partRef, f.partRef] }));
  const r = await f.inspect();
  assert.equal(r.parts.length, 1);
  assert.ok(r.diagnostics.some(d => d.code === 'PARENT_MISMATCH'));
  assert.equal(r.diagnostics.filter(d => d.code === 'REPEATED_DPART').length, 2);
});

test('nested indirect arrays and array cycles are bounded', async () => {
  const f = fixture();
  const arrayRef = Ref.get(30, 0); f.objects.set(arrayRef, [arrayRef]);
  f.part.set('DParts', [arrayRef]);
  const r = await f.inspect();
  assert.equal(r.parts.length, 1);
  assert.ok(r.diagnostics.some(d => d.code === 'REPEATED_CHILD_REFERENCE'));
});

test('broken child references stay diagnostic and preserve other children', async () => {
  const f = fixture(); const child = Ref.get(32, 0);
  f.objects.set(child, f.dict({ Parent: f.partRef }));
  f.part.set('DParts', [Ref.get(99, 0), child]);
  const r = await f.inspect();
  assert.equal(r.parts.length, 2);
  assert.deepEqual(r.parts[0].children[0].ref, { num: 32, gen: 0 });
  assert.ok(r.diagnostics.some(d => d.code === 'INVALID_REFERENCE'));
});

test('non-page, detached-page, reversed and explicit-null ranges are invalid', async () => {
  for (const mode of ['not-page', 'detached', 'reversed', 'null']) {
    const f = fixture();
    if (mode === 'not-page') f.catalog.getPageIndex = async () => { throw new Error('Not a page'); };
    if (mode === 'detached') f.catalog.getPageDict = async () => [null, Ref.get(70, 0)];
    if (mode === 'null') f.part.set('End', null);
    if (mode === 'reversed') {
      const last = Ref.get(21, 0); f.part.set('Start', last); f.part.set('End', f.pageRef);
      f.catalog.getPageIndex = async r => r === last ? 1 : 0;
      f.catalog.getPageDict = async i => [null, i ? last : f.pageRef];
    }
    const r = await f.inspect();
    assert.equal(r.parts[0].pageRange.state, 'invalid', mode);
    assert.ok(r.diagnostics.some(d => d.code === 'INVALID_PAGE_RANGE'), mode);
  }
});

test('complex DPM values are typed without traversing cyclic dictionaries', async () => {
  const f = fixture(); const cyclic = f.dict({}); cyclic.set('Self', cyclic);
  f.part.set('DPM', f.dict({ Arbitrary: cyclic, Values: [cyclic] }));
  const r = await f.inspect();
  assert.equal(r.status, 'partial');
  assert.deepEqual(r.parts[0].dpm.entries.map(e => e.value.kind), ['dictionary', 'array']);
  assert.deepEqual(JSON.parse(JSON.stringify(r)), r);
});

test('legacy traversal counts do not truncate; string byte budgets remain explicit', async () => {
  for (const mode of ['depth', 'visits', 'entries', 'string', 'total-strings']) {
    const f = fixture();
    const limits = (await f.inspect()).limits;
    let expected;
    if (mode === 'depth') {
      let kids = []; for (let i = 0; i < 50; i++) kids = [kids];
      f.part.set('DParts', kids); expected = 'TRAVERSAL_LIMIT';
    } else if (mode === 'visits') {
      f.part.set('DParts', Array(limits.maxNodes + 1).fill(f.partRef)); expected = 'TRAVERSAL_LIMIT';
    } else {
      const dpm = f.dict({}); f.part.set('DPM', dpm);
      if (mode === 'entries') { for (let i = 0; i <= limits.maxEntries; i++) dpm.set('K' + i, 1); expected = 'ENTRY_LIMIT'; }
      if (mode === 'string') { dpm.set('Large', 'a'.repeat(65537)); expected = 'STRING_LIMIT'; }
      if (mode === 'total-strings') { for (let i = 0; i <= limits.maxTotalStringBytes / limits.maxStringBytes; i++) dpm.set('K' + i, 'a'.repeat(limits.maxStringBytes)); expected = 'STRING_LIMIT'; }
    }
    const r = await f.inspect();
    if (mode === 'string' || mode === 'total-strings') {
      assert.equal(r.status, 'partial', mode);
      assert.ok(r.diagnostics.some(d => d.code === expected), mode);
    } else {
      assert.ok(!r.diagnostics.some(d => d.code === expected), mode);
      if (mode === 'entries') assert.equal(r.parts[0].dpm.entries.length, limits.maxEntries + 1);
    }
  }
});

test('MissingDataException propagates so PDFManager can fetch and retry', async () => {
  const f = fixture(); f.catalog.xref.fetch = () => { throw new MissingDataException(0, 100); };
  await assert.rejects(f.inspect(), MissingDataException);
});
