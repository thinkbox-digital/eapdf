"""Freeze independent XMP/GUID probes from the PDF/A-valid Table 11 control."""
import hashlib
import json
from pathlib import Path
import shutil
import sys
from xml.etree import ElementTree as ET

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import validate_eapdf as v


def main():
    source = ROOT / 'tests/fixtures/eapdf/validator-table11/valid.pdf'
    output = ROOT / 'tests/fixtures/eapdf/validator-xmp-identity'
    output.mkdir(parents=True, exist_ok=True)
    cases = [dict(file='valid.pdf', expected_failures=[])]
    shutil.copyfile(source, output / 'valid.pdf')
    modes = [
        ('guid-spaces-consistent', []),
        ('guid-uri-looking-text', []),
        ('guid-qualified', []),
        ('dpm-guid-whitespace-mismatch', ['DPM_MAIL_GUID_MATCH', 'MAIL_MESSAGE_GUID_MATCH_DPM_XMP',
                                         'ATTACHMENT_ANNOTATIONS_PRESENT']),
        ('structure-guid-whitespace-mismatch', ['MAIL_MESSAGE_GUID_MATCH_DPM_XMP']),
        ('guid-uri-value', ['MAIL_GUID_XMP', 'DPM_MAIL_GUID_MATCH']),
        ('email-ordered', ['EMAIL_XMP_CONTAINER']),
        ('assets-ordered', ['ASSET_XMP']),
        ('attachments-ordered', ['ATTACHMENT_XMP']),
        ('to-unordered', ['MESSAGE_TO_FOAF']),
    ]
    for mode, failures in modes:
        with pikepdf.Pdf.open(source) as pdf:
            data = v.read_metadata_stream(pdf)
            root, namespaces, _ = v.parse_xmp(data, v.Report('parse'))
            for prefix, uri in namespaces.items():
                ET.register_namespace(prefix, uri)
            _, _, dparts = v.get_dpart_nodes(pdf)
            identity = next(item for item in dparts if 'Mail_GUID' in item['dpm'])
            original = str(identity['dpm']['Mail_GUID'])
            elements = []
            v.walk_struct_elems(pdf.Root.StructTreeRoot.K, elements,
                                class_map=pdf.Root.StructTreeRoot.get('/ClassMap'))
            properties = [prop for elem in elements for prop in v.parse_userproperties(elem['attrs'])
                          if str(prop.get('/N', '')) == 'Mail_GUID']
            guid_nodes = list(root.iter(v.qn(v.NS_PDFMAILMETA, 'Mail_GUID')))
            if mode in ('guid-spaces-consistent', 'guid-uri-looking-text'):
                replacement = ' ' + original + ' ' if mode == 'guid-spaces-consistent' else 'https://example.org/id/A'
                for node in guid_nodes:
                    if node.text == original:
                        node.text = replacement
                for part in dparts:
                    if str(part['dpm'].get('Mail_GUID', '')) == original:
                        part['node'].DPM.Mail_GUID = replacement
                for prop in properties:
                    if str(prop.V) == original:
                        prop.V = replacement
            elif mode == 'guid-qualified':
                for node in guid_nodes:
                    node.text = None
                    node.set(v.qn(v.NS_RDF, 'parseType'), 'Resource')
                    ET.SubElement(node, v.qn(v.NS_RDF, 'value')).text = original
                    ET.SubElement(node, v.qn(v.NS_XMP, 'Label')).text = 'identity qualifier'
            elif mode == 'guid-uri-value':
                for email in root.iter(v.qn(v.NS_PDFMAILMETA, 'email')):
                    for node in email.iter(v.qn(v.NS_PDFMAILMETA, 'Mail_GUID')):
                        node.text = None
                        node.set(v.qn(v.NS_RDF, 'resource'), original)
            elif mode == 'dpm-guid-whitespace-mismatch':
                identity['node'].DPM.Mail_GUID = ' ' + original + ' '
            elif mode == 'structure-guid-whitespace-mismatch':
                assert properties
                properties[0].V = ' ' + original + ' '
            else:
                prop = {'email-ordered': 'email', 'assets-ordered': 'assets',
                        'attachments-ordered': 'attachments', 'to-unordered': 'To'}[mode]
                nodes = list(root.iter(v.qn(v.NS_PDFMAILMETA, prop)))
                if not nodes:
                    assert prop == 'attachments'
                    node = ET.SubElement(v.xmp_descriptions(root)[0], v.qn(v.NS_PDFMAILMETA, prop))
                    ET.SubElement(node, v.qn(v.NS_RDF, 'Bag'))
                    nodes = [node]
                for node in nodes:
                    assert len(node) == 1 and v.local(node[0].tag) in ('Bag', 'Seq')
                    node[0].tag = v.qn(v.NS_RDF, 'Bag' if mode == 'to-unordered' else 'Seq')
            # Preserve the packet processing instructions and registered prefixes.
            text = ET.tostring(root, encoding='utf-8')
            for prefix, uri in (('pdfmail', v.NS_PDFMAIL), ('pdfmailid', v.NS_PDFMAILID),
                                ('pdfmailmeta', v.NS_PDFMAILMETA)):
                if ('xmlns:' + prefix + '=').encode() not in text:
                    root.set('xmlns:' + prefix, uri)
            text = ET.tostring(root, encoding='utf-8')
            packet_start = data[:data.index(b'?>') + 2] if data.startswith(b'<?xpacket') else b''
            packet_end = data[data.rfind(b'<?xpacket'):] if data.rfind(b'<?xpacket') > 0 else b''
            pdf.Root.Metadata.write(packet_start + b'\n' + text + b'\n' + packet_end)
            filename = mode + '.pdf'
            pdf.save(output / filename, fix_metadata_version=False)
            cases.append(dict(file=filename, expected_failures=failures))
    for case in cases:
        case['sha256'] = hashlib.sha256((output / case['file']).read_bytes()).hexdigest()
        case['expected_eapdf_status'] = 'FAIL' if case['expected_failures'] else 'PASS'
    (output / 'expectations.json').write_text(json.dumps(dict(
        authority='Reviewed XMP-SEMANTIC-VALUES, XMP-ARRAY-SEMANTICS and GUID-IDENTITY contracts in validator_rule_text.json',
        source=str(source.relative_to(ROOT)), source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
        cases=cases), indent=2) + '\n')
    print('Wrote', len(cases), 'fixtures to', output)


if __name__ == '__main__':
    main()
