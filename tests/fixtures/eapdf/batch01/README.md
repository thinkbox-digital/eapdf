# Batch 01 rule fixtures

`source.eml` is a synthetic message with no native attachments. `cases.json` records the seven expected outcomes, reviewed against EA-PDF 1.0 section 9.7 / Table 12 and ISO 32000-2:2020 Table 355 / section 14.7.6.

Run from the repository root:

```bash
EAPDF_GENERATOR_CLASSPATH=/tmp/eapdf-improvement-batch01/build/emailtopdfa-1.4.0.jar \
  python3 -B scripts/build_eapdf_rule_fixtures.py
```

The script creates the seed using the current creator, then saves legal dictionary/array representations and single-property mutations. Every case must pass PDF/A. The dictionary, array and untagged cases must pass EA-PDF; the other four must fail exactly the named rule. Creation dates and PDF bytes may change between runs; the expected findings remain fixed.

Artifacts default to `docs/improvement/batch-01-corpus/`. Set `EAPDF_RULE_FIXTURE_DIR` to use another directory. PDF inputs must be visible to the configured veraPDF Docker mount. Docker/veraPDF and the packaged creator are required; missing validation is not treated as success.

The packaged regression also suppresses the UserProperties checker and verifies that the complete validation result becomes INCOMPLETE. See [the partial requirements register](../../../../docs/improvement/requirements.json) and [work log](../../../../docs/EA_PDF_WORK_LOG.md). This corpus covers a small reviewed subset; it does not establish complete EA-PDF or ISO/TS 32005 conformance.
