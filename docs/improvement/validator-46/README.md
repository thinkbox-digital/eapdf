# V46 malformed property-name decoding

Every UserProperties name consumer now decodes only actual PDF strings and guards
unreadable Text data. PDF Names, arrays, dictionaries and numbers are not coerced
into property names. Raw N type/byte checks still report the prescribed ASCII-string
violation even when decoding is unavailable. Invalid names no longer abort later
checks with VALIDATION_ERROR.

The change covers direct property checks, source candidate names and enclosing-message
identity, header identity recommendations, active occurrences and scalar name maps.
The initial partial edit missed an identity comprehension; its actual exception stack
and failed focused log are retained. Final tests exercise physical PDFs end to end.

## Authority

EA-PDF9.7 printed p76/Table12 requires PDF ASCII-string N entries. ISO32000-2:2020
7.9.2 distinguishes string classes;14.7.6.4 Table362 defines N as a Text string.
These requirements are unchanged. [Review](rule-review.json).

Readable UTF16 known-name presence remains distinct from its forbidden ASCII encoding.
Legal arbitrary/empty ASCII names and Unicode V text remain permitted. Whole-P-array
replacement remains unchanged; an inactive earlier array does not supply active names.

## Verification

- 756 unit tests: 716 passes and 40 integration/schema skips; twelve focused tests pass.
- 435 custom cases, preserving all 427 prior hashes, statuses and failure sets.
- Eight exact-byte before/after controls pass PDF/A and verified ISO checks.
- Four malformed-name cases retain FAIL status but replace operational exceptions
  with definite ASCII-name and missing-required-GUID data findings.
- A CLI rejection reports the data findings and renders HTML successfully. A worker
  accepts the legal empty ASCII extension with its Unicode value.
- Twelve fresh creator runs retain known operand/child failures while passing the
  exercised PDF/A/ISO gates. Creator acceptance remains false.

Eight exact V45 before receipts are reused by hash. Eight after engine controls,
two entry points and twelve retained creator engine runs are fresh. Skipped gates
are not passes; Java and original creator outputs are unchanged.

## Limits

This fixes known property-name data/operational classification. It does not close all
G5 operational coverage, malformed value decoding, scalar owner policies, source
occurrences, logical coverage, media-specific semantics or XMP group binding. Eleven
listed expert/dependency questions and known creator defects remain open.

[Evidence](evidence.json) · [Inputs](verification-inputs.json) ·
[Custom results](custom-regressions.json) · [Pipeline results](pdf-results.json) ·
[Observation review](observation-review.md)
