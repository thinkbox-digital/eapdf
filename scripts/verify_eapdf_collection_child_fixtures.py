"""Independent checks of final collection bytes retained by the Java regressions."""
import hashlib
import json
import os
from pathlib import Path
import sys
from xml.etree import ElementTree as ET

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import validate_eapdf as validator


def main():
    output = Path(os.environ.get('EAPDF_CHILD_ACCEPTANCE_FIXTURE_DIR',
                                ROOT / 'docs/improvement/batch-11-corpus')).resolve()
    cases = json.loads((ROOT / 'tests/fixtures/eapdf/batch11/cases.json').read_text())
    results = []
    for case in cases:
        path = output / (case['id'] + '.pdf')
        with pikepdf.Pdf.open(path) as parent:
            files = parent.Root.Names.EmbeddedFiles.Names
            children = [files[index] for index in range(1, len(files), 2)
                        if str(files[index].UF).endswith('.pdf')]
            assert len(children) == 1, case['id']
            child_bytes = children[0].EF.UF.read_bytes()
            # The envelope checksum must describe the replacement, not hide it
            # behind an unrelated outer checksum failure.
            assert bytes(children[0].EF.UF.Params.CheckSum) == hashlib.md5(child_bytes).digest(), case['id']
        from io import BytesIO
        with pikepdf.Pdf.open(BytesIO(child_bytes)) as child:
            xml = ET.fromstring(child.Root.Metadata.read_bytes())
            sent = xml.findall('.//{http://www.pdfa.org/eapdf/ns/meta/}Sent')
            assert bool(sent) == (case['mutation'] != 'missing-sent'), case['id']
            assert ('/DPart' in child.pages[0]) == (case['mutation'] != 'missing-dpart'), case['id']
            annotations = [annotation for page in child.pages for annotation in page.get('/Annots', [])
                           if str(annotation.get('/Subtype')) == '/FileAttachment'
                           and str(annotation.FS.UF) == 'native.txt']
            assert len(annotations) == 1, case['id']
            stream = annotations[0].FS.EF.UF
            checksum_agrees = bytes(stream.Params.CheckSum) == hashlib.md5(stream.read_bytes()).digest()
            assert checksum_agrees == (case['mutation'] != 'bad-attachment-checksum'), case['id']
        report = validator.validate_one(str(path), html_path=str(path.with_suffix('.html')))
        path.with_suffix('.json').write_text(json.dumps(report, indent=2, default=str) + '\n')
        assert report['pdfa']['status'] == 'PASS', (case['id'], report['pdfa'])
        failures = sorted({row['code'] for row in report['items'] if row['level'] == 'FAIL'})
        assert failures == case['failures'], (case['id'], failures)
        assert report['summary']['status'] == case['eapdf'], (case['id'], report['summary'])
        children = [row['details'] for row in report['items'] if row['code'] == 'COLLECTION_CHILD_VALIDATION']
        assert len(children) == 1, (case['id'], children)
        child = children[0]
        child_failures = sorted({row['code'] for row in child['findings'] if row['level'] == 'FAIL'})
        assert child['pdfa_status'] == 'PASS', (case['id'], child['pdfa'])
        assert child['status'] == case['eapdf'] and child_failures == case['child_failures'], (case['id'], child)
        assert child['sha256'] == hashlib.sha256(child_bytes).hexdigest(), case['id']
        results.append(dict(case=case['id'], sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                            pdfa='PASS', eapdf=report['summary']['status'], failures=failures,
                            child_sha256=child['sha256'], child_pdfa='PASS', child_failures=child_failures,
                            expected_result_observed=True))
        print(case['id'], 'PDF/A PASS', report['summary']['status'], child_failures, flush=True)
    (output / 'results.json').write_text(json.dumps(results, indent=2) + '\n')


if __name__ == '__main__':
    main()
