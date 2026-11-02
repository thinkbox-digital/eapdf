"""Observe mandatory standard-field shapes without changing the validator."""
import hashlib,json,os,re,sys
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[4]
HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2')
sys.path.insert(0,str(ROOT/'.eapdf-verification-v32'))
import validate_eapdf as v
seed=ROOT/'tests/fixtures/eapdf/validator-operands/valid.pdf'
recipes=[('creator-bag','dc:creator',None),('producer-uri','pdf:Producer','<pdf:Producer rdf:resource="urn:producer"/>'),('createdate-uri','xmp:CreateDate','<xmp:CreateDate rdf:resource="urn:date"/>'),('createdate-array','xmp:CreateDate','<xmp:CreateDate><rdf:Seq><rdf:li>2026-10-20T12:00:00Z</rdf:li></rdf:Seq></xmp:CreateDate>')]
rows=[]
for name,tag,replacement in recipes:
 with pikepdf.Pdf.open(seed) as pdf:
  data=pdf.Root.Metadata.read_bytes().decode('utf-8')
  old=re.search('<'+tag+r'\b.*?</'+tag+'>',data,re.S).group()
  if replacement is None:replacement=old.replace('rdf:Seq','rdf:Bag')
  data=data.replace(old,replacement)
  pdf.Root.Metadata.write(data.encode('utf-8'))
  path=HERE/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
 result=v.validate_one(str(path),write_html=False)
 (HERE/(name+'.json')).write_text(json.dumps(result,indent=2,default=str)+'\n')
 row=dict(file=str(path.relative_to(ROOT)),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),status=result['summary']['status'],pdfa=result['pdfa']['status'],iso=next(i['level'] for i in result['items'] if i['code']=='ISO32005_UPSTREAM'),findings=[i for i in result['items'] if i['code'] in ('XMP_CREATOR','XMP_PRODUCER','XMP_CREATEDATE')])
 rows.append(row);(HERE/'results.json').write_text(json.dumps(rows,indent=2)+'\n');print(row,flush=True)
