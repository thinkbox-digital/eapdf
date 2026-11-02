"""PDF/A-3A/3U controls for the EA-PDF Info/XMP crosswalk."""
from datetime import datetime
import hashlib,json,re
from pathlib import Path
from xml.etree import ElementTree as ET
from xml.sax.saxutils import escape
import pikepdf
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'tests/fixtures/eapdf/validator-docinfo-crosswalk'
SEED=OUT/'_source-3u.pdf'
NS=dict(rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#',dc='http://purl.org/dc/elements/1.1/',pdf='http://ns.adobe.com/pdf/1.3/',xmp='http://ns.adobe.com/xap/1.0/')

def set_property(data,tag,value):
    pattern='<'+tag+r'\b.*?</'+tag+'>'
    if re.search(pattern,data,re.S):return re.sub(pattern,lambda _:value,data,flags=re.S)
    prefix=tag.split(':')[0]
    description=f'<rdf:Description rdf:about="" xmlns:{prefix}="{NS[prefix]}">{value}</rdf:Description>'
    return data.replace('</rdf:RDF>',description+'</rdf:RDF>')

def alt(tag,items):return f'<{tag}><rdf:Alt>'+''.join(f'<rdf:li xml:lang="{lang}">{escape(value)}</rdf:li>' for lang,value in items)+f'</rdf:Alt></{tag}>'

def main():
    cases=[]
    failures={'title-localized':['DOCINFO_TITLE_MATCH'],'subject-localized':['DOCINFO_SUBJECT_MATCH'],
        'keywords-differ':['DOCINFO_KEYWORDS'],'keywords-missing':['DOCINFO_KEYWORDS','XMP_KEYWORDS'],
        'info-missing':['DOCINFO_KEYWORDS','DOCINFO_PRESENT']}
    names=('optional-3u','optional-3a','full-3u','full-3a','title-localized','title-default-later',
           'title-empty-default','subject-localized','subject-default-later','keywords-differ','keywords-missing','info-missing')
    for name in names:
        with pikepdf.Pdf.open(SEED) as pdf:
            data=pdf.Root.Metadata.read_bytes().decode('utf-8')
            # PDF1.7 controls do not assert conformance to the PDF2.0 inclusion standard.
            data=re.sub(r'<pdfd:declarations\b.*?</pdfd:declarations>','',data,flags=re.S)
            if name.endswith('-3a'):data=data.replace('<pdfaid:conformance>U</pdfaid:conformance>','<pdfaid:conformance>A</pdfaid:conformance>')
            if not name.startswith('optional-'):
                data=set_property(data,'dc:description',alt('dc:description',[('x-default','Archive description')]))
                data=set_property(data,'xmp:CreatorTool','<xmp:CreatorTool>Independent fixture builder</xmp:CreatorTool>')
                data=set_property(data,'xmp:ModifyDate','<xmp:ModifyDate>2026-10-20T12:00:00Z</xmp:ModifyDate>')
                x=ET.fromstring(data)
                created=x.find('.//xmp:CreateDate',NS).text
                pdf.docinfo.Subject='Archive description'
                pdf.docinfo.Creator='Independent fixture builder'
                pdf.docinfo.CreationDate=datetime.fromisoformat(created.replace('Z','+00:00')).strftime('D:%Y%m%d%H%M%SZ')
                pdf.docinfo.ModDate='D:20261020120000Z'
            title=str(pdf.docinfo.Title)
            if name=='title-localized':
                pdf.docinfo.Title='Localized title';data=set_property(data,'dc:title',alt('dc:title',[('fr','Localized title')]))
            elif name=='title-default-later':data=set_property(data,'dc:title',alt('dc:title',[('fr','Titre'),('x-default',title)]))
            elif name=='title-empty-default':
                pdf.docinfo.Title='';data=set_property(data,'dc:title',alt('dc:title',[('x-default',''),('fr','Titre')]))
            elif name=='subject-localized':
                pdf.docinfo.Subject='Localized description';data=set_property(data,'dc:description',alt('dc:description',[('fr','Localized description')]))
            elif name=='subject-default-later':data=set_property(data,'dc:description',alt('dc:description',[('fr','Description traduite'),('x-default','Archive description')]))
            elif name=='keywords-differ':pdf.docinfo.Keywords='EA-PDF; different keywords'
            elif name=='keywords-missing':data=re.sub(r'<pdf:Keywords\b.*?</pdf:Keywords>','',data,flags=re.S)
            elif name=='info-missing':del pdf.trailer['/Info']
            pdf.Root.Metadata.write(data.encode('utf-8'))
            path=OUT/(name+'.pdf');pdf.save(path,static_id=True,force_version='1.7',fix_metadata_version=False)
        codes=failures.get(name,[])
        cases.append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),expected_status='FAIL' if codes else 'PASS',expected_failures=codes,
            expected_pdfa_profile='PDF/A-3A validation profile' if name.endswith('-3a') else 'PDF/A-3U validation profile'))
    (OUT/'expectations.json').write_text(json.dumps(dict(authority='EA-PDF9.1 printed p61; ISO19005-3:2012 section6.6.3/Table7 printed p22',
        seed_sha256=hashlib.sha256(SEED.read_bytes()).hexdigest(),
        seed_recipe=['docs/improvement/validator-33/next-pdfa3-crosswalk-probes/probe.py','docs/improvement/validator-33/next-pdfa3-crosswalk-probes/add-schemas.py'],
        boundary='PDF/A-3 alone permits inconsistent Info/XMP; EA-PDF makes the crosswalk mandatory. Later-default controls assess language selection, not generic XMP ordering. ISO32005 is correctly inapplicable to these PDF1.7 inputs. Initial source/schema attempts remain historical failures.',cases=cases),indent=2)+'\n')
    print('Built twelve independent PDF/A-3 crosswalk controls.')

if __name__=='__main__':main()
