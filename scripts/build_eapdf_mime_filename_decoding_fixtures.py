"""Unknown MIME filename decoding is an evidence gap, not a guessed filename."""
import hashlib
import io
import json
from pathlib import Path
import re
from xml.etree import ElementTree as ET

import pikepdf
from build_eapdf_empty_raw_fixtures import update_source, qn, META, RDF

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/fixtures/eapdf/validator-mime-filename-decoding'
SEED = ROOT / 'tests/fixtures/eapdf/validator-source-mailbox-data/from-exact.pdf'


def main():
    OUT.mkdir(exist_ok=True)
    cases = []
    recipes = [
        ('invalid-utf8-replacement', "UTF-8''%FF.txt", '\ufffd.txt', 'INCOMPLETE'),
        ('invalid-utf8-different', "UTF-8''%FF.txt", 'original.txt', 'INCOMPLETE'),
        ('unknown-charset', "X-NO-CHARSET''abc.txt", 'abc.txt', 'INCOMPLETE'),
        ('known-alternative', "UTF-8''%FF.txt", None, 'PASS'),
        ('valid-unicode', "UTF-8''caf%C3%A9.txt", 'café.txt', 'PASS'),
        ('actual-replacement-scalar', "UTF-8''%EF%BF%BD.txt", '\ufffd.txt', 'PASS'),
        ('known-wrong-name', "UTF-8''caf%C3%A9.txt", 'different.txt', 'FAIL'),
        ('invalid-name-wrong-size', "UTF-8''%FF.txt", '\ufffd.txt', 'FAIL'),
    ]
    for name, parameter, filename, status in recipes:
        with pikepdf.open(SEED) as pdf:
            packet = pdf.Root.Metadata.read_bytes()
            for _, (prefix, uri) in ET.iterparse(io.BytesIO(packet), events=['start-ns']):
                ET.register_namespace(prefix, uri)
            root = ET.fromstring(packet)
            email = root.find('.//' + qn(META, 'email') + '/' + qn(RDF, 'Bag') + '/' + qn(RDF, 'li'))
            attachment = root.find('.//' + qn(META, 'attachments') + '/' + qn(RDF, 'Bag') + '/' + qn(RDF, 'li'))
            source = next(s for s in pdf.Root.AF if s.AFRelationship == pikepdf.Name.Source)
            data, count = re.subn(br'(?im)^Content-Disposition:[^\r\n]*(?:\r?\n[ \t][^\r\n]*)*',
                                 ('Content-Disposition: attachment; filename*=' + parameter).encode(),
                                 source.EF.F.read_bytes(), count=1)
            assert count == 1
            update_source(pdf, root, email, data)
            source.EF.UF = source.EF.F
            if filename is not None:
                attachment.find(qn(META, 'Filename')).text = filename
            if name == 'invalid-name-wrong-size':
                attachment.find(qn(META, 'SizeInBytes')).text = '1'
            serialized = ET.tostring(root, encoding='utf-8')
            for prefix, uri in [('pdfmail', 'http://www.pdfa.org/eapdf/'),
                                ('pdfmailid', 'http://www.pdfa.org/eapdf/ns/id/'), ('pdfmailmeta', META)]:
                if ('xmlns:' + prefix + '=').encode() not in serialized:
                    root.set('xmlns:' + prefix, uri)
            pdf.Root.Metadata.write(ET.tostring(root, encoding='utf-8'))
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        failures = (['ATTACHMENT_XMP_MATCH'] if name == 'known-wrong-name' else
                    ['ATTACHMENT_ANNOTATIONS_PRESENT', 'ATTACHMENT_XMP_MATCH'] if name == 'invalid-name-wrong-size' else [])
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                          expected_status=status, expected_failures=failures,
                          seed=str(SEED.relative_to(ROOT)), seed_sha256=hashlib.sha256(SEED.read_bytes()).hexdigest()))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF 9.8.4 Table 19 requires a filename from the original asset. '
                  'Unknown charset/invalid byte decoding cannot establish its value. A separately understood '
                  'Content-Type name remains a permitted alternative; byte/size failures are independent.',
        boundary='No charset guessing, complete MIME parameter grammar or duplicate-parameter recovery is claimed.',
        cases=cases), indent=2) + '\n')


if __name__ == '__main__':
    main()
