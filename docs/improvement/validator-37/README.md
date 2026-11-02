# V37: DPart metadata values

The V36 continuation probes demonstrated ten complete-pipeline false PASS results
on exact retained bytes. The repair follows ISO32000-2:2020 and the PDF
Association industry-approved clause14 corrections, recorded in
[rule-review.json](rule-review.json). The creator is not the expected-result oracle.

## Verification

- 633 unit tests: 593 pass, 40 integration/schema skips; twelve focused tests pass.
- 350 custom PDF outcomes match; all 334 prior fixture hashes/outcomes are unchanged.
- Sixteen exact-byte before/after pipeline controls: ten false passes now FAIL,
  two unresolved XML-token key cases become INCOMPLETE, four legal controls PASS.
- All sixteen controls pass the exercised PDF/A-4F and verified ISO32005 profiles.
  Ten exact V36 before-runs are reused; 22 new control engine runs are recorded.
- Actual CLI rejection and isolated-worker INCOMPLETE results match the library.
- Twelve fresh retained creator runs cover all six profiles in both modes. Their
  PDF/A/ISO checks pass; their known operand/child conformance failures persist.
  Creator acceptance is still failed.

Permitted extension value types, UTF-8 names containing non-XML characters,
null dictionary omissions and shared/cyclic data containers remain accepted.
Traversal limits remain INCOMPLETE. No acyclic-tree rule is imposed on DPM data.
The XML Name/NMTOKEN wording, broader obligations, eleven listed questions,
forty skips and source occurrence review remain explicit limits.

## Reproduction

`runtime-inputs.zip` and `verification-inputs.json` freeze the relevant runtime,
rules, tests and exact fixtures. Full Python tests run within
`.eapdf-verification-v37`. `verify-regressions.py` checks custom outcomes;
`verify-pdfs.py` runs pinned veraPDF 1.31.18 and real entry points.
`refresh-observations.py` reads recorded outcomes without certifying coverage;
`assemble-evidence.py` verifies counts, hashes and retained acceptance failures.
See [evidence.json](evidence.json) for artifact hashes and boundaries.
