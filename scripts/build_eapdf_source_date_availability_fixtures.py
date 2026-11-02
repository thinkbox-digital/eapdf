"""Source date parser limits are evidence gaps, not invalid originals."""
import hashlib
import io
import json
from pathlib import Path
import re
from xml.etree import ElementTree as ET
import pikepdf
from build_eapdf_empty_raw_fixtures import update_source, qn, META, RDF

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/fixtures/eapdf/validator-source-date-availability'
SEED = ROOT / 'tests/fixtures/eapdf/validator-source-mailbox-data/from-exact.pdf'


def main():
    OUT.mkdir(exist_ok=True)
    recipes = [
        ('negative-zero','Tue, 28 Jan 2003 21:20:54 -0000','2003-01-28T21:20:54Z','PASS',[]),
        ('negative-zero-wrong','Tue, 28 Jan 2003 21:20:54 -0000','2003-01-28T21:20:55Z','FAIL',['MESSAGE_SENT_XMP']),
        ('extended-year','1 Jan 10000 12:00:00 +0000','10000-01-01T12:00:00Z','INCOMPLETE',[]),
        ('extended-year-raw','1 Jan 10000 12:00:00 +0000','10000-01-01T12:00:00Z','INCOMPLETE',[]),
        ('extended-missing-sent','1 Jan 10000 12:00:00 +0000',None,'FAIL',['MESSAGE_SENT_XMP']),
        ('invalid-calendar','29 Feb 10001 12:00:00 +0000','10001-02-28T12:00:00Z','FAIL',['MESSAGE_RAW_SENT_REQUIRED_IF_INVALID']),
        ('wide-offset','1 Jan 2003 12:00:00 +1500','2002-12-31T21:00:00Z','PASS',[]),
        ('wide-offset-wrong','1 Jan 2003 12:00:00 +1500','2003-01-01T12:00:00Z','FAIL',['MESSAGE_SENT_XMP']),
        ('unknown-xmp-zone','Tue, 28 Jan 2003 21:20:54 +0000','2003-01-28T21:20:54','INCOMPLETE',[]),
        ('utc-overflow','31 Dec 9999 23:00:00 -1500','10000-01-01T14:00:00Z','INCOMPLETE',[])]
    cases=[]
    for name, date, sent, status, failures in recipes:
        with pikepdf.open(SEED) as pdf:
            packet=pdf.Root.Metadata.read_bytes()
            for _,(prefix,uri) in ET.iterparse(io.BytesIO(packet),events=['start-ns']):ET.register_namespace(prefix,uri)
            root=ET.fromstring(packet)
            email=root.find('.//'+qn(META,'email')+'/'+qn(RDF,'Bag')+'/'+qn(RDF,'li'))
            source=next(spec for spec in pdf.Root.AF if spec.AFRelationship==pikepdf.Name.Source)
            data,count=re.subn(br'(?im)^Date:[^\r\n]*(?:\r?\n[ \t][^\r\n]*)*',lambda _:('Date: '+date).encode(),source.EF.F.read_bytes(),count=1)
            assert count==1
            update_source(pdf,root,email,data)
            prop=email.find(qn(META,'Sent'));assert prop is not None
            if sent is None:email.remove(prop)
            else:prop.text=sent
            for child in list(email):
                if child.tag==qn(META,'Raw-Sent'):email.remove(child)
            if name=='extended-year-raw':ET.SubElement(email,qn(META,'Raw-Sent')).text=date
            serialized=ET.tostring(root,encoding='utf-8')
            for prefix,uri in [('pdfmail','http://www.pdfa.org/eapdf/'),('pdfmailid','http://www.pdfa.org/eapdf/ns/id/'),('pdfmailmeta',META)]:
                if ('xmlns:'+prefix+'=').encode() not in serialized:root.set('xmlns:'+prefix,uri)
            pdf.Root.Metadata.write(ET.tostring(root,encoding='utf-8'))
            path=OUT/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
        cases.append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),expected_status=status,expected_failures=failures))
    (OUT/'expectations.json').write_text(json.dumps(dict(
        authority=['EA-PDF Tables4/18 Raw-Sent condition and original date meaning','RFC5322 section3.3','W3C XSD1.0 dateTime lexical/value space'],
        boundary='UTC instant semantics do not infer the originating local timezone. Extended calendar-cycle checks identify parser limits; they do not normalize or compare the original extended-year instant. Full source grammar remains unverified.',
        seed=str(SEED.relative_to(ROOT)),seed_sha256=hashlib.sha256(SEED.read_bytes()).hexdigest(),cases=cases),indent=2)+'\n')


if __name__=='__main__':main()
