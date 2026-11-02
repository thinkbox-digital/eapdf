# Isolated-email Table 18 probes

Reproduce with `python3 scripts/build_eapdf_email_schema_fixtures.py` after sample
regeneration. The source is a source-omitting `1si` PDF. Original email bytes are
unavailable, so internal schema checks cannot depend on source comparisons.

All twelve PDFs pass PDF/A. URI sizes, negative counts, URI subjects, array raw
headers, URI Sent and impossible dates fail their declared Table 18 checks.
The control, absent optional size, empty free text, long zero-padded integer and
legal timezone-free/extended-year dateTime forms pass.

`expectations.json` pins source/probe hashes and exact failure codes. Optional
presence, source fidelity and unresolved Message-ID/FOAF/In-Reply-To questions
remain separate. The associated tests additionally cover unknown instants and
fractional precision beyond Python datetime's microseconds.
