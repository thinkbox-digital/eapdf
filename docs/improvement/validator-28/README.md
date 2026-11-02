# V28: exact PDF Boolean values

EA-PDF 9.1 (printed p62) requires ViewerPreferences DisplayDocTitle to be true.
Section 9.5 (printed p68) requires a present Collection CustomField E to be false,
including extra custom fields. The authoritative ownership review retains both
assertions in the custom validator for all five configured PDF/A profiles.

Python truthiness formerly accepted integer 1 and nonempty text for DisplayDocTitle,
and integer 0, empty text, arrays and dictionaries for E. The checks now compare
the decoded PDF Boolean directly. Absent E remains permitted; absent DisplayDocTitle
still fails. Requirement text, rule codes and ownership are unchanged.

## Controls and evidence

Six focused tests serialize and reopen real PDF values, including true/false,
integers, text, names, arrays, dictionaries, absent entries and extra custom fields.
Thirteen PDF controls have expectations derived directly from the two clauses.
The collection child operands are independently corrected using structure/MCID
inspection; only the new fixture copies are changed. Payload size/checksum
parameters are recomputed. Original email CI Size is retained.

The collection controls illustrate an execution boundary: custom-only recursion
skips child PDF/A and reports INCOMPLETE. Complete validation executes PDF/A and
verified ISO checks for the parent and child, and its legal controls PASS.
Source-dependent recommendation warnings remain visible. The initial mistaken
PASS expectation and subsequent diagnosis are retained in
[initial-collection-control.json](initial-collection-control.json).

The verification scripts assert these gates before producing [evidence](evidence.json):

- 551 unit tests: 511 pass and 40 explicit skips.
- 245 custom PDF outcomes, with all 232 prior hashes/outcomes unchanged.
- Thirteen identical-byte before/after complete-pipeline probes. Seven former
  PASS results now FAIL on the appropriate Boolean assertion; all exercised
  PDF/A and verified ISO results PASS.
- Six actual CLI/isolated-worker checks across PASS, FAIL and INCOMPLETE.
- Twelve exact retained creator outputs keep their known operand/child failures.
  Creator conformance remains failed; no new Java build is claimed.

Frozen inputs: `.eapdf-verification-v28`, `verification-inputs.json` and
`runtime-inputs.zip`. Run `verify-regressions.py`, `verify-pdfs.py` and
`assemble-evidence.py`. Before execution uses the complete V27 snapshot.
Eleven open decisions and remaining shared/obligation coverage are unchanged.
