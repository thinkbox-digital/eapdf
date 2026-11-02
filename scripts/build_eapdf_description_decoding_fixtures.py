"""DPM description decoding, known empty/classes and independent EA failure."""
import hashlib,json
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'tests/fixtures/eapdf/validator-description-decoding'
SEED=ROOT/'tests/fixtures/eapdf/validator-contentset-parent/nested-single.pdf'

def leaves(pdf):
 def walk(node):
  parts=node.get('/DParts',[])
  children=[child for group in parts for child in (group if isinstance(group,pikepdf.Array) else [group])]
  if not children:yield node
  for child in children:yield from walk(child)
 return list(walk(pdf.Root.DPartRoot.DPartRootNode))

def main():
 OUT.mkdir(parents=True,exist_ok=True);rows=[]
 for name in ('unreadable-utf8','unreadable-utf8-surrogate','valid-utf8','valid-utf16','empty','wrong-name','utf16-decoded-empty','unreadable-with-revision-failure'):
  path=OUT/(name+'.pdf')
  with pikepdf.open(SEED) as pdf:
   nodes=leaves(pdf);leaf=next(n for n in nodes if n.DPM.Mail_ContentSetType==pikepdf.Name.BodyRendering)
   value=pikepdf.String(b'\xef\xbb\xbf\xff') if name.startswith('unreadable') else pikepdf.String(b'\xef\xbb\xbf'+'Résumé body'.encode()) if name=='valid-utf8' else pikepdf.String(b'\xfe\xff'+'Résumé body'.encode('utf-16be')) if name=='valid-utf16' else pikepdf.Name('/Body') if name=='wrong-name' else pikepdf.String(b'\xfe\xff\xd8\x00') if name=='utf16-decoded-empty' else pikepdf.String('')
   if name=='unreadable-utf8-surrogate':value=pikepdf.String(b'\xef\xbb\xbf\xed\xa0\x80')
   leaf.DPM.Mail_Desc=value
   if name=='unreadable-with-revision-failure':
    data=pdf.Root.Metadata.read_bytes();changed=data.replace(b'<pdfmailid:rev>2024</pdfmailid:rev>',b'<pdfmailid:rev>2023</pdfmailid:rev>');assert data!=changed;pdf.Root.Metadata.write(changed)
   pdf.save(path,static_id=True,fix_metadata_version=False)
   unchanged_desc=bool(len(nodes)>1)
  level='WARN' if name.startswith('unreadable') else 'PASS' if name.startswith('valid') else 'FAIL'
  failures=['EAPDF_REV'] if name=='unreadable-with-revision-failure' else ['DPM_DESC_REQUIRED'] if level=='FAIL' else []
  rows.append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),expected_status='FAIL' if failures else 'INCOMPLETE' if level=='WARN' else 'PASS',expected_failures=failures,expected_description_level=level,expected_description_levels=sorted({level}|({'PASS'} if unchanged_desc else set()))))
 (OUT/'expectations.json').write_text(json.dumps(dict(authority='EA-PDF1.0 Table8 human-readable Mail_Desc text string; actual PDF String decoding and V35 object-class scope',boundary='Unreadable text is unavailable, not certified valid or empty. Known empty/wrong class and independent revision failures remain.',source_files={str(SEED.relative_to(ROOT)):hashlib.sha256(SEED.read_bytes()).hexdigest()},cases=rows),indent=2)+'\n')
 print('Built eight DPM description controls.')
if __name__=='__main__':main()
