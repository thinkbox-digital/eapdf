import hashlib,importlib.util,json,os,shutil
from pathlib import Path
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2')
spec=importlib.util.spec_from_file_location('before_desc',ROOT/'.eapdf-verification-v55-r2/validate_eapdf.py');v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)
for c in json.loads((ROOT/'tests/fixtures/eapdf/validator-description-decoding/expectations.json').read_text())['cases']:
 p=ROOT/'tests/fixtures/eapdf/validator-description-decoding'/c['file'];out=HERE/(p.stem+'.json')
 if out.exists():continue
 shutil.copyfile(p,HERE/p.name);r=v.validate_one(str(p),write_html=False);out.write_text(json.dumps(r,indent=2,default=str)+'\n');print(p.stem,r['summary']['status'],r['pdfa']['status'],[(i['code'],i['level']) for i in r['items'] if i['level']=='FAIL'],flush=True)
