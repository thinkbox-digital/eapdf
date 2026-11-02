"""Connected group types, exact opaque names and unbound XMP candidate fields."""
import hashlib
import json
from pathlib import Path
import sys
from xml.etree import ElementTree as ET

import pikepdf
from build_eapdf_message_owner_fixtures import structure_elements

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import validate_eapdf as v

OUT = ROOT / 'tests/fixtures/eapdf/validator-content-group-types'
SEED = ROOT / 'tests/fixtures/eapdf/validator-dpart-object-ranges/multi-ordered.pdf'


def type_property(element):
    return next(prop for prop in element.A.P if str(prop.N) == 'Mail_ContentSetType')


def add_xmp_candidate(pdf, value):
    xroot, namespaces, _ = v.parse_xmp(v.read_metadata_stream(pdf), v.Report('parse'))
    for prefix, uri in namespaces.items():
        ET.register_namespace(prefix, uri)
    description = xroot.find('.//' + v.qn(v.NS_RDF, 'Description'))
    ET.SubElement(description, v.qn(v.NS_PDFMAILMETA, 'ContentSetType')).text = value
    packet = ET.tostring(xroot, encoding='utf-8')
    for prefix, uri in (('pdfmail', v.NS_PDFMAIL), ('pdfmailid', v.NS_PDFMAILID),
                        ('pdfmailmeta', v.NS_PDFMAILMETA)):
        if ('xmlns:' + prefix + '=').encode() not in packet:
            xroot.set('xmlns:' + prefix, uri)
    pdf.Root.Metadata.write(ET.tostring(xroot, encoding='utf-8'))


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    cases = []
    invalid = ('foreign-group-type', 'padded-group-type', 'empty-group-type')
    unavailable = ('xmp-unbound-body', 'xmp-unbound-empty', 'xmp-unbound-other')
    for name in ('matching-group',) + invalid + (
            'omitted-group-types', 'unicode-group-value', 'opaque-group-types') + unavailable:
        with pikepdf.open(SEED) as pdf:
            elements = structure_elements(pdf)
            groups = [element for element in elements if element.get('/S') == pikepdf.Name('/Mail_ContentGroup')]
            assert groups and all(str(type_property(group).V) == 'BodyRendering' for group in groups)
            if name == 'foreign-group-type': type_property(groups[0]).V = 'FrontMatter'
            elif name == 'padded-group-type': type_property(groups[0]).V = ' BodyRendering '
            elif name == 'empty-group-type': type_property(groups[0]).V = ''
            elif name == 'omitted-group-types':
                for group in groups:
                    assert len(group.A.P) == 1
                    del group.A
            elif name == 'unicode-group-value':
                type_property(groups[0]).V = pikepdf.String(b'\xfe\xff' + 'BodyRendering'.encode('utf-16be'))
            elif name == 'opaque-group-types':
                opaque = ' Vendor  rendering '
                for element in elements:
                    if element.get('/S') in (pikepdf.Name('/Mail_ContentGroup'), pikepdf.Name('/Mail_ContentSet')):
                        prop = type_property(element)
                        if str(prop.V) == 'BodyRendering': prop.V = opaque
                _, _, nodes = v.get_dpart_nodes(pdf)
                for item in nodes:
                    if not item['child_count'] and item['dpm'].get('Mail_ContentSetType') == pikepdf.Name.BodyRendering:
                        item['node'].DPM.Mail_ContentSetType = pikepdf.Name('/' + opaque)
            elif name in unavailable:
                add_xmp_candidate(pdf, {'xmp-unbound-body': 'BodyRendering', 'xmp-unbound-empty': '',
                                        'xmp-unbound-other': 'FrontMatter'}[name])
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
            expected_status='FAIL' if name in invalid else 'INCOMPLETE' if name in unavailable else 'PASS',
            expected_failures=['MAIL_CONTENTGROUP_CONTENTSETTYPE_MATCH_IF_PRESENT'] if name in invalid else []))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF9.7 Table13 conditional Content Group type correspondence; Table12 string representation of DPM name values',
        seed_sha256=hashlib.sha256(SEED.read_bytes()).hexdigest(),
        boundary='Actual content identifies corresponding DPart data. Absence remains a recommendation; empty supplied values still compare. Opaque extension names preserve characters; external extension/source semantics are not certified. Candidate XMP fields lack a reviewed group binding and remain unverified.',
        cases=cases), indent=2) + '\n')
    print('Built ten Content Group type controls.')


if __name__ == '__main__':
    main()
