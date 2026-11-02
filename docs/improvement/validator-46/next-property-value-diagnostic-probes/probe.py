"""Wrong object types must remain data findings even if display decoding fails."""
import hashlib,json,os,sys
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2');sys.path.insert(0,str(ROOT/'.eapdf-verification-v46'));sys.path.insert(0,str(ROOT/'scripts'))
import validate_eapdf as v
from build_eapdf_message_owner_fixtures import structure_elements,guid_property
rows=[]
for name in ('guid-name-value','guid-array-value','subject-name-value','date-name-value',
 'size-name-value','generic-name-value','unicode-string-value','baseline-guid-value',
 'guid-dictionary-value','subject-dictionary-value','generic-dictionary-value'):
 with pikepdf.open(ROOT/'tests/fixtures/eapdf/validator-operands/valid.pdf') as pdf:
  message=next(e for e in structure_elements(pdf) if e.get('/S')==pikepdf.Name('/Mail_Message'))
  if name.startswith('guid-'):
   guid_property(message).V=(pikepdf.Array([pikepdf.Object.parse(b'/Vendor#FF')]) if name=='guid-array-value' else pikepdf.Object.parse(b'<< /Vendor#FF 1 >>') if name=='guid-dictionary-value' else pikepdf.Object.parse(b'/Vendor#FF'))
  elif name!='baseline-guid-value':
   key={'subject-name-value':'Mail_Subject','date-name-value':'Mail_Sent','size-name-value':'Mail_SizeInBytes','generic-name-value':'Mail_X-Extra','unicode-string-value':'Mail_Subject','subject-dictionary-value':'Mail_Subject','generic-dictionary-value':'Mail_X-Extra'}[name]
   value=('Unicode résumé' if name=='unicode-string-value' else pikepdf.Object.parse(b'<< /Vendor#FF 1 >>') if name.endswith('dictionary-value') else pikepdf.Object.parse(b'/Vendor#FF'))
   message.A.P.append(pikepdf.Dictionary(N=key,V=value))
  path=HERE/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
 if '--engines' in sys.argv:result=v.validate_one(str(path),write_html=False)
 else:
  report=v.validate(str(path));result=dict(summary=report.summary(),items=report.items)
 (HERE/(name+'.json')).write_text(json.dumps(result,indent=2,default=str)+'\n')
 row=dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),status=result['summary']['status'],failures=sorted({i['code'] for i in result['items'] if i['level']=='FAIL'}))
 if '--engines' in sys.argv:row.update(pdfa=result['pdfa']['status'],iso=next(i['level'] for i in result['items'] if i['code']=='ISO32005_UPSTREAM'))
 rows.append(row);(HERE/('results.json' if '--engines' in sys.argv else 'custom-results.json')).write_text(json.dumps(rows,indent=2)+'\n');print(row,flush=True)
