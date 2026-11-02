"""Table 19 permits either original MIME filename parameter without trimming."""
import hashlib
import io
import json
from pathlib import Path
import re
from xml.etree import ElementTree as ET

import pikepdf
from build_eapdf_empty_raw_fixtures import update_source, qn, META, RDF

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/fixtures/eapdf/validator-filename-alternatives'
SEED = ROOT / 'tests/fixtures/eapdf/validator-source-mailbox-data/from-exact.pdf'


def main():
    OUT.mkdir(exist_ok=True)
    cases = []
    for name in ('cd-choice', 'type-choice', 'space-cd', 'tab-cd', 'unicode-type-choice',
                 'type-choice-no-checksum', 'wrong-choice', 'wrong-size'):
        with pikepdf.open(SEED) as pdf:
            packet = pdf.Root.Metadata.read_bytes()
            for _, (prefix, uri) in ET.iterparse(io.BytesIO(packet), events=['start-ns']):
                ET.register_namespace(prefix, uri)
            root = ET.fromstring(packet)
            email = root.find('.//' + qn(META, 'email') + '/' + qn(RDF, 'Bag') + '/' + qn(RDF, 'li'))
            attachment = root.find('.//' + qn(META, 'attachments') + '/' + qn(RDF, 'Bag') + '/' + qn(RDF, 'li'))
            type_name = attachment.find(qn(META, 'Filename')).text
            cd_name = ' ' if name == 'space-cd' else '\t' if name == 'tab-cd' else 'disposition.txt'
            header = ("Content-Disposition: attachment; filename*=utf-8''caf%C3%A9.txt" if name == 'unicode-type-choice'
                      else 'Content-Disposition: attachment; filename="' + cd_name + '"')
            source = next(s for s in pdf.Root.AF if s.AFRelationship == pikepdf.Name.Source)
            data, count = re.subn(br'(?im)^Content-Disposition:[^\r\n]*(?:\r?\n[ \t][^\r\n]*)*',
                                 header.encode(), source.EF.F.read_bytes(), count=1)
            assert count == 1
            update_source(pdf, root, email, data)
            source.EF.UF = source.EF.F
            chosen = (type_name if name in ('type-choice', 'unicode-type-choice', 'type-choice-no-checksum') else
                      'not-original.txt' if name == 'wrong-choice' else cd_name)
            attachment.find(qn(META, 'Filename')).text = chosen
            if name == 'type-choice-no-checksum':
                attachment.remove(attachment.find(qn(META, 'CheckSum')))
            if name == 'wrong-size':
                attachment.find(qn(META, 'SizeInBytes')).text = '1'
            serialized = ET.tostring(root, encoding='utf-8')
            for prefix, uri in [('pdfmail', 'http://www.pdfa.org/eapdf/'),
                                ('pdfmailid', 'http://www.pdfa.org/eapdf/ns/id/'), ('pdfmailmeta', META)]:
                if ('xmlns:' + prefix + '=').encode() not in serialized:
                    root.set('xmlns:' + prefix, uri)
            pdf.Root.Metadata.write(ET.tostring(root, encoding='utf-8'))
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        failures = (['ATTACHMENT_XMP_MATCH'] if name == 'wrong-choice' else
                    ['ATTACHMENT_ANNOTATIONS_PRESENT', 'ATTACHMENT_XMP_MATCH'] if name == 'wrong-size' else [])
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                          expected_status='FAIL' if failures else 'PASS', expected_failures=failures,
                          chosen_filename=chosen, original_disposition=header, original_type_filename=type_name,
                          seed=str(SEED.relative_to(ROOT)), seed_sha256=hashlib.sha256(SEED.read_bytes()).hexdigest()))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF 9.8.4 Table 19 Filename, printed p89: a case-sensitive free text filename can come '
                  'from either Content-Disposition filename or Content-Type name. No parameter precedence or '
                  'trimming of actual original filename text is prescribed.',
        boundary='Both understood original parameters are permitted alternatives. This does not infer unknown '
                 'filename encodings, duplicate parameter resolution, arbitrary native aliases or unique occurrence identity.',
        cases=cases), indent=2) + '\n')


if __name__ == '__main__':
    main()
