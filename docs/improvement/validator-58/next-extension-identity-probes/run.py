import importlib.util,json,os
from pathlib import Path
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2')
s=importlib.util.spec_from_file_location('before_ext',ROOT/'.eapdf-verification-v58/validate_eapdf.py');v=importlib.util.module_from_spec(s);s.loader.exec_module(v)
for c in json.loads((HERE/'expectations.json').read_text())['cases']:
 p=HERE/c['file'];out=HERE/(p.stem+'.json')
 if out.exists():continue
 r=v.validate_one(str(p),write_html=False);out.write_text(json.dumps(r,indent=2,default=str)+'\n');print(p.stem,r['summary']['status'],r['pdfa']['status'],[(i['code'],i['level']) for i in r['items'] if i['level']=='FAIL'],flush=True)
