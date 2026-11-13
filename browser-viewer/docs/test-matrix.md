# Inspection fixtures and viewer regression tests

[manifest.json](../tests/inspection/manifest.json) pins all 27 input and expected
JSON hashes, engine versions, extractor/generator/contract hashes, source evidence,
hand-reviewed facts, required relationships and diagnostic codes. Frozen PDFs are referenced relative to the repository root; routine tests do not regenerate them.
These are inspection expectations, not replacement validator outcomes.

## Six real repository examples

The frozen inspection inputs live under `browser-viewer/tests/inspection/source-examples/`; consult the manifest for exact paths and hashes. These are historical inspection fixtures, separate from the regenerated public examples. Each has a full
expected record in `tests/inspection/expected/example-<suffix>.json`.

| Suffix/profile | Pages | Messages in catalog XMP | Physical file specs | Source-asset records | Body page ranges (zero-based, inclusive) | Child profiles |
|---|---:|---:|---:|---:|---|---|
| 1s / s | 2 | 1 | 2 | 1 | 0–1 | none |
| 1si / si | 2 | 1 | 1 | 0 | 0–1 | none |
| 1m / m | 5 | 2 | 5 | 2 | 1–2, 3–4 | none |
| 1mi / mi | 5 | 2 | 3 | 0 | 1–2, 3–4 | none |
| 1c / c | 1 | 2 | 4 | 2 | none in outer PDF | s, s |
| 1ci / ci | 1 | 2 | 2 | 0 | none in outer PDF | si, si |

These samples declare EA-PDF version 1/revision 2024. Their provenance is recorded in the inspection manifest. Independent direct XMP/object/byte checks establish
the table, rather than accepting the source manifest's validation status. For
example, the s sample's attachment is 49,338 decoded bytes and its EML source is
69,830 bytes. The corresponding si attachment is an actual zero-byte stream.
The multi-message examples also include an XLSX physical file whose role/owner
the oracle leaves unresolved. The c/ci records retain independent child results.

## Original structural controls

The 18 small PDFs in `tests/inspection/controls/` contain original Apache-2.0 test
content from `reference/make_controls.py`. They have blank pages and deliberately
minimal or malformed structures. Profile declarations are test inputs; **these
controls are not conforming EA-PDF/PDF/A examples**.

| Control | Required interpretation |
|---|---|
| s-missing-guid | GUID missing; Message-ID present; no guessed DPart owner |
| si-name-guid | PDF name in identity position invalid; raw bytes retained |
| m-duplicate-guid | Distinct message occurrences; ambiguous GUID binding |
| mi-reversed-range | Start after End invalid |
| c-folder-cycle | Cycle terminates with explicit diagnostic |
| ci-corrupt-child | Physical bytes retained; child PDF parsing invalid |
| c-nested-mixed | c contains m and ci; nested ci contains si |
| s-unknown-profile | Unsupported declaration, no guessed profile |
| s-conflicting-profile | Conflicting declarations ambiguous |
| s-uri-guid | RDF resource not coerced into text |
| s-duplicate-files | Equal bytes in two file specs retain ambiguous physical binding |
| s-object-af-only | Reachable object AF source found; owner unavailable |
| s-two-streams | Distinct F/UF stream alternatives preserved |
| s-bad-checksum | Declared/computed MD5 mismatch, both retained |
| s-missing-stream | Missing EF distinguished from zero-byte content |
| s-malformed-xmp | Invalid catalog XMP; profile unavailable |
| s-dpart-cycle | Repeated DPart terminates traversal |
| s-invalid-page-ref | Non-page Start reference invalid |

## Interpretation sources

Source hashes preserve the original M1 provenance. Repository HEAD at the start of M1 was
`b73d3fd5144f5e1a3e204342551fc1f63dcbdc88`.

## Checking and refreshing

Use the development environment in the README. `npm run test:m1` runs the Node contract and Python oracle suites. Checks include all frozen JSON/schema records,
all six hand-reviewed profiles, direct page-object and decoded-byte comparison,
raw XMP identities and DPM ancestor ownership, empty/missing streams, unknown and
ambiguous declarations, case-sensitive identities, cycle/limit outcomes, extraction
locator rejection, and reproducible control generation. Node checks compile all
27 literal results against the readonly model and require invalid examples to fail.
`npm run fixtures:check` compares every record and provenance hash without writing.

To deliberately change the contract or fixtures:

1. Edit the canonical contract or independent extractor/control generator. Explain
   the changed interpretation and evidence before accepting new expected data.
2. Run `node scripts/inspection-contract.mjs` for a contract change. Generate
   candidate controls with `python3 -B reference/make_controls.py --output /tmp/eapdf-m1-controls`
   and review differences before replacing the checked-in synthetic controls.
   Never regenerate the referenced real examples to conceal a discrepancy.
3. Run `python3 -B reference/fixtures.py --write` only for an intentional refresh.
   Review manifest facts, relationships, diagnostics and JSON diffs independently.
   New machine output alone is not evidence that an interpretation is correct.
4. Run `npm run check`, `npm run test:m1`, `npm run fixtures:check` and the existing
   `npm test` rendering gate. Remove temporary control copies after review.

Python 3.12.3, pikepdf 10.2.0 (qpdf 12.2.0), jsonschema 4.24.0 and their tested
dependency versions are recorded/pinned. Version drift deliberately requires a
review; platform-specific qpdf error wording may require a reviewed expectation
update. pikepdf (MPL-2.0) and jsonschema (MIT) are development dependencies only.

## Remaining evidence limits

The six real examples come from this converter, not six independent producers.
Nested mixed-profile coverage is synthetic. No new private email archive was
copied; expected JSON contains selected identity and asset metadata, so treat it
as extracted repository data rather than anonymized content. Full header/body,
logical-structure, source-email and content-stream AF inspection remain outside
this oracle's declared projection. Future PDF.js output still needs comparison
against these expectations using the current inspection-contract and browser tests.


## Current runtime and review regressions

The oracle dependency pins above describe its frozen development environment, not
web production dependencies. The web lock now pins pypdf 7.0.0; use
`requirements-validator.lock` for installation. Viewer tool versions are defined in
`package.json` (Node 24.19.0, npm 11.17.0, PDF.js 6.5.438).

Run `npm run check`, `npm run build`, `node --test tests/*.test.mjs`,
`npm run test:viewer-improvements`, and `npm run test:pdfjs-compat` from
`browser-viewer/`. The improvements test selects a filtered email, searches for
unique text on another page, and checks the displayed page and highlight.
`npm run build:pdfjs` tests the maintained upstream XRef regression during the
hash-verified source rebuild. At repository root, run
`python3 -B -m unittest regression.test_worker regression.test_web` for the
real-JVM aggregate scratch-budget and cleanup regression.

## Section 8 acceptance

Run `npm run test:section8` from browser-viewer. The suite builds both viewers and records group results in test-results/section8-acceptance.json. Set SECTION8_EXTERNAL_SAMPLE to a local external-sample threading collection to include its unnamed-root regression. Synthetic mutations isolate malformed-input cases; published real-message PDFs provide the baseline.

| Requirement | Coverage |
|---|---|
| PDF/A page and annotation rendering | Five PDF.js compatibility formats, three rounds; representative rendering only, not complete PDF/A qualification |
| EA-PDF identification and unsupported versions | Visible claim and unsupported embedded-version warning |
| Read-only operation | Original hash unchanged after attachment interaction |
| Initial viewing mode | UseAttachments and UseOutlines on a medium viewport |
| Outlines | Visible bookmark panel and bookmark activation |
| Embedded-file extraction | Name-tree, annotation-only and object-AF asset fixtures; independent byte comparisons |
| File descriptions | Stored Unicode description shown in Files pane |
| Attachment annotations and Contents | Unicode comment and native PPTX download through page icon |
| Collections and filenames | Collection fixtures, invalid path rejection, case collisions, unnamed root allowed, unnamed child rejected |
| XMP display and extraction | Object metadata, original-byte ZIP comparison, 300,000-character paged preview |
| Boundary/error handling | Cancellation, unreadable streams, unknown/foreign locators, invalid counts, ambiguous metadata and empty selection |
| URL policy | Code path remains explicit-annotation only; exhaustive URI/action coverage outstanding |

Resource budgets remain finite. PDF OpenAction page/zoom handling is not implemented; Collection /D initial-member selection is separate and implemented. An invalid Collection /D currently falls back to a file rather than warning. These remain known navigation gaps.
