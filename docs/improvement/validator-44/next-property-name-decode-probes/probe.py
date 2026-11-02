"""Operational decoding must not replace a known property-name data violation."""
import hashlib,json,sys
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'.eapdf-verification-v44'));sys.path.insert(0,str(ROOT/'scripts'))
import validate_eapdf as v
from build_eapdf_message_owner_fixtures import structure_elements,guid_property
rows=[]
for name in ('name-object-as-property-name','undecodable-name-object','undecodable-text-string'):
 with pikepdf.open(ROOT/'tests/fixtures/eapdf/validator-operands/valid.pdf') as pdf:
  message=next(e for e in structure_elements(pdf) if e.get('/S')==pikepdf.Name('/Mail_Message'))
  prop=guid_property(message)
  prop.N=(pikepdf.Name('/Mail_GUID') if name=='name-object-as-property-name' else pikepdf.Object.parse(b'/Vendor#FF') if name=='undecodable-name-object' else pikepdf.String(b'\xef\xbb\xbf\xff'))
  path=HERE/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
 report=v.validate(str(path));result=dict(summary=report.summary(),items=report.items)
 (HERE/(name+'.json')).write_text(json.dumps(result,indent=2,default=str)+'\n')
 row=dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),status=result['summary']['status'],failures=sorted({i['code'] for i in report.items if i['level']=='FAIL'}));rows.append(row)
 (HERE/'custom-results.json').write_text(json.dumps(rows,indent=2)+'\n');print(row,flush=True)
