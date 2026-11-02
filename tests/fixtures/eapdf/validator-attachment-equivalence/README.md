# Equivalent attachment comparisons

Two emails each contain `same.txt` with identical `payload` bytes. Their XMP
records have different owners and matching size, checksum and media type.
The accepted creator JAR produced `_source.pdf`; both source emails are retained.

- `two-owners` and `two-owners-reversed`: real owner/body routing, source
  comparisons and equivalent native comparisons; PASS in either metadata order.
- `isolated-equivalent`: sources omitted, no BodyRendering requirement, and
  optional checksums absent; identical native observations permit comparisons.
- `isolated-wrong-size`: both records declare eight bytes, native files have
  seven; ATTACHMENT_XMP_MATCH fails.
- `isolated-wrong-media`: both records declare application/pdf, native files
  are text/plain; EMBEDDED_SUBTYPE_MATCH_XMP fails.
- `isolated-ambiguous-size` and `isolated-ambiguous-media`: records disagree;
  association remains INCOMPLETE without selecting by the field under test.
- `isolated-distinct-payloads`: equal metadata but different stored bytes;
  observations remain INCOMPLETE.

V18 reports all eight cases as INCOMPLETE. V19 verifies equivalent field
comparisons without assigning a unique owner or occurrence. Table 19 byte/media
checks and §9.3 body-owner checks remain separate. Each record still receives
schema checks, and supplied invalid values do not establish equivalence.

Run `python3 -B scripts/build_eapdf_attachment_equivalence_fixtures.py` to repeat
the mutations. The script reuses `_source.pdf` when present. Expectations pin
the exact PDF hashes; full reports are in `docs/improvement/validator-19`.
