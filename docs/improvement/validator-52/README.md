# V52 unreadable source header comparisons

Source header-value decoding and unfolding now guard decoder rejection. An unreadable attribute leaves the SHOULD source-fidelity comparison WARN/unavailable. It does not prove equality or modification, add a conformance failure or mark mandatory validation incomplete. Other active properties remain assessable.

Invalid PDF dates and object types retain their separate checks. Source/owner uncertainty, duplicate source headers, valid encoded strings and readable differences keep their existing behavior. No new text-encoding prohibition or source/rendering semantic certification is introduced.

[Source review](source-text-decoding-review.json) · [Rule review](rule-review.json)

## Verification

- 833 unit tests: 793 passes and 40 integration/schema skips; twelve focused tests pass, with seven decoder errors before the repair.
- 486 custom cases; all 478 prior hashes, statuses and failure sets remain unchanged.
- Eight exact-byte before/after controls pass PDF/A and verified ISO. Four operational failures become PASS with explicit unavailable recommendation warnings.
- Pipeline source findings are checked by property name: unreadable subject/raw date/from/generic header are unavailable; valid UTF8/UTF16 subjects compare equal; readable mismatch remains WARN/different; wrong Name data retains its separate type rejection.
- Focused source tests also cover descendant ownership and both orders of readable/unreadable duplicate attributes, plus the separate date/type paths.
- One CLI accepts the source warning and renders JSON/HTML safely. One worker retains the wrong-type rejection.
- Twelve fresh retained creator runs pass exercised PDF/A/ISO gates and retain known operand/child failures. Creator acceptance remains false.

Eight exact V51 before receipts are reused by hash. Eight after controls, two entry points and twelve creator runs are fresh. Java and original creator outputs remain unchanged. Skips are not passed gates.

Other String V paths, DPM/XMP decoding, the open collection-parent conflict, scalar FrontMatter nesting, complete encoding validation, source-owner duplicates, source occurrences, logical coverage, XMP group binding and eleven questions remain open.

[Evidence](evidence.json) · [Inputs](verification-inputs.json) · [Custom results](custom-regressions.json) · [Pipeline results](pdf-results.json) · [Observation review](observation-review.md)
