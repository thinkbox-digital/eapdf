"""Observe optional DPart dictionary entry types without changing their applicability."""
import hashlib,json,os,sys
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2')
sys.path.insert(0,str(ROOT/'.eapdf-verification-v35'))
import validate_eapdf as v
rows=json.loads((HERE/'results.json').read_text())
for name in ('node-names-text','node-names-count','node-name-xmlbad'):
    with pikepdf.Pdf.open(ROOT/'tests/fixtures/eapdf/validator-operands/valid.pdf') as pdf:
        root,node,nodes=v.get_dpart_nodes(pdf)
        depths={}
        for item in nodes:depths[item['objgen']]=0 if item['parent'] is None else depths[v.objgen(item['parent'])]+1
        labels=pikepdf.Array([pikepdf.Name('/Level'+str(i)) for i in range(max(depths.values())+1)])
        root.NodeNameList=labels
        if name=='root-type-text':root.Type=pikepdf.String('DPartRoot')
        elif name=='node-type-text':
            for item in nodes:item['node'].Type=pikepdf.String('DPart')
        elif name=='root-type-wrong':root.Type=pikepdf.Name('/Other')
        elif name=='node-type-wrong':nodes[-1]['node'].Type=pikepdf.Name('/Other')
        elif name=='record-level-boolean':root.RecordLevel=False
        elif name=='node-names-text':root.NodeNameList=pikepdf.Array([pikepdf.String(str(x).lstrip('/')) for x in root.NodeNameList])
        elif name=='node-names-count':root.NodeNameList=pikepdf.Array(list(root.NodeNameList)[:-1])
        elif name=='node-name-xmlbad':root.NodeNameList[0]=pikepdf.Name('/1Invalid')
        path=HERE/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
    result=v.validate_one(str(path),write_html=False)
    (HERE/(name+'.json')).write_text(json.dumps(result,indent=2,default=str)+'\n')
    row=dict(file=str(path.relative_to(ROOT)),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),status=result['summary']['status'],pdfa=result['pdfa']['status'],iso=next(i['level'] for i in result['items'] if i['code']=='ISO32005_UPSTREAM'),failures=sorted({i['code'] for i in result['items'] if i['level']=='FAIL'}))
    rows.append(row);(HERE/'results.json').write_text(json.dumps(rows,indent=2)+'\n');print(row,flush=True)
