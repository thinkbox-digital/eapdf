# Clearer Java validator findings

2026-10-24. Delivery: [eapdf-validator.jar](../../../dist/eapdf-validator.jar).

- Each collection email rule now names the field or comparison that belongs in the embedded email PDFs. The same generic sentence no longer fills those rows.
- Conditional N/A findings name the relevant profile, property or object instead of saying only that a condition is absent.
- Rule 202 reports an XMP checksum format result, shows the supplied CheckSum, and distinguishes this from agreement with attachment bytes. When omitted, it says the optional property is omitted.

## Rule 202 and the specification

Section 9.8.4, Table 19, printed page 90 (PDF page 97) of
[the supplied specification](../../reference/EA-PDF-v1.pdf) defines the optional
XMP attachment CheckSum as a 32-character ASCII hexadecimal MD5 value.
Its representation differs from the PDF embedded-stream parameter CheckSum.
The existing format predicate is correct and is unchanged.

The sample's `Lean Six Sigma Kickoff Meeting.pptx` record supplies
`c0dd123ac17a66c1b42ba8898896858b`. The updated finding shows that value.
In the wrong-checksum control, the 32-character format passes rule 202 while
`ATTACHMENT_XMP_MATCH` fails because the checksum contradicts the native payload.
Empty and URI-valued XMP checksums fail the format check; omission passes.

## Verification

All 105 existing Java checks pass, including the desktop. The first desktop
run timed out; the unchanged check passed on rerun, and the separate exact-JAR
GUI check also passed. No timeout or assertion was relaxed.

Compared 16 PDFs and 2 embedded reports with the preceding verified JAR:
all rule outcomes, summary counts and overall results agree. These include all
six ordinary samples, checksum controls, and every previously generic
rule/message combination (67 combinations). Random temporary child filenames
are excluded when comparing summary objects. No validation condition, rulebook,
web check or creator code was changed. This is a focused follow-up to the
[full parity comparison](../validator-desktop-parity/README.md), not a repeat of it.

The exact copied JAR passed through its GUI with only the JAR in its working
directory and `PATH=/unavailable`. Both updated findings and the bundled
specification pane were checked; temporary worker files were removed.

- [Collection findings](collection.png)
- [Rule 202 and actual checksum](checksum.png)
- [Focused comparison](check.json)
- [Checksum controls](checksum-controls.json)
- [Standalone proof](standalone-proof.json)
- [Build receipt](../../../dist/validator-build.json)
