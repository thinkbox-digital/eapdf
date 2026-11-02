# V18: unordered attachment source comparisons

Table 19's attachment records form an unordered set. The earlier validator
consumed source attachments in metadata order. An optional checksum record or
zero-byte placeholder could take the source occurrence needed by a later
record, causing a false failure.

The validator now finds a compatible assignment across the supplied records
before pairing invalid records for diagnostics. Each original occurrence is
used at most once. Owner, literal filename, size, media type and supplied
checksum constraints remain checked. Permitted placeholders retain their
source-size/checksum exception.

Compatible assignments establish field agreement. They do not establish unique
occurrence identity or a complete original attachment inventory. Native stream
and body annotation matching are unchanged. Two optional-checksum probes retain
INCOMPLETE because their native payload association remains ambiguous.

## Verification

Final results and input hashes are in `evidence.json`. Inputs are frozen in
`.eapdf-verification-v18-final`.

- 439 unit tests: 404 pass, 35 skip.
- Six PDF cases, checked before and after on identical bytes.
- Twelve generated profiles: all six in normal and accessibility modes.
- 156 custom comparisons, including all 150 prior fixture bytes and outcomes.
- Eight actual CLI and isolated web-worker checks.

The first profile attempt used an incorrect container mount. Its failed reports
remain in `profiles-initial-mount-error`; the corrected runs set
`VERAPDF_MOUNT_ROOT` to the repository root. The initial eager-candidate results remain in `initial-eager`. A final worker
retry sets `GOMAXPROCS=2` after Docker could not allocate a thread; its failed
report is retained as `placeholder-first-worker-thread-error.json`. The first
unit attempt predates the
fixture expectation correction; `unit-tests-final.log` is the accepted run.

## Reproduction

Run from the repository root:

```bash
python3 -B docs/improvement/validator-18/verify-regressions.py
python3 -B docs/improvement/validator-18/verify-pdfs.py
```

Run unit discovery from `.eapdf-verification-v18-final`. For profile integrations, set
`VERAPDF_MOUNT_ROOT` to the main repository, `EAPDF_GENERATOR_CLASSPATH` to the
accepted `.eapdf-build-creator-structure/emailtopdfa-1.4.0.jar`, and
`EAPDF_INTEGRATION_REPORT_DIR` to a new report directory. Run the existing
`test_generated_profiles.py` and `test_accessibility_profiles.py` modules.

`validator_rule_text.json` remains authoritative. The supporting audit and rule
browser were regenerated. Java source and the creator JAR are unchanged; no
Maven build or full packaged-suite acceptance is claimed. Eleven review
questions and the wider validator coverage review remain open.
