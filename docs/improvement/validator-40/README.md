# V40: exact attribute names and ASCII bytes

EA-PDF Table12 requires PDF ASCII strings for UserProperties N values. ISO32000-2
Table35/7.9.2 specifies the encoded bytes, not just decoded characters. Required
Table12 names retain their exact characters. [Authority review](rule-review.json).

## Verification

- 666 unit tests: 626 pass, forty integration/schema skips. Eight focused tests pass.
- 377 custom fixture outcomes match; all 367 prior hashes/outcomes remain unchanged.
- Ten exact-byte before/after controls: five false passes now FAIL; five legal forms
  PASS, including Unicode V values and unrelated ASCII extension names with spaces
  or tab characters. All ten pass exercised PDF/A and verified ISO32005 profiles.
- Four exact V39 before-runs are reused; sixteen new control engine runs are recorded.
- Actual CLI and isolated-worker rejections match, including raw-name diagnostics.
- Twelve current original creator custom checks keep their known operand/child
  failures. Independent raw-name guards cover all six profiles in both modes and
  embedded collection children. Fresh V39 upstream reports are reused on identical
  hashes; no new creator engine run or creator acceptance is claimed.

A bounded raw-byte hex prefix explains encoding failures even when decoded text
looks like ASCII. Encoding and attribute presence retain separate findings.
Unrelated extension names are not a closed vocabulary; V text is not restricted
to ASCII. Earlier Unicode value comparison policy is preserved, not recertified.
The O text-string probe already remains INCOMPLETE and is not a claimed repair.
Forty skips, eleven listed questions and broader obligation/source review remain.

## Reproduction

The input manifest/archive freeze runtime, rules, tests and exact seeds/fixtures.
Run tests in `.eapdf-verification-v40`; use `verify-regressions.py`, `verify-pdfs.py`
and `verify-retained.py`. The regression checkpoint is tied to frozen inputs;
the PDF verifier supports `--resume`. `refresh-observations.py` reads recorded
outcomes; `assemble-evidence.py` asserts hashes, expected findings and reuse limits.
The initial source-key freeze error stopped before copying; the successful archive
includes the pinned seed explicitly. See [evidence.json](evidence.json).
