"""Retain bounded association observations without changing production reports."""
import json
from pathlib import Path
import sys

import pikepdf

ROOT = Path(__file__).resolve().parents[3]
FROZEN = ROOT / '.eapdf-verification-v13-final'
sys.path.insert(0, str(FROZEN))
import validate_eapdf as v

directory = FROZEN / 'tests/fixtures/eapdf/validator-attachment-filenames'
rows = []
for case in json.loads((directory / 'expectations.json').read_text())['cases']:
    with pikepdf.open(directory / case['file']) as pdf:
        root, _, _ = v.parse_xmp(v.read_metadata_stream(pdf), v.Report('parse'))
        records = v.flatten_email_records(root)
        summaries = v.summarize_source_emails(v.get_af_specs(pdf))
        _, _, parts = v.get_dpart_nodes(pdf)
        bodies = v.collect_body_content_sets(pdf, parts)
        annotations = v.collect_file_attachment_annotations(pdf)
        for attachment in v.flatten_attachments(root):
            for body in bodies:
                if not v.identities_match(attachment, body['identity']):
                    continue
                hint = v.attachment_source_checksum(attachment, body, records, summaries)
                for annotation in annotations:
                    file = annotation.get('file') or {}
                    if annotation['page'] not in body['pages'] or file.get('afrelationship') == 'Source':
                        continue
                    rows.append({'file': case['file'], 'original_xmp_filename': attachment['Filename'],
                        'native_pdf_filename': annotation['filename'], 'supplied_checksum': attachment.get('CheckSum'),
                        'source_association_checksum': hint, 'actual_native_md5': file.get('md5'),
                        'actual_native_size': file.get('size'),
                        'association_and_field_comparisons_match': v.attachment_record_matches_annotation(attachment, annotation, hint),
                        'visible_in_bounds': bool(annotation.get('visible_ok') and annotation.get('within_page_box'))})
assert len(rows) == 12, len(rows)
Path(__file__).with_name('association-observations-final.json').write_text(json.dumps(rows, indent=2) + '\n')
print('Retained', len(rows), 'native/source association observations; no completeness or occurrence identity claim.')
