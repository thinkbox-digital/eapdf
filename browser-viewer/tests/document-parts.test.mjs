import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';
import { verifyPatchedPdfjs } from '../scripts/pdfjs-patch.mjs';

test('patched distribution matches the pinned source patch and generated outputs', async () => {
  const manifest = await verifyPatchedPdfjs();
  assert.equal(Object.keys(manifest.outputs).length, 2);
});

test('the new public method has readonly generic types on PDFDocumentProxy', async () => {
  const root = fileURLToPath(new URL('../', import.meta.url));
  const temp = await mkdtemp(join(tmpdir(), 'eapdf-m2-types-'));
  try {
    const source = `
import ${JSON.stringify(join(root, 'src/pdfjs-document-parts.js'))};
import type { PDFDocumentProxy } from ${JSON.stringify(join(root, 'node_modules/pdfjs-dist/types/src/pdf.js'))};
declare const pdf: PDFDocumentProxy;
const result = await pdf.getDocumentParts();
const version: 1 = result.apiVersion;
const docId: string = result.documentId;
// @ts-expect-error Parsed records are readonly at the application boundary.
result.parts.push(result.parts[0]);
// @ts-expect-error No internal xref is exposed by the public API.
result.xref.fetch(1);
// @ts-expect-error There is no conformance verdict in the generic API.
result.validationStatus;
`;
    const path = join(temp, 'public-api.ts'); await writeFile(path, source);
    const program = ts.createProgram([path], { noEmit: true, strict: true, skipLibCheck: true, types: [],
      target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022, moduleResolution: ts.ModuleResolutionKind.Bundler });
    const diagnostics = ts.getPreEmitDiagnostics(program);
    assert.equal(diagnostics.length, 0, ts.formatDiagnosticsWithColorAndContext(diagnostics, {
      getCurrentDirectory: () => root, getCanonicalFileName: p => p, getNewLine: () => '\n',
    }));
  } finally { await rm(temp, { recursive: true, force: true }); }
});
