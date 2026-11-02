"""Table12 EA fields versus unrelated generic PDF UserProperties V(any)."""
import hashlib,json,os,sys
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2');sys.path.insert(0,str(ROOT/'.eapdf-verification-v47'));sys.path.insert(0,str(ROOT/'scripts'))
import validate_eapdf as v
from build_eapdf_message_owner_fixtures import structure_elements
rows=[]
for name in ('vendor-name-value','vendor-array-value','vendor-dictionary-value','vendor-unreadable-name-value',
 'mail-header-name-value','vendor-unicode-text','vendor-missing-value','vendor-null-value'):
 with pikepdf.open(ROOT/'tests/fixtures/eapdf/validator-operands/valid.pdf') as pdf:
  message=next(e for e in structure_elements(pdf) if e.get('/S')==pikepdf.Name('/Mail_Message'))
  key='Mail_X-Extra' if name=='mail-header-name-value' else 'Vendor_Data'
  if name=='vendor-null-value':prop=pikepdf.Object.parse(b'<< /N (Vendor_Data) /V null >>')
  elif name=='vendor-missing-value':prop=pikepdf.Dictionary(N=key)
  else:
   value=(pikepdf.Array(['one',2,True]) if name=='vendor-array-value' else
     pikepdf.Dictionary(Label='value') if name=='vendor-dictionary-value' else
     pikepdf.Object.parse(b'/Vendor#FF') if name=='vendor-unreadable-name-value' else
     'Unicode résumé' if name=='vendor-unicode-text' else pikepdf.Name('/Example'))
   prop=pikepdf.Dictionary(N=key,V=value)
  message.A.P.append(prop)
  path=HERE/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
 if '--engines' in sys.argv:result=v.validate_one(str(path),write_html=False)
 else:
  report=v.validate(str(path));result=dict(summary=report.summary(),items=report.items)
 (HERE/(name+'.json')).write_text(json.dumps(result,indent=2,default=str)+'\n')
 row=dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),status=result['summary']['status'],failures=sorted({i['code'] for i in result['items'] if i['level']=='FAIL'}))
 if '--engines' in sys.argv:row.update(pdfa=result['pdfa']['status'],iso=next(i['level'] for i in result['items'] if i['code']=='ISO32005_UPSTREAM'))
 rows.append(row);(HERE/('results.json' if '--engines' in sys.argv else 'custom-results.json')).write_text(json.dumps(rows,indent=2)+'\n');print(row,flush=True)
