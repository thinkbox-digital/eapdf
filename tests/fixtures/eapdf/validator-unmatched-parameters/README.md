# Unmatched attachment parameters

Eight probes reuse an accepted isolated archive. Actual native payload bytes,
sizes and checksums remain unchanged. Native file-spec aliases and XMP media
fields isolate the missing-association reporting path.

Four controls require parameter WARN when native files exist but cannot be
associated. These include malformed original text, a mixed verified/unmatched
set, and a verified bare type alongside an unmatched parameter declaration.
Other controls retain a definite mismatch, a checksum-supported native alias,
a declaration without parameters and an Alternative-only file inventory.
The last control marks the archive's index workbook Alternative too, so it has
no files in the existing native comparison scope.

No alias is guessed and no new native-copy requirement is introduced.
Source/Alternative exclusions and invalid field shapes have direct unit tests.
`expectations.json` records the expected parameter severity and overall result,
seed and PDF hashes. Regenerate with
`python3 -B scripts/build_eapdf_unmatched_parameter_fixtures.py`.

Authority: EA-PDF 7.5 p44; 9.4 pp66–67; Table 19 pp89–90.
