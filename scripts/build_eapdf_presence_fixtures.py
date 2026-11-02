"""Required first/count values and attribution spelling, independent of source bytes."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from xml.etree import ElementTree as ET

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import validate_eapdf as v


def main():
    source = ROOT / 'tests/fixtures/eapdf/validator-email-schema/valid.pdf'
    asset_source = ROOT / 'tests/fixtures/eapdf/validator-xmp-records/valid.pdf'
    output = ROOT / 'tests/fixtures/eapdf/validator-presence'
    output.mkdir(parents=True, exist_ok=True)
    plain_source = output / '_source-no-attachments.pdf'
    jar = os.environ.get('EAPDF_GENERATOR_CLASSPATH')
    if jar:
        with tempfile.TemporaryDirectory(prefix='.eapdf-presence-', dir=ROOT) as directory:
            stage = Path(directory)
            mail = stage / 'plain.eml'
            mail.write_bytes(b'From: Alice <alice@example.org>\r\nTo: Bob <bob@example.org>\r\n'
                b'Date: Tue, 28 Jan 2003 21:20:54 -0500\r\nSubject: No attachments\r\n\r\nPlain body.\r\n')
            config = stage / 'config.json'
            config.write_text(json.dumps({'branding': {'enabled': False}, 'premis': {'enabled': False}}))
            process = subprocess.run(['java', '-Xmx512m', '-Djava.awt.headless=true', '-cp', jar,
                'eapdf.EmailToPDFA', '--omit-source', '--out', str(stage / 'out'), str(mail)],
                cwd=ROOT, env=dict(os.environ, EAPDF_CONFIG=str(config)), capture_output=True, text=True, timeout=120)
            assert process.returncode == 0, process.stdout + process.stderr
            generated = list((stage / 'out').glob('*.pdf'))
            assert len(generated) == 1
            shutil.copy2(generated[0], plain_source)
    assert plain_source.exists(), 'Set EAPDF_GENERATOR_CLASSPATH to generate the no-attachment source'
    recipes = [('valid', None, []), ('creator-first-empty', 'first-empty', ['XMP_CREATOR']),
        ('creator-first-whitespace', 'first-space', ['XMP_CREATOR']),
        ('creator-address-name', 'creator-name', []), ('producer-address-name', 'producer-name', []),
        ('isolated-count-absent', 'count-absent', ['MESSAGE_ATTACHMENT_COUNT_XMP']),
        ('no-attachments-count-absent', 'plain-count-absent', []),
        ('asset-count-empty', 'asset-empty', ['ASSET_NUMBERMESSAGES_TYPE']),
        ('asset-count-negative', 'asset-negative', ['ASSET_NUMBERMESSAGES_TYPE']),
        ('asset-count-uri', 'asset-uri', ['ASSET_NUMBERMESSAGES_TYPE']),
        ('asset-count-array', 'asset-array', ['ASSET_NUMBERMESSAGES_TYPE']),
        ('asset-count-signed-zeros', 'asset-signed', [])]
    cases = []
    for name, mutation, failures in recipes:
        selected = plain_source if mutation == 'plain-count-absent' else asset_source if mutation and mutation.startswith('asset-') else source
        with pikepdf.Pdf.open(selected) as pdf:
            if selected != asset_source:
                assert not any(f['afrelationship'] == 'Source' for f in v.get_af_specs(pdf))
            if mutation:
                root, namespaces, _ = v.parse_xmp(v.read_metadata_stream(pdf), v.Report('parse'))
                for prefix, uri in namespaces.items():
                    ET.register_namespace(prefix, uri)
                if mutation.startswith('asset-'):
                    record = root.find('.//' + v.qn(v.NS_PDFMAILMETA, 'assets')).find('.//' + v.qn(v.NS_RDF, 'li'))
                    field = ET.SubElement(record, v.qn(v.NS_PDFMAILMETA, 'NumberMessages'))
                    if mutation == 'asset-uri':
                        field.set(v.qn(v.NS_RDF, 'resource'), '1')
                    elif mutation == 'asset-array':
                        ET.SubElement(ET.SubElement(field, v.qn(v.NS_RDF, 'Bag')), v.qn(v.NS_RDF, 'li')).text = '1'
                    else:
                        field.text = {'asset-empty': '', 'asset-negative': '-1', 'asset-signed': '+0001'}[mutation]
                elif mutation.startswith('first-'):
                    seq = root.find('.//' + v.qn(v.NS_DC, 'creator')).find(v.qn(v.NS_RDF, 'Seq'))
                    first = ET.Element(v.qn(v.NS_RDF, 'li'))
                    first.text = '   ' if mutation == 'first-space' else ''
                    seq.insert(0, first)
                elif mutation == 'creator-name':
                    root.find('.//' + v.qn(v.NS_DC, 'creator')).find('.//' + v.qn(v.NS_RDF, 'li')).text = 'alice@example.org'
                elif mutation == 'producer-name':
                    root.find('.//' + v.qn(v.NS_PDF, 'Producer')).text = 'mailtool@example.org'
                else:
                    record = root.find('.//' + v.qn(v.NS_PDFMAILMETA, 'email')).find('.//' + v.qn(v.NS_RDF, 'li'))
                    node = record.find(v.qn(v.NS_PDFMAILMETA, 'NumberAttachments'))
                    assert node is not None
                    record.remove(node)
                packet = ET.tostring(root, encoding='utf-8')
                for prefix, uri in (('pdfmail', v.NS_PDFMAIL), ('pdfmailid', v.NS_PDFMAILID), ('pdfmailmeta', v.NS_PDFMAILMETA)):
                    if ('xmlns:' + prefix + '=').encode() not in packet:
                        root.set('xmlns:' + prefix, uri)
                pdf.Root.Metadata.write(ET.tostring(root, encoding='utf-8'))
            path = output / (name + '.pdf')
            pdf.save(path, fix_metadata_version=False)
        cases.append(dict(file=path.name, expected_failures=failures, sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
    (output / 'expectations.json').write_text(json.dumps(dict(
        authority=['EA-PDF 9.8 first creator item', 'EA-PDF Table 18 conditional NumberAttachments', 'EA-PDF Table 16 optional NumberMessages type', 'Reviewed XMP creator/producer attribution contract'],
        sources={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in (source, plain_source, asset_source)},
        cases=cases), indent=2) + '\n')
    print('Wrote', len(cases), 'presence/provenance probes to', output)


if __name__ == '__main__':
    main()
