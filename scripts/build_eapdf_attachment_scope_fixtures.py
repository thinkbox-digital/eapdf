"""Preserve collection member and Alternative roles during Table 19 comparisons."""
from pathlib import Path
import hashlib
import json
import sys

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import validate_eapdf as v


def main():
    output = ROOT / 'tests/fixtures/eapdf/validator-attachment-scope'
    output.mkdir(exist_ok=True)
    cases = []
    for profile in ('c', 'ci'):
        samples = json.loads((ROOT / 'examples/eml/manifest.json').read_text())['samples']
        source = ROOT / 'examples/eml' / next(row['file'] for row in samples if row['profile'] == '1' + profile)
        with pikepdf.open(source) as pdf:
            root, _, _ = v.parse_xmp(v.read_metadata_stream(pdf), v.Report('parse'))
            filename = v.flatten_attachments(root)[0]['Filename']
            member = next(f for f in v.get_embedded_files(pdf) if f['afrelationship'] == 'Data')
            spec = pdf.get_object(member['objgen'])
            spec.F = pikepdf.String(filename)
            spec.UF = pikepdf.String(filename)
            path = output / ('member-name-collision-' + profile + '.pdf')
            pdf.save(path, fix_metadata_version=False)
        cases.append(case(path, source))
    source = ROOT / 'tests/fixtures/eapdf/validator-attachment-streams/valid.pdf'
    with pikepdf.open(source) as pdf:
        native = next(f for f in v.embedded_file_candidates(pdf, v.get_embedded_files(pdf), v.get_af_specs(pdf))
                      if f['afrelationship'] != 'Source')
        data = b'An alternative representation of the original attachment.\n'
        stream = pdf.make_stream(data)
        stream.Type = pikepdf.Name.EmbeddedFile
        stream.Subtype = pikepdf.Name('/text/plain')
        stream.Params = pikepdf.Dictionary(Size=len(data), CheckSum=pikepdf.String(bytes.fromhex(v.md5hex(data))),
                                          ModDate=pikepdf.String('D:20261020000000Z'))
        alternate = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Filespec,
            F=pikepdf.String(native['filename']), UF=pikepdf.String(native['ufilename']),
            AFRelationship=pikepdf.Name.Alternative, EF=pikepdf.Dictionary(F=stream, UF=stream)))
        pdf.get_object(native['ef_objgen']).AF = pikepdf.Array([alternate])
        if '/Names' not in pdf.Root:
            pdf.Root.Names = pikepdf.Dictionary()
        if '/EmbeddedFiles' not in pdf.Root.Names:
            pdf.Root.Names.EmbeddedFiles = pdf.make_indirect(pikepdf.Dictionary(Names=pikepdf.Array()))
        tree = pikepdf.NameTree(pdf.Root.Names.EmbeddedFiles)
        tree['alternative-representation.txt'] = alternate
        path = output / 'alternative-name-collision.pdf'
        pdf.save(path, fix_metadata_version=False)
    cases.append(case(path, source))
    (output / 'expectations.json').write_text(json.dumps({'authority': [
        'EA-PDF 9.8.4 Table 19: corresponding native attachment stream',
        'EA-PDF 7.5: Alternative relationship distinguishes rendered representations',
        'EA-PDF 7.6 (printed p54) and 9.5: collection members are EA-PDFs; attachment payload comparisons belong to their owning children'],
        'cases': cases}, indent=2) + '\n')
    print('Wrote', len(cases), 'attachment-role probes')


def case(path, source):
    return {'file': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
            'source': str(source.relative_to(ROOT)), 'source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
            'expected_failures': [], 'expected_status': 'PASS', 'required_unavailable_codes': []}


if __name__ == '__main__':
    main()
