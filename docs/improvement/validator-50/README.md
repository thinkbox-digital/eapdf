# V50 unreadable message GUID evidence

Required GUID presence and every active correspondence occurrence now guard text decoding. An unreadable String cannot establish an identity or prove a mismatch. When no known nonempty value establishes required presence, that assessment stays unavailable. A valid companion establishes presence but cannot suppress an unreadable matching occurrence. Mandatory unknowns retain VALIDATION_INCOMPLETE.

Readable contradictions still fail even alongside unreadable values. No identity is inferred from bytes, and no new UUID grammar or encoding prohibition is introduced. Actual object-type checks, decoded empty strings, valid Unicode identities and whole-array replacement retain separate behavior.

[Source review](guid-decoding-source-review.json) · [Rule review](rule-review.json)

## Verification

- 811 unit tests: 771 passes and 40 integration/schema skips; twelve focused tests pass.
- 470 custom cases; all 462 prior hashes, statuses and failure sets remain unchanged.
- Eight exact-byte before/after controls pass PDF/A and verified ISO. Three operational failures become INCOMPLETE; one retains FAIL with the definite correspondence mismatch.
- Valid UTF8/UTF16 GUIDs remain accepted; decoded empty text and wrong PDF object types remain rejected. Duplicate occurrences are checked in both orders; replaced arrays remain inactive.
- One CLI rejects the mixed unreadable/known-wrong identity. One worker retains INCOMPLETE for unreadable identity evidence.
- Twelve fresh retained creator runs pass exercised PDF/A/ISO gates and retain known operand/child failures. Creator acceptance remains false.

Eight exact V49 before engine receipts are reused by hash. Eight after controls, two entry points and twelve creator runs are fresh. Java and original creator outputs remain unchanged; skips are not passed gates.

The initial type assertion included independent passing property types. The final assertion selects Mail_GUID. Initial logs remain; the final twelve tests also run against exact prior runtime bytes, with seven failures before the repair.

DPM/XMP GUID decoders, other String V paths, complete text encoding validation, source-owner duplicate policies, source occurrence assignment, logical coverage, XMP group binding and eleven listed questions remain open.

[Evidence](evidence.json) · [Inputs](verification-inputs.json) · [Custom results](custom-regressions.json) · [Pipeline results](pdf-results.json) · [Observation review](observation-review.md)
