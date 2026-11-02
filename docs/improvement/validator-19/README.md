# V19: equivalent attachment comparisons

Several attachment records can describe the same stored bytes. V18 treated
multiple candidates as unavailable even when their expected size, checksum and
media type agreed.

V19 permits comparisons when all candidate records have equivalent valid
expectations. Each record receives the same checks. Media evidence returned to
the caller contains no arbitrary owner or occurrence identity. Different
expectations, distinct stored observations and unreadable bytes remain
unavailable. Body-owner checks retain their existing behavior.

The reviewed basis is EA-PDF §7.5, printed p.44 (nonunique filenames and shared
streams), §9.3, printed p.65 (body ownership), and Table 19, printed pp.89–90
(stored size, optional checksum and media type). `validator_rule_text.json`
remains authoritative.

Final checks are recorded in `evidence.json`; runtime inputs are frozen in
`.eapdf-verification-v19`.

- 448 unit tests: 412 pass, 36 skip.
- Eight exact-byte PDF probes checked before and after.
- Twelve generated profiles: all six in normal and accessibility modes.
- 164 custom fixture comparisons, including 156 prior cases.
- Eight actual CLI and isolated web-worker checks.

The first fixture recipe used the wrong existing archive switch and omitted
matching group tags when changing body sets. The corrected recipes and final
reports support acceptance; the initial logs remain as history.

Repeat the gates with `verify-regressions.py`, `verify-pdfs.py`, and the existing
profile integration tests from the frozen snapshot. Set `VERAPDF_MOUNT_ROOT` to
the main repository, use the accepted creator JAR and set `GOMAXPROCS=2` to limit
Docker client threads. `assemble-evidence.py` verifies the retained results.

Java sources/JAR are unchanged. No new Maven build or full packaged-suite gate
is claimed. Eleven review questions, unique occurrence assignment, original
inventory completeness and broader coverage review remain open.
