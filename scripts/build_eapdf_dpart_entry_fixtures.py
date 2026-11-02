"""Optional DPart entries with valid, invalid and genuinely unavailable controls."""
import hashlib,json
from pathlib import Path
import pikepdf
import sys
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
import validate_eapdf as v
OUT=ROOT/'tests/fixtures/eapdf/validator-dpart-entries'
SEED=ROOT/'tests/fixtures/eapdf/validator-operands/valid.pdf'

def main():
    OUT.mkdir(parents=True,exist_ok=True)
    invalid=('root-type-text','node-type-text','root-type-wrong','node-type-wrong','record-level-boolean','node-names-text','node-names-count','node-name-space')
    unavailable=('node-name-xmlbad','node-name-unreadable')
    recipes=list(invalid)+list(unavailable)+['valid','optional-omitted','valid-names','unicode-names']
    cases=[]
    for name in recipes:
        with pikepdf.Pdf.open(SEED) as pdf:
            root,node,nodes=v.get_dpart_nodes(pdf)
            depths={}
            for item in nodes:depths[item['objgen']]=0 if item['parent'] is None else depths[v.objgen(item['parent'])]+1
            count=max(depths.values())+1
            labels=pikepdf.Array([pikepdf.Name('/Level'+str(i)) for i in range(count)])
            if name.startswith('node-name') or name in ('valid-names','unicode-names'):root.NodeNameList=labels
            if name=='root-type-text':root.Type=pikepdf.String('DPartRoot')
            elif name=='node-type-text':
                for item in nodes:item['node'].Type=pikepdf.String('DPart')
            elif name=='root-type-wrong':root.Type=pikepdf.Name('/Other')
            elif name=='node-type-wrong':nodes[-1]['node'].Type=pikepdf.Name('/Other')
            elif name=='record-level-boolean':root.RecordLevel=False
            elif name=='node-names-text':root.NodeNameList=pikepdf.Array([pikepdf.String(str(x).lstrip('/')) for x in labels])
            elif name=='node-names-count':root.NodeNameList=pikepdf.Array(list(labels)[:-1])
            elif name=='node-name-space':root.NodeNameList[0]=pikepdf.Name('/bad space')
            elif name=='node-name-xmlbad':root.NodeNameList[0]=pikepdf.Name('/1Invalid')
            elif name=='node-name-unreadable':root.NodeNameList[0]=pikepdf.Object.parse(b'/#ff')
            elif name=='optional-omitted':
                for item in [root]+[x['node'] for x in nodes]:
                    if '/Type' in item:del item['/Type']
                for key in ('/RecordLevel','/NodeNameList'):
                    if key in root:del root[key]
            elif name=='valid-names':root.RecordLevel=1
            elif name=='unicode-names':root.NodeNameList=pikepdf.Array([pikepdf.Name('/层级'+str(i)) for i in range(count)])
            path=OUT/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
        status='FAIL' if name in invalid else 'INCOMPLETE' if name in unavailable else 'PASS'
        cases.append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),expected_status=status,expected_failures=['DPART_OBJECT_STRUCTURE'] if name in invalid else []))
    (OUT/'expectations.json').write_text(json.dumps(dict(authority='ISO32000-2:2020 Tables408/409, sections7.3.5 and14.12.4.1; XML1.0 Fifth Edition Name/NameChar/Nmtoken',seed_sha256=hashlib.sha256(SEED.read_bytes()).hexdigest(),boundary='Optional omissions remain valid. Node labels that are definitely invalid under both XML Name and NMTOKEN fail; NMTOKEN-only/undecodable cases remain unavailable. RecordLevel range/meaning and broader DPart metadata errata are separate.',cases=cases),indent=2)+'\n')
    print('Built fourteen optional DPart entry controls.')

if __name__=='__main__':main()
