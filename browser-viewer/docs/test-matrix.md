# M1 fixtures and evidence

[manifest.json](../tests/inspection/manifest.json) pins all 27 input and expected
JSON hashes, engine versions, extractor/generator/contract hashes, source evidence,
hand-reviewed facts, required relationships and diagnostic codes. Existing PDFs
are referenced relative to the repository root, never copied or regenerated.
These are inspection expectations, not replacement validator outcomes.

## Six real repository examples

All paths below start `examples/eml/eml_sample_` and end `.pdf`. Each has a full
expected record in `tests/inspection/expected/example-<suffix>.json`.

| Suffix/profile | Pages | Messages in catalog XMP | Physical file specs | Source-asset records | Body page ranges (zero-based, inclusive) | Child profiles |
|---|---:|---:|---:|---:|---|---|
| 1s / s | 2 | 1 | 2 | 1 | 0–1 | none |
| 1si / si | 2 | 1 | 1 | 0 | 0–1 | none |
| 1m / m | 5 | 2 | 5 | 2 | 1–2, 3–4 | none |
| 1mi / mi | 5 | 2 | 3 | 0 | 1–2, 3–4 | none |
| 1c / c | 1 | 2 | 4 | 2 | none in outer PDF | s, s |
| 1ci / ci | 1 | 2 | 2 | 0 | none in outer PDF | si, si |

These samples declare EA-PDF version 1/revision 2024. Their source manifest is
`examples/eml/manifest.json`. Independent direct XMP/object/byte checks establish
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

Source hashes make these references traceable even in this pre-existing dirty
checkout. Repository HEAD at the start of M1 was
`b73d3fd5144f5e1a3e204342551fc1f63dcbdc88`.

## Checking and refreshing

Use the development environment in the README. `npm run test:m1` runs two Node
contract tests and 15 Python tests. Checks include all frozen JSON/schema records,
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

Python 3.12.3, pikepdf 10.2.0 (qpdf 12.4.2), jsonschema 4.24.0 and their tested
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
against these expectations; M2 implementation has not begun.
