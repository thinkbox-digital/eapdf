"""Retain source fidelity recommendations with unreadable and valid encoded V data."""
import hashlib,json,re,sys
from pathlib import Path
import pikepdf
from build_eapdf_message_owner_fixtures import structure_elements
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
import validate_eapdf as v
OUT=ROOT/'tests/fixtures/eapdf/validator-source-text-decoding'
SEED=ROOT/'tests/fixtures/eapdf/validator-operands/valid.pdf'
def main():
 OUT.mkdir(parents=True,exist_ok=True)
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
   path=OUT/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
  legal=name!='wrong-name-subject'
  level='N/A' if not legal else 'WARN' if name.startswith('unreadable') or name=='readable-subject-mismatch' else 'PASS'
  comparison='unavailable' if name.startswith('unreadable') else 'different' if name=='readable-subject-mismatch' else 'equal' if legal else None
  rows.append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),expected_status='PASS' if legal else 'FAIL',expected_failures=[] if legal else ['USERPROPS_TYPE'],source_name=key,source_level=level,source_comparison=comparison))
 (OUT/'expectations.json').write_text(json.dumps(dict(authority='EA9.7 source attribute V fidelity SHOULD NOT be modified; Table12 field types retain separate mandatory checks',source_files={str(SEED.relative_to(ROOT)):hashlib.sha256(SEED.read_bytes()).hexdigest()},boundary='Unreadable header values leave SHOULD source comparisons unavailable without new mandatory incompleteness. Preserve invalid date/type handling, owner/source uncertainty and valid encoded/readable comparisons. Source/rendering and full text encoding semantics are not certified.',cases=rows),indent=2)+'\n')
 print('Built eight source text decoding controls.')

if __name__=='__main__':main()
