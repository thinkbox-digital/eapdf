"""Source-asset probes with independent bytes/MD5/size/media expectations."""
from copy import deepcopy
import hashlib
import io
import json
from pathlib import Path
from xml.etree import ElementTree as ET

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/fixtures/eapdf/validator-ambiguous-sources'
META = 'http://www.pdfa.org/eapdf/ns/meta/'
RDF = 'http://www.w3.org/1999/02/22-rdf-syntax-ns#'
qn = lambda namespace, name: '{' + namespace + '}' + name


def main():
    OUT.mkdir(exist_ok=True)
    seed = ROOT / 'tests/fixtures/eapdf/validator-operands/valid.pdf'
    recipes = [('compatible', []), ('wrong-size', ['ASSET_SIZE_MATCH']),
               ('wrong-media', ['ASSET_CONTENTTYPE_MATCH']),
               ('wrong-size-and-media', ['ASSET_SIZE_MATCH', 'ASSET_CONTENTTYPE_MATCH']),
               ('zero-padded-size', []), ('compatible-parameters', []),
               ('malformed-parameters', ['ASSET_CONTENTTYPE_MATCH']), ('unique-name', [])]
    cases = []
    for name, failures in recipes:
        with pikepdf.Pdf.open(seed) as pdf:
            primary = next(spec for spec in pdf.Root.AF if spec.AFRelationship == pikepdf.Name.Source)
            data = primary.EF.F.read_bytes()
            actual_size = len(data)
            checksum = hashlib.md5(data).hexdigest()
            duplicate = pikepdf.AttachedFileSpec(pdf, data, filename='copy.txt', mime_type='text/plain',
                                               description='Independent duplicate source candidate')
            duplicate.obj.AFRelationship = pikepdf.Name.Source
            stream = duplicate.obj.EF.F
            stream.Params = pikepdf.Dictionary(Size=actual_size, CheckSum=pikepdf.String(bytes.fromhex(checksum)),
                                               ModDate='D:20261020120000Z')
            pdf.attachments['copy.txt'] = duplicate
            raw = pdf.Root.Metadata.read_bytes()
            for _, (prefix, uri) in ET.iterparse(io.BytesIO(raw), events=['start-ns']):
                ET.register_namespace(prefix, uri)
            root = ET.fromstring(raw)
            bag = root.find('.//' + qn(META, 'assets') + '/' + qn(RDF, 'Bag'))
            assert len(bag) == 1
            bag.append(deepcopy(bag[0]))
            for index, row in enumerate(bag):
                row.find(qn(META, 'Filename')).text = ('original-a.eml', 'original-b.eml')[index]
                row.find(qn(META, 'Checksum')).text = checksum
                row.find(qn(META, 'SizeInBytes')).text = str(actual_size)
                row.find(qn(META, 'ContentType')).text = ('message/rfc822', 'text/plain')[index]
            if name in ('wrong-size', 'wrong-size-and-media'):
                bag[0].find(qn(META, 'SizeInBytes')).text = str(actual_size + 1)
            if name in ('wrong-media', 'wrong-size-and-media'):
                bag[0].find(qn(META, 'ContentType')).text = 'application/pdf'
            if name == 'zero-padded-size':
                bag[0].find(qn(META, 'SizeInBytes')).text = '+000' + str(actual_size)
            if name == 'compatible-parameters':
                primary.EF.F.Mail_MediaTypeParameters = 'name="A  B"'
                bag[0].find(qn(META, 'ContentType')).text = "MESSAGE/RFC822; name*=utf-8''A%20%20B"
            if name == 'malformed-parameters':
                primary.EF.F.Mail_MediaTypeParameters = 'note=x; =first'
                bag[0].find(qn(META, 'ContentType')).text = 'message/rfc822;note=x; =different'
            if name == 'unique-name':
                bag[0].find(qn(META, 'Filename')).text = str(primary.UF)
                bag[1].find(qn(META, 'Filename')).text = 'copy.txt'
            packet = ET.tostring(root, encoding='utf-8')
            for prefix, uri in [('pdfmail', 'http://www.pdfa.org/eapdf/'),
                                ('pdfmailid', 'http://www.pdfa.org/eapdf/ns/id/'), ('pdfmailmeta', META)]:
                if ('xmlns:' + prefix + '=').encode() not in packet:
                    root.set('xmlns:' + prefix, uri)
            pdf.Root.Metadata.write(ET.tostring(root, encoding='utf-8'))
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                          expected_failures=sorted(failures), expected_status='FAIL' if failures else 'PASS' if name == 'unique-name' else 'INCOMPLETE',
                          actual_source_size=actual_size, actual_source_md5=checksum))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF 9.8.2 Table 16 printed p85; 9.4 printed pp66-67',
        seed=str(seed.relative_to(ROOT)), seed_sha256=hashlib.sha256(seed.read_bytes()).hexdigest(),
        boundary='Only universally contradicted size/media values fail; no source owner or occurrence is selected',
        cases=cases), indent=2) + '\n')
    print('Built eight independent ambiguous source-asset PDF probes.')


if __name__ == '__main__':
    main()
