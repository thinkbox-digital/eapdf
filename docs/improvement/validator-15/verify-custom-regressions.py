"""All current fixtures through custom validation; no new PDF/A/ISO run claim."""
from pathlib import Path
import importlib.util
import json

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
engines={}
for label,snapshot in [('before','.eapdf-verification-v14-accepted'),('after','.eapdf-verification-v15')]:
    spec=importlib.util.spec_from_file_location(label,ROOT/snapshot/'validate_eapdf.py')
    engine=importlib.util.module_from_spec(spec);spec.loader.exec_module(engine)
    engines[label]=engine
rows=[]
for manifest in sorted((ROOT/'.eapdf-verification-v15/tests/fixtures/eapdf').rglob('expectations.json')):
    for case in json.loads(manifest.read_text())['cases']:
        path=manifest.parent/case['file']
        row=dict(file=str(path.relative_to(ROOT/'.eapdf-verification-v15')))
        for label,engine in engines.items():
            report=engine.validate(str(path))
            row[label]=dict(status=report.summary()['status'],failures=sorted({item['code'] for item in report.items if item['level']=='FAIL'}))
        rows.append(row)
        if row['before']!=row['after']:print(row,flush=True)
(HERE/'custom-regressions.json').write_text(json.dumps(rows,indent=2)+'\n')
changes=[row for row in rows if row['before']!=row['after']]
assert len(rows)==133 and len(changes)==3,(len(rows),len(changes))
assert {Path(row['file']).name for row in changes}=={'uri.pdf','array.pdf','structure.pdf'}
assert all(row['after']['failures']==['ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID'] for row in changes)
print('All',len(rows),'custom-only cases checked; only the three declared schema failures changed',flush=True)
