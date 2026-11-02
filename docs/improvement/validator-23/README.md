# V23: unavailable parameters without a native association

V22 could report N/A when an attachment declared parameters but existing native
files could not be associated with it. One verified attachment could also
produce an aggregate parameter PASS despite another unmatched declaration.

V23 retains WARN/unavailable for those parameter comparisons. Definite failures
still win. Existing native-file scope and association selection are unchanged;
no alias, owner or occurrence is guessed and no native-copy requirement is added.
Source/Alternative exclusions and schema validation remain separate.

Authority: EA-PDF 9.4 printed pp66–67 (equivalent media and reconstruction),
Table 19 printed pp89–90 (original filenames and Content-Type), and 7.5 printed
p44 (shared streams and removed attachments). `validator_rule_text.json` remains
authoritative.

Acceptance requires `evidence.json`, using `.eapdf-verification-v23` inputs:

- 487 unit tests: 447 pass, 40 skip.
- Nine identical-byte before/after PDF probes: eight new and one retained case.
- Twelve generated normal/accessibility profiles.
- 202 custom comparisons; all 194 prior PDF hashes and status/failure outcomes
  unchanged.
- Eight actual CLI/isolated-worker checks.

Ten parameter-rule results improve: nine N/A-to-WARN and one PASS-to-WARN.
Overall statuses and failure codes are unchanged across the entire corpus.
Controls preserve a definite parameter failure, checksum-supported aliases,
bare declarations and inventories outside the native-file comparison scope.
The Alternative-only PDF control includes its index workbook in that exclusion.

Repeat using `verify-regressions.py`, `verify-pdfs.py` and the snapshot profile
integration tests. Set `VERAPDF_MOUNT_ROOT` to the repository, use the unchanged
accepted creator JAR and set `GOMAXPROCS=2` for Docker client threads.
`assemble-evidence.py` checks retained reports and hashes before acceptance.

Java sources/JAR are unchanged. No new Maven or complete packaged-suite gate is
claimed. Occurrence assignment, source-asset association, shared veraPDF runtime
coverage and original-inventory completeness remain open, alongside eleven
review questions.
