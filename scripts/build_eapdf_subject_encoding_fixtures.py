"""Subject comparison needs reliable encoding evidence, not decoder repair."""
import hashlib
import io
import json
from pathlib import Path
import re
from xml.etree import ElementTree as ET

import pikepdf
from build_eapdf_empty_raw_fixtures import update_source, qn, META, RDF

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/fixtures/eapdf/validator-subject-encoding'
SEED = ROOT / 'tests/fixtures/eapdf/validator-source-mailbox-data/from-exact.pdf'


def main():
    OUT.mkdir(exist_ok=True)
    recipes = [
        ('malformed-q-repaired', '=?utf-8?Q?bad=ZZ?=', 'bad=ZZ', 'INCOMPLETE'),
        ('malformed-b-repaired', '=?utf-8?B?YQ?=', 'a', 'INCOMPLETE'),
        ('unknown-charset', '=?X-NO-CHARSET?Q?subject?=', 'subject', 'INCOMPLETE'),
        ('malformed-different', '=?utf-8?Q?bad=ZZ?=', 'Different', 'INCOMPLETE'),
        ('malformed-literal', '=?utf-8?Q?bad=ZZ?=', '=?utf-8?Q?bad=ZZ?=', 'PASS'),
        ('valid-encoded', '=?utf-8?Q?caf=C3=A9?=', 'café', 'PASS'),
        ('valid-different', '=?utf-8?Q?caf=C3=A9?=', 'Different', 'FAIL'),
        ('supplied-malformed', 'bad=ZZ', '=?utf-8?Q?bad=ZZ?=', 'FAIL'),
        ('literal-replacement-scalar', '=?utf-8?B?77+9?=', '\ufffd', 'PASS'),
        ('required-subject-omitted', '=?utf-8?Q?bad=ZZ?=', None, 'FAIL'),
    ]
    cases = []
    for name, original, supplied, status in recipes:
        with pikepdf.open(SEED) as pdf:
            packet = pdf.Root.Metadata.read_bytes()
            for _, (prefix, uri) in ET.iterparse(io.BytesIO(packet), events=['start-ns']):
                ET.register_namespace(prefix, uri)
            root = ET.fromstring(packet)
            email = root.find('.//' + qn(META, 'email') + '/' + qn(RDF, 'Bag') + '/' + qn(RDF, 'li'))
            source = next(s for s in pdf.Root.AF if s.AFRelationship == pikepdf.Name.Source)
            data, count = re.subn(br'(?im)^Subject:[^\r\n]*(?:\r?\n[ \t][^\r\n]*)*',
                                 ('Subject: ' + original).encode('utf-8'), source.EF.F.read_bytes(), count=1)
            assert count == 1
            update_source(pdf, root, email, data)
            source.EF.UF = source.EF.F
            prop = email.find(qn(META, 'Subject'))
            if supplied is None:
                email.remove(prop)
            else:
                prop.text = supplied
            serialized = ET.tostring(root, encoding='utf-8')
            for prefix, uri in [('pdfmail', 'http://www.pdfa.org/eapdf/'),
                                ('pdfmailid', 'http://www.pdfa.org/eapdf/ns/id/'), ('pdfmailmeta', META)]:
                if ('xmlns:' + prefix + '=').encode() not in serialized:
                    root.set('xmlns:' + prefix, uri)
            pdf.Root.Metadata.write(ET.tostring(root, encoding='utf-8'))
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                          expected_status=status, expected_failures=['MESSAGE_SUBJECT_XMP'] if status == 'FAIL' else [],
                          original_subject=original, supplied_subject=supplied,
                          seed=str(SEED.relative_to(ROOT)), seed_sha256=hashlib.sha256(SEED.read_bytes()).hexdigest()))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF 7.3.1 and Tables 4/18 require equivalent complete source Core Field values, '
                  'subject to representation/encoding differences. RFC2047 6.3 does not require decoder repair '
                  'of incorrectly formed encoded-words; unsupported recovery cannot establish equality or mismatch.',
        source='https://www.rfc-editor.org/rfc/rfc2047.html#section-6.3',
        boundary='Literal original text can be preserved exactly. Missing required Subject, supplied text types '
                 'and known decoded source differences retain independent checks. No general header grammar, '
                 'charset recovery or normalization-policy certification is claimed.', cases=cases), indent=2) + '\n')


if __name__ == '__main__':
    main()
