# V30: profile navigation values must be PDF names

EA-PDF 9.1 (printed p62) mandates PageMode and NonFullScreenPageMode values
according to the profile and attachment condition. The PDF catalog and viewer
preference tables in ISO 32000-2 define these entries as names. Text with the
same spelling cannot satisfy those entries.

V29's independently saved name/text probes pass both the actual pinned PDF/A
engine and verified ISO profile. The previous custom checks also passed text
values because safe_name accepted both representations. The authoritative
ownership register retains the named-mode assertion in the custom validator.

V30 requires a PDF name before comparing the prescribed mode in every profile
branch. Findings retain the observed spelling and add is_pdf_name. Profile
selection, attachment conditions, PDF/A-part policy and entry-presence checks
are unchanged. No general safe_name behavior is changed.

## Verification

Four focused tests cover all six profiles and s/si attachment/part conditions,
with canonical names, equivalent text, missing entries and wrong names. The
before run has 24 failed mode-type assertions and 24 errors from the missing
new diagnostic field. An initial empty discovery log is preserved; it is not a
passing test gate. The restored focused tests pass.

[Acceptance evidence](evidence.json) asserts:

- 568 unit tests: 528 pass and 40 explicit skips.
- 265 custom PDF outcomes, retaining all 259 prior hashes/outcomes.
- Six identical-byte before/after complete-pipeline controls: correct names,
  separate and combined text modes (including slash-prefixed text), wrong names
  and a missing mode. Three former false PASS results now fail. All exercised
  PDF/A and verified ISO results pass.
- One real CLI and one isolated worker check for the two repaired fields.
- Independent native-name/hash checks on all twelve retained creator outputs.
  Their exact V29 full-pipeline runs are reused; twelve new engine runs are not
  claimed. Every added guard is true on those inputs, and their known failed
  operand/child conformance remains applicable. The creator JAR is unchanged.

Frozen inputs: `.eapdf-verification-v30`, `verification-inputs.json` and
`runtime-inputs.zip`. Run `verify-regressions.py`, `verify-pdfs.py`,
`verify-retained-names.py`, `refresh-observations.py`, then `assemble-evidence.py`.
The before engine uses the complete V29 snapshot.

The [observation review](observation-review.md) contains only the recorded V30
cohort; focused unit assertions and historical pipeline runs retain separate
boundaries. Eleven decisions and wider coverage remain open. Actual next probes
show text-valued Collection Subtype and Catalog Version also pass the complete
pipeline; these need their own authority/ownership review before repair.
