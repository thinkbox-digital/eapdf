"""Observe inherited DPart/DPM requirements without using creator behavior as an oracle."""
import hashlib,json,os,sys,traceback
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2')
sys.path.insert(0,str(ROOT/'.eapdf-verification-v36'))
import validate_eapdf as v
rows=[]
for name in ('record-negative','record-outside','metadata-stream','dpm-stream','dpm-nested-stream','dpm-array-stream','dpm-array-null','dpm-value-name-utf8','dpm-key-space','dpm-nested-key-space'):
    with pikepdf.Pdf.open(ROOT/'tests/fixtures/eapdf/validator-operands/valid.pdf') as pdf:
        root,node,nodes=v.get_dpart_nodes(pdf);leaf=nodes[-1]['node']
        if name=='record-negative':root.RecordLevel=-1
        elif name=='record-outside':root.RecordLevel=100
        elif name=='metadata-stream':leaf.Metadata=pdf.Root.Metadata
        elif name=='dpm-stream':leaf.DPM.VendorPolicy=pdf.make_stream(b'policy')
        elif name=='dpm-nested-stream':leaf.DPM.VendorPolicy=pikepdf.Dictionary(Data=pdf.make_stream(b'policy'))
        elif name=='dpm-array-stream':leaf.DPM.VendorPolicy=pikepdf.Array([pdf.make_stream(b'policy')])
        elif name=='dpm-array-null':leaf.DPM.VendorPolicy=pikepdf.Array([None])
        elif name=='dpm-value-name-utf8':leaf.DPM.VendorPolicy=pikepdf.Object.parse(b'/#ff')
        elif name=='dpm-key-space':leaf.DPM['/bad key']='value'
        elif name=='dpm-nested-key-space':leaf.DPM.VendorPolicy=pikepdf.Dictionary({'/bad key':'value'})
        path=HERE/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
    try:
        result=v.validate_one(str(path),write_html=False)
        (HERE/(name+'.json')).write_text(json.dumps(result,indent=2,default=str)+'\n')
        row=dict(file=str(path.relative_to(ROOT)),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),status=result['summary']['status'],pdfa=result['pdfa']['status'],iso=next(i['level'] for i in result['items'] if i['code']=='ISO32005_UPSTREAM'),failures=sorted({i['code'] for i in result['items'] if i['level']=='FAIL'}))
    except Exception as error:
        (HERE/(name+'-error.log')).write_text(traceback.format_exc())
        row=dict(file=str(path.relative_to(ROOT)),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),exception=type(error).__name__)
    rows.append(row);(HERE/'results.json').write_text(json.dumps(rows,indent=2)+'\n');print(row,flush=True)
