# V14 — native attachment consistency and file roles

Table 19 requires supplied attachment `SizeInBytes` and optional `CheckSum` to
agree with the corresponding stored native payload. These comparisons apply
when a source is omitted and when no BodyRendering Content Set is declared.
Previously, the association helper filtered out a size/checksum mismatch before
checking it. Exact-byte PDF/A-valid probes reproduce the false PASS.

Association now uses independently available name/checksum evidence before
comparing size/checksum/media fields. The field under test cannot choose an
otherwise ambiguous candidate. Unsupported observations and ambiguous aliases
remain unavailable/INCOMPLETE. Legal large integers, leading zeros, optional
checksums, permitted stored filename differences and actual zero-length
placeholders retain their existing treatment.

Collection member EA-PDFs contain their own attachment payloads. Their PDF bytes
must not be compared with parent-level metadata about attachments inside them;
recursive child validation performs those checks. The container completion
matrix records that scope explicitly. Section 7.5 also distinguishes converted
representations by `AFRelationship Alternative`; their bytes cannot stand in for
native attachment bytes. Two collection filename collisions and one Alternative
collision reproduce the previous false FAIL/INCOMPLETE outcomes.

The final application snapshot is `.eapdf-verification-v14-accepted`. The unit
suite uses identical application/rule hashes and one corrected historical test:
the native media-parameter test now declares mail profile `si`, replacing the
old `ci` shortcut. See `unit-test-profile-correction.patch`,
`unit-verified-inputs.json` and `runtime-inputs-unit-verified.zip`. The earlier
failed unit log is retained rather than counted as a pass.

All final lanes completed. `assemble-evidence.py` verifies
379 unit passes/31 skips, 125 exact complete-pipeline PDF outcomes, twelve
generated profile reports and ten actual CLI/isolated-worker checks before
writing [evidence.json](evidence.json). Fifteen new exact-byte before/after
probes distinguish V13's stored-byte false passes from the provisional V14
collection/Alternative failures.

The recovered thread is `01a1553a-2130-75e0-b322-6eb87f1c81aa`. Its original
snapshot, mount failures, interrupted logs and provisional 122-case run remain
separate. Commands for frozen runs set `VERAPDF_MOUNT_ROOT` to the actual root
mounted by the existing daemon, `/media/lepore/Work/thinkbox/convert`.

This is bounded consistency, not a global attachment occurrence/owner proof,
source completeness or certification. Eleven expert/dependency questions and
the inherited-edition boundary remain. Creator/CLI/debug behavior and the
accepted creator JAR are unchanged in V14; there is no new Java-build claim.
