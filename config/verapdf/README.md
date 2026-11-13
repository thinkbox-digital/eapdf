# Upstream ISO 32005 profile selection

`ISO-32005.xml` is a local selection from the veraPDF Consortium's bundled
`PDFUA-2-ISO32005.xml` in veraPDF 1.31.224. It retains all 1,636 `ISO_32005` rules
unchanged and excludes all 92 `ISO_14289_2` rules. `PDFUA_2` selects the engine's
model; the selected predicates do not require PDF/UA conformance.

This is not an officially published standalone profile. The retained upstream
source is in `upstream/PDFUA-2-ISO32005.xml`; hashes and selection evidence are in
[profile provenance](ISO-32005.json).
Upstream source project: [veraPDF validation profiles](https://github.com/veraPDF/veraPDF-validation-profiles).
The upstream creator attribution is retained in both XML files.

Reproduce with `python3 scripts/build_eapdf_iso32005_profile.py`, then verify with
the assertions in the selection script. The script compares every
selected rule's canonical XML with its pinned upstream counterpart.

Ship this directory with `validate_eapdf.py` and `validator_rule_text.json`.
Complete validation runs this profile for PDF 2.0 files with logical structure
after a successful PDF/A first pass, including contained PDFs. CLI and isolated
web workers use the same execution rule; the latter mount the profile read only.
The authoritative `ISO32005_UPSTREAM` rule pins the profile hash and reported
apps/core/model versions. A missing profile, unsupported engine, incomplete
report or execution error prevents overall PASS. A verified ISO violation fails
the file. Custom-only `validate()` calls do not execute external profiles and do
not establish overall conformance.

This integration does not resolve the specification's remaining dependency
edition questions or certify complete EA-PDF coverage.
