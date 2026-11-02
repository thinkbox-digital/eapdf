"""Unrelated DPM extension names cannot select EA email identities."""
import hashlib,json
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'tests/fixtures/eapdf/validator-dpm-extension-identity'
SEED=ROOT/'tests/fixtures/eapdf/validator-contentset-parent/nested-single.pdf'
def nodes(pdf):
 def walk(node):
  yield node
  for group in node.get('/DParts',[]):
   for child in group if isinstance(group,pikepdf.Array) else [group]:yield from walk(child)
 return list(walk(pdf.Root.DPartRoot.DPartRootNode))
def main():
 OUT.mkdir(parents=True,exist_ok=True);rows=[]
 for name in ('leaf-message-dash-id','leaf-message-id','leaf-lower-messageid','leaf-generic-name','leaf-generic-array','intermediate-generic-aliases','nested-vendor-alias','canonical-guid-mismatch'):
  path=OUT/(name+'.pdf')
  with pikepdf.open(SEED) as pdf:
   allnodes=nodes(pdf);body=next(n for n in allnodes if n.get('/DPM',{}).get('/Mail_ContentSetType')==pikepdf.Name.BodyRendering)
   owner=next(n for n in allnodes if n.get('/DPM',{}).get('/Mail_GUID') is not None)
   key='Message-ID' if name!='leaf-message-id' and name!='leaf-lower-messageid' else 'MessageID' if name=='leaf-message-id' else 'messageid'
   if name=='intermediate-generic-aliases':
    for alias in ('Message-ID','MessageID','messageid'):owner.DPM['/'+alias]='unrelated extension'
   elif name=='nested-vendor-alias':body.DPM.Vendor_Data=pikepdf.Dictionary({'/Message-ID':'unrelated extension'})
   else:
    body.DPM['/'+key]=pikepdf.Name('/VendorData') if name=='leaf-generic-name' else pikepdf.Array(['vendor',42,True]) if name=='leaf-generic-array' else pikepdf.String('unrelated extension')
   if name=='canonical-guid-mismatch':owner.DPM.Mail_GUID='different-guid'
   pdf.save(path,static_id=True,fix_metadata_version=False)
  failures=['ATTACHMENT_ANNOTATIONS_PRESENT','DPM_MAIL_GUID_MATCH','MAIL_MESSAGE_GUID_MATCH_DPM_XMP'] if name=='canonical-guid-mismatch' else []
  rows.append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),expected_status='FAIL' if failures else 'PASS',expected_failures=failures))
 (OUT/'expectations.json').write_text(json.dumps(dict(authority='EA-PDF1.0 Table7 exact Mail_GUID/Mail_MessageID entries and V35 unrelated DPM extension scope',boundary='Generic extension aliases are retained as data and do not identify an email for EA predicates. Canonical fields and readable contradictions retain their behavior.',cases=rows),indent=2)+'\n')
if __name__=='__main__':main()
