"""Run actual CLI/isolated-worker checks from immutable V17."""
from pathlib import Path
import json
import shutil
import subprocess
import sys

sys.path.insert(0,str(Path.cwd()))
import validator_web as web
OUT=Path('../docs/improvement/validator-17/entry-points-final')
OUT.mkdir(exist_ok=True)
rows=[]
cases=[('validator-attachment-owners',name,failures,status) for name,failures,status in
       [('message-id-only',[],'PASS'),('optional-dpm-id-omitted',[],'PASS'),('message-id-only-native-alias',[],'PASS'),
        ('wrong-owner',['ATTACHMENT_ANNOTATIONS_PRESENT','ATTACHMENT_XMP_MATCH','MESSAGE_ATTACHMENT_COUNT_XMP'],'FAIL'),
        ('guid-primary-wrong-id',['ATTACHMENT_XMP_MATCH'],'FAIL'),
        ('source-omitted-message-id',[],'PASS'),
        ('source-omitted-missing-count',['MESSAGE_ATTACHMENT_COUNT_XMP'],'FAIL'),
        ('source-omitted-zero-count',['MESSAGE_ATTACHMENT_COUNT_XMP'],'FAIL')]]
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
OUT.with_name('entry-points-final-results.json').write_text(json.dumps(rows,indent=2)+'\n')

assert len(rows)==16
