"""Table 15 semantic simple-value probes from an independently valid PDF."""
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
    source = ROOT / 'tests/fixtures/eapdf/upstream-issues/valid.pdf'
    output = ROOT / 'tests/fixtures/eapdf/validator-identification'
    output.mkdir(parents=True, exist_ok=True)
    recipes = [('valid', None, None, None, []),
        ('version-signed-zeros', 'version', 'text', '+0001', []),
        ('revision-zeros', 'rev', 'text', '002024', []),
        ('version-uri', 'version', 'uri', '1', ['EAPDF_VERSION']),
        ('revision-array', 'rev', 'array', '2024', ['EAPDF_REV']),
        ('conformance-uri', 'conformance', 'uri', 's', ['EAPDF_CONFORMANCE_VALUE']),
        ('conformance-array', 'conformance', 'array', 's', ['EAPDF_CONFORMANCE_VALUE']),
        ('conformance-padded', 'conformance', 'text', ' s ', ['EAPDF_CONFORMANCE_VALUE']),
        ('version-qualified', 'version', 'qualified', '+001', []),
        ('conformance-qualified', 'conformance', 'qualified', 's', []),
        ('version-float', 'version', 'text', '1.0', ['EAPDF_VERSION'])]
    cases = []
    for name, field, kind, value, failures in recipes:
        with pikepdf.Pdf.open(source) as pdf:
            if field:
                root, namespaces, _ = v.parse_xmp(v.read_metadata_stream(pdf), v.Report('parse'))
                for prefix, uri in namespaces.items():
                    ET.register_namespace(prefix, uri)
                node = root.find('.//' + v.qn(v.NS_PDFMAILID, field))
                assert node is not None
                node.clear()
                if kind == 'uri':
                    node.set(v.qn(v.NS_RDF, 'resource'), value)
                elif kind == 'array':
                    ET.SubElement(ET.SubElement(node, v.qn(v.NS_RDF, 'Bag')), v.qn(v.NS_RDF, 'li')).text = value
                elif kind == 'qualified':
                    node.set(v.qn(v.NS_RDF, 'parseType'), 'Resource')
                    ET.SubElement(node, v.qn(v.NS_RDF, 'value')).text = value
                else:
                    node.text = value
                packet = ET.tostring(root, encoding='utf-8')
                for prefix, uri in (('pdfmail', v.NS_PDFMAIL), ('pdfmailid', v.NS_PDFMAILID), ('pdfmailmeta', v.NS_PDFMAILMETA)):
                    if ('xmlns:' + prefix + '=').encode() not in packet:
                        root.set('xmlns:' + prefix, uri)
                pdf.Root.Metadata.write(ET.tostring(root, encoding='utf-8'))
            path = output / (name + '.pdf')
            pdf.save(path, fix_metadata_version=False)
        cases.append(dict(file=path.name, expected_failures=failures, sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
    (output / 'expectations.json').write_text(json.dumps(dict(
        authority=['EA-PDF 9.8.1 Table 15', 'Reviewed XMP semantic simple-value types'],
        source=str(source.relative_to(ROOT)), source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
        cases=cases), indent=2) + '\n')
    print('Wrote', len(cases), 'identification probes to', output)


if __name__ == '__main__':
    main()
