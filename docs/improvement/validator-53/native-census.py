"""Independent native K-path ancestry census; never read tested V to choose ancestry."""
import hashlib,json,sys
from collections import Counter
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'.eapdf-verification-v53'))
import validate_eapdf as v


def inspect(path):
 row=dict(file=str(path.relative_to(ROOT)),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),orphans=[],errors=[])
 with pikepdf.open(path) as pdf:
  report=v.Report(str(path));xroot,ns,_=v.parse_xmp(v.read_metadata_stream(pdf),report)
  row['profile']=v.check_eapdf_xmp(xroot,ns,report) if xroot is not None else ''
  tree=pdf.Root.get('/StructTreeRoot');seen=set();work=0
  def walk(value,ancestors=(),depth=0):
   nonlocal work
   work+=1
   if work>100000 or depth>256:raise ValueError('Native census traversal bound')
   if isinstance(value,pikepdf.Array):
    for child in value:walk(child,ancestors,depth+1)
    return
   if not isinstance(value,pikepdf.Dictionary):return
   identity=tuple(value.objgen)
   if identity!=(0,0):
    if identity in seen:raise ValueError('Shared/cyclic native structure dictionary')
    seen.add(identity)
   tag=value.get('/S')
   if tag==pikepdf.Name('/Mail_ContentSet') and pikepdf.Name('/Mail_Message') not in ancestors:
    attrs,errors=v.structure_attribute_objects(value,tree.get('/ClassMap'))
    values=[v.observed_pdf_value(p.get('/V')) for p in v.parse_userproperties(attrs) if v.userprop_name(p)=='Mail_ContentSetType']
    row['orphans'].append(dict(objgen=list(value.objgen),ancestor_names=[bytes(a).hex() if isinstance(a,pikepdf.Name) else None for a in ancestors],self_types=values,attribute_errors=errors))
   kids=value.get('/K')
   if kids is not None:walk(kids,ancestors+(tag,),depth+1)
  if isinstance(tree,pikepdf.Dictionary):
   try:walk(tree.get('/K'))
   except (ValueError,UnicodeError,pikepdf.PdfError) as error:row['errors'].append(str(error))
 return row

rows=[]
for manifest in sorted((ROOT/'tests/fixtures/eapdf').rglob('expectations.json')):
 for case in json.loads(manifest.read_text())['cases']:
  path=manifest.parent/case['file']
  try:row=inspect(path)
  except Exception as error:row=dict(file=str(path.relative_to(ROOT)),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),orphans=[],errors=[str(error)])
  row['expected_status']=case['expected_status'];rows.append(row)
profiles=[]
for profile in json.loads((ROOT/'docs/improvement/validator-24/profile-diagnosis.json').read_text())['profiles']:
 original=json.loads((ROOT/profile['report']).read_text());row=inspect(Path(original['summary']['file']));row.update(mode=profile['mode'],profile_label=profile['profile'],failures_before=profile['failures']);profiles.append(row)
output=dict(method='Bounded direct K traversal carries ancestor Name objects. Self property values are observed only after ancestry is established; they never choose it. Shared/cyclic traversal is recorded unavailable.',custom=rows,creator=profiles)
(HERE/'native-census.json').write_text(json.dumps(output,indent=2)+'\n')
print('Custom rows',len(rows),'orphan files by profile',dict(Counter(r.get('profile') for r in rows if r['orphans'])), 'errors',sum(bool(r['errors']) for r in rows))
print('Creator orphan files',[(r['mode'],r['profile_label'],len(r['orphans'])) for r in profiles if r['orphans']])
for r in rows:
 if r['orphans']:print(r['file'],r['profile'],r['expected_status'],len(r['orphans']))
