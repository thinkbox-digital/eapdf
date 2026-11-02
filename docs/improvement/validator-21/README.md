# V21: byte contradictions across ambiguous candidates

V20 could mark native-byte comparisons unavailable without reporting a supplied
size or checksum contradicted by every possible native payload.

V21 reports the provable field contradiction and retains unavailable association
reporting. No attachment owner or occurrence is selected using the checked
field. A nonempty candidate set is required; any matching or unreadable
candidate blocks that field failure. Schema checks remain separate.

Authority: EA-PDF 7.5, printed p44 (repeated filenames and shared streams), and
9.8.4 Table 19, printed pp89–90 (stored SizeInBytes and optional CheckSum).
`validator_rule_text.json` remains authoritative.

Acceptance requires the results and hashes in `evidence.json`, from frozen
`.eapdf-verification-v21-final` inputs:

- 468 unit tests: 430 pass, 38 skip.
- Twelve identical-byte before/after PDF probes: ten new and two retained cases.
- Twelve generated normal/accessibility profiles.
- 184 custom comparisons; all 174 prior PDF hashes unchanged.
- Eight actual CLI/isolated-worker checks.

Five new negative probes and the retained `isolated-ambiguous-size.pdf` and
`ambiguous-records.pdf` change
from INCOMPLETE to FAIL with `ATTACHMENT_XMP_MATCH`. All 172 other prior custom
outcomes remain unchanged. Four new unavailable controls remain INCOMPLETE;
one equivalent-metadata control retains PASS.

Repeat with `verify-regressions.py`, `verify-pdfs.py` and the existing profile
integration tests from the snapshot. Set `VERAPDF_MOUNT_ROOT` to the repository,
use the accepted unchanged creator JAR, and set `GOMAXPROCS=2` for Docker client
threads. `assemble-evidence.py` validates retained results and hashes.

Java sources/JAR are unchanged. No new Maven or complete packaged-suite gate is
claimed. Ambiguous media comparisons, occurrence assignment and original
inventory completeness remain open, along with eleven review questions.

The first full corpus comparison exposed both retained negative cases. Native
objects confirm size 8 against 7 bytes and size 1 against 49,338 bytes. Initial
results and their failed expected-change assertion are retained. Final inputs
differ from the initial frozen inputs only in the older stream-fixture
expectation manifest; runtime, configuration and PDF hashes are identical.
`initial-to-final-inputs.json` records that boundary.
