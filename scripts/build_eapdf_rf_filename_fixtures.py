"""One RF filename changes; original companion bytes and metadata remain."""
import hashlib
import json
from pathlib import Path

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/fixtures/eapdf/validator-rf-filenames'
SEED = ROOT / 'tests/fixtures/eapdf/validator-coverage-branches/rf-valid.pdf'


def main():
    OUT.mkdir(exist_ok=True)
    cases = []
    for name in ('unreadable-utf8', 'unreadable-surrogate', 'unreadable-bad-pair',
                 'unreadable-revision', 'valid-utf8', 'valid-utf16', 'decoded-empty', 'wrong-name-class'):
        with pikepdf.open(SEED) as pdf:
            source = next(spec for spec in pdf.Root.AF if spec.AFRelationship == pikepdf.Name.Source)
            if name.startswith('unreadable'):
                value = pikepdf.String(b'\xef\xbb\xbf' + (b'\xed\xa0\x80' if name == 'unreadable-surrogate' else b'\xff'))
            elif name == 'valid-utf8':
                value = pikepdf.String(b'\xef\xbb\xbfcompanion.txt')
            elif name == 'valid-utf16':
                value = pikepdf.String(b'\xfe\xff' + 'companion.txt'.encode('utf-16-be'))
            elif name == 'decoded-empty':
                value = pikepdf.String('')
            else:
                value = pikepdf.Name.WrongClass
            source.RF.UF[0] = value
            if name == 'unreadable-bad-pair':
                source.RF.UF.extend([pikepdf.Name.Bad, source.RF.UF[1]])
            if name == 'unreadable-revision':
                text = pdf.Root.Metadata.read_bytes().replace(b'<pdfmailid:rev>2024</pdfmailid:rev>', b'<pdfmailid:rev>2023</pdfmailid:rev>')
                pdf.Root.Metadata.write(text)
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        failures = (['SOURCE_FILE_SET'] if name == 'unreadable-bad-pair' else
                    ['EAPDF_REV'] if name == 'unreadable-revision' else
                    ['ASSET_MATCH', 'SOURCE_FILE_SET'] if name in ('decoded-empty', 'wrong-name-class') else [])
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                          expected_status='FAIL' if failures else 'INCOMPLETE' if name.startswith('unreadable') else 'PASS',
                          expected_failures=failures))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF9.1/9.4; ISO32000-2 Table43 and section7.11.4.2 RF filename/stream pairs; unavailable decoding is distinct from known missing evidence.',
        boundary='RF pair Unicode decoder guards, independent metadata/stream checks and known violations; no general file-specification text-decoding or original inventory certification.',
        seed=str(SEED.relative_to(ROOT)), seed_sha256=hashlib.sha256(SEED.read_bytes()).hexdigest(), cases=cases), indent=2) + '\n')


if __name__ == '__main__':
    main()
