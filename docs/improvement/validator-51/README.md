# V51 unreadable group and subtype comparisons

Group-type and content-subtype correspondence now guard text decoding. Supplied unreadable String values leave the mandatory comparison unavailable with VALIDATION_INCOMPLETE. Every readable companion occurrence is still checked; definite contradictions fail even alongside unreadable data.

Independent SHOULD presence recommendations, actual PDF object types, exact opaque group values, reviewed MIME equivalence and whole-array replacement remain intact. No new encoding prohibition, inferred ownership or source/vendor semantic certification is introduced.

[Source review](comparison-decoding-source-review.json) · [Rule review](rule-review.json)

## Verification

- 821 unit tests: 781 passes and 40 integration/schema skips; ten focused tests pass across both comparison families.
- 478 custom cases; all 470 prior hashes, statuses and failure sets remain unchanged.
- Eight exact-byte before/after controls pass PDF/A and verified ISO. Two operational failures become INCOMPLETE; two retain FAIL with definite correspondence mismatches.
- Focused tests cover unreadable values on both sides of valid and mismatching duplicates, presence recommendations, valid UTF8/UTF16, wrong PDF types and inactive replaced arrays.
- A real CLI rejects the mixed group contradiction; a worker retains INCOMPLETE for an unreadable subtype.
- Twelve fresh retained creator runs pass exercised PDF/A/ISO gates and retain known operand/child failures. Creator acceptance remains false.

Eight exact V50 before receipts are reused by hash. Eight after controls, two entry points and twelve creator runs are fresh. Java and original creator outputs remain unchanged. Skips are not passed gates. The final ten focused tests have twelve failing subcases across six methods before the repair.

Other String V paths, DPM/XMP decoding, scalar FrontMatter nesting, complete text encoding validation, source-owner duplicate policies, source occurrences, logical coverage, XMP group binding and eleven listed questions remain open.

[Evidence](evidence.json) · [Inputs](verification-inputs.json) · [Custom results](custom-regressions.json) · [Pipeline results](pdf-results.json) · [Observation review](observation-review.md)
