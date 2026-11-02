"""Reproduce scoped EA-PDF 7.5, 9.4 and Table 8 probes from retained controls."""
import hashlib
import json
from pathlib import Path
import sys
from xml.etree import ElementTree as ET

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import validate_eapdf as v


def set_asset_type(pdf, text):
    root, namespaces, _ = v.parse_xmp(v.read_metadata_stream(pdf), v.Report('parse'))
    for prefix, uri in namespaces.items():
        ET.register_namespace(prefix, uri)
    prop = root.find('.//' + v.qn(v.NS_PDFMAILMETA, 'assets') + '//'
                     + v.qn(v.NS_PDFMAILMETA, 'ContentType'))
    assert prop is not None
    prop.text = text
    packet = ET.tostring(root, encoding='utf-8')
    for prefix, uri in (('pdfmail', v.NS_PDFMAIL), ('pdfmailid', v.NS_PDFMAILID),
                        ('pdfmailmeta', v.NS_PDFMAILMETA)):
        if ('xmlns:' + prefix + '=').encode() not in packet:
            root.set('xmlns:' + prefix, uri)
    pdf.Root.Metadata.write(ET.tostring(root, encoding='utf-8'))


def main():
    output = ROOT / 'tests/fixtures/eapdf/validator-file-requirements'
    output.mkdir(parents=True, exist_ok=True)
    source = ROOT / 'tests/fixtures/eapdf/upstream-issues/valid.pdf'
    container = ROOT / 'examples/eml/eml_Agenda_and_timeline_slides_archive_1ci.pdf'
    cases = []

    def save(name, base, mutate, expected):
        with pikepdf.Pdf.open(base) as pdf:
            mutate(pdf)
            pdf.save(output / (name + '.pdf'), fix_metadata_version=False)
        cases.append(dict(file=name + '.pdf', expected_failures=expected,
                          sha256=hashlib.sha256((output / (name + '.pdf')).read_bytes()).hexdigest()))

    def source_stream(pdf):
        spec = next(s for s in pdf.Root.AF if s.AFRelationship == pikepdf.Name.Source)
        return spec.EF.UF

    save('valid-mail', source, lambda pdf: None, [])
    save('valid-container', container, lambda pdf: None, [])
    save('associated-date-missing', source, lambda pdf: source_stream(pdf).Params.__delitem__('/ModDate'),
         ['ASSOCIATED_FILE_MODDATE'])
    save('associated-date-calendar', source, lambda pdf: setattr(source_stream(pdf).Params, 'ModDate', 'D:20260229000000Z'),
         ['ASSOCIATED_FILE_MODDATE'])
    save('parameters-not-string', source, lambda pdf: setattr(source_stream(pdf), 'Mail_MediaTypeParameters', 17),
         ['ASSET_CONTENTTYPE_MATCH', 'EMBEDDED_MEDIATYPE_RECONSTRUCTION', 'EMBEDDED_SUBTYPE_MATCH_XMP'])

    def parameters(pdf, mismatch=False):
        text = 'name="résumé  final.eml"'
        source_stream(pdf).Mail_MediaTypeParameters = pikepdf.String(b'\xfe\xff' + text.encode('utf-16be'))
        set_asset_type(pdf, 'message/rfc822;' + (text.replace('  ', ' ') if mismatch else text))

    save('parameters-utf16', source, parameters, [])
    save('parameters-value-mismatch', source, lambda pdf: parameters(pdf, True),
         ['ASSET_CONTENTTYPE_MATCH', 'EMBEDDED_SUBTYPE_MATCH_XMP', 'SOURCE_MEDIATYPE_PARAMETERS_MATCH'])

    def content_set(pdf, kind):
        # Structure is optional. Use an untagged container so adding a second
        # Content Set does not independently violate custom-tag cardinality.
        for key in ('/StructTreeRoot', '/MarkInfo'):
            if key in pdf.Root:
                del pdf.Root[key]
        for page in pdf.pages:
            instructions = [(operands, operator) for operands, operator in pikepdf.parse_content_stream(page)
                            if str(operator) not in {'BMC', 'BDC', 'EMC', 'MP', 'DP'}]
            page.Contents = pdf.make_stream(pikepdf.unparse_content_stream(instructions))
            if '/StructParents' in page.obj:
                del page.obj['/StructParents']
        _, _, nodes = v.get_dpart_nodes(pdf)
        leaf = next(n for n in nodes if not n['child_count'])
        # Keep FrontMatter and its page connection; add one independent
        # Content Set over the same page. This historical recipe also violates
        # unique DPart coverage (ISO32000-2 14.12.2/3); it is not an isolated
        # positive for the otherwise permitted extension kind.
        added = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.DPart,
            Parent=leaf['parent'], Start=leaf['start'],
            DPM=pikepdf.Dictionary(Mail_ContentSetType=pikepdf.Name('/' + kind),
                Mail_Desc='Independent Table 8 probe', Mail_Subtype=pikepdf.Name('/text/plain'))))
        leaf['parent'].DParts.append(pikepdf.Array([added]))

    for kind in ('AttachmentRendering', 'BodyRendering', 'EmailHeaderRendering', 'RawEmailRendering'):
        save('container-' + kind, container, lambda pdf, k=kind: content_set(pdf, k),
             ['DPM_CONTAINER_CONTENTSET_TYPES', 'PAGE_DPART_PRESENT'])
    save('container-extension', container, lambda pdf: content_set(pdf, 'VendorExtension'), ['PAGE_DPART_PRESENT'])
    (output / 'expectations.json').write_text(json.dumps(dict(
        authority=['EA-PDF 7.5 associated-file ModDate', 'EA-PDF 9.4 reconstruction and XMP consistency',
                   'EA-PDF 9.6 Table 8 container prohibitions; extension values allowed'],
        sources={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                 for p in (source, container)}, cases=cases), indent=2, ensure_ascii=False) + '\n')
    print('Wrote', len(cases), 'probes to', output)


if __name__ == '__main__':
    main()
