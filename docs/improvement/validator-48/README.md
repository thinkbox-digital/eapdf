# V48 generic UserProperties value scope

EA Table12 field types apply to the named EA properties and the Mail_* email-header row. Unrelated ASCII property names retain the generic ISO Table362 V type of any PDF object. Required N/V entries, EA mail-field types, tag scope and raw name ASCII checks remain separate.

This is an explicit combined-clause interpretation, recorded in [the source review](generic-property-source-review.json) and [rule review](rule-review.json). It does not certify vendor-specific semantics. Generic reader acceptance is distinct from the writer recommendation to prefer scalar values.

## Verification

- 787 unit tests: 747 passes and 40 integration/schema skips; seventeen focused tests pass.
- 454 custom cases; all 446 prior hashes, statuses and failure sets remain unchanged.
- Four false unrelated-property type failures now pass. Existing Mail_* rejections, Unicode text and missing/null V rejection remain.
- Eight exact-byte before/after PDF/A and verified ISO controls; seven distinct hashes because pikepdf canonicalizes explicit null to missing. A direct parsed-null unit test independently exercises null interpretation.
- One CLI rejection and one worker acceptance match their expected outcomes.
- Twelve fresh retained creator runs pass exercised PDF/A/ISO gates and retain known operand/child failures. Creator acceptance remains false.

Eight exact V47 before engine receipts are reused by hash. Eight after controls, two entry points and twelve creator runs are fresh. Java and original creator outputs remain unchanged. Skips are not passed gates.

Unreadable String V paths, full operational and logical coverage, source occurrence assignment, XMP group binding and eleven listed questions remain open.

[Evidence](evidence.json) · [Inputs](verification-inputs.json) · [Custom results](custom-regressions.json) · [Pipeline results](pdf-results.json) · [Observation review](observation-review.md)
