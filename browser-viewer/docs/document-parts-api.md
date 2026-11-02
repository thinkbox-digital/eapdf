# M2 generic document-part API

The local patch adds `PDFDocumentProxy.getDocumentParts(): Promise<DocumentPartsInspection>`.
It uses the same supported display/worker request architecture as getOutline:

```text
PDFDocumentProxy.getDocumentParts()
  → WorkerTransport.getDocumentParts()
  → GetDocumentParts message
  → PDFManager.ensureCatalog("getDocumentParts")
  → Catalog + generic core/document_parts.js
```

This is a local API extension to the exact pinned PDF.js revision, not an API
already accepted upstream. The extension uses only the public proxy method.
The parser does not import application code or recognize any EA-PDF vocabulary.

## Returned data

[pdfjs-document-parts.d.ts](../src/pdfjs-document-parts.d.ts) defines the readonly
application types and augments the public PDFDocumentProxy type. The return value
is a structured-clone-safe object, with no Dict, Ref, Stream, xref, callbacks,
executable values or binary stream data.

| Field | Meaning |
|---|---|
| `apiVersion` | `1`; independent of the M1 semantic inspection schema version. |
| `documentId` | PDF.js loaded-document token, scoped to this loading task. It is not a file hash and changes on reopening. |
| `status` | `missing` if no DPartRoot, `invalid` for an unusable root, `partial` if extraction encounters limits/problems, otherwise `present`. None means validation PASS. |
| `root` | Root locator or null. Locators have `{ref: {num, gen} \| null, path}`. |
| `parts` | Flat preorder tree; every part has locator, traversed parent, declared parent, children, DPM, Start/End and page range. |
| `diagnostics` | Stable code and locator; no process-specific error strings. |
| `limits` | The fixed ceilings actually used by the operation. |

Indirect locators use `object:<number>:<generation>`. Direct objects use paths
relative to their owning dictionary/array. Treat a locator as belonging to its
top-level `documentId`; identical object numbers in two documents are unrelated.
Returned records are detached snapshots: mutating a JavaScript result does not
mutate parser state. TypeScript consumers see readonly fields and arrays.

Each DPM entry preserves its key, key bytes and a typed value. Supported scalar
kinds are string, name, number, boolean and null. Indirect scalar values also
preserve their reference. Strings have PDF-decoded text plus raw bytes as hex;
names retain name bytes without the lexical slash. Names are not coerced to PDF
strings. Dictionary, array and stream values are identified by type and reported
as unsupported; their graphs/content are not exposed. A failed lookup or byte
limit has an explicit invalid/unavailable kind. This is intentionally not a
complete PDF object serializer or an XMP reader.

Start, End and Parent retain reference numbers/generations, or missing/invalid
operand state. Page ranges have `indexBase: 0`, `endInclusive: true` and
present/missing/invalid state. Missing End defaults to Start. Resolved pages must
belong to this document's actual page tree; reversed and detached references are
invalid. Convert indices with `pdf.getPage(index + 1)` for the existing public
rendering API. Tree order is preserved, not sorted by page index.

## Limits and failure behavior

The reader caps total node/array visits at 2,048, nesting depth at 32, DPM entries
at 8,192, individual raw strings at 65,536 bytes, and total raw strings at
1,048,576 bytes. Repeated nodes/arrays stop traversal with a diagnostic. Other
siblings can still be returned unless the global traversal budget is exhausted.
Null/invalid roots, bad parent references, invalid DPM containers, bad page
references, unavailable strings and complex values remain distinguishable.

Codes include `INVALID_DPART_ROOT`, `INVALID_DPART`, `INVALID_REFERENCE`,
`PARENT_MISMATCH`, `REPEATED_DPART`, `REPEATED_CHILD_REFERENCE`, `INVALID_DPM`,
`UNSUPPORTED_DPM_VALUE`, `INVALID_PAGE_RANGE`, `TRAVERSAL_LIMIT`, `ENTRY_LIMIT` and
`STRING_LIMIT`. These report extraction behavior, not full PDF validity.
PDF.js MissingDataException is rethrown for normal manager fetch/retry handling.
Destroying the loading task/worker remains the standard cancellation mechanism.

The underlying PDF parser/page-tree resolver still uses PDF.js's existing
allocation and traversal behavior. These new output/traversal ceilings are not a
hard CPU timeout, decompression sandbox or full M6 hostile-input guarantee.
Range-streaming behavior is covered at the retry boundary, not by a new network
integration test. Local file-picker input is the tested product path.

## Test now in Chromium

From `browser-viewer/`, run `npm run build`. Load `dist/chromium` as an unpacked
extension in `chrome://extensions` and open its viewer tab. The existing UI still
renders pages only. There is no M2 email tree or DPart panel.

To inspect the API manually, open DevTools **for the viewer tab** and run this
snippet. It adds a temporary second file picker to that tab; click it to select
`examples/eml/eml_Agenda_and_timeline_slides_1s.pdf` or `eml_sample_1m.pdf`. It does not alter app code.

```js
const picker = document.createElement("input");
picker.type = "file";
picker.accept = ".pdf,application/pdf";
picker.onchange = async () => {
  const file = picker.files[0];
  if (!file) return;
  const { loadPdf } = await import("./pdfjs-adapter.js");
  const task = loadPdf(new Uint8Array(await file.arrayBuffer()));
  try {
    const pdf = await task.promise;
    console.log(await pdf.getDocumentParts());
  } finally {
    await task.destroy();
  }
};
document.body.append(picker);
```

Expected: 1s returns 3 parts and body range 0–1; 1m returns 6 parts, front matter
at page 0 and body ranges 1–2 and 3–4. Both have no inspection diagnostics.
The file picker in the normal UI clears its selected file after opening, which
is why this snippet uses its own temporary picker.

Unpacked installations do **not** auto-update. After editing application source,
run `npm run build`, click Reload in `chrome://extensions`, then close and reopen
the viewer tab. Source patch changes additionally require the reviewed rebuild
procedure in [patch maintenance](pdfjs-patch-maintenance.md). Firefox temporary
add-ons likewise need Reload from `about:debugging` and a fresh viewer tab; the
new API's Firefox execution is not verified by this M2 slice.
