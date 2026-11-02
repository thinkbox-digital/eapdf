# V29: exact Collection names and naming scope

EA-PDF 9.5 (printed p68) incorporates the filename restrictions and case
normalization rules following ISO 32000-2:2020 Table 159 (printed pp454–455).
Names are PDF text strings with 1–255 characters, no NULL or listed forbidden
characters, and no final period. Sibling folders and files in the same folder
must remain unique after case normalization.

The validator formerly cleaned whitespace, omitted empty filenames, deduplicated
file specifications before checking their name-tree aliases, ignored identical
sibling names, and assigned unprefixed files to folder zero. These produced both
false failures and missed failures. V29 now:

- Preserves exact decoded filename/folder text, including whitespace and line feeds.
- Checks text-string types and every actual EmbeddedFiles name-tree entry.
- Checks aliases independently even when their file specification is shared.
- Detects identical and case-equivalent siblings within the applicable folder.
- Uses the actual root folder ID for unprefixed names, and recognizes only ASCII
  digits in a folder prefix.

Casefold policy is retained. Names in different folders may coincide. This batch
does not certify the full folder graph, folder-ID integrity or every Unicode
version. Other AF discoveries do not create additional collection names.

## Independent controls

Thirteen focused tests exercise legal whitespace, identical/case-only names,
Unicode casefold, empty text, character limits, every prohibited character,
shared aliases, actual root scope, distinct folders, line feeds and PDF types.
The saved V28 before run contains thirteen failed subtest assertions.

Fourteen PDFs derive their expected results from the two cited standards:

- Eight missed violations now fail: identical siblings, empty/overlong filenames,
  overlong folders, shared case aliases, implicit/explicit root aliases,
  non-ASCII prefix digits and a non-text folder name.
- Three permitted forms lose false failures: distinct folder whitespace,
  whitespace-only names and a filename line feed.
- Case-only siblings remain rejected; distinct aliases and different folders
  remain permitted.

New controls reuse V28's independently corrected collection child. The initial
recipe's unrelated AF error is diagnosed and retained in
[initial-cloned-spec-control.json](initial-cloned-spec-control.json). The final
recipe preserves the original catalog-associated file in the name tree.

## Verification and remaining review

The scripts assert these gates before writing [accepted evidence](evidence.json):

- 564 unit tests: 524 pass and 40 explicit skips.
- 259 custom PDF outcomes; all 245 prior PDF hashes/outcomes unchanged.
- Fourteen identical-byte before/after real PDF/A and verified ISO probes,
  with eight false PASS and three false FAIL results corrected.
- Six actual CLI/isolated-worker checks across PASS, FAIL and INCOMPLETE.
- Twelve exact retained creator outputs keep their known operand/child failures.

Custom-only collection controls stay INCOMPLETE because child PDF/A is skipped;
their complete-pipeline legal controls PASS. No creator repair, new Java build
or complete packaged-suite acceptance is claimed.

Frozen inputs: `.eapdf-verification-v29`, `verification-inputs.json` and
`runtime-inputs.zip`. Run `verify-regressions.py`, `verify-pdfs.py`,
`refresh-observations.py`, then `assemble-evidence.py`. Before execution uses the
complete V28 snapshot.

The refreshed [observation matrix](observation-matrix.json) and
[review queue](observation-review.md) read recorded V29 runs. They count 259
custom PDFs and fourteen current complete-pipeline controls. Focused unit
assertions and historical pipeline cohorts are outside this view. A code-level
PASS does not establish each linked obligation, and missing FAIL observations
are review prompts rather than defects. The V23 census remains historical.
Eleven interpretation/dependency questions and broader coverage remain open.
