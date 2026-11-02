"""Coverage follows scoped ownership, including unassigned and second sources."""
import hashlib,io,json,re
from pathlib import Path
from xml.etree import ElementTree as ET
import pikepdf
from build_eapdf_empty_raw_fixtures import update_source,qn,META,RDF
from build_eapdf_ambiguous_source_header_fixtures import add_source,replace_header
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'tests/fixtures/eapdf/validator-source-scope'
SEED=ROOT/'tests/fixtures/eapdf/validator-source-mailbox-data/from-exact.pdf'


def remove_header(data,name):
    result,count=re.subn(br'(?im)^'+name.encode()+br':[^\r\n]*(?:\r?\n[ \t][^\r\n]*)*\r?\n',b'',data,count=1)
    assert count==1
    return result


def main():
    OUT.mkdir(exist_ok=True);cases=[]
    recipes=[('missing-'+field.lower(),field) for field in ('To','From','Cc','Bcc','Subject')]
    recipes += [('mixed-date',None),('all-require-to',None),('all-require-raw-sent',None),('assigned-second','To'),('assigned-positive',None),
                ('assigned-date-second-valid',None),('assigned-date-second-invalid',None),('missing-to-reversed','To'),('assigned-second-reversed','To')]
    for name,field in recipes:
        with pikepdf.open(SEED) as pdf:
            packet=pdf.Root.Metadata.read_bytes()
            for _,(prefix,uri) in ET.iterparse(io.BytesIO(packet),events=['start-ns']):ET.register_namespace(prefix,uri)
            root=ET.fromstring(packet);email=root.find('.//'+qn(META,'email')+'/'+qn(RDF,'Bag')+'/'+qn(RDF,'li'))
            source=next(spec for spec in pdf.Root.AF if spec.AFRelationship==pikepdf.Name.Source)
            original=source.EF.F.read_bytes();primary=original;other=original.replace(b'Attached is a basic agenda',b'attached is a basic agenda',1)
            if field in ('Cc','Bcc'):
                header=(field+': recipient@example.org\r\n').encode();primary=header+primary;other=header+other
            if field:
                other=remove_header(other,field)
                for prop in (field,'Raw-'+field):
                    for child in list(email):
                        if child.tag==qn(META,prop):email.remove(child)
            if name.startswith('assigned-second'):
                other=other.replace(b'Subject: Agenda',b'Subject: agenda',1)
                email.find(qn(META,'Subject')).text=email.find(qn(META,'Subject')).text.replace('Agenda','agenda',1)
            if name in ('mixed-date','all-require-raw-sent'):
                primary=replace_header(primary,'Date',b'not a normalizable date')
                if name=='all-require-raw-sent':other=replace_header(other,'Date',b'not a normalizable date')
                email.find(qn(META,'Sent')).text='2010-09-30T13:24:56'
                for child in list(email):
                    if child.tag==qn(META,'Raw-Sent'):email.remove(child)
            if name=='all-require-to':
                for prop in ('To','Raw-To'):
                    for child in list(email):
                        if child.tag==qn(META,prop):email.remove(child)
            if name.startswith('assigned-date-second'):
                if name=='assigned-date-second-valid':
                    primary=replace_header(primary,'Date',b'not a normalizable date')
                    for child in list(email):
                        if child.tag==qn(META,'Raw-Sent'):email.remove(child)
                else:
                    other=replace_header(other,'Date',b'not a normalizable date')
                    other=other.replace(b'Subject: Agenda',b'Subject: agenda',1)
                    email.find(qn(META,'Subject')).text=email.find(qn(META,'Subject')).text.replace('Agenda','agenda',1)
                    email.find(qn(META,'Sent')).text='2010-09-30T13:24:56'
                    raw=email.find(qn(META,'Raw-Sent'))
                    if raw is None:raw=ET.SubElement(email,qn(META,'Raw-Sent'))
                    raw.text='not a normalizable date'
            if name!='assigned-positive':
                length=max(len(primary),len(other));primary+=b' '*(length-len(primary));other+=b' '*(length-len(other))
                update_source(pdf,root,email,primary)
                source.EF.UF=source.EF.F
                add_source(pdf,root,source,other,'candidate.eml')
                if name.endswith('-reversed'):pdf.Root.AF=pikepdf.Array(list(reversed(list(pdf.Root.AF))))
            serialized=ET.tostring(root,encoding='utf-8')
            for prefix,uri in [('pdfmail','http://www.pdfa.org/eapdf/'),('pdfmailid','http://www.pdfa.org/eapdf/ns/id/'),('pdfmailmeta',META)]:
                if ('xmlns:'+prefix+'=').encode() not in serialized:root.set('xmlns:'+prefix,uri)
            pdf.Root.Metadata.write(ET.tostring(root,encoding='utf-8'))
            path=OUT/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
        failures=['MESSAGE_TO_XMP'] if name=='all-require-to' else ['MESSAGE_RAW_SENT_REQUIRED_IF_INVALID'] if name=='all-require-raw-sent' else []
        status='FAIL' if failures else 'PASS' if name.startswith('assigned-') else 'INCOMPLETE'
        cases.append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),expected_status=status,expected_failures=failures))
    (OUT/'expectations.json').write_text(json.dumps(dict(
        authority='Required source-header presence and Raw-Sent conditions belong to the actual email scope; global source order is not an ownership decision.',
        boundary='Schema checks and universal contradictions remain independent. Unassigned ownership stays incomplete, without deriving a condition from an arbitrary catalog Source.',
        seed=str(SEED.relative_to(ROOT)),seed_sha256=hashlib.sha256(SEED.read_bytes()).hexdigest(),cases=cases),indent=2)+'\n')


if __name__=='__main__':main()
