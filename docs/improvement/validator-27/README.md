# V27: exact decoded source filenames

EA-PDF 9.8.2 Table 16, printed p85, defines a case-sensitive original filename
text string and permits original path components. Whitespace is filename data.
The former source matching paths collapsed whitespace and used display-cleaned
PDF filenames. That could both lose valid associations and invent a unique
name match selecting the wrong media owner.

V27 preserves exact decoded source filename text. Native association prefers
the already retained raw UF/F values, with legacy model fallbacks. The existing
basename comparison still removes directory components; it no longer trims or
collapses basename whitespace. URI/array values do not provide normal-string
filename evidence. Display cleanup, checksum priority, unique-name tie-breaking
and universal contradiction checks are retained.

No owner is selected from its tested size/media value. This is not a claim of
unique original occurrence identity, original-platform reconstruction or full
input-inventory assurance. Those retain the plan's existing limits.

## Reviewed controls

Ten focused tests cover spaces, leading/trailing text, tabs, line feeds,
nonbreaking spaces, case, composed/decomposed Unicode, original paths, raw PDF
UF/F precedence, unique checksums and invalid URI metadata.

Eight independent PDFs establish these outcomes:

- Four permitted filename forms change INCOMPLETE to PASS: distinct spaces,
  original paths, leading/trailing spaces and nonbreaking spaces.
- An invented whitespace alias changes FAIL to INCOMPLETE. The source owner
  remains unresolved and no value is contradicted by every candidate.
- A universally contradicted media type still fails; a false per-file media
  failure caused by the invented owner disappears.
- Unique checksum aliases still pass; a genuinely wrong size still fails.

The recipes inspect exact native UF strings and set original XMP filenames
directly. Historical source PDFs and creator outputs are unchanged.

## Verification boundary

[Final evidence](evidence.json) accepts **505 unit passes / 40 skips, 232 custom
outcomes, eight PDF probes and eight actual CLI/worker checks**. All 224 prior
hashes/outcomes and the twelve known creator conformance failures are unchanged.
The asserted frozen gates are:

- 545 unit tests: 505 pass and 40 explicit skips.
- 232 exact custom outcomes; all 224 prior PDF hashes/outcomes unchanged.
- Eight identical-byte before/after real PDF/A and verified-ISO probes.
- Eight real CLI/isolated-worker checks across PASS, FAIL and INCOMPLETE.
- Revalidation of all twelve exact creator profile outputs. V24's known
  operand/child failures remain failed; their exercised PDF/A and ISO checks
  pass. Creator conformance is not accepted.

Frozen inputs: `.eapdf-verification-v27`, `verification-inputs.json` and
`runtime-inputs.zip`. Run `verify-regressions.py`, `verify-pdfs.py` and
`assemble-evidence.py`. The before-state engine uses the complete V26 snapshot.
Previously accepted outcomes are reused only for exact hashed prior inputs.

Authority remains [validator_rule_text.json](../../../validator_rule_text.json).
Rule codes/requirements, CLI arguments, creator and saved samples are unchanged.
No new Java or complete packaged-suite acceptance is claimed. Forty skips,
eleven decisions, source occurrence/inventory limits, remaining shared-predicate
and obligation review, and the creator repair remain explicit.
