# V47 wrong-value diagnostics and identity handling

Known UserProperties V type violations now use the existing guarded object observation
instead of unsafe text conversion. An unreadable Name, array or dictionary display
cannot abort a definite type failure. Source owner identity also requires actual PDF
strings and guarded decoding. Invalid identity data remains unavailable rather than
being coerced into an owner or throwing an operational exception.

## Authority

EA-PDF9.7 Table12 prescribes the named field types and generic Mail_* header scalar
types. ISO32000-2:2020 Table362 allows generic PDF V(any); that does not replace the
narrower EA field types. No new conformance constraint is introduced.
[Source/implementation review](rule-review.json).

Raw name validation, required field presence and matching uncertainty remain separate.
Unicode strings stay permitted. Source-value recommendations retain SHOULD strength;
invalid owner evidence is not a source-value mismatch.

## Verification

- 770 unit tests: 730 passes and 40 integration/schema skips; fourteen focused tests pass.
- 446 custom fixtures, preserving all 435 prior hashes, statuses and failure sets.
- Eleven exact-byte before/after controls pass PDF/A and verified ISO checks.
- Seven formerly operational failures retain FAIL status and now report the definite
  USERPROPS_TYPE violation. Two existing data rejections and two valid controls remain.
- One CLI rejection renders the unreadable Name diagnostic safely in JSON/HTML.
  One worker accepts Unicode text with recommendation-only source comparison results.
- Twelve fresh creator runs retain known operand/child failures while passing the
  exercised PDF/A/ISO gates. Creator acceptance remains false.

Eleven exact V46 before receipts are reused by hash. Eleven after engine controls,
two entry points and twelve retained creator engine runs are fresh. Java and original
creator outputs are unchanged; skipped gates are not passes.

## Limits

Unreadable String V data, full operational coverage, other scalar/duplicate owner
policies and unrelated generic property constraints remain open. This does not close
source occurrence assignment, logical coverage, media-specific semantics, XMP group
binding or the eleven listed expert/dependency questions.

[Evidence](evidence.json) · [Inputs](verification-inputs.json) ·
[Custom results](custom-regressions.json) · [Pipeline results](pdf-results.json) ·
[Observation review](observation-review.md)
