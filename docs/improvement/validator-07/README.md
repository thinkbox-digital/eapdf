# V07 — canonical PDF Declarations schema

The validator, writer and creation gate now use the published
`http://pdfa.org/declarations/` namespace. The registered ISO identifier remains
`https://pdfa.org/declarations#iso32005`. Document-level declarations use a Bag of
structures; equivalent qualified/URI encodings are supported. Optional claim
fields stay optional and supplied values retain their declared types.

Authority: EA-PDF 9.7 and [PDF Declarations 2019, sections 7/8](https://pdfa.org/download-area/specifications/PDF-Declarations.pdf).
The published [identifier registry](https://pdfa.org/declarations/) and
[URI-fragment clarification](https://pdf-issues.pdfa.org/pdf-declarations/clause08.html)
are separate from the namespace. The PDF was readable through the web reader;
direct download returned HTTP 403. No local dependency download is claimed.

Six authored Python schema tests pass. The full offline Maven build passed all
161 Java tests, including two new namespace regressions. Saved sample generation
and active fixture migration completed; logs and pre-migration archives remain
here. `verification-inputs.json` identifies the frozen source boundary.

The broad V07 run was interrupted by a host restart. A missing historical probe
script in the snapshot was supplied and its targeted test passed before that
restart. Neither the interrupted run nor the correction establishes a completed
broad-suite PASS. V08 continues broad acceptance with the separate upstream ISO
execution integrated. Its seven declaration/inclusion probes and six actual
CLI/worker checks independently cover this namespace fix.

No public CLI arguments changed. Declaration presence alone does not establish
ISO 32005 conformance. See [V08](../validator-08/README.md).

## Cumulative acceptance and historical boundaries

The frozen V11 full packaged suite completed 376 tests: 375
passes and 1 optional PREMIS schema skip. All 92 retained cases meet exact
custom/upstream outcomes. This is the cumulative V07–V11 gate with the corrected
complete creator artifact. [Machine-readable evidence](evidence.json) retains
each earlier run's own outcome; earlier running/pending notes above describe
that historical boundary. V08's original creator/packaging failures and V10's
original URI-size failure remain recorded as failures, not passing full runs.

V12's subsequent asset-association repair has its own [targeted acceptance](../validator-12/evidence.json).
