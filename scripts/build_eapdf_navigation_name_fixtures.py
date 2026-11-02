"""EA-PDF navigation names: independent native-value controls."""
import hashlib
import json
from pathlib import Path

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/fixtures/eapdf/validator-navigation-names'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    seed = ROOT / 'tests/fixtures/eapdf/validator-operands/valid.pdf'
    page_code = 'PROFILE_PAGEMODE_SINGLE_WITH_ATTACHMENTS'
    nfsm_code = 'PROFILE_NFSM_SINGLE_WITH_ATTACHMENTS'
    recipes = [('valid', []), ('page-text', [page_code]), ('nfsm-text', [nfsm_code]),
               ('both-text', [page_code, nfsm_code]), ('wrong-name', [page_code]),
               ('missing-page-mode', ['PAGEMODE_PRESENT', page_code])]
    cases = []
    for name, failures in recipes:
        with pikepdf.Pdf.open(seed) as pdf:
            assert pdf.Root.PageMode == pikepdf.Name.UseAttachments
            assert pdf.Root.ViewerPreferences.NonFullScreenPageMode == pikepdf.Name.UseAttachments
            if name in ('page-text', 'both-text'):
                pdf.Root.PageMode = pikepdf.String('UseAttachments')
            if name in ('nfsm-text', 'both-text'):
                pdf.Root.ViewerPreferences.NonFullScreenPageMode = pikepdf.String('/UseAttachments')
            if name == 'wrong-name':
                pdf.Root.PageMode = pikepdf.Name.UseOutlines
            if name == 'missing-page-mode':
                del pdf.Root.PageMode
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                          expected_status='FAIL' if failures else 'PASS', expected_failures=sorted(failures)))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF 9.1 printed p62; ISO 32000-2:2020 catalog PageMode and ViewerPreferences NonFullScreenPageMode name types',
        seed=str(seed.relative_to(ROOT)), seed_sha256=hashlib.sha256(seed.read_bytes()).hexdigest(),
        boundary='Only native navigation values change; source attachment condition and PDF/A/ISO baseline remain independent',
        cases=cases), indent=2) + '\n')
    print('Built six independent navigation-name controls.')


if __name__ == '__main__':
    main()
