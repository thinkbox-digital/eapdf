"""Exact decoded source filename probes; no creator-based expected findings."""
import hashlib
import io
import json
from pathlib import Path
from xml.etree import ElementTree as ET

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/fixtures/eapdf/validator-source-filenames'
META = 'http://www.pdfa.org/eapdf/ns/meta/'
RDF = 'http://www.w3.org/1999/02/22-rdf-syntax-ns#'
qn = lambda namespace, name: '{' + namespace + '}' + name


def main():
    OUT.mkdir(exist_ok=True)
    seed = ROOT / 'tests/fixtures/eapdf/validator-ambiguous-sources/compatible.pdf'
    recipes = [
        ('exact-double-space', 'PASS', []), ('original-paths', 'PASS', []),
        ('leading-trailing', 'PASS', []), ('unicode-space', 'PASS', []),
        ('invented-alias', 'INCOMPLETE', []),
        ('alias-universally-wrong-media', 'FAIL', ['ASSET_CONTENTTYPE_MATCH']),
        ('unique-checksum-alias', 'PASS', []), ('wrong-size', 'FAIL', ['ASSET_SIZE_MATCH'])]
    cases = []
    for name, status, failures in recipes:
        with pikepdf.Pdf.open(seed) as pdf:
            raw = pdf.Root.Metadata.read_bytes()
            for _, (prefix, uri) in ET.iterparse(io.BytesIO(raw), events=['start-ns']):
                ET.register_namespace(prefix, uri)
            root = ET.fromstring(raw)
            assets = root.find('.//' + qn(META, 'assets') + '/' + qn(RDF, 'Bag'))
            sources = [spec for spec in pdf.Root.AF if spec.AFRelationship == pikepdf.Name.Source]
            assert len(sources) == len(assets) == 2
            native_names = ['a  b.eml', 'a b.eml']
            metadata_names = list(native_names)
            if name == 'original-paths':
                metadata_names = ['C:\\archive\\a  b.eml', 'folder/a b.eml']
            if name == 'leading-trailing':
                native_names = metadata_names = [' report.eml', 'report.eml ']
            if name == 'unicode-space':
                native_names = metadata_names = ['a\u00a0b.eml', 'a b.eml']
            if name in ('invented-alias', 'alias-universally-wrong-media'):
                native_names = ['a  b.eml', 'other.eml']
                metadata_names = ['a b.eml', 'unknown.eml']
                assets[0].find(qn(META, 'ContentType')).text = 'text/plain' if name == 'invented-alias' else 'application/pdf'
                assets[1].find(qn(META, 'ContentType')).text = 'message/rfc822'
            if name == 'unique-checksum-alias':
                native_names = ['native-a.eml', 'native-b.txt']
                metadata_names = ['folder/original.eml', 'another/original.eml']
                stream = sources[1].EF.F
                data = stream.read_bytes() + b'\r\n'
                stream.write(data)
                stream.Params.Size = len(data)
                stream.Params.CheckSum = pikepdf.String(hashlib.md5(data).digest())
                assets[1].find(qn(META, 'SizeInBytes')).text = str(len(data))
                assets[1].find(qn(META, 'Checksum')).text = hashlib.md5(data).hexdigest()
            for spec, native, row, original in zip(sources, native_names, assets, metadata_names):
                spec.F = native
                spec.UF = native
                row.find(qn(META, 'Filename')).text = original
            if name == 'wrong-size':
                assets[0].find(qn(META, 'SizeInBytes')).text = str(len(sources[0].EF.F.read_bytes()) + 1)
            packet = ET.tostring(root, encoding='utf-8')
            for prefix, uri in [('pdfmail', 'http://www.pdfa.org/eapdf/'),
                                ('pdfmailid', 'http://www.pdfa.org/eapdf/ns/id/'), ('pdfmailmeta', META)]:
                if ('xmlns:' + prefix + '=').encode() not in packet:
                    root.set('xmlns:' + prefix, uri)
            pdf.Root.Metadata.write(ET.tostring(root, encoding='utf-8'))
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                          expected_status=status, expected_failures=sorted(failures),
                          native_filenames=native_names, original_filenames=metadata_names))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF 9.8.2 Table 16 printed p85: case-sensitive original filename text; permitted original paths',
        seed=str(seed.relative_to(ROOT)), seed_sha256=hashlib.sha256(seed.read_bytes()).hexdigest(),
        boundary='Preserve exact decoded filename whitespace; checksums remain primary; unknown ownership stays unavailable',
        cases=cases), indent=2, ensure_ascii=False) + '\n')
    print('Built eight exact source-filename controls.')


if __name__ == '__main__':
    main()
