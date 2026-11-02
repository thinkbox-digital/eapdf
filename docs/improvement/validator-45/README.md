# V45 Content Set subtype recommendation applicability

The subtype SHOULD recommendation applies when Mail_ContentSet occurs. Its
assessment no longer depends on the optional self Mail_ContentSetType property
or a rendering-only category filter. Resolved missing names warn. Unresolved
attribute evidence remains explicitly unverified. Absent optional custom vocabulary
keeps N/A, while known supplied names retain separate byte/type/value assessment.

## Authority

EA-PDF9.7 Table13 printed p79 and the reviewed T13-SET-SUBTYPE inventory record
condition the recommendation on occurrence of Mail_ContentSet. The previous
where-appropriate/rendering-only paraphrase added an unsupported qualification.
[Primary review](recommendation-source-review.json), [rule review](rule-review.json).

The recommendation remains SHOULD guidance. It adds no mandatory DPM subtype
presence, conformance failure or incomplete marker for ordinary known absence.
Empty supplied values are still present; their separate matching/type checks
can fail. The tested unresolved owner remains INCOMPLETE for its pre-existing
attribute/matching evidence, independently of the recommendation warning.

## Verification

- 744 unit tests: 704 passes and 40 integration/schema skips; ten focused tests pass.
- 427 custom cases, preserving all 419 prior hashes, statuses and failure sets.
  Recommendation observations are compared separately; changed prior records
  are enumerated in [evidence](evidence.json).
- Eight exact-byte before/after controls pass PDF/A and verified ISO checks.
  Four unsupported recommendation passes become WARN; conformance outcomes remain.
- One CLI rejection retains its separate value mismatch with recommendation PASS.
  One worker accepts a file with a missing recommended subtype and a WARN result.
- Twelve fresh creator runs preserve known operand/child failures while passing
  the exercised PDF/A/ISO gates. Creator acceptance remains false.

Eight exact V44 before receipts are reused by hash. Eight after engine controls,
two entry points and twelve retained creator engine runs are fresh. The checkpointed
corpus and final creator run were resumed after user steering interrupted commands.

Initial unused-import and in-memory-path test attempts are retained. Final focused
tests save actual temporary PDFs so page extraction and conformance assertions use
physical files. Forty skips are not passed gates; no Java build or original sample
regeneration is claimed.

## Limits

FrontMatter fixture data establishes internal metadata correspondence, not source
or vendor fidelity. Complete logical coverage, other scalar consumers, media-specific
semantics, malformed property-name decoding, source occurrence assignment, XMP group
binding and eleven listed expert/dependency questions remain open.

[Inputs](verification-inputs.json) · [Custom results](custom-regressions.json) ·
[Pipeline results](pdf-results.json) · [Observation review](observation-review.md)
