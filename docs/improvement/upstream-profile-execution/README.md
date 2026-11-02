# Actual execution of all five allowed PDF/A profiles

Fifteen independent geometric PDFs exercise pinned veraPDF 1.31.18: one
baseline, one JavaScript action and one Launch action for PDF/A-3A, 3U, 4,
4F and 4E. All detected profiles and outcomes match the reviewed expectations:
eight PASS and seven FAIL. [Results](results.json), [hashes](evidence.json).

These are upstream PDF/A controls. They are not complete EA-PDF examples and
do not close the sixty shared/custom splits, all eleven delegated assertions,
ISO coverage or the PDF/A edition question.

The builder independently constructs pages, geometric marked content,
structure and standard XMP. Only the ICC output intent is copied from a saved
sample. No email creator or rendering behavior determines expected findings.
The verifier asserts the detected profile, reported version, normal execution
and exact prohibited-action predicate, retaining full engine JSON.

Authority: retained profiles `PDFA-3A.xml` and `PDFA-3U.xml`, clause 6.5.1
test 1; `PDFA-4.xml`, `PDFA-4F.xml` and `PDFA-4E.xml`, clause 6.6.1 test 1,
under [the pinned profile directory](../verapdf-coverage/profiles).
Launch is prohibited by all five. JavaScript is prohibited by the two part 3
profiles and explicitly permitted by the three retained part 4 predicates.

The initial assumption that JavaScript would fail every profile was wrong.
Its full reports and failed expectation are preserved under
`initial-javascript-expectation/`. Those reports are not an engine defect.
No document action is executed by this verification.

Reproduce with `python3 -B scripts/build_eapdf_upstream_profile_controls.py`,
then `python3 -B scripts/verify_eapdf_upstream_profile_controls.py`. Set
`VERAPDF_MOUNT_ROOT` to the repository and `GOMAXPROCS=2`. The fixture manifest
records exact input hashes. This gate used V23's unchanged upstream runner,
retained in `validator-24/before-validate_eapdf.py`; the V24 custom operand
repair is a separately bounded gate.

## V25 follow-up: predicate and operational witnesses

Five successful `--success` runs retain actual object check contexts in
[predicate-witnesses/reference-witnesses.json](predicate-witnesses/reference-witnesses.json).
Of 502 mapped profile-reference rows, 156 have actual object witnesses in these
geometric controls and 346 have none. These are reference rows, not distinct
requirements or a coverage percentage. A generic XMPProperty witness can concern
standard metadata; it does not prove every custom EA-PDF field or permitted form.

[Four real ISO boundary cases](iso-boundaries/results.json) retain a verified
inclusion-rule failure, a valid control, and controlled missing/corrupt profile
configurations. The unavailable cases use an isolated configuration root with
real missing/corrupt files; engine results are not mocked. PDF/A passes in all
four. Required unavailable ISO execution leaves INCOMPLETE; the actual proved
ISO violation fails. The negative's separately known creator operand failure
also remains visible.

Reproduce with `verify-iso-boundaries.py` and `collect-predicate-witnesses.py`.
These use immutable V25 runtime/rules; hashes are in `evidence.json`. The witness
collector's initial version assertion confused the core component version with
the application version. Its log is retained; the final collector checks every
component against the already authoritative version pin.

### Font-object follow-up

The geometric controls had no font objects, leaving four mapped font codes
without an object witness. [Ten additional controls](font-controls/evidence.json)
now retain one mapped glyph in a copied, embedded Type0/CID TrueType font for
each of the five profiles, plus its missing-font counterpart. All five positive
controls pass with actual successful font predicate contexts; all five missing
programs fail the pinned embedding predicate. `verify-font-witnesses.py` retains
the construction, exact PDF hashes and full real engine JSON.

This addresses the concrete no-font-object gap in these controls. It does not
certify other font formats, every glyph, shaping, readability or all mapped
font predicates. Creator conformance and uncovered EA-PDF assertions retain
their separate gates.
