"""Table 16/19 canonical property and value-shape probes for the PDF validator."""
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
    output = ROOT / 'tests/fixtures/eapdf/validator-xmp-records'
    output.mkdir(parents=True, exist_ok=True)
    cases = [
        ('valid', None, None, None, []),
        ('asset-checksum-alias', 'assets', 'Checksum', ('rename', 'CheckSum'),
         ['ASSET_XMP_FIELDS', 'ASSET_CHECKSUM_FORM', 'ASSET_MD5_MATCH']),
        ('asset-type-alias', 'assets', 'ContentType', ('rename', 'Format'),
         ['ASSET_XMP_FIELDS', 'ASSET_CONTENTTYPE', 'ASSET_CONTENTTYPE_MATCH']),
        ('asset-negative-size', 'assets', 'SizeInBytes', ('text', '-1'),
         ['ASSET_SIZE_TYPE', 'ASSET_SIZE_MATCH']),
        ('asset-uri-size', 'assets', 'SizeInBytes', ('uri', None), ['ASSET_SIZE_TYPE']),
        ('attachment-type-alias', 'attachments', 'Content-Type', ('rename', 'ContentType'),
         ['ATTACHMENT_XMP_CONTENTTYPE', 'ATTACHMENT_XMP_CONTENTTYPE_MATCH']),
        ('attachment-empty-checksum', 'attachments', 'CheckSum', ('text', ''), ['ATTACHMENT_XMP_CHECKSUM_FORM']),
        ('attachment-uri-checksum', 'attachments', 'CheckSum', ('uri', None), ['ATTACHMENT_XMP_CHECKSUM_FORM']),
        ('attachment-checksum-absent', 'attachments', 'CheckSum', ('remove', None), []),
        ('asset-signed-zero-padding', 'assets', 'SizeInBytes', ('signed', None), []),
    ]
    manifest = []
    for name, prop, field, mutation, failures in cases:
        with pikepdf.Pdf.open(source) as pdf:
            if prop:
                root, namespaces, _ = v.parse_xmp(v.read_metadata_stream(pdf), v.Report('parse'))
                for prefix, uri in namespaces.items():
                    ET.register_namespace(prefix, uri)
                parent = root.find('.//' + v.qn(v.NS_PDFMAILMETA, prop))
                node = parent.find('.//' + v.qn(v.NS_PDFMAILMETA, field))
                assert node is not None, (name, field)
                kind, value = mutation
                if kind == 'rename':
                    node.tag = v.qn(v.NS_PDFMAILMETA, value)
                elif kind == 'text':
                    node.text = value
                elif kind == 'uri':
                    node.set(v.qn(v.NS_RDF, 'resource'), node.text)
                    node.text = None
                elif kind == 'signed':
                    node.text = '+000' + node.text
                elif kind == 'remove':
                    next(n for n in parent.iter() if node in list(n)).remove(node)
                packet = ET.tostring(root, encoding='utf-8')
                for prefix, uri in (('pdfmail', v.NS_PDFMAIL), ('pdfmailid', v.NS_PDFMAILID),
                                    ('pdfmailmeta', v.NS_PDFMAILMETA)):
                    if ('xmlns:' + prefix + '=').encode() not in packet:
                        root.set('xmlns:' + prefix, uri)
                pdf.Root.Metadata.write(ET.tostring(root, encoding='utf-8'))
            pdf.save(output / (name + '.pdf'), fix_metadata_version=False)
        manifest.append(dict(file=name + '.pdf', expected_failures=failures,
                             sha256=hashlib.sha256((output / (name + '.pdf')).read_bytes()).hexdigest()))
    (output / 'expectations.json').write_text(json.dumps(dict(
        authority=['EA-PDF 9.8.2 Table 16', 'EA-PDF 9.8.4 Table 19',
                   'Reviewed XMP normal-string versus URI and array value semantics'],
        source=str(source.relative_to(ROOT)), source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
        cases=manifest), indent=2) + '\n')
    print('Wrote', len(manifest), 'probes to', output)


if __name__ == '__main__':
    main()
