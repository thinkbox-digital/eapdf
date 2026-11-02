# V22: media contradictions across ambiguous candidates

V21 could leave a media type or declared parameters unavailable even when every
possible native reconstruction contradicted the value. Ambiguous parameter
comparisons could also appear as N/A.

V22 reports the proved media or parameter contradiction and retains unavailable
association reporting. Parameters are compared independently of the bare type.
A compatible or unreadable candidate blocks that field failure. Ambiguous
parameter comparisons without a proved mismatch remain WARN/unavailable and
cannot suppress a definite mismatch elsewhere in the PDF.

Authority: EA-PDF 7.5 printed p44 (repeated names/shared streams), 9.4 printed
pp66–67 (equivalent XMP and reconstruction), and Table 19 printed pp89–90
(original attachment Content-Type including parameters and errors).
`validator_rule_text.json` remains authoritative.

Acceptance requires `evidence.json`, using `.eapdf-verification-v22` inputs:

- 479 unit tests: 440 pass, 39 skip.
- Twelve identical-byte before/after PDF probes: ten new and two retained cases.
- Twelve generated normal/accessibility profiles.
- 194 custom comparisons; all 184 prior PDF hashes unchanged.
- Eight actual CLI/isolated-worker checks.

Six new negative probes and retained `isolated-ambiguous-media.pdf` change from
INCOMPLETE to FAIL. Retained `ambiguous-records.pdf` keeps its size failure and
gains the missing media failure. Two compatible-parameter controls improve from
N/A to WARN without changing overall status. All 182 other prior status/failure
outcomes and their media-rule severities remain unchanged.

Legal encoded parameters retain PASS. Malformed text requires exact preserved
spacing, including after semicolons. Unknown reconstruction, schema-invalid
fields, missing candidates, Source/Alternative exclusions and mixed definite/
unavailable parameter findings have regression controls.

Repeat using `verify-regressions.py`, `verify-pdfs.py` and the snapshot profile
integration tests. Set `VERAPDF_MOUNT_ROOT` to the repository, use the unchanged
accepted creator JAR and set `GOMAXPROCS=2` for Docker client threads.
`assemble-evidence.py` validates retained reports and hashes.

Java sources/JAR are unchanged. No new Maven or complete packaged-suite gate is
claimed. Occurrence assignment, unresolved candidate comparisons and original
inventory completeness remain open, together with eleven review questions.
