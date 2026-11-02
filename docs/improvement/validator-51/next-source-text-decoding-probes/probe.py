"""Source-value SHOULD comparisons with unreadable and valid encoded strings."""
import hashlib,json,os,re,sys
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2');sys.path.insert(0,str(ROOT/'.eapdf-verification-v51'));sys.path.insert(0,str(ROOT/'scripts'))
import validate_eapdf as v
from build_eapdf_message_owner_fixtures import structure_elements
SEED=ROOT/'tests/fixtures/eapdf/validator-operands/valid.pdf'
rows=[]
for name in ('unreadable-subject','unreadable-raw-sent','unreadable-from','unreadable-generic-header',
             'valid-utf8-subject','valid-utf16-subject','readable-subject-mismatch','wrong-name-subject'):
 with pikepdf.open(SEED) as pdf:
  source=v.summarize_source_emails(v.get_embedded_files(pdf));assert len(source)==1
  raw=source[0]['header_values']['subject'];assert len(raw)==1
  original=v.decoded_source_header(re.sub(r'\r?\n(?=[ \t])','',raw[0]).strip(' \t'))
  message=next(e for e in structure_elements(pdf) if e.get('/S')==pikepdf.Name('/Mail_Message'))
  key={'unreadable-raw-sent':'Mail_Raw_Sent','unreadable-from':'Mail_From','unreadable-generic-header':'Mail_X-Extra'}.get(name,'Mail_Subject')
  if name.startswith('unreadable'):value=pikepdf.String(b'\xef\xbb\xbf\xff')
  elif name=='valid-utf8-subject':value=pikepdf.String(b'\xef\xbb\xbf'+original.encode())
  elif name=='valid-utf16-subject':value=pikepdf.String(b'\xfe\xff'+original.encode('utf-16-be'))
  elif name=='readable-subject-mismatch':value='different source subject'
  else:value=pikepdf.Name('/WrongType')
  prop=next((p for p in message.A.P if str(p.N)==key),None)
  if prop is None:message.A.P.append(pikepdf.Dictionary(N=key,V=value))
  else:prop.V=value
  path=HERE/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
 if '--engines' in sys.argv:result=v.validate_one(str(path),write_html=False)
 else:
  report=v.validate(str(path));result=dict(summary=report.summary(),items=report.items)
 suffix='' if '--engines' in sys.argv else '-custom'
 (HERE/(name+suffix+'.json')).write_text(json.dumps(result,indent=2,default=str)+'\n')
 row=dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),status=result['summary']['status'],failures=sorted({i['code'] for i in result['items'] if i['level']=='FAIL'}))
 if '--engines' in sys.argv:row.update(pdfa=result['pdfa']['status'],iso=next(i['level'] for i in result['items'] if i['code']=='ISO32005_UPSTREAM'))
 rows.append(row);(HERE/('results.json' if '--engines' in sys.argv else 'custom-results.json')).write_text(json.dumps(rows,indent=2)+'\n');print(row,flush=True)
