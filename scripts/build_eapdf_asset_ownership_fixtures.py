"""Mutate a retained two-source PDF to test unordered source-asset association."""
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
    output = ROOT / 'tests/fixtures/eapdf/validator-asset-ownership'
    source = output / '_source.pdf'
    assert source.exists(), 'Retain the two-source creator control before mutation.'
    recipes = [('valid', False, False, False, False),
               ('assets-reversed', True, False, False, False),
               ('collision-forward', False, True, False, False),
               ('collision-reversed', True, True, False, False),
               ('checksum-wrong', False, False, True, False),
               ('association-ambiguous', False, True, True, True)]
    cases = []
    for name, reverse, collision, wrong_checksum, ambiguous in recipes:
        with pikepdf.open(source) as pdf:
            root, namespaces, _ = v.parse_xmp(v.read_metadata_stream(pdf), v.Report('parse'))
            for prefix, uri in namespaces.items():
                ET.register_namespace(prefix, uri)
            bag = root.find('.//' + v.qn(v.NS_PDFMAILMETA, 'assets')).find(v.qn(v.NS_RDF, 'Bag'))
            assert len(bag) == 2
            if collision:
                for index, child in enumerate(bag, start=1):
                    child.find(v.qn(v.NS_PDFMAILMETA, 'Filename')).text = f'folder-{index}/message.eml'
                specs = [spec for spec in pdf.Root.AF if str(spec.AFRelationship) == '/Source']
                assert len(specs) == 2
                for index, spec in enumerate(specs):
                    filename = 'message.eml' if ambiguous or index == 0 else 'message_2.eml'
                    spec.F = pikepdf.String(filename)
                    spec.UF = pikepdf.String(filename)
            if wrong_checksum:
                for child in bag if ambiguous else [bag[0]]:
                    child.find(v.qn(v.NS_PDFMAILMETA, 'Checksum')).text = '0' * 32
            if reverse:
                bag[:] = list(reversed(list(bag)))
            packet = ET.tostring(root, encoding='utf-8')
            for prefix, uri in (('pdfmail', v.NS_PDFMAIL), ('pdfmailid', v.NS_PDFMAILID), ('pdfmailmeta', v.NS_PDFMAILMETA)):
                if ('xmlns:' + prefix + '=').encode() not in packet:
                    root.set('xmlns:' + prefix, uri)
            pdf.Root.Metadata.write(ET.tostring(root, encoding='utf-8'))
            path = output / (name + '.pdf')
            pdf.save(path, fix_metadata_version=False)
        cases.append({'file': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                      'expected_failures': ['ASSET_MD5_MATCH'] if wrong_checksum else [],
                      'expected_status': 'FAIL' if wrong_checksum else 'PASS',
                      'required_unavailable_codes': ['ASSET_MATCH', 'SOURCE_WITHOUT_XMP', 'VALIDATION_INCOMPLETE'] if ambiguous else []})
    (output / 'expectations.json').write_text(json.dumps({
        'authority': ['EA-PDF 9.8.2 assets are an unordered set', 'Table 16 paths are allowed',
                      'Reviewed ASSET_MATCH: ambiguous correspondence cannot establish a violation'],
        'source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(), 'cases': cases}, indent=2) + '\n')
    print('Wrote', len(cases), 'source-asset ownership cases')


if __name__ == '__main__':
    main()
