# Attachment source occurrences

Table 19 describes an unordered set. Optional checksums and removed attachments
must not make source comparisons depend on record order.

`source.eml` contains two attachments called `same.txt`, holding `a` and `longer`.
The retained creator control is `_source.pdf`. The mutation script replaces the
second native payload with an allowed zero-byte placeholder, updates its PDF
parameters and XMP, and changes the metadata order.

- `control`: both original payloads and checksums; PASS.
- `placeholder-empty-checksum` and `retained-first-empty-checksum`: identical
  descriptions in opposite orders; both PASS.
- `placeholder-first` and `retained-first`: the placeholder checksum is absent.
  Source comparisons agree in both orders. Native stream association remains
  ambiguous, so both are INCOMPLETE.
- `wrong-retained-size`: the retained file declares two bytes instead of one;
  annotation and attachment metadata checks FAIL.

V17 wrongly failed the two placeholder-first cases during source comparison.
V18 assigns compatible source occurrences before reporting mismatches. Each
source occurrence can be used once. Equivalent assignments establish field
agreement; they do not identify a unique original occurrence.

Regenerate with `python3 -B scripts/build_eapdf_attachment_occurrence_fixtures.py`.
The script reuses `_source.pdf` when present. Expectations include PDF hashes;
independent before/after reports are in `docs/improvement/validator-18`.
