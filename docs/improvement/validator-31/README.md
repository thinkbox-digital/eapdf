# V31: version precedence, header restriction and native name types

Recovered thread `01a15a98-5d15-7373-8fe5-1356234612b2` ended during this repair.
The recovered before logs, partial implementation and seven tests are retained.

Authority: EA-PDF 9.1 printed p61 requires both the header and effective version
to be 1.7 or 2.0. ISO 32000-2:2020 Table 29 printed p99 requires a name for
Catalog Version and gives precedence to the later header/catalog version.
EA-PDF 9.5/Table 6 printed p68 and ISO Table 155 printed p451 require the nine
prescribed Collection field Subtype names. Additional custom fields remain
permitted. The authoritative requirements remain `validator_rule_text.json`.

The custom validator now rejects text substitutions, empty/malformed Version
names and 1.4–1.6 headers. Non-name Version values cannot affect interpretation.
An earlier named Catalog Version cannot downgrade the effective version or
activate the PDF 1.7 Collection extension requirement on a PDF 2.0 header.
A lower valid version does not independently violate the EA-PDF version check.

## Verification and limits

- Nine focused tests pass, including all nine Collection fields, omitted
  Version, wrong/missing subtypes, extra fields, malformed names and headers.
- The frozen Python suite runs 577 tests: 537 pass and 40 explicitly skip.
- All 276 custom PDF outcomes match. The 265 previous PDF hashes and outcomes
  are unchanged; nine new-control outcomes differ from V30 as reviewed.
- Eleven exact-byte before/after complete-pipeline controls and one CLI plus
  two isolated worker checks retain full engine reports. Five false pipeline
  PASS results now FAIL on the custom name requirement. Eight controls pass
  PDF/A and verified ISO execution.
- Three other controls deliberately fail PDF/A: PDF/A-4 requires a 2.n Catalog
  Version, and its header rule also rejects 1.6. ISO is consequently not run
  for those controls. The custom precedence/header outcomes are checked
  separately; no full-pipeline acceptance is claimed for those PDFs.
- Twelve original creator inputs have their actual header, Version and required
  Subtypes inspected. All added guards are true and precedence is unchanged;
  their exact V29 engine runs remain applicable, reused through V30. No new
  creator run, build, sample regeneration or creator acceptance is claimed.

The initial PDF/A expectation wrongly assumed a lower Catalog Version was
permitted by PDF/A-4 because ISO header precedence ignores it. Its log,
expectations and input archive are preserved. The repaired expectation records
the actual pinned PDF/A-4 predicate, ISO 19005-4:2020 6.1.12 test 1. A copied
verifier path error and its log are also retained. Neither is a validator defect.

Reproduction: `freeze-inputs.py`, frozen unit discovery, `verify-regressions.py`,
`verify-pdfs.py`, `verify-retained-names.py`, `refresh-observations.py`, then
`assemble-evidence.py`. Runtime/fixture hashes are in `verification-inputs.json`
and `runtime-inputs.zip`. The original snapshot is `.eapdf-verification-v31`.
Eleven review decisions, broader obligation/shared coverage, source occurrence
assignment and creator repairs remain open.
