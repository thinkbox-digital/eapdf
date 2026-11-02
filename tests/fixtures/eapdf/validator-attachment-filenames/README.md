# Original and stored attachment filenames

EA-PDF 9.8.4 Table 19 requires the original MIME filename in XMP. Its note permits
the corresponding PDF filename to differ. CheckSum is optional. SizeInBytes and
content type retain their required consistency checks.

`_source.pdf` is the accepted non-isolated sample. `_placeholder-source.pdf` is
the accepted isolated zero-byte-placeholder control. Their hashes and all twelve
authored cases are recorded in `expectations.json`, including incorrect large
integer sizes and legal long zero padding. Recreate mutations with
`scripts/build_eapdf_attachment_filename_fixtures.py`.

Cases cover declared checksums, an independently selected retained source,
omitted source/checksum evidence, a renamed empty placeholder, and incorrect
size/type/checksum values. The unknown source-omitted alias is INCOMPLETE, not a
missing-annotation FAIL or a demonstrated PASS. Source omission removes only
Source associations/annotations and their OBJR/annotation ParentTree entries;
rendered text MCIDs and native attachments remain intact.

No original occurrence identity, complete source enumeration or cryptographic
identity guarantee is claimed. See `docs/improvement/validator-13/` for before
results, the initial fixture construction error and final acceptance.
