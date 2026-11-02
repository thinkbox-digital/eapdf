import importlib.util,json,os
from pathlib import Path
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2')
spec=importlib.util.spec_from_file_location('prior_art',ROOT/'.eapdf-verification-v54/validate_eapdf.py');v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)
for c in json.loads((ROOT/'tests/fixtures/eapdf/validator-art-usage/expectations.json').read_text())['cases']:
 path=ROOT/'tests/fixtures/eapdf/validator-art-usage'/c['file']; out=HERE/(path.stem+'.json')
 if out.exists():continue
 r=v.validate_one(str(path),write_html=False);out.write_text(json.dumps(r,indent=2,default=str)+'\n')
 print(path.stem,r['summary']['status'],r['pdfa']['status'],[(i['code'],i['level']) for i in r['items'] if i['level']=='FAIL'],flush=True)
