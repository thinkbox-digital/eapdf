# M1 results — 2026-10-27

M1 acceptance is met: a versioned readonly TypeScript model, JSON schema and
traceable fixture manifest are checked in, with expected inspection records for
all six real repository profiles. M2 has not started. The extension remains the
M0 page viewer and makes no EA-PDF conformance claim.

All work is under `browser-viewer/`. Converter, Python validator, Java validator,
existing samples, CLI behavior and existing print statements are unchanged.
The pre-existing dirty checkout was preserved. The baseline covered 3,181 existing
nonignored files; the [verification receipt](m1-verification.json) records the
scope comparison and exact changed/new file list.

## Deliverables

- [Inspection contract guide](inspection-schema.md), generated
  [TypeScript model](../src/domain/inspection.d.ts) and
  [JSON schema](../schema/inspection-v1.schema.json), with one canonical generator.
- [Fixture manifest](../tests/inspection/manifest.json) and 27 expected JSON records:
  six existing profile examples, 18 original structural controls, two existing
  negative fixtures and one ordinary PDF. [Test matrix](test-matrix.md) documents
  their provenance and the limits of each case.
- `reference/inspect_pdf.py`: independent, read-only qpdf extraction and bounded
  in-memory retrieval. No imports from the validator or PDF.js; no PASS verdicts
  used as extraction evidence. `make_controls.py` and `fixtures.py` provide
  deterministic controls and explicit, reviewed expectation refreshes.
- Focused TypeScript/Python checks, pinned development requirements, updated
  README and appended M1 decisions. Python is required only for reference checks.

## Commands and actual results

All commands ran from `browser-viewer/`; every final command below exited 0.

| Command | Result |
|---|---|
| `node scripts/inspection-contract.mjs` | Generated schema and readonly declarations. |
| `python3 -B reference/make_controls.py --output tests/inspection/controls` | Wrote 18 deterministic original structural controls. |
| `python3 -B reference/fixtures.py --write` | Explicitly froze 27 reviewed expectations and provenance manifest. |
| `npm run test:m1` | 2 Node tests passed; 15 Python tests passed; zero failures/skips. |
| `npm run fixtures:check` | All 27 reference records and provenance hashes match. |
| `npm run check` | Strict TypeScript check passed; schema/declarations match canonical source. |
| `npm run verify:licenses` | Verified 16 locked packages and 203 pinned PDF.js files; notices/font sources present. |
| `CHROMIUM_PATH=/snap/chromium/current/usr/lib/chromium-browser/chrome npm test` | Built Chromium and Firefox packages; all 6 Node tests passed; Chromium smoke PASS. |

The reference checks used CPython 3.12.3, pikepdf 10.2.0/qpdf 12.4.2 and
jsonschema 4.24.0. The full tested Python dependency closure is pinned and checked.
Node 24.19.0, npm 11.17.0, TypeScript 5.9.3 and PDF.js 6.4.299 remain unchanged.
No new npm dependencies were installed; package-lock.json remains byte-identical
to M0 (`b6389eedc4b9ae28f9f6f7d9be3cddd3811c967a7b500d0f30da233101b6acf8`).

The Chromium 156.0.8078.47 smoke test loaded the actual unpacked MV3 extension,
opened an ordinary PDF and the existing 1s EA-PDF, checked rendered pixels, real
worker use, navigation, zoom and invalid-file recovery. All operations succeeded
offline with zero HTTP(S) requests and zero uncaught page errors.

Both rebuilt package inventories and contents match the M0 hashes exactly:

- Chromium build-info SHA-256:
  `3b89a94e5636cffa1b30a75a6f90b7aa5f3d02d8c91a55f3091e236c9332be4f`
- Firefox build-info SHA-256:
  `7356d24d7c3ac889159323ecb0af48d6709e8f7ae120c0c9d4ef99d7aa0a9fa0`

The declarations emit no JavaScript. No Python, Java, reference fixtures or JSON
schema is shipped. Firefox was rebuilt but its browser test was not rerun for
this data-only milestone; its unchanged package retains the historical M0 test
evidence and [documented compatibility limits](compatibility.md).

## Findings retained in the model

Source assets cannot reliably be assigned to messages from Table 16 alone.
Filename/size-only matches do not prove a physical attachment binding. Duplicate
GUIDs and duplicate physical byte matches remain ambiguous. An isolated file's
zero-byte stream is distinct from missing embedded content. Collection metadata
can list messages without owning their pages in the outer PDF. GUIDs and
Internet Message-ID remain separate, with original types and bytes preserved.

Initial checks exposed a mistaken expectation for `multi-reversed.pdf`: its
DPart traversal order is reversed, while individual ranges are readable. Direct
object inspection and the existing fixture builder established the correct
expectation. The oracle preserves that order without inventing a range error or
claiming conformance. A qpdf error also contained a process-specific memory
address; normalization now retains the error meaning while making snapshots
reproducible. Both corrections are covered by the final passing checks.

## Remaining work and limitations

- The six real examples are from this converter. Nested mixed-profile coverage
  is synthetic; independent-producer interoperability remains untested.
- This is an identity/asset projection, not full EA-PDF extraction or validation.
  Full headers, logical structure, source email parsing, content-stream AF and
  complete RDF/collection-schema support remain explicitly not evaluated.
- qpdf decodes into memory before checking decoded-byte limits. This development
  reference tool is for trusted pinned fixtures; production worker hardening and
  browser-side bounded extraction remain future milestones.
- There is no browser inspection implementation or PDF.js patch in M1. The next
  milestone must prove generic DPart/DPM access against these expectations.
- Firefox signing, store review and older-browser support remain as documented
  in M0. No new Firefox compatibility claim is made here.

No unresolved issue blocks the stated M1 acceptance gate.
