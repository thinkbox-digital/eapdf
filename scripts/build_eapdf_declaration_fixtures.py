"""Freeze declaration schema and upstream-only inclusion probes from a valid sample."""
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
    output = ROOT / 'tests/fixtures/eapdf/validator-declarations'
    output.mkdir(parents=True, exist_ok=True)
    recipes = [('valid', []), ('legacy-https-namespace', ['LOGICAL_TS32005_DECLARATION']),
               ('ordered-declarations', ['LOGICAL_TS32005_DECLARATION']),
               ('invalid-claim-date', ['LOGICAL_TS32005_DECLARATION']),
               ('qualified-uri-identifier', []), ('reduced-precision-claim-date', []),
               ('paragraph-in-paragraph', ['ISO32005_UPSTREAM'])]
    cases = []
    for name, failures in recipes:
        with pikepdf.Pdf.open(source) as pdf:
            if name == 'paragraph-in-paragraph':
                elements = []
                v.walk_struct_elems(pdf.Root.StructTreeRoot.K, elements, None)
                element = next(e['elem'] for e in elements if e['type'] == 'Span'
                               and v.safe_name(e['elem'].P.S) == 'P')
                element.S = pikepdf.Name.P
            elif name != 'valid':
                root, namespaces, _ = v.parse_xmp(v.read_metadata_stream(pdf), v.Report('parse'))
                for prefix, uri in namespaces.items():
                    ET.register_namespace(prefix, uri)
                declarations = root.find('.//' + v.qn(v.NS_PDFD, 'declarations'))
                bag = declarations.find(v.qn(v.NS_RDF, 'Bag'))
                if name == 'legacy-https-namespace':
                    for element in declarations.iter():
                        if element.tag.startswith('{' + v.NS_PDFD + '}'):
                            element.tag = v.qn('https://pdfa.org/declarations/', v.local(element.tag))
                elif name == 'ordered-declarations':
                    bag.tag = v.qn(v.NS_RDF, 'Seq')
                elif name in ('invalid-claim-date', 'reduced-precision-claim-date'):
                    date = bag[0].find('.//' + v.qn(v.NS_PDFD, 'claimDate'))
                    assert date is not None
                    date.text = '2026-02-29' if name == 'invalid-claim-date' else '2026-10'
                elif name == 'qualified-uri-identifier':
                    target = bag[0].find(v.qn(v.NS_PDFD, 'conformsTo'))
                    target.text = None
                    target.set(v.qn(v.NS_RDF, 'parseType'), 'Resource')
                    ET.SubElement(target, v.qn(v.NS_RDF, 'value'), {v.qn(v.NS_RDF, 'resource'): v.ISO32005_IDENTIFIER})
                packet = ET.tostring(root, encoding='utf-8')
                for prefix, uri in (('pdfmail', v.NS_PDFMAIL), ('pdfmailid', v.NS_PDFMAILID), ('pdfmailmeta', v.NS_PDFMAILMETA)):
                    if ('xmlns:' + prefix + '=').encode() not in packet:
                        root.set('xmlns:' + prefix, uri)
                pdf.Root.Metadata.write(ET.tostring(root, encoding='utf-8'))
            path = output / (name + '.pdf')
            pdf.save(path, fix_metadata_version=False)
        cases.append(dict(file=path.name, expected_failures=failures, sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
    (output / 'expectations.json').write_text(json.dumps(dict(
        authority=['EA-PDF 9.7', 'PDF Declarations 2019 sections 7/8', 'Pinned upstream ISO 32005 inclusion predicates'],
        source=str(source.relative_to(ROOT)), source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
        cases=cases), indent=2) + '\n')
    print('Wrote', len(cases), 'probes to', output)


if __name__ == '__main__':
    main()
