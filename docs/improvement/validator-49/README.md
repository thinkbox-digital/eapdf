# V49 unreadable PDF dates

Mail_Sent requires a PDF date. A decoder rejection now makes the existing date predicate false, producing USERPROPS_TYPE instead of aborting validation. ISO7.9.4 requires the processed text to satisfy its date grammar. Valid UTF8 and UTF16 encoded dates remain valid; grammar, calendar and offset rules are unchanged.

[Source review](date-decoding-source-review.json) · [Rule review](rule-review.json)

## Verification

- 799 unit tests: 759 passes and 40 integration/schema skips; twelve focused tests pass.
- 462 custom cases; all 454 prior hashes, statuses and failure sets remain unchanged.
- Eight exact-byte before/after PDF/A and verified ISO controls. Two operational failures become date-type findings; all eight conformance statuses remain unchanged.
- Valid ASCII/UTF8/UTF16 dates remain accepted; readable malformed dates, truncated UTF16 and fullwidth digits remain rejected.
- Source recommendations defer invalid dates to the mandatory type check on both message and field tags. One CLI rejection and one worker acceptance match expectations.
- Twelve fresh retained creator runs pass exercised PDF/A/ISO gates and retain known operand/child failures. Creator acceptance remains false.

Eight exact V48 before receipts are reused by hash. Eight after controls, two entry points and twelve creator runs are fresh. Java and original creator outputs remain unchanged. Skips are not passed gates.

The initial patch script did not match its literal and made no runtime change. Initial source assertions also included the independent valid Message-ID result; final assertions select Mail_Sent explicitly. These failed logs remain preserved. Final focused tests also run against exact prior runtime bytes and demonstrate seven failing paths before this fix.

This is not complete text-encoding validation. Truncated UTF16 is decoded lossily by pikepdf in the exercised control, and the resulting D: fails existing grammar. Other unreadable String V fields, full operational/logical coverage, source occurrence assignment, XMP group binding and eleven listed questions remain open.

[Evidence](evidence.json) · [Inputs](verification-inputs.json) · [Custom results](custom-regressions.json) · [Pipeline results](pdf-results.json) · [Observation review](observation-review.md)
