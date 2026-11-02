# Batch 03 PDF-date fixtures

`source.eml` copies the synthetic batch-01 source. `cases.json` records 25 expectations for EA-PDF 1.0 section 9.7 / Table 12 and ISO 32000 section 7.9.4.

Nine files must pass EA-PDF: dictionary/array full dates, a year-only date, a leap day, the standard's minute-precision offset example, a legacy terminating apostrophe, Z with zero offsets, the permitted maximum offset and a decoded UTF-16 date. Sixteen mutations must fail exactly `USERPROPS_TYPE`: nonsense, XMP/raw-email syntax in a PDF-date field, missing prefix, whitespace, impossible days/times, bad timezone ranges, nonzero Z offsets, missing timezone apostrophe and non-ASCII digits. All 25 must independently pass PDF/A.

```bash
EAPDF_GENERATOR_CLASSPATH=/tmp/eapdf-improvement-batch01/build/emailtopdfa-1.4.0.jar \
  EAPDF_RULE_FIXTURE_SET=batch03 python3 -B scripts/build_eapdf_rule_fixtures.py
```

The current creator generates a seed; the separate pikepdf recipe adds one optional Mail_Sent property to a Mail_Message. Expectations come from the reviewed date definition, not the validator. Unit tests cover both permitted owning tags, dictionary/array attributes, each date precision, omitted timezone components, encoding, syntax and calendar boundaries. Date validity is checked here; comparison with the source email date remains separate work.

Artifacts default to `docs/improvement/batch-03-corpus/`. Set `EAPDF_RULE_FIXTURE_DIR` to another directory visible to the configured veraPDF Docker mount. Missing independent validation fails the run. Batch-01/02 recipes and saved artifacts remain available.

See the [date review and sources](../../../../docs/improvement/batch-03-date-review.md), [requirements register](../../../../docs/improvement/requirements.json) and [restart log](../../../../docs/EA_PDF_WORK_LOG.md). This batch does not validate every date throughout a PDF or establish full EA-PDF conformance.
