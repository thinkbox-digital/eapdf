# V26: source parameter evidence and known source associations

EA-PDF 9.4, printed pp66–67, requires media reconstruction and equivalence;
Table 16, printed p85, includes required source parameters in ContentType.
The parameter rule could report N/A for an unresolved source or inherit PASS
from a verified attachment while declared source parameters remained untested.

V26 retains WARN/unavailable for those declarations and records required
unavailable comparisons as VALIDATION_INCOMPLETE. Definite failures still win.
Absent Source copies, bare declarations and invalid metadata shapes do not
create new parameter obligations.

The media comparison now reuses checksum priority and an unambiguous filename
tie-break, consistent with the existing source-asset check. This preserves
verified source comparisons when basenames collide or payload checksums are
shared. Tested size/media values never select an owner. Ambiguous/unmatched
sources retain explicit limits.

Authority remains [validator_rule_text.json](../../../validator_rule_text.json),
`SOURCE_MEDIATYPE_PARAMETERS_MATCH` and `EMBEDDED_SUBTYPE_MATCH_XMP`. Requirement
IDs, code count, CLI arguments and creator are unchanged.

## Controls and evidence

Three original regressions reproduce wrong PASS/N/A results. Ten final focused
tests cover missing/ambiguous associations, mixed verified/unavailable evidence,
definite failure precedence, schema shapes, absent sources, unique names with
shared checksums and unique checksums with colliding original basenames.

Nine independent new PDFs include ambiguous and unmatched declarations with
and without attachment metadata; bare and malformed-shape controls; unique
source parameters; unique names with shared checksums; and a definite native
parameter mismatch. The latter retains its pre-existing annotation/media
failures. Two retained V25 parameter PDFs are full before/after probes too.

[Final evidence](evidence.json) accepts **495 unit passes / 40 skips, all 224
custom outcomes, eleven PDF probes and eight CLI/worker checks**. All 215 prior
hashes/outcomes and the twelve known creator conformance failures are unchanged.
The asserted frozen gates are:

- 535 unit tests: 495 pass, 40 explicit skips.
- 224 custom PDF comparisons; all 215 prior hashes and overall status/failure
  outcomes unchanged.
- Eleven identical-byte before/after PDF/A/verified-ISO probes.
- Eight actual CLI/isolated-worker outcomes.
- All twelve exact retained creator profile outputs revalidated. Their known
  operand/child conformance failures must remain unchanged; creator acceptance
  remains failed.

The full custom comparison reviews ten changed media-reporting rows: six
parameter WARN corrections, four verified source subtype confirmations, and
two unavailable subtype results overlapping two of the parameter corrections.
This is an evidence improvement, not a conformance-coverage percentage.

Inputs are frozen under `.eapdf-verification-v26`; the manifest and runtime ZIP
retain the verification boundary. Run `verify-regressions.py`, `verify-pdfs.py`
and `assemble-evidence.py`. Before execution imports the complete V25 snapshot.

Initial fixture mistakes are retained: native attachments in this seed live
in page annotations, and removing metadata parameters alone leaves a real
media mismatch unless the bare control's native parameters are removed too.
`initial-fixtures.zip` and `initial-fixture-review.json` preserve that boundary.

## Remaining work

The next retained counterexample, `next-source-filename-probe.json`, shows that
source filename whitespace collapse can invent a name association. Review exact
decoded filenames in both matching paths before claiming source identity.
Occurrence assignment, source inventory completeness, the remaining shared
predicate/obligation review and eleven interpretation/dependency questions stay
open. The creator operand repair remains in the subsequent phase; no Java
build, saved-example regeneration or complete packaged acceptance is claimed.
