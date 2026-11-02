"""Table12 exact N names, ASCII byte encoding and permitted Unicode V values."""
import hashlib
import json
from pathlib import Path

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/fixtures/eapdf/validator-userproperty-names'
SEED = ROOT / 'tests/fixtures/eapdf/validator-operands/valid.pdf'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    wrong_names = ('guid-name-leading-space', 'guid-name-trailing-space', 'guid-name-control-padding')
    wrong_encoding = ('guid-name-utf16', 'guid-name-utf8-bom')
    cases = []
    for name in wrong_names + wrong_encoding + (
            'valid', 'extension-name-spaces', 'unicode-guid-value', 'extension-name-control', 'unicode-extension-value'):
        with pikepdf.open(SEED) as pdf:
            pending = [pdf.Root.StructTreeRoot.K]
            while pending:
                element = pending.pop()
                if not isinstance(element, pikepdf.Dictionary):
                    continue
                if element.get('/S') == pikepdf.Name('/Mail_Message'):
                    prop = next(p for p in element.A.P if str(p.N) == 'Mail_GUID')
                    if name == 'guid-name-leading-space': prop.N = ' Mail_GUID'
                    elif name == 'guid-name-trailing-space': prop.N = 'Mail_GUID '
                    elif name == 'guid-name-control-padding': prop.N = '\tMail_GUID\n'
                    elif name == 'guid-name-utf16': prop.N = pikepdf.String(b'\xfe\xff' + 'Mail_GUID'.encode('utf-16be'))
                    elif name == 'guid-name-utf8-bom': prop.N = pikepdf.String(b'\xef\xbb\xbfMail_GUID')
                    elif name == 'extension-name-spaces':
                        element.A.P.append(pikepdf.Dictionary(N=' Vendor flag ', V=True))
                    elif name == 'unicode-guid-value':
                        prop.V = pikepdf.String(b'\xfe\xff' + str(prop.V).encode('utf-16be'))
                    elif name == 'extension-name-control':
                        element.A.P.append(pikepdf.Dictionary(N='Vendor\tFlag', V=True))
                    elif name == 'unicode-extension-value':
                        element.A.P.append(pikepdf.Dictionary(N='VendorLabel', V='中文 policy'))
                    break
                children = element.get('/K', [])
                pending.extend(list(children) if isinstance(children, pikepdf.Array) else [children])
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        failures = (['MAIL_MESSAGE_GUID_ATTRIBUTE'] if name in wrong_names else
                    ['USERPROPS_NAME_ASCII'] if name in wrong_encoding else [])
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                          expected_status='FAIL' if failures else 'PASS', expected_failures=failures))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF9.7 Table12 N and V entries; Table13 Mail_GUID; ISO32000-2:2020 Table35 and7.9.2.1 string encodings',
        seed_sha256=hashlib.sha256(SEED.read_bytes()).hexdigest(),
        boundary='Known names are matched exactly. Arbitrary unrelated ASCII extension names may retain spaces/control characters; Unicode text values are not restricted to ASCII. Name presence and its separate encoding constraint retain distinct findings.',
        cases=cases), indent=2) + '\n')
    print('Built ten exact-name/ASCII-byte controls.')


if __name__ == '__main__':
    main()
