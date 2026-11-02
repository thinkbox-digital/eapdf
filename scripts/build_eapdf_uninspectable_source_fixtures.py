"""Unsupported original parsing and permitted source omission/extra assets."""
import hashlib
import io
import json
from pathlib import Path
import shutil
from xml.etree import ElementTree as ET
import pikepdf
from build_eapdf_empty_raw_fixtures import update_source, qn, META, RDF
from build_eapdf_attachment_filename_fixtures import omit_source

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/fixtures/eapdf/validator-uninspectable-source'
SEED = ROOT / 'tests/fixtures/eapdf/validator-source-mailbox-data/from-exact.pdf'
FOAF = 'http://xmlns.com/foaf/0.1/'
ID = 'http://www.pdfa.org/eapdf/ns/id/'


def main():
    OUT.mkdir(exist_ok=True)
    original = ROOT / 'in/msgKitOut_noattach.msg'
    shutil.copyfile(original, OUT / 'original.msg')
    data = (OUT / 'original.msg').read_bytes()
    assert data.startswith(bytes.fromhex('d0cf11e0a1b11ae1'))
    cases = []
    for name in ('msg-s', 'msg-si', 'opaque-s', 'msg-bad-xmp', 'isolated-omitted', 'known-source', 'extra-unknown', 'opaque-si'):
        with pikepdf.open(SEED) as pdf:
            packet = pdf.Root.Metadata.read_bytes()
            for _, (prefix, uri) in ET.iterparse(io.BytesIO(packet), events=['start-ns']):
                ET.register_namespace(prefix, uri)
            root = ET.fromstring(packet)
            email = root.find('.//' + qn(META, 'email') + '/' + qn(RDF, 'Bag') + '/' + qn(RDF, 'li'))
            source = next(spec for spec in pdf.Root.AF if spec.AFRelationship == pikepdf.Name.Source)
            if name == 'isolated-omitted':
                omit_source(pdf, root)
            elif name == 'extra-unknown':
                stream = pdf.make_stream(data)
                stream.Type = pikepdf.Name.EmbeddedFile
                stream.Subtype = pikepdf.Name('/application/vnd.ms-outlook')
                stream.Params = pikepdf.Dictionary(Size=len(data), CheckSum=pikepdf.String(hashlib.md5(data).digest()), ModDate=source.EF.F.Params.ModDate)
                extra = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Filespec, F='unused-original.msg', UF='unused-original.msg',
                    Desc='Additional unrendered original', AFRelationship=pikepdf.Name.Source, EF=pikepdf.Dictionary(F=stream, UF=stream)))
                pdf.Root.AF.append(extra)
                names = pdf.Root.Names.EmbeddedFiles.Names
                assert not pdf.Root.Names.EmbeddedFiles.get('/Kids')
                pairs = [(str(names[i]), names[i + 1]) for i in range(0, len(names), 2)] + [('unused-original.msg', extra)]
                pdf.Root.Names.EmbeddedFiles.Names = pikepdf.Array(value for key, spec in sorted(pairs) for value in (key, spec))
                bag = root.find('.//' + qn(META, 'assets') + '/' + qn(RDF, 'Bag'))
                item = ET.SubElement(bag, qn(RDF, 'li'), {qn(RDF, 'parseType'): 'Resource'})
                for key, value in {'Filename':'unused-original.msg', 'SizeInBytes':str(len(data)), 'ContentType':'application/vnd.ms-outlook', 'Checksum':hashlib.md5(data).hexdigest()}.items():
                    ET.SubElement(item, qn(META, key)).text = value
            elif name != 'known-source':
                update_source(pdf, root, email, data)
                filename = 'original.data' if name.startswith('opaque') else 'original.msg'
                media = 'application/octet-stream' if name.startswith('opaque') else 'application/vnd.ms-outlook'
                source.EF.F.Subtype = pikepdf.Name('/' + media)
                source.F = source.UF = filename
                names = pdf.Root.Names.EmbeddedFiles.Names
                for i in range(0, len(names), 2):
                    if names[i+1].objgen == source.objgen:
                        names[i] = pikepdf.String(filename)
                for asset in root.findall('.//' + qn(META, 'assets') + '/' + qn(RDF, 'Bag') + '/' + qn(RDF, 'li')):
                    if asset.find(qn(META, 'Checksum')).text == hashlib.md5(data).hexdigest():
                        asset.find(qn(META, 'Filename')).text = filename
                        asset.find(qn(META, 'ContentType')).text = media
                if name.endswith('-si'):
                    root.find('.//' + qn(ID, 'conformance')).text = 'si'
            if name == 'msg-bad-xmp':
                email.remove(email.find(qn(META, 'From')))
                prop = ET.SubElement(email, qn(META, 'From'))
                item = ET.SubElement(ET.SubElement(prop, qn(RDF, 'Seq')), qn(RDF, 'li'))
                ET.SubElement(item, qn(RDF, 'Description'), {qn(FOAF, 'mbox'):'mailto:bad..address@example.org'})
            serialized = ET.tostring(root, encoding='utf-8')
            for prefix, uri in [('pdfmail','http://www.pdfa.org/eapdf/'), ('pdfmailid',ID), ('pdfmailmeta',META)]:
                if ('xmlns:' + prefix + '=').encode() not in serialized:
                    root.set('xmlns:' + prefix, uri)
            pdf.Root.Metadata.write(ET.tostring(root, encoding='utf-8'))
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        failures = []
        if name in ('msg-s','msg-si','msg-bad-xmp'):
            failures += ['MESSAGE_SUBJECT_XMP', 'MESSAGE_ATTACHMENT_COUNT_XMP', 'MESSAGE_SENT_XMP', 'MESSAGE_FROM_FOAF', 'MESSAGE_TO_FOAF', 'ATTACHMENT_XMP_MATCH', 'MESSAGE_ID_XMP', 'MESSAGE_RAW_TO_XMP', 'MESSAGE_RAW_FROM_XMP']
        status = 'FAIL' if failures else 'PASS' if name in ('isolated-omitted','known-source','extra-unknown') else 'INCOMPLETE'
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(), expected_status=status, expected_failures=failures))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='Source comparisons require an inspectable matched original. Isolated omission is permitted; additional independent originals can contain unrendered data.',
        boundary='Stored MSG properties can prove field contradictions; full header and external original-inventory completeness remain unavailable.',
        seed=str(SEED.relative_to(ROOT)), seed_sha256=hashlib.sha256(SEED.read_bytes()).hexdigest(),
        original=str(original.relative_to(ROOT)), original_sha256=hashlib.sha256(data).hexdigest(), cases=cases), indent=2)+'\n')


if __name__ == '__main__':
    main()
