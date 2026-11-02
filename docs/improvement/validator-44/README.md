# V44 DPM entry presence and empty Names

An actual empty PDF Name is present. Entry presence no longer depends on decoded
string truthiness. Name type and UTF8 encoding remain separate checks. An empty
Content Set extension Name is permitted by the open value set. An empty subtype
Name fails media syntax, including when the subtype entry is optional.

Known non-body applicability accepts valid empty extension Names, while retaining
successful UTF8 decoding, actual Name type and page-range evidence. Missing, null,
text and unreadable Name values cannot establish that applicability. Human-readable
descriptions and profile prohibitions are unchanged.

## Authority

ISO32000-2:2020 7.3.5 explicitly permits the empty Name. EA-PDF9.6 Table8 requires
Content Set type names with an open value set and prescribes media type syntax for
subtypes. Dictionary nulls are absent. [Primary review](name-source-review.json),
[rule review](rule-review.json).

## Verification

- 734 unit tests: 694 passes and 40 integration/schema skips. Thirteen new focused
  tests and eight retained schema tests pass.
- 419 custom fixtures; all 411 prior PDF hashes and overall statuses are retained.
  410 prior status/failure sets are unchanged. One proved false missing-entry code
  is removed from the malformed UTF8 Name fixture; true encoding/operand failures
  remain. [Native proof](prior-presence-review.json).
- Eight exact-byte before/after PDF controls pass PDF/A and verified ISO checks.
  One false pass fails, one false failure passes, and one required empty subtype
  receives the correct media syntax failure instead of a missing-entry failure.
- One CLI rejection and one worker acceptance match expectations.
- Twelve fresh final creator runs preserve known operand/child failures while
  passing the exercised PDF/A/ISO gates. Creator acceptance remains false.

Three exact V43 before-runs are reused. Five further before-runs use the accepted
V43 runtime; those eight before receipts remain valid through the amendment.
All eight after controls, two entry points and twelve creator engine runs are
executed again on the final frozen runtime. [Rerun record](rerun-review.json).

The provisional full suite correctly caught loss of the unreadable-UTF8 safeguard.
The guard was repaired; the existing unit expectation was preserved. Initial unit,
corpus and engine reports, archive and manifest are retained. Initial null setter
attempts are also retained; null controls now use actual parsed PDF syntax.

## Limits

Open extension names and optional FrontMatter controls prove internal declared
correspondence, not source/vendor semantics or complete logical coverage. Other
scalar consumers, media-specific parameter semantics, source occurrence assignment,
XMP group binding, eleven listed questions and known creator defects remain open.
Forty skips are not passed gates. No Java build or original creator regeneration.

[Evidence](evidence.json) · [Inputs](verification-inputs.json) ·
[Custom results](custom-regressions.json) · [Pipeline results](pdf-results.json) ·
[Observation review](observation-review.md)
