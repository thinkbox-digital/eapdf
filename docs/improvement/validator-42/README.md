# V42 active UserProperties and subtype correspondence

Every applicable property in the active UserProperties P array is checked. A later
same-name property cannot hide an earlier wrong or empty value. Uniform legal
repetitions and whole-P-array replacement across eligible attributes remain valid.
Only the actual PDF name UserProperties selects this owner.

Subtype comparisons use actual connected DPart content, independently of tested
attributes. Metadata on another Content Set cannot justify a match or a failure.
Missing or ambiguous counterparts stay unverified; supplied known contradictions
fail. GUID, type and subtype evidence are assessed independently. Context candidate
sets are folded once per element, avoiding repeated reference/property products.

## Authority and limits

The pinned EA-PDF9.7 Tables12/13 and ISO32000-2:2020 14.7.6.4 Tables361/362 establish
individual properties and their named constraints. Corresponding subtype ownership
is inferred from the combined content representation and matching clauses, as with
the earlier GUID/group repairs. [Source review](rule-review.json).

Subtype formatting policy, other scalar consumers, source fidelity/occurrences,
complete logical coverage, FrontMatter nesting interpretation and XMP group binding
remain open. Optional FrontMatter controls establish internal declared correspondence;
they do not certify source/vendor format fidelity. Eleven listed questions remain.

## Verification

- 707 unit tests: 667 passes and 40 integration/schema skips.
- 403 custom fixtures; all 387 previously accepted hashes/statuses/failure sets retained.
- Sixteen exact-byte before/after controls: PDF/A and verified ISO PASS throughout.
- Six former pipeline passes now fail; one missing required owner changes from
  INCOMPLETE to FAIL; two unsupported comparison failures become INCOMPLETE.
- One CLI rejection and one worker INCOMPLETE result match expectations.
- Twelve fresh original creator runs retain the known operand/child conformance
  failures while passing the exercised PDF/A/ISO gates. Creator acceptance is false.

Four exact V41 before reports are reused by byte hash; twenty-eight control engine
calls, two entry points and twelve creator engine runs are fresh. Forty skipped
gates are not counted as passed. No Java build or original sample regeneration.

The audit generator's ordering-sensitive consistency assertion was repaired without
changing requirement links. All 301 records and 224 codes regenerate successfully.
Initial failed audit/resume attempts and provisional inputs are retained. Historical
`creator_changed` in the generated audit describes the whole improvement baseline;
it does not indicate a creator change in V42. The unused preliminary retained-profile
reuse script is not an acceptance receipt; all twelve profiles are freshly exercised.

[Evidence](evidence.json) · [Inputs](verification-inputs.json) ·
[Custom outcomes](custom-regressions.json) · [Pipeline outcomes](pdf-results.json) ·
[Observation review](observation-review.md)
