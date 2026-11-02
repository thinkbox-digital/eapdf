"""Required standard XMP types, independent of email creator behavior."""
import hashlib,json,re
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'tests/fixtures/eapdf/validator-required-metadata'
SEED=ROOT/'tests/fixtures/eapdf/validator-operands/valid.pdf'

def main():
    OUT.mkdir(parents=True,exist_ok=True)
    recipes=[('valid',[]),('creator-bag',['XMP_CREATOR']),('creator-late-uri',['XMP_CREATOR']),
        ('producer-uri',['XMP_PRODUCER']),('producer-array',['XMP_PRODUCER']),
        ('createdate-uri',['XMP_CREATEDATE']),('createdate-array',['XMP_CREATEDATE']),
        ('createdate-invalid',['XMP_CREATEDATE']),('createdate-reduced',[]),
        ('createdate-no-zone',[]),('qualified-fields',[]),('attribute-fields',[])]
    cases=[]
    for name,failures in recipes:
        with pikepdf.Pdf.open(SEED) as pdf:
            data=pdf.Root.Metadata.read_bytes().decode('utf-8')
            tags=('dc:creator','pdf:Producer','xmp:CreateDate')
            original={tag:re.search('<'+tag+r'\b.*?</'+tag+'>',data,re.S).group() for tag in tags}
            if name=='creator-bag':data=data.replace(original['dc:creator'],original['dc:creator'].replace('rdf:Seq','rdf:Bag'))
            elif name=='creator-late-uri':data=data.replace(original['dc:creator'],original['dc:creator'].replace('</rdf:Seq>','<rdf:li rdf:resource="urn:creator"/></rdf:Seq>'))
            elif name in ('producer-uri','createdate-uri'):
                tag='pdf:Producer' if name.startswith('producer') else 'xmp:CreateDate'
                value='urn:producer' if name.startswith('producer') else 'urn:date'
                data=data.replace(original[tag],f'<{tag} rdf:resource="{value}"/>')
            elif name in ('producer-array','createdate-array'):
                tag='pdf:Producer' if name.startswith('producer') else 'xmp:CreateDate'
                value='Producer' if name.startswith('producer') else '2026-10-20T12:00:00Z'
                data=data.replace(original[tag],f'<{tag}><rdf:Seq><rdf:li>{value}</rdf:li></rdf:Seq></{tag}>')
            elif name.startswith('createdate-'):
                value={'createdate-invalid':'2026-02-30','createdate-reduced':'2026','createdate-no-zone':'2026-10-20T12:00'}[name]
                data=data.replace(original['xmp:CreateDate'],'<xmp:CreateDate>'+value+'</xmp:CreateDate>')
            elif name=='qualified-fields':
                for tag in ('pdf:Producer','xmp:CreateDate'):
                    value=re.search('>(.*?)</',original[tag],re.S).group(1)
                    data=data.replace(original[tag],f'<{tag} xmlns:ex="urn:example:qualifier" rdf:parseType="Resource"><rdf:value>{value}</rdf:value><ex:note>Qualifier</ex:note></{tag}>')
            elif name=='attribute-fields':
                for tag in ('pdf:Producer','xmp:CreateDate'):
                    value=re.search('>(.*?)</',original[tag],re.S).group(1)
                    data=data.replace(original[tag],'')
                    # Keep standard property namespaces at the correct Description scope.
                    uri='http://ns.adobe.com/pdf/1.3/' if tag.startswith('pdf') else 'http://ns.adobe.com/xap/1.0/'
                    prefix=tag.split(':')[0]
                    desc=f'<rdf:Description rdf:about="" xmlns:{prefix}="{uri}" {tag}="{value}"/>'
                    data=data.replace('</rdf:RDF>',desc+'</rdf:RDF>')
            pdf.Root.Metadata.write(data.encode('utf-8'))
            path=OUT/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
        cases.append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
            expected_status='FAIL' if failures else 'PASS',expected_failures=failures))
    (OUT/'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF 9.8 printed p84; standard dc:creator ordered ProperName array, pdf:Producer AgentName, xmp:CreateDate Date',
        sources=['https://developer.adobe.com/xmp/docs/xmp-namespaces/dc/','https://developer.adobe.com/xmp/docs/xmp-namespaces/pdf/','https://developer.adobe.com/xmp/docs/xmp-namespaces/xmp/','https://developer.adobe.com/xmp/docs/xmp-namespaces/xmp-data-types/'],
        seed_sha256=hashlib.sha256(SEED.read_bytes()).hexdigest(),boundary='Required EA-PDF field types only. No product-name grammar or filesystem/source timestamp equality is inferred.',cases=cases),indent=2)+'\n')
    print('Built twelve mandatory standard-metadata controls.')

if __name__=='__main__':main()
