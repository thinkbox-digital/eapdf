"""Observe exact property names, raw ASCII bytes and owner-name object types."""
import hashlib,json,os,sys
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2');sys.path.insert(0,str(ROOT/'.eapdf-verification-v39'))
import validate_eapdf as v
rows=[]
for name in ('guid-name-leading-space','guid-name-trailing-space','guid-name-utf16','guid-name-utf8-bom','attribute-owner-text'):
 with pikepdf.open(ROOT/'tests/fixtures/eapdf/validator-operands/valid.pdf') as pdf:
  pending=[pdf.Root.StructTreeRoot.K]
  while pending:
   element=pending.pop()
   if not isinstance(element,pikepdf.Dictionary):continue
   if element.get('/S')==pikepdf.Name('/Mail_Message'):
    prop=next(p for p in element.A.P if str(p.N)=='Mail_GUID')
    if name=='guid-name-leading-space':prop.N=' Mail_GUID'
    elif name=='guid-name-trailing-space':prop.N='Mail_GUID '
    elif name=='guid-name-utf16':prop.N=pikepdf.String(b'\xfe\xff'+'Mail_GUID'.encode('utf-16be'))
    elif name=='guid-name-utf8-bom':prop.N=pikepdf.String(b'\xef\xbb\xbfMail_GUID')
    elif name=='attribute-owner-text':element.A.O=pikepdf.String('UserProperties')
    break
   children=element.get('/K',[]);pending.extend(list(children) if isinstance(children,pikepdf.Array) else [children])
  path=HERE/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
 result=v.validate_one(str(path),write_html=False);(HERE/(name+'.json')).write_text(json.dumps(result,indent=2,default=str)+'\n')
 row=dict(file=str(path.relative_to(ROOT)),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),status=result['summary']['status'],pdfa=result['pdfa']['status'],iso=next(i['level'] for i in result['items'] if i['code']=='ISO32005_UPSTREAM'),failures=sorted({i['code'] for i in result['items'] if i['level']=='FAIL'}));rows.append(row);(HERE/'results.json').write_text(json.dumps(rows,indent=2)+'\n');print(row,flush=True)
