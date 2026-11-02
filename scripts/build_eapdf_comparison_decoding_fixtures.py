"""Retain group/subtype unknowns and readable comparison contradictions."""
import hashlib,json
from pathlib import Path
import pikepdf
from build_eapdf_message_owner_fixtures import structure_elements
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'tests/fixtures/eapdf/validator-comparison-decoding'
SEEDS=dict(group=ROOT/'tests/fixtures/eapdf/validator-dpart-object-ranges/multi-ordered.pdf',subtype=ROOT/'tests/fixtures/eapdf/validator-active-userproperties/owner-subtype-consistent.pdf')
def main():
 OUT.mkdir(parents=True,exist_ok=True)
 rows=[]
 for family in ('group','subtype'):
  for mode in ('unreadable','unreadable-and-wrong','valid-utf8','valid-utf16'):
   name=family+'-'+mode
   with pikepdf.open(SEEDS[family]) as pdf:
    elems=structure_elements(pdf)
    key='Mail_ContentSetType' if family=='group' else 'Mail_Subtype'
    if family=='group':target=next(e for e in elems if e.get('/S')==pikepdf.Name('/Mail_ContentGroup'))
    else:target=next(e for e in elems if e.get('/S')==pikepdf.Name('/Mail_ContentSet') and any(str(p.N)=='Mail_ContentSetType' and str(p.V)=='FrontMatter' for p in e.A.P))
    prop=next(p for p in target.A.P if str(p.N)==key);original=str(prop.V)
    if mode.startswith('unreadable'):
     prop.V=pikepdf.String(b'\xef\xbb\xbf\xff')
     if mode.endswith('wrong'):target.A.P.append(pikepdf.Dictionary(N=key,V='FrontMatter' if family=='group' else 'application/octet-stream'))
    elif mode=='valid-utf8':prop.V=pikepdf.String(b'\xef\xbb\xbf'+original.encode())
    else:prop.V=pikepdf.String(b'\xfe\xff'+original.encode('utf-16-be'))
    path=OUT/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
   code='MAIL_CONTENTGROUP_CONTENTSETTYPE_MATCH_IF_PRESENT' if family=='group' else 'MAIL_CONTENTSET_SUBTYPE_MATCH_IF_PRESENT'
   status='INCOMPLETE' if mode=='unreadable' else 'FAIL' if mode=='unreadable-and-wrong' else 'PASS'
   rows.append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),expected_status=status,expected_failures=[code] if status=='FAIL' else []))
 (OUT/'expectations.json').write_text(json.dumps(dict(authority='EA Tables12/13 conditional group/subtype correspondence; V41/V42 owner and occurrence review; V43 MIME semantics',source_files={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in SEEDS.values()},boundary='Unreadable comparison values remain unavailable without hiding definite readable contradictions. Preserve presence recommendations, exact opaque group names, MIME semantics and actual string types. Original-source/vendor semantics and XMP group binding are not certified.',cases=rows),indent=2)+'\n')
 print('Built eight conditional comparison decoding controls.')

if __name__=='__main__':main()
