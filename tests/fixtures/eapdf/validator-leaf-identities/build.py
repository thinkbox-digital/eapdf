"""Every supplied identity value must match, including leaf DPM entries."""
import hashlib,json
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[4];OUT=Path(__file__).resolve().parent
SEED=ROOT/'tests/fixtures/eapdf/validator-contentset-parent/nested-subtype.pdf'
def nodes(pdf):
 def walk(node):
  yield node
  for group in node.get('/DParts',[]):
   for child in group if isinstance(group,pikepdf.Array) else [group]:yield from walk(child)
 return list(walk(pdf.Root.DPartRoot.DPartRootNode))
def main():
 rows=[]
 for name in ('leaf-guid-unrelated','leaf-messageid-unrelated','leaf-guid-matching','leaf-messageid-matching','leaf-guid-name','leaf-messageid-name','leaf-guid-unreadable','leaf-messageid-unreadable','leaf-guid-empty','leaf-messageid-empty'):
  p=OUT/(name+'.pdf');guid=name.startswith('leaf-guid-');key='/Mail_GUID' if guid else '/Mail_MessageID'
  with pikepdf.open(SEED) as pdf:
   ns=nodes(pdf);owner=next(n for n in ns if n.get('/DPM',{}).get('/Mail_GUID') is not None)
   front=next(n for n in ns if n.get('/DPM',{}).get('/Mail_ContentSetType')==pikepdf.Name.FrontMatter)
   original=str(owner.DPM[key])
   value=pikepdf.String(b'\xef\xbb\xbf\xff') if name.endswith('unreadable') else pikepdf.Name('/'+original) if name.endswith('name') else pikepdf.String(original) if name.endswith('matching') else pikepdf.String('') if name.endswith('empty') else pikepdf.String('unrelated-value')
   front.DPM[key]=value;pdf.save(p,static_id=True,fix_metadata_version=False)
  level='WARN' if name.endswith('unreadable') else 'PASS' if name.endswith('matching') else 'FAIL';code='DPM_MAIL_GUID_MATCH' if guid else 'DPM_MAIL_MESSAGEID_MATCH';failures=[code] if level=='FAIL' else []
  rows.append(dict(file=p.name,sha256=hashlib.sha256(p.read_bytes()).hexdigest(),expected_status='FAIL' if failures else 'INCOMPLETE' if level=='WARN' else 'PASS',expected_failures=failures,identity_key=key,identity_code=code,expected_leaf_level=level))
 (OUT/'expectations.json').write_text(json.dumps(dict(authority='EA-PDF1.0 following Table7, printedp70: every Mail_MessageID and Mail_GUID value must match a corresponding document XMP property',boundary='Supplied canonical leaf entries are checked; neither identity key is required on a leaf. Current field typing, GUID exact matching and MessageID lexical policy are retained.',cases=rows),indent=2)+'\n')
if __name__=='__main__':main()
