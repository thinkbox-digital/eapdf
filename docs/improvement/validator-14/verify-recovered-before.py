"""Reproduce repaired cases with prior immutable engines on identical PDF bytes."""
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path
import hashlib
import importlib.util
import json
import sys

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
OUT = HERE / 'recovered-before-reports'
OUT.mkdir(exist_ok=True)


def check(job):
    family, snapshot, case = job
    spec = importlib.util.spec_from_file_location('before_engine', ROOT / snapshot / 'validate_eapdf.py')
    engine = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(engine)
    path = ROOT / 'tests/fixtures/eapdf' / family / case['file']
    assert hashlib.sha256(path.read_bytes()).hexdigest() == case['sha256']
    result = engine.validate_one(str(path), write_html=False)
    (OUT / (family + '--' + case['file'] + '.json')).write_text(json.dumps(result, indent=2, default=str))
    iso = next(item for item in result['items'] if item['code'] == 'ISO32005_UPSTREAM')
    return dict(file=str(path.relative_to(ROOT)), snapshot=snapshot, sha256=case['sha256'],
                pdfa=result['pdfa']['status'], status=result['summary']['status'], iso=iso['level'],
                failures=sorted({item['code'] for item in result['items'] if item['level'] == 'FAIL'}))


if __name__ == '__main__':
    jobs=[]
    for family, snapshot in [('validator-attachment-streams', '.eapdf-verification-v13-final'),
                             ('validator-attachment-scope', '.eapdf-verification-v14-final')]:
        for case in json.loads((ROOT / 'tests/fixtures/eapdf' / family / 'expectations.json').read_text())['cases']:
            jobs.append((family, snapshot, case))
    rows=[]
    with ProcessPoolExecutor(max_workers=2) as pool:
        for future in as_completed([pool.submit(check, job) for job in jobs]):
            row=future.result(); rows.append(row); print(row, flush=True)
    rows.sort(key=lambda row: row['file'])
    (HERE / 'recovered-before-results.json').write_text(json.dumps(rows, indent=2)+'\n')
    assert all(row['pdfa'] == row['iso'] == 'PASS' for row in rows)
