"""Inspect raw Info types independently of the string-coercing comparison API."""
import hashlib,json,os,re,sys
from pathlib import Path
from xml.sax.saxutils import escape
import pikepdf
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2')
sys.path.insert(0,str(ROOT/'.eapdf-verification-v34'))
sys.path.insert(0,str(ROOT/'scripts'))
import validate_eapdf as v
from build_eapdf_docinfo_crosswalk_fixtures import set_property,alt
seed=ROOT/'tests/fixtures/eapdf/validator-docinfo-crosswalk/full-3u.pdf'
rows=[]
for key,tag,kind in [('Title','dc:title','text'),('Subject','dc:description','text'),('Author','dc:creator','text'),('Creator','xmp:CreatorTool','text'),('Producer','pdf:Producer','text'),('Keywords','pdf:Keywords','name'),('CreationDate','xmp:CreateDate','date'),('ModDate','xmp:ModifyDate','date')]:
    with pikepdf.Pdf.open(seed) as pdf:
        value='/EA-PDF' if kind=='name' else '2026' if kind=='date' else '42'
        pdf.docinfo['/'+key]=pikepdf.Name('/EA-PDF') if kind=='name' else 2026 if kind=='date' else 42
        data=pdf.Root.Metadata.read_bytes().decode('utf-8')
        if tag in ('dc:title','dc:description'):replacement=alt(tag,[('x-default',value)])
        elif tag=='dc:creator':replacement=f'<dc:creator><rdf:Seq><rdf:li>{value}</rdf:li></rdf:Seq></dc:creator>'
        else:replacement=f'<{tag}>{escape(value)}</{tag}>'
        data=set_property(data,tag,replacement);pdf.Root.Metadata.write(data.encode('utf-8'))
        path=HERE/(key.lower()+'.pdf');pdf.save(path,static_id=True,force_version='1.7',fix_metadata_version=False)
    result=v.validate_one(str(path),write_html=False)
    (HERE/(key.lower()+'.json')).write_text(json.dumps(result,indent=2,default=str)+'\n')
    row=dict(key=key,file=str(path.relative_to(ROOT)),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),status=result['summary']['status'],pdfa=result['pdfa']['status'],failures=sorted({i['code'] for i in result['items'] if i['level']=='FAIL'}))
    rows.append(row);(HERE/'results.json').write_text(json.dumps(rows,indent=2)+'\n');print(row,flush=True)
