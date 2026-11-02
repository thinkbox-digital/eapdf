"""Check permitted PDF attachment filenames against original Table 19 names."""
import json
from pathlib import Path
import sys
from xml.etree import ElementTree as ET

import pikepdf

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))
import validate_eapdf as v

stage = Path(__file__).with_name('attachment-filenames')
stage.mkdir(exist_ok=True)
rows = []
for name, rename, checksum in [('valid', False, True), ('stored-name-differs', True, True),
                               ('stored-name-no-checksum', True, False)]:
    path = stage / (name + '.pdf')
    with pikepdf.open(ROOT / 'examples/sample_1s.pdf') as pdf:
        root, namespaces, _ = v.parse_xmp(v.read_metadata_stream(pdf), v.Report('parse'))
        attachments = v.flatten_attachments(root)
        assert len(attachments) == 1
        old_name = attachments[0]['Filename']
        if rename:
            specs = [pdf.get_object(annotation['fs_objgen']) for annotation in v.collect_file_attachment_annotations(pdf)
                     if annotation['file']['afrelationship'] != 'Source']
            assert len(specs) == 1
            specs[0].F = pikepdf.String('stored-safe.pptx')
            specs[0].UF = pikepdf.String('stored-safe.pptx')
        if not checksum:
            for prefix, uri in namespaces.items():
                ET.register_namespace(prefix, uri)
            field = root.find('.//' + v.qn(v.NS_PDFMAILMETA, 'attachments')).find('.//' + v.qn(v.NS_PDFMAILMETA, 'CheckSum'))
            assert field is not None
            for parent in root.iter():
                if field in list(parent):
                    parent.remove(field)
                    break
            packet = ET.tostring(root, encoding='utf-8')
            for prefix, uri in (('pdfmail', v.NS_PDFMAIL), ('pdfmailid', v.NS_PDFMAILID), ('pdfmailmeta', v.NS_PDFMAILMETA)):
                if ('xmlns:' + prefix + '=').encode() not in packet:
                    root.set('xmlns:' + prefix, uri)
            pdf.Root.Metadata.write(ET.tostring(root, encoding='utf-8'))
        pdf.save(path, fix_metadata_version=False)
    result = v.validate_one(str(path), write_html=False)
    (stage / (name + '.json')).write_text(json.dumps(result, indent=2, default=str))
    row = dict(file=path.name, original_filename=old_name, pdfa=result['pdfa']['status'],
               status=result['summary']['status'],
               failures=sorted({item['code'] for item in result['items'] if item['level'] == 'FAIL'}))
    rows.append(row)
    print(row, flush=True)
(stage / 'results.json').write_text(json.dumps(rows, indent=2) + '\n')
