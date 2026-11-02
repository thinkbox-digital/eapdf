# M3 — Associated files, metadata, PDF Collections and child PDFs

Acceptance gate: Extract representative root-level and annotation-only attachments
with exact byte hashes; enumerate and open a collection child; identify and report
an intentionally malformed association without hanging or guessing ownership.

This gate passed in Chromium 156.0.8078.47. `npm run test:assets` ran 11 offline
cases: all six real profiles, object-AF-only, two EF streams, missing EF, mixed
nested collections and a collection folder cycle. Extracted sizes/SHA-256 matched
the independent M1 records, annotation page ownership context matched, and
collection child PDFs opened directly from extracted bytes. No HTTP(S) request
was made. Detailed evidence is in m3-assets-parity.json.

Source patch 0002 adds generic getDocumentAssets and getAssociatedFileBytes APIs.
Discovery records catalog/object AF, name-tree and annotation origins; catalog
and object metadata, folder links and every EF alternative remain separate.
Extraction requires the current document token and a discovered file/stream pair.
An opt-in DecodeStream allocation ceiling leaves ordinary rendering unchanged.
Failed bounded decodes cannot later return partial data as a successful download.

The clean source build passed 13 focused tests and 73 existing upstream tests.
Focused additions cover exact detached bytes, foreign locators, shared EF streams,
cycles, a compressed 1 MiB expansion stopped before growing past 4,097 bytes,
zero-byte content and unsupported filter rejection. Strict TypeScript and both
extension builds passed. No converter/validator or M1 expectation changed.

Generic raw metadata does not assign semantic ownership. M4 adds that interpretation
and application-level recursive document/total-byte budgets; M5 connects it to UI.
M6 will add cross-browser, malformed-corpus and release checks. No conformance
verdict is inferred from this extraction gate.
