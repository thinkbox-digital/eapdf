"""Non-SMTP original tokens: preserve raw data, never fabricate SMTP facts."""
import hashlib,io,json
from pathlib import Path
from xml.etree import ElementTree as ET
import pikepdf
from build_eapdf_empty_raw_fixtures import update_source,qn,META,RDF
from build_eapdf_ambiguous_source_header_fixtures import replace_header
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'tests/fixtures/eapdf/validator-nonsmtp-source'
SEED=ROOT/'tests/fixtures/eapdf/validator-source-mailbox-data/from-exact.pdf'
FOAF='http://xmlns.com/foaf/0.1/'


def main():
    OUT.mkdir(exist_ok=True);cases=[]
    for name in ('omitted','raw-preserved','normalized-unverified','raw-mismatch','raw-omitted-normalized','mixed-known','mixed-missing','bad-foaf'):
        raw='Sender </o=Exchange/cn=recipient>'
        if name.startswith('mixed-'):raw+=', Known <known@example.org>'
        with pikepdf.open(SEED) as pdf:
            packet=pdf.Root.Metadata.read_bytes()
            for _,(prefix,uri) in ET.iterparse(io.BytesIO(packet),events=['start-ns']):ET.register_namespace(prefix,uri)
            root=ET.fromstring(packet);email=root.find('.//'+qn(META,'email')+'/'+qn(RDF,'Bag')+'/'+qn(RDF,'li'))
            source=next(spec for spec in pdf.Root.AF if spec.AFRelationship==pikepdf.Name.Source)
            data=replace_header(source.EF.F.read_bytes(),'From',raw.encode());update_source(pdf,root,email,data)
            for child in list(email):
                if child.tag in (qn(META,'From'),qn(META,'Raw-From')):email.remove(child)
            if name not in ('omitted','raw-omitted-normalized'):
                ET.SubElement(email,qn(META,'Raw-From')).text='Different </o=Exchange/cn=other>' if name=='raw-mismatch' else raw
            mailbox={'normalized-unverified':'normalized@example.org','raw-omitted-normalized':'normalized@example.org',
                     'mixed-known':'known@example.org','bad-foaf':'bad..address@example.org'}.get(name)
            if mailbox:
                prop=ET.SubElement(email,qn(META,'From'));item=ET.SubElement(ET.SubElement(prop,qn(RDF,'Seq')),qn(RDF,'li'))
                ET.SubElement(item,qn(RDF,'Description'),{qn(FOAF,'mbox'):'mailto:'+mailbox})
            serialized=ET.tostring(root,encoding='utf-8')
            for prefix,uri in [('pdfmail','http://www.pdfa.org/eapdf/'),('pdfmailid','http://www.pdfa.org/eapdf/ns/id/'),('pdfmailmeta',META)]:
                if ('xmlns:'+prefix+'=').encode() not in serialized:root.set('xmlns:'+prefix,uri)
            pdf.Root.Metadata.write(ET.tostring(root,encoding='utf-8'))
            path=OUT/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
        failures={'raw-mismatch':['MESSAGE_RAW_FROM_XMP'],'mixed-missing':['MESSAGE_FROM_XMP'],'bad-foaf':['MESSAGE_FROM_FOAF']}.get(name,[])
        status='FAIL' if failures else 'PASS' if name in ('raw-preserved','mixed-known') else 'INCOMPLETE'
        cases.append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),expected_status=status,expected_failures=failures))
    (OUT/'expectations.json').write_text(json.dumps(dict(
        authority='V61 local REQ-FOAF-REPRESENTATION: exact raw non-SMTP preservation or unavailable normalization, never invented SMTP addresses.',
        boundary='Known SMTP presence, supplied mailbox syntax and supplied raw-text fidelity remain independent. No directory lookup, inferred SMTP address or complete source-normalization certification is added.',
        seed=str(SEED.relative_to(ROOT)),seed_sha256=hashlib.sha256(SEED.read_bytes()).hexdigest(),cases=cases),indent=2)+'\n')


if __name__=='__main__':main()
