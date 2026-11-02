# Batch 02 rule fixtures

`source.eml` copies the small synthetic batch-01 message. `cases.json` declares 27 reviewed cases for EA-PDF 1.0 section 9.7 / Table 12. The expected results come from the specification, independently of the validator.

The nine valid cases cover dictionary/array attributes, Unicode text, nonnegative integers and generic header values (text, integer, real and Boolean). Eighteen invalid cases cover PDF names/dictionaries/arrays/nulls/Booleans/reals used as text, text/reals/Booleans/negative integers used as counts, compound or null generic values, and a generic email header on a forbidden tag. Each invalid file must fail exactly `USERPROPS_TYPE` or `USERPROPS_TAG_SCOPE` as declared. All 27 must pass independent PDF/A validation.

```bash
EAPDF_GENERATOR_CLASSPATH=/tmp/eapdf-improvement-batch01/build/emailtopdfa-1.4.0.jar \
  EAPDF_RULE_FIXTURE_SET=batch02 python3 -B scripts/build_eapdf_rule_fixtures.py
```

The builder creates a fresh seed with the existing creator, traverses its PDF objects and changes one field. A missing optional property is added before its type is tested. `null` recipes remove `/V`: PDF treats a null dictionary entry as absent, and pikepdf requires deletion. String-type mutations use optional `Mail_MessageID` to avoid unrelated required-GUID matching failures. Unit regressions also exercise `Mail_GUID`, both count fields, ASCII-string names, streams, missing entries and all permitted/forbidden generic-header tags in dictionary and array forms.

Reports/PDFs default to `docs/improvement/batch-02-corpus/`; `EAPDF_RULE_FIXTURE_DIR` selects another path visible to the configured veraPDF Docker mount. Missing independent validation fails the run. The batch-01 recipes and archived artifacts are retained.

This batch checks PDF object types and generic header scope. It does not complete date syntax, source-value comparison, attribute-owner/class-map/revision resolution or the standards register. See [the work log](../../../../docs/EA_PDF_WORK_LOG.md).
