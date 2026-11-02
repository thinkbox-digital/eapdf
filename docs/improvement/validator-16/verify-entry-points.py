"""Run actual CLI/isolated-worker checks from immutable V16."""
from pathlib import Path
import json
import shutil
import subprocess
import sys

sys.path.insert(0,str(Path.cwd()))
import validator_web as web
OUT=Path('../docs/improvement/validator-16/entry-points')
OUT.mkdir(exist_ok=True)
rows=[]
cases=[('validator-attachment-evidence',name,failures,status) for name,failures,status in
       [('exact-double-spaces',[],'PASS'),('original-path',[],'INCOMPLETE'),('original-padding',[],'INCOMPLETE'),
        ('native-double-spaces',[],'INCOMPLETE'),('source-name-differs',['ATTACHMENT_XMP_MATCH'],'FAIL'),
        ('alternative-annotation',['ATTACHMENT_ANNOTATIONS_PRESENT'],'FAIL')]]
for family,name,expected,status in cases:
    path=Path('tests/fixtures/eapdf')/family/(name+'.pdf')
    for entry in ('cli','web-worker'):
        if entry=='cli':
            process=subprocess.run([sys.executable,'validate_eapdf.py',str(path),'--json','--html',str(OUT/(name+'.html'))],
                capture_output=True,text=True,timeout=240)
            (OUT/(name+'.stderr')).write_text(process.stderr)
            assert process.returncode in (0,2),(process.returncode,process.stderr)
            result=json.loads(process.stdout)
        else:
            result=web.run_pdf_validation(path.resolve())
            if result.get('html_output'): shutil.copy2(result['html_output'],OUT/(name+'-worker.html'))
        (OUT/(name+'-'+entry+'.json')).write_text(json.dumps(result,indent=2,default=str))
        iso=next(item for item in result['items'] if item['code']=='ISO32005_UPSTREAM')
        row=dict(file=path.name,entry=entry,pdfa=result['pdfa']['status'],status=result['summary']['status'],iso=iso['level'],
                 failures=sorted({item['code'] for item in result['items'] if item['level']=='FAIL'}))
        rows.append(row)
        print(row,flush=True)
        assert row['pdfa']==row['iso']=='PASS' and iso['details']['profile_verified']
        assert row['failures']==expected and row['status']==status,row
OUT.with_name('entry-points-results.json').write_text(json.dumps(rows,indent=2)+'\n')
