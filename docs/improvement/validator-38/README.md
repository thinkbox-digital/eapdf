# V38: DPart object types and page ranges

The primary ISO32000-2:2020 document-part clauses require dictionary objects,
unique page ownership and depth-first page order. Four exact-byte before probes
incorrectly passed the complete pipeline. [Rule review](rule-review.json).

## Verification

- 641 unit tests: 601 pass, forty integration/schema skips. Eight focused tests pass;
  six initially failing assertions are retained in the before log.
- 358 custom PDF outcomes match; all 350 prior fixture hashes remain. Of their outcomes, 345 are unchanged and five
  gain proved overlap failures after independent native inspection.
- Eight before/after PDF controls: four false passes now FAIL, a previously rejected
  leaf stream retains its two failures, and three valid controls PASS.
- Every control passes the exercised PDF/A and verified ISO32005 profiles.
  Six exact V37 before-runs are reused; ten new control engine runs are recorded.
- Five retained negative controls have actual before/after PDF/A checks: all pass PDF/A;
  ISO32005 is correctly inapplicable to their unstructured outer PDFs. Their earlier
  failures persist and only the proved page-range failure is added.
- Actual CLI and isolated-worker rejection results match the library.
- Twelve original creator PDFs pass independent native dictionary/coverage/order
  guards and current custom checks of the repaired assertions. Their known overall
  operand/child failures persist. Fresh V37 upstream reports are reused on identical
  hashes; no new creator engine run or creator acceptance is claimed.

The multi-message positive uses a fixture copy of retained creator bytes, with only
independently proved operand repairs before range/order mutations. Original creator
files and JAR remain unchanged. Failed preliminary fixture recipes are retained in
V37 next probes; they are not passing controls. Optional types remain optional.
Broader obligations, source occurrences, forty skips and eleven listed questions
remain open.

## Reproduction

The input manifest and runtime archive include the independent seed PDFs and
inspection helper. Run tests inside `.eapdf-verification-v38`, then
`verify-regressions.py`, `verify-pdfs.py` and `verify-retained.py`.
`refresh-observations.py` reads the bounded execution cohort;
`assemble-evidence.py` verifies hashes, expected findings and reuse limits.
An input message interrupted two long commands; completed reports were retained,
and the remaining gates resumed with exact-input checkpoints. The complete final
logs and strict assembly assertions establish the accepted result. The initial
regression expectation failure and provisional archive remain separate evidence.
See [evidence.json](evidence.json).
