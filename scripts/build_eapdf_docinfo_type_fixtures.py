"""Raw PDF Info type controls with equivalent valid text counterparts."""
import hashlib,json
from pathlib import Path
from xml.sax.saxutils import escape
import pikepdf
from build_eapdf_docinfo_crosswalk_fixtures import set_property,alt
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'tests/fixtures/eapdf/validator-docinfo-types'
SEED=ROOT/'tests/fixtures/eapdf/validator-docinfo-crosswalk/full-3u.pdf'
FIELDS=[('Title','dc:title','DOCINFO_TITLE_MATCH'),('Subject','dc:description','DOCINFO_SUBJECT_MATCH'),
 ('Author','dc:creator','DOCINFO_AUTHOR_MATCH'),('Creator','xmp:CreatorTool','DOCINFO_CREATOR_MATCH'),
 ('Producer','pdf:Producer','DOCINFO_PRODUCER_MATCH'),('Keywords','pdf:Keywords','DOCINFO_KEYWORDS'),
 ('CreationDate','xmp:CreateDate','DOCINFO_CREATIONDATE_MATCH'),('ModDate','xmp:ModifyDate','DOCINFO_MODDATE_MATCH')]

def main():
    OUT.mkdir(parents=True,exist_ok=True)
    recipes=[(key.lower(),key,tag,code,False) for key,tag,code in FIELDS]
    recipes += [('valid',None,None,None,True),('title-string','Title','dc:title',None,True),
                ('keywords-string','Keywords','pdf:Keywords',None,True),('empty-title','Title','dc:title',None,True)]
    cases=[]
    for name,key,tag,code,valid in recipes:
        with pikepdf.Pdf.open(SEED) as pdf:
            data=pdf.Root.Metadata.read_bytes().decode('utf-8')
            if key:
                value='' if name=='empty-title' else '/EA-PDF' if key=='Keywords' else '2026' if key in ('CreationDate','ModDate') else '42'
                raw=pikepdf.String(value) if valid else pikepdf.Name('/EA-PDF') if key=='Keywords' else 2026 if key in ('CreationDate','ModDate') else 42
                pdf.docinfo['/'+key]=raw
                if tag in ('dc:title','dc:description'):replacement=alt(tag,[('x-default',value)])
                elif tag=='dc:creator':replacement=f'<dc:creator><rdf:Seq><rdf:li>{value}</rdf:li></rdf:Seq></dc:creator>'
                else:replacement=f'<{tag}>{escape(value)}</{tag}>'
                data=set_property(data,tag,replacement);pdf.Root.Metadata.write(data.encode('utf-8'))
            path=OUT/(name+'.pdf');pdf.save(path,static_id=True,force_version='1.7',fix_metadata_version=False)
        cases.append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),expected_status='PASS' if valid else 'FAIL',expected_failures=[] if valid else [code],expected_pdfa_profile='PDF/A-3U validation profile'))
    (OUT/'expectations.json').write_text(json.dumps(dict(authority='EA-PDF9.1 / ISO19005-3:2012 Table7 PDF text/date entry types; ISO32000-2 7.3.9 null dictionary semantics',
        seed=str(SEED.relative_to(ROOT)),seed_sha256=hashlib.sha256(SEED.read_bytes()).hexdigest(),boundary='Only raw PDF string types change. Existing text/date comparison policy is retained; PDF/A-3 generic Info dictionary handling does not catch these controls.',cases=cases),indent=2)+'\n')
    print('Built twelve raw Info type/valid-string controls.')

if __name__=='__main__':main()
