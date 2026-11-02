"""Run from frozen V14; two independent complete-validation workers."""
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path
import hashlib
import json
import sys

sys.path.insert(0,str(Path.cwd()))
import validate_eapdf as v
OUT=Path('../docs/improvement/validator-14/retained-corpus-accepted')


def check(job):
    family, case = job
    path = Path('tests/fixtures/eapdf') / family / case['file']
    assert hashlib.sha256(path.read_bytes()).hexdigest() == case['sha256']
    result=v.validate_one(str(path),write_html=False)
    (OUT/(family+'--'+path.name+'.json')).write_text(json.dumps(result,indent=2,default=str))
    failures=sorted({item['code'] for item in result['items'] if item['level']=='FAIL'})
    expected=sorted(set(case['expected_failures']+case.get('expected_upstream_failures',[])))
    iso=next(item for item in result['items'] if item['code']=='ISO32005_UPSTREAM')
    clauses=sorted(rule['clause'] for rule in (iso.get('details') or {}).get('rule_summaries',[]))
    return dict(file=str(path),sha256=case['sha256'],pdfa=result['pdfa']['status'],status=result['summary']['status'],
        failures=failures,expected=expected,iso=iso['level'],iso_clauses=clauses,
        expected_status=case.get('expected_status','FAIL' if expected else 'PASS'),
        clauses_match=clauses==sorted(case['expected_iso_clauses']) if 'expected_iso_clauses' in case else True,
        unavailable_match=all(any(item['code']==code and item['level']=='WARN' for item in result['items'])
                              for code in case.get('required_unavailable_codes',[])))


if __name__=='__main__':
    OUT.mkdir(exist_ok=True)
    jobs=[(str(manifest.parent.relative_to(Path('tests/fixtures/eapdf'))),case)
          for manifest in sorted(Path('tests/fixtures/eapdf').rglob('expectations.json'))
          for case in json.loads(manifest.read_text())['cases']]
    rows=[]
    with ProcessPoolExecutor(max_workers=2) as pool:
        for future in as_completed([pool.submit(check,job) for job in jobs]):
            row=future.result()
            rows.append(row)
            print(row,flush=True)
    rows.sort(key=lambda row:row['file'])
    OUT.with_name('retained-corpus-accepted-results.json').write_text(json.dumps(rows,indent=2)+'\n')
    assert len(rows)==125
    assert all(row['pdfa']=='PASS' and row['failures']==row['expected'] and row['status']==row['expected_status']
               and row['clauses_match'] and row['unavailable_match'] for row in rows)
    print('All',len(rows),'frozen complete-pipeline outcomes match',flush=True)
