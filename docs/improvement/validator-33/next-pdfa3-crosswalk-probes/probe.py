"""Try a full EA-PDF/PDF-A-3U crosswalk control, keeping engine failures visible."""
from datetime import datetime
import hashlib,json,os,re,sys
from pathlib import Path
from xml.etree import ElementTree as ET
import pikepdf
ROOT=Path(__file__).resolve().parents[4]
HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2')
sys.path.insert(0,str(ROOT/'.eapdf-verification-v33'))
import validate_eapdf as v
seed=ROOT/'tests/fixtures/eapdf/validator-operands/valid.pdf'
with pikepdf.Pdf.open(seed) as pdf:
    data=pdf.Root.Metadata.read_bytes().decode('utf-8')
    data,n=re.subn(r'<pdfaid:part>4</pdfaid:part>','<pdfaid:part>3</pdfaid:part>',data);assert n==1
    data,n=re.subn(r'<pdfaid:conformance>F</pdfaid:conformance>','<pdfaid:conformance>U</pdfaid:conformance>',data);assert n==1
    data=re.sub(r'<pdfaid:rev>.*?</pdfaid:rev>','',data,flags=re.S)
    pdf.Root.Metadata.write(data.encode('utf-8'))
    pdf.Root.Version=pikepdf.Name('/1.7')
    x=ET.fromstring(data)
    # Crosswalk mapping is independently specified by the retained Table 7; copying values is only control construction.
    for info,ns,tag in [('Title',v.NS_DC,'title'),('Author',v.NS_DC,'creator'),('Keywords',v.NS_PDF,'Keywords'),('Producer',v.NS_PDF,'Producer')]:
        node=x.find('.//'+v.qn(ns,tag))
        if tag in ('title','creator'):node=node.find('.//'+v.qn(v.NS_RDF,'li'))
        pdf.docinfo['/'+info]=node.text
    path=HERE/'candidate-3u.pdf';pdf.save(path,force_version='1.7',static_id=True,fix_metadata_version=False)
result=v.validate_one(str(path),write_html=False)
(HERE/'candidate-3u.json').write_text(json.dumps(result,indent=2,default=str)+'\n')
row=dict(file=str(path.relative_to(ROOT)),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),status=result['summary']['status'],pdfa=result['pdfa']['status'],profile=result['pdfa'].get('profile_name'),iso=next(i['level'] for i in result['items'] if i['code']=='ISO32005_UPSTREAM'),pdfa_failures=result['pdfa'].get('rule_summaries'),failures=[i for i in result['items'] if i['level']=='FAIL'])
(HERE/'results.json').write_text(json.dumps(row,indent=2)+'\n');print(row,flush=True)
