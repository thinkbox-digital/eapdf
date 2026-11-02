"""Media contradictions across possible native attachments, without owner selection."""
from pathlib import Path
import hashlib
import json
import sys
from xml.etree import ElementTree as ET

import pikepdf

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
import validate_eapdf as v


def main():
    output=ROOT/'tests/fixtures/eapdf/validator-ambiguous-media';output.mkdir(exist_ok=True)
    inputs=ROOT/'tests/fixtures/eapdf/validator-attachment-equivalence'
    media='EMBEDDED_SUBTYPE_MATCH_XMP';params='SOURCE_MEDIATYPE_PARAMETERS_MATCH'
    recipes=[('one-type-wrong','isolated-ambiguous-media','FAIL',[media]),
             ('all-parameters-wrong','isolated-equivalent','FAIL',[media,params]),
             ('distinct-types-impossible','isolated-distinct-payloads','FAIL',[media]),
             ('distinct-types-compatible','isolated-distinct-payloads','INCOMPLETE',[]),
             ('parameters-compatible','isolated-distinct-payloads','INCOMPLETE',[]),
             ('distinct-parameters-wrong','isolated-distinct-payloads','FAIL',[media,params]),
             ('malformed-parameters-wrong','isolated-equivalent','FAIL',[media,params]),
             ('malformed-parameters-compatible','isolated-distinct-payloads','INCOMPLETE',[]),
             ('legal-parameters-control','isolated-equivalent','PASS',[]),
             ('matching-parameters-wrong-type','isolated-distinct-payloads','FAIL',[media])]
    cases=[]
    for name,seed,status,failures in recipes:
        with pikepdf.open(inputs/(seed+'.pdf')) as pdf:
            root,namespaces,_=v.parse_xmp(v.read_metadata_stream(pdf),v.Report('parse'))
            for prefix,uri in namespaces.items():ET.register_namespace(prefix,uri)
            bag=root.find('.//'+v.qn(v.NS_PDFMAILMETA,'attachments')+'/'+v.qn(v.NS_RDF,'Bag'))
            files=[f for f in v.embedded_file_candidates(pdf,v.get_embedded_files(pdf),v.get_af_specs(pdf))
                   if f['afrelationship']=='Data' and f['ufilename']=='same.txt']
            assert len(bag)==len(files)==2
            def types(values):
                for row,value in zip(bag,values):row.find(v.qn(v.NS_PDFMAILMETA,'Content-Type')).text=value
            def native(index,subtype='text/plain',parameters=None):
                stream=pdf.get_object(files[index]['objgen']).EF.F
                stream.Subtype=pikepdf.Name('/'+subtype)
                if parameters is not None:stream.Mail_MediaTypeParameters=pikepdf.String(parameters)
                elif '/Mail_MediaTypeParameters' in stream:del stream.Mail_MediaTypeParameters
            if name=='all-parameters-wrong':types(('text/plain; name=A','text/plain; name=B'))
            if name=='distinct-types-impossible':
                types(('application/pdf','application/pdf'));native(1,'text/csv')
            if name=='distinct-types-compatible':native(1,'application/pdf')
            if name=='parameters-compatible':
                types(('text/plain; charset=utf-8','text/plain; charset=iso-8859-1'))
                native(0,parameters='charset=utf-8');native(1,parameters='charset=iso-8859-1')
            if name=='distinct-parameters-wrong':
                types(('text/plain; name=A','text/plain; name=B'))
                native(0,parameters='name=C');native(1,parameters='name=D')
            if name=='malformed-parameters-wrong':
                types(('text/plain; note=x; =ignored','text/plain; note=x; =other'))
                native(0,parameters='note=x');native(1,parameters='note=x')
            if name=='malformed-parameters-compatible':
                types(('text/plain; note=x; =ignored','text/plain; note=x; =ignored'))
                native(0,parameters=' note=x; =ignored');native(1,parameters='note=x')
            if name=='legal-parameters-control':
                types(('TEXT/PLAIN; charset=UTF-8; name="A  B"',"text/plain; name*=utf-8''A%20%20B; charset=utf-8"))
                for index in (0,1):native(index,parameters='name="A  B"; charset=utf-8')
            if name=='matching-parameters-wrong-type':
                types(('application/pdf; charset=utf-8','application/pdf; charset=utf-8'))
                native(0,parameters='charset=utf-8');native(1,'text/csv',parameters='charset=UTF-8')
            packet=ET.tostring(root,encoding='utf-8')
            for prefix,uri in (('pdfmail',v.NS_PDFMAIL),('pdfmailid',v.NS_PDFMAILID),('pdfmailmeta',v.NS_PDFMAILMETA)):
                if ('xmlns:'+prefix+'=').encode() not in packet:root.set('xmlns:'+prefix,uri)
            pdf.Root.Metadata.write(ET.tostring(root,encoding='utf-8'))
            path=output/(name+'.pdf');pdf.save(path,fix_metadata_version=False,deterministic_id=True)
        cases.append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),expected_status=status,
                          expected_failures=sorted(failures),required_parameter_warning=name in (
                              'parameters-compatible','malformed-parameters-compatible','matching-parameters-wrong-type')))
    (output/'expectations.json').write_text(json.dumps(dict(
        authority=['EA-PDF 7.5 printed p44: repeated names/shared streams',
                   'EA-PDF 9.4 printed pp66-67: equivalent XMP media and parameter reconstruction',
                   'EA-PDF 9.8.4 Table 19 printed pp89-90: original attachment Content-Type including parameters/errors'],
        boundary='Only values contradicted by every observed possible native payload fail. Compatible/unreadable observations do not select an occurrence.',
        seeds={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(inputs.glob('isolated-*.pdf'))},cases=cases),indent=2)+'\n')
    print('Wrote',len(cases),'ambiguous media probes')


if __name__=='__main__':main()
