"""Retain V21/V22 custom outcomes and media-rule severity evidence."""
from pathlib import Path
import hashlib
import importlib.util
import json
import sys

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
FROZEN=ROOT if '--preflight' in sys.argv else ROOT/'.eapdf-verification-v22'
sys.path.insert(0,str(FROZEN))
import validate_eapdf as after
spec=importlib.util.spec_from_file_location('before',ROOT/'.eapdf-verification-v21-final/validate_eapdf.py')
before=importlib.util.module_from_spec(spec);spec.loader.exec_module(before)
assert hashlib.sha256(Path(before.__file__).read_bytes()).digest()==hashlib.sha256((HERE/'before-validate_eapdf.py').read_bytes()).digest()
rows=[]
for manifest in sorted((FROZEN/'tests/fixtures/eapdf').rglob('expectations.json')):
    for case in json.loads(manifest.read_text())['cases']:
        path=manifest.parent/case['file']
        row=dict(file=str(path.relative_to(FROZEN)),sha256=hashlib.sha256(path.read_bytes()).hexdigest())
        for label,engine in [('before',before),('after',after)]:
            report=engine.validate(str(path))
            row[label]=dict(status=report.summary()['status'],failures=sorted({i['code'] for i in report.items if i['level']=='FAIL'}))
            order={'N/A':0,'PASS':1,'WARN':2,'FAIL':3}
            row[label+'_media_rules']={code:max((i['level'] for i in report.items if i['code']==code),key=order.get,default='N/A')
                                      for code in ('EMBEDDED_SUBTYPE_MATCH_XMP','SOURCE_MEDIATYPE_PARAMETERS_MATCH')}
        rows.append(row)
        if row['before']!=row['after'] or row['before_media_rules']!=row['after_media_rules']:print(row,flush=True)
(HERE/('preflight-regressions.json' if '--preflight' in sys.argv else 'custom-regressions.json')).write_text(json.dumps(rows,indent=2)+'\n')
print('Compared',len(rows),'custom cases;',sum(r['before']!=r['after'] for r in rows),'status/failure changes;',
      sum(r['before_media_rules']!=r['after_media_rules'] for r in rows),'media-rule severity changes.',flush=True)
