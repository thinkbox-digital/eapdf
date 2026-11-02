"""Table 19 native consistency without BodyRendering; PDF/A remains valid."""
import hashlib
import json
from pathlib import Path
import sys
from xml.etree import ElementTree as ET

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import validate_eapdf as v


def main():
    output = ROOT / 'tests/fixtures/eapdf/validator-attachment-streams'
    output.mkdir(exist_ok=True)
    source = ROOT / 'tests/fixtures/eapdf/validator-attachment-filenames/source-absent-original-no-checksum.pdf'
    recipes = [
        ('valid', {}, False, False, [], 'PASS'),
        ('wrong-size', {'SizeInBytes': '1'}, False, False, ['ATTACHMENT_XMP_MATCH'], 'FAIL'),
        ('wrong-checksum', {'CheckSum': '0' * 32}, False, False, ['ATTACHMENT_XMP_MATCH'], 'FAIL'),
        ('wrong-media', {'Content-Type': 'application/pdf'}, False, False, ['EMBEDDED_SUBTYPE_MATCH_XMP'], 'FAIL'),
        ('wrong-size-and-media', {'SizeInBytes': '1', 'Content-Type': 'application/pdf'}, False, False,
         ['ATTACHMENT_XMP_MATCH', 'EMBEDDED_SUBTYPE_MATCH_XMP'], 'FAIL'),
        ('wrong-checksum-and-media', {'CheckSum': '0' * 32, 'Content-Type': 'application/pdf'}, False, False,
         ['ATTACHMENT_XMP_MATCH', 'EMBEDDED_SUBTYPE_MATCH_XMP'], 'FAIL'),
        ('large-size', {'SizeInBytes': '9' * 5000}, False, False, ['ATTACHMENT_XMP_MATCH'], 'FAIL'),
        ('zero-padded-size', {'SizeInBytes': 'zeros'}, False, False, [], 'PASS'),
        ('checksum-alias', {'CheckSum': 'actual'}, True, False, [], 'PASS'),
        ('wrong-size-checksum-alias', {'CheckSum': 'actual', 'SizeInBytes': '1'}, True, False, ['ATTACHMENT_XMP_MATCH'], 'FAIL'),
        ('unknown-alias', {}, True, False, [], 'INCOMPLETE'),
        ('ambiguous-records', {}, False, True, [], 'INCOMPLETE')]
    cases = []
    for name, changes, rename, duplicate, failures, status in recipes:
        with pikepdf.open(source) as pdf:
            root, namespaces, _ = v.parse_xmp(v.read_metadata_stream(pdf), v.Report('parse'))
            for prefix, uri in namespaces.items():
                ET.register_namespace(prefix, uri)
            bag = root.find('.//' + v.qn(v.NS_PDFMAILMETA, 'attachments')).find(v.qn(v.NS_RDF, 'Bag'))
            assert len(bag) == 1
            row = bag[0]
            for item in v.get_dpart_nodes(pdf)[2]:
                if v.safe_name(item['dpm'].get('Mail_ContentSetType')) == 'BodyRendering':
                    item['node'].DPM.Mail_ContentSetType = pikepdf.Name.EmailHeaderRendering
            for obj in pdf.objects:
                if isinstance(obj, pikepdf.Dictionary) and str(obj.get('/S')) == '/Mail_ContentSet':
                    for prop in obj.A.P:
                        if str(prop.N) == 'Mail_ContentSetType':
                            prop.V = pikepdf.String('EmailHeaderRendering')
            native = [f for f in v.embedded_file_candidates(pdf,v.get_embedded_files(pdf),v.get_af_specs(pdf))
                      if f['afrelationship'] != 'Source']
            assert len(native) == 1
            for field, value in changes.items():
                node = row.find(v.qn(v.NS_PDFMAILMETA, field))
                if node is None:
                    node = ET.SubElement(row, v.qn(v.NS_PDFMAILMETA, field))
                node.text = native[0]['md5'].upper() if value == 'actual' else '+' + '0' * 5000 + node.text if value == 'zeros' else value
            if rename:
                spec = pdf.get_object(native[0]['objgen'])
                spec.F = pikepdf.String('stored-safe.pptx')
                spec.UF = pikepdf.String('stored-safe.pptx')
            if duplicate:
                other = ET.fromstring(ET.tostring(row))
                other.find(v.qn(v.NS_PDFMAILMETA, 'SizeInBytes')).text = '1'
                other.find(v.qn(v.NS_PDFMAILMETA, 'Content-Type')).text = 'application/pdf'
                bag.append(other)
                root.find('.//' + v.qn(v.NS_PDFMAILMETA, 'NumberAttachments')).text = '2'
            packet = ET.tostring(root, encoding='utf-8')
            for prefix, uri in (('pdfmail', v.NS_PDFMAIL), ('pdfmailid', v.NS_PDFMAILID), ('pdfmailmeta', v.NS_PDFMAILMETA)):
                if ('xmlns:' + prefix + '=').encode() not in packet:
                    root.set('xmlns:' + prefix, uri)
            pdf.Root.Metadata.write(ET.tostring(root, encoding='utf-8'))
            path = output / (name + '.pdf')
            pdf.save(path, fix_metadata_version=False)
        cases.append({'file': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
            'expected_failures': sorted(failures), 'expected_status': status,
            'required_unavailable_codes': ['ATTACHMENT_XMP_MATCH', 'EMBEDDED_SUBTYPE_MATCH_XMP', 'VALIDATION_INCOMPLETE'] if status == 'INCOMPLETE' else []})
    (output / 'expectations.json').write_text(json.dumps({
        'authority': ['EA-PDF 9.8.4 Table 19 SizeInBytes/CheckSum', 'EA-PDF 9.4 equivalent XMP media type'],
        'source': str(source.relative_to(ROOT)), 'source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
        'cases': cases}, indent=2) + '\n')
    print('Wrote', len(cases), 'non-body attachment stream probes')


if __name__ == '__main__':
    main()
