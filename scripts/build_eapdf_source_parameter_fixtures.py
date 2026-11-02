"""Mixed verified/unavailable source parameter evidence, with fixed expectations."""
import hashlib
import io
import json
from pathlib import Path
from xml.etree import ElementTree as ET

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/fixtures/eapdf/validator-source-parameters'
META = 'http://www.pdfa.org/eapdf/ns/meta/'
RDF = 'http://www.w3.org/1999/02/22-rdf-syntax-ns#'
qn = lambda namespace, name: '{' + namespace + '}' + name


def main():
    OUT.mkdir(exist_ok=True)
    recipes = [
        ('ambiguous-verified', 'INCOMPLETE', [], 'WARN'),
        ('ambiguous-no-attachments', 'FAIL', ['ATTACHMENT_XMP'], 'WARN'),
        ('unknown-verified', 'FAIL', ['ASSET_MATCH', 'SOURCE_WITHOUT_XMP'], 'WARN'),
        ('unknown-no-attachments', 'FAIL', ['ASSET_MATCH', 'ATTACHMENT_XMP', 'SOURCE_WITHOUT_XMP'], 'WARN'),
        ('bare-control', 'INCOMPLETE', [], 'PASS'),
        ('unique-parameters', 'PASS', [], 'PASS'),
        ('unique-names-shared-checksum', 'PASS', [], 'PASS'),
        ('invalid-shape-control', 'FAIL', ['ASSET_XMP_FIELDS', 'ASSET_CONTENTTYPE'], 'PASS'),
        ('definite-failure', 'FAIL', ['ATTACHMENT_ANNOTATIONS_PRESENT', 'EMBEDDED_SUBTYPE_MATCH_XMP',
                                    'SOURCE_MEDIATYPE_PARAMETERS_MATCH'], 'FAIL')]
    cases = []
    for name, status, failures, level in recipes:
        seed = ROOT / ('tests/fixtures/eapdf/validator-operands/valid.pdf' if name == 'unique-parameters' else
                       'tests/fixtures/eapdf/validator-ambiguous-sources/compatible-parameters.pdf')
        with pikepdf.Pdf.open(seed) as pdf:
            raw = pdf.Root.Metadata.read_bytes()
            for _, (prefix, uri) in ET.iterparse(io.BytesIO(raw), events=['start-ns']):
                ET.register_namespace(prefix, uri)
            root = ET.fromstring(raw)
            assets = root.find('.//' + qn(META, 'assets') + '/' + qn(RDF, 'Bag'))
            if name.startswith('unknown'):
                for asset in assets:
                    asset.find(qn(META, 'Filename')).text = 'unmatched-original.eml'
                    asset.find(qn(META, 'Checksum')).text = '0' * 32
            if name.endswith('no-attachments'):
                for parent in root.iter():
                    for child in list(parent):
                        if child.tag == qn(META, 'attachments'):
                            parent.remove(child)
            if name == 'bare-control':
                for asset in assets:
                    element = asset.find(qn(META, 'ContentType'))
                    element.text = element.text.split(';', 1)[0]
                for spec in pdf.Root.AF:
                    if spec.AFRelationship == pikepdf.Name.Source and '/Mail_MediaTypeParameters' in spec.EF.F:
                        del spec.EF.F.Mail_MediaTypeParameters
            if name == 'unique-parameters':
                source = next(spec for spec in pdf.Root.AF if spec.AFRelationship == pikepdf.Name.Source)
                source.EF.F.Mail_MediaTypeParameters = 'name="A  B"'
                assets[0].find(qn(META, 'ContentType')).text = "message/rfc822; name*=utf-8''A%20%20B"
            if name == 'unique-names-shared-checksum':
                sources = [spec for spec in pdf.Root.AF if spec.AFRelationship == pikepdf.Name.Source]
                for row, source in zip(assets, sources):
                    row.find(qn(META, 'Filename')).text = str(source.UF)
            if name == 'invalid-shape-control':
                element = assets[0].find(qn(META, 'ContentType'))
                element.set(qn(RDF, 'resource'), 'message/rfc822; name=A')
                element.text = None
            if name == 'definite-failure':
                native = next(annotation.FS for page in pdf.pages for annotation in page.obj.get('/Annots', [])
                              if '/FS' in annotation and annotation.FS.get('/AFRelationship') != pikepdf.Name.Source)
                native.EF.F.Mail_MediaTypeParameters = 'injected=wrong'
            packet = ET.tostring(root, encoding='utf-8')
            for prefix, uri in [('pdfmail', 'http://www.pdfa.org/eapdf/'),
                                ('pdfmailid', 'http://www.pdfa.org/eapdf/ns/id/'), ('pdfmailmeta', META)]:
                if ('xmlns:' + prefix + '=').encode() not in packet:
                    root.set('xmlns:' + prefix, uri)
            pdf.Root.Metadata.write(ET.tostring(root, encoding='utf-8'))
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(), expected_status=status,
                          expected_failures=sorted(failures), expected_parameter_level=level,
                          seed=str(seed.relative_to(ROOT)), seed_sha256=hashlib.sha256(seed.read_bytes()).hexdigest()))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF 9.4 printed pp66-67; 9.8.2 Table 16 printed p85',
        boundary='Existing Source associations only; no new requirement to retain missing sources', cases=cases), indent=2) + '\n')
    print('Built nine source-parameter evidence controls.')


if __name__ == '__main__':
    main()
