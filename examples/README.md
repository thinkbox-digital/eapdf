# EA-PDF examples

These 24 examples were rebuilt on 2026-11-02 from the current local creator.
All primary PDFs pass the standalone Java validator, including PDF/A, EA-PDF
and selected ISO checks. Collection children are checked as part of validation.
The eight finding-aid sidecars pass PDF/A; they are not EA-PDF files.

Filenames use the input type, input filename stem and profile. Multiple-input
examples add `_archive` after the first input name; their manifests list every
input. For example, `mbox_enron-sample_1m.pdf` comes from `enron-sample.mbox`.
Normal conversions also retain the input stem; `auriza.mbox` produces
`auriza_1m.pdf` in archive mode or `auriza__subject_000001.pdf` per message.

| Input | Single | Isolated single | Archive | Isolated archive | Collection | Isolated collection |
| --- | --- | --- | --- | --- | --- | --- |
| EML | [1s](eml/eml_Agenda_and_timeline_slides_1s.pdf) | [1si](eml/eml_Agenda_and_timeline_slides_1si.pdf) | [1m](eml/eml_Agenda_and_timeline_slides_archive_1m.pdf) | [1mi](eml/eml_Agenda_and_timeline_slides_archive_1mi.pdf) | [1c](eml/eml_Agenda_and_timeline_slides_archive_1c.pdf) | [1ci](eml/eml_Agenda_and_timeline_slides_archive_1ci.pdf) |
| MSG | [1s](msg/msg_TxtSampleEmailWithAttachment_1s.pdf) | [1si](msg/msg_TxtSampleEmailWithAttachment_1si.pdf) | [1m](msg/msg_TxtSampleEmailWithAttachment_archive_1m.pdf) | [1mi](msg/msg_TxtSampleEmailWithAttachment_archive_1mi.pdf) | [1c](msg/msg_TxtSampleEmailWithAttachment_archive_1c.pdf) | [1ci](msg/msg_TxtSampleEmailWithAttachment_archive_1ci.pdf) |
| PST | [1s](pst/pst_quality-attached-message_1s.pdf) | [1si](pst/pst_quality-attached-message_1si.pdf) | [1m](pst/pst_quality-attached-message_1m.pdf) | [1mi](pst/pst_quality-attached-message_1mi.pdf) | [1c](pst/pst_quality-attached-message_1c.pdf) | [1ci](pst/pst_quality-attached-message_1ci.pdf) |
| MBOX | [1s](mbox/mbox_enron-sample_1s.pdf) | [1si](mbox/mbox_enron-sample_1si.pdf) | [1m](mbox/mbox_enron-sample_1m.pdf) | [1mi](mbox/mbox_enron-sample_1mi.pdf) | [1c](mbox/mbox_enron-sample_1c.pdf) | [1ci](mbox/mbox_enron-sample_1ci.pdf) |

Sources:

- EML: `in/Agenda and timeline slides.eml`, `in/eml_01_403.eml`.
- MSG: `in/TxtSampleEmailWithAttachment.msg`, `in/test.MSG`.
- PST: `in/quality-attached-message.pst`.
- MBOX: `in/enron-sample.mbox`.

PST and MBOX single-message examples are unchanged copies of one message child
from the corresponding collection. Their manifests identify that child. The
original PST/MBOX containers are not embedded; the PDFs preserve message exports.
The small PST has seven messages; large PSTs were excluded.

Keep each collection beside its `_index.xlsx` and `_finding_aid.pdf` sidecars.
Each format’s `manifest.json` records hashes, inputs, creator/validator builds,
configuration, processing limits and validation results. Original input hashes
were checked before and after regeneration. The demonstration creator identity
is Thinkbox.DIGITAL, not the original email author.

Conversion logs and Java reports are in
`output/examples-input-names-2026-11-02/`. These are local builds, not a newly
published GitHub release or independent certification.

Older signed, accessibility and failing demonstrations are preserved unchanged
as [historical regression fixtures](../tests/fixtures/eapdf/historical-examples/README.md).
They no longer sit alongside the current passing examples.

Regenerate using `scripts/regenerate_email_samples.py`; see the project README
for configuration and build options. Temporary staging uses `/tmp` and is
removed after success or failure. Only a fully validated set replaces the samples.
