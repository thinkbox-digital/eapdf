# Ambiguous attachment media probes

Ten probes reuse accepted isolated V19 attachment PDFs, with no retained source
or BodyRendering dependency. Changes are confined to attachment Content-Type
metadata and native Subtype/Mail_MediaTypeParameters entries. Native payload
bytes, sizes and checksums remain unchanged.

Six negative probes contradict every possible native reconstruction. Three
unavailable controls retain a compatible candidate. One positive control
preserves legal case, parameter order, quoted spaces and RFC 2231 equivalence.
Malformed-text controls preserve exact header and parameter spaces.

No media value selects an owner or occurrence. Unreadable reconstruction and
schema-invalid metadata are exercised separately by unit tests. This does not
establish occurrence assignment or original-inventory completeness.

Authority: EA-PDF 7.5 p44; 9.4 pp66–67; Table 19 pp89–90.
`expectations.json` records expected results, seed hashes and exact probe hashes.
Regenerate with `python3 -B scripts/build_eapdf_ambiguous_media_fixtures.py`.
