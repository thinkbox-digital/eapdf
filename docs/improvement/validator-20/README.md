# V20: original media-parameter text

V19's MIME comparisons could normalize away malformed parameter text. Source
summaries could also discard malformed entries or collapse quoted spaces.
This caused false passes for altered values and false failures for preserved
source values.

V20 compares preserved text when MIME recovery reports invalid parameter
syntax. Source attachment/body summaries retain the original Content-Type
header. Legal folding, quoted values, encoded parameters and MIME body defaults
remain supported. Malformed free-text source fields are still permitted.

Authority: EA-PDF §9.4, printed pp.66–67 (embedded media reconstruction), and
§9.8.4 Table 19, printed pp.89–90 (original attachment Content-Type, including
errors). `validator_rule_text.json` remains authoritative.

Final gates and hashes are in `evidence.json`; inputs are frozen in
`.eapdf-verification-v20`:

- 458 unit tests: 421 pass, 37 skip.
- Ten exact-byte PDF probes checked before and after.
- Twelve generated normal/accessibility profiles.
- 174 custom comparisons, including 164 prior cases.
- Eight actual CLI/isolated-worker checks.

Five changed values previously passed and now fail the expected checks. Two
preserved-source cases previously failed and now pass. Matching malformed text
and legal encoded representations retain positive controls.

Repeat the retained gates using `verify-regressions.py`, `verify-pdfs.py` and
the existing profile integration tests from the snapshot. Set
`VERAPDF_MOUNT_ROOT` to the main repository, use the accepted creator JAR and
set `GOMAXPROCS=2` to limit Docker client threads. `assemble-evidence.py` checks
all retained acceptance results before writing the accepted checkpoint.

Java sources and the creator JAR are unchanged. No new Maven build or full
packaged-suite run is claimed. This is a scoped syntax/text-preservation
repair, not exhaustive MIME semantic or specification coverage. Eleven review
questions, unique occurrence assignment and original inventory completeness
remain open.
