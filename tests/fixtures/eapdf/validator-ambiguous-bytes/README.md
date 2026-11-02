# Ambiguous native-byte comparisons

These ten probes reuse the accepted V19 isolated attachment PDFs. They retain
repeated filenames, no retained source and no BodyRendering dependency. Only
Table 19 stored sizes/checksums or actual native payload bytes are changed.
Native stream Size and binary CheckSum entries track the actual payloads.

Five negative probes declare a size or checksum contradicted by every native
candidate. Four unavailable controls retain a matching candidate, omit the
optional checksum, or include a zero-byte candidate. One equivalent-metadata
control retains PASS. Ambiguous association and media checks remain unavailable;
no owner, unique occurrence or complete original inventory is inferred.

Authority: EA-PDF 7.5, printed p44, and 9.8.4 Table 19, printed pp89–90.
`expectations.json` records expected results, seed hashes and exact probe hashes.
Regenerate with `python3 -B scripts/build_eapdf_ambiguous_byte_fixtures.py`.
