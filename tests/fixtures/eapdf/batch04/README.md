# Batch 04 source-value fixtures

Two synthetic messages in `sources/` share Message-ID but have different subjects and sent dates. `cases.json` declares 17 expected outcomes for EA-PDF 1.0 section 9.7, rule 113 (a SHOULD recommendation).

```bash
EAPDF_GENERATOR_CLASSPATH=/tmp/eapdf-improvement-batch01/build/emailtopdfa-1.4.0.jar \
  python3 -B scripts/build_eapdf_source_fixtures.py
```

The current creator makes m/mi seeds. Independent pikepdf recipes locate the Source Alpha structure owner from seed XMP/GUID and change one attribute. Eight cases must show recommendation PASS; eight must WARN for altered/truncated subject, raw/decoded mixup, changed date/count or custom header; the isolated mi case must give an explained N/A. Every file must PASS PDF/A and overall mandatory EA-PDF validation. Recommendations do not become mandatory failures.

The crucial negative case puts Source Bravo's subject on Source Alpha's owner. Finding the same text somewhere else in the archive must not satisfy the check. Expectations and the owner GUID oracle are independent of the validator's findings. Unit cases also cover descendant attributes, ambiguous sources, unavailable ownership, folded/repeated headers, internal spaces, typed custom fields and equivalent reduced PDF-date defaults.

Artifacts default to `docs/improvement/batch-04-corpus/`. Set `EAPDF_SOURCE_FIXTURE_DIR` to another directory visible to the configured veraPDF Docker mount. Docker/veraPDF and the packaged creator are required; absent independent validation fails. The packaged integration archives its exact corpus under `EAPDF_INTEGRATION_REPORT_DIR/source-corpus` before temporary cleanup, when that report directory is configured.

See [the interpretation and limits](../../../../docs/improvement/batch-04-source-review.md), [partial requirements register](../../../../docs/improvement/requirements.json) and [restart log](../../../../docs/EA_PDF_WORK_LOG.md). This check reuses existing source matching; arbitrary source-format reconstruction, proof of all metadata/source associations and class-map/revision resolution remain outside this batch.
