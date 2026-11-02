"""Independent originals: universal contradictions versus unknown ownership."""
import hashlib,io,json,re
from pathlib import Path
from urllib.parse import quote
from xml.etree import ElementTree as ET
import pikepdf
from build_eapdf_empty_raw_fixtures import qn,META,RDF
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'tests/fixtures/eapdf/validator-ambiguous-source-headers'
SEED=ROOT/'tests/fixtures/eapdf/validator-source-mailbox-data/from-exact.pdf'
FOAF='http://xmlns.com/foaf/0.1/'


def replace_header(data,name,value):
    result,count=re.subn(br'(?im)^'+name.encode()+br':[^\r\n]*(?:\r?\n[ \t][^\r\n]*)*',lambda _:(name+': ').encode()+value,data,count=1)
    assert count==1
    return result


def set_agent(email,field,mailbox):
    prop=email.find(qn(META,field));assert prop is not None;email.remove(prop)
    prop=ET.SubElement(email,qn(META,field));item=ET.SubElement(ET.SubElement(prop,qn(RDF,'Seq')),qn(RDF,'li'))
    ET.SubElement(item,qn(RDF,'Description'),{qn(FOAF,'mbox'):'mailto:'+quote(mailbox,safe='@.')})


def add_source(pdf,root,source,data,filename,media='message/rfc822'):
    stream=pdf.make_stream(data);stream.Type=pikepdf.Name.EmbeddedFile;stream.Subtype=pikepdf.Name('/'+media)
    stream.Params=pikepdf.Dictionary(Size=len(data),CheckSum=pikepdf.String(hashlib.md5(data).digest()),ModDate=source.EF.F.Params.ModDate)
    spec=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Filespec,F=filename,UF=filename,Desc='Independent original source',
                        AFRelationship=pikepdf.Name.Source,EF=pikepdf.Dictionary(F=stream,UF=stream)))
    pdf.Root.AF.append(spec)
    names=pdf.Root.Names.EmbeddedFiles.Names;assert not pdf.Root.Names.EmbeddedFiles.get('/Kids')
    pairs=[(str(names[i]),names[i+1]) for i in range(0,len(names),2)]+[(filename,spec)]
    pdf.Root.Names.EmbeddedFiles.Names=pikepdf.Array(value for name,item in sorted(pairs) for value in (name,item))
    assets=root.find('.//'+qn(META,'assets')+'/'+qn(RDF,'Bag'));item=ET.SubElement(assets,qn(RDF,'li'),{qn(RDF,'parseType'):'Resource'})
    for key,value in {'Filename':filename,'SizeInBytes':str(len(data)),'ContentType':media,'Checksum':hashlib.md5(data).hexdigest(),'NumberMessages':'1'}.items():
        ET.SubElement(item,qn(META,key)).text=value


def main():
    OUT.mkdir(exist_ok=True);cases=[]
    for name in ('wrong-to','wrong-subject','empty-raw-from','wrong-size','compatible-other','unknown-format',
                 'tentative-unknown','partial-headers','composite-fields','unknown-other-field','matching-ambiguous','consistent-candidate'):
        with pikepdf.open(SEED) as pdf:
            packet=pdf.Root.Metadata.read_bytes()
            for _,(prefix,uri) in ET.iterparse(io.BytesIO(packet),events=['start-ns']):ET.register_namespace(prefix,uri)
            root=ET.fromstring(packet);email=root.find('.//'+qn(META,'email')+'/'+qn(RDF,'Bag')+'/'+qn(RDF,'li'))
            source=next(spec for spec in pdf.Root.AF if spec.AFRelationship==pikepdf.Name.Source)
            original=source.EF.F.read_bytes();assert b'Attached is a basic agenda' in original
            other=original.replace(b'Attached is a basic agenda',b'attached is a basic agenda',1)
            if name in ('compatible-other','composite-fields','consistent-candidate'):
                assert b'oonjoku@us.ibm.com' in other
                other=other.replace(b'oonjoku@us.ibm.com',b'Oonjoku@us.ibm.com',1)
                set_agent(email,'To','Oonjoku@us.ibm.com')
                prop=email.find(qn(META,'Raw-To'));assert prop is not None
                prop.text=prop.text.replace('oonjoku@us.ibm.com','Oonjoku@us.ibm.com')
            if name in ('composite-fields','consistent-candidate'):
                other=other.replace(b'Subject: Agenda',b'Subject: agenda',1)
                if name=='consistent-candidate':email.find(qn(META,'Subject')).text=email.find(qn(META,'Subject')).text.replace('Agenda','agenda',1)
            if name=='partial-headers':other=other.replace(b'Subject:',b'broken header\r\nSubject:',1)
            if name=='unknown-other-field':other=replace_header(other,'From',b'j\xffrg@example.org')
            if name!='tentative-unknown':add_source(pdf,root,source,other,'other.eml')
            if name in ('wrong-to','unknown-format','tentative-unknown','partial-headers','unknown-other-field'):set_agent(email,'To','wrong@example.org')
            if name in ('unknown-format','tentative-unknown'):
                add_source(pdf,root,source,(ROOT/'tests/fixtures/eapdf/validator-uninspectable-source/original.msg').read_bytes(),'unknown.msg','application/vnd.ms-outlook')
            if name=='wrong-subject':email.find(qn(META,'Subject')).text='Changed subject'
            if name=='empty-raw-from':email.find(qn(META,'Raw-From')).text=''
            if name=='wrong-size':email.find(qn(META,'SizeInBytes')).text='999999'
            serialized=ET.tostring(root,encoding='utf-8')
            for prefix,uri in [('pdfmail','http://www.pdfa.org/eapdf/'),('pdfmailid','http://www.pdfa.org/eapdf/ns/id/'),('pdfmailmeta',META)]:
                if ('xmlns:'+prefix+'=').encode() not in serialized:root.set('xmlns:'+prefix,uri)
            pdf.Root.Metadata.write(ET.tostring(root,encoding='utf-8'))
            path=OUT/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
        failures={'wrong-to':['MESSAGE_TO_FOAF'],'wrong-subject':['MESSAGE_SUBJECT_XMP'],'empty-raw-from':['MESSAGE_RAW_FROM_XMP'],
                  'wrong-size':['MESSAGE_SIZE_XMP'],'composite-fields':['MESSAGE_SOURCE_MATCH'],'unknown-other-field':['MESSAGE_TO_FOAF']}.get(name,[])
        status='FAIL' if failures else 'PASS' if name=='consistent-candidate' else 'INCOMPLETE'
        cases.append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),expected_status=status,expected_failures=failures))
    (OUT/'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF Tables4/18 source Core Field fidelity and available-original comparison; candidate ownership cannot be inferred from a contradicted field under test.',
        boundary='Every catalog Source is considered, including previously consumed occurrences. Unsupported/recovered inventories block a universal proof. Known valid header comparisons do not certify full source grammar, encoding policy, provenance or unique occurrence assignment.',
        seed=str(SEED.relative_to(ROOT)),seed_sha256=hashlib.sha256(SEED.read_bytes()).hexdigest(),cases=cases),indent=2)+'\n')


if __name__=='__main__':main()
