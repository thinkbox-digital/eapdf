"""Independent geometric PDF controls for actual upstream profile execution.

These are PDF/A engine controls, not complete EA-PDF examples. Expected
results come from the retained profile predicates, not the email creator.
"""
import hashlib
import json
from pathlib import Path
import sys

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/fixtures/eapdf/upstream-profile-controls'


def build(path, part, conformance, action=None):
    with pikepdf.Pdf.new() as pdf, pikepdf.Pdf.open(ROOT / 'examples/eml/eml_Agenda_and_timeline_slides_1s.pdf') as seed:
        page = pdf.add_blank_page(page_size=(200, 200))
        # Only the ICC output intent is reused. Page, structure and metadata
        # are independently constructed and have no email-generator semantics.
        pdf.Root.OutputIntents = pikepdf.Array([pdf.copy_foreign(seed.Root.OutputIntents[0])])
        pdf.Root.Lang = 'en-US'
        root = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructTreeRoot))
        element = pdf.make_indirect(pikepdf.Dictionary(
            Type=pikepdf.Name.StructElem, S=pikepdf.Name.P, P=root, Pg=page.obj, K=0))
        root.K = element
        root.ParentTree = pdf.make_indirect(pikepdf.Dictionary(Nums=pikepdf.Array([0, pikepdf.Array([element])])))
        root.ParentTreeNextKey = 1
        pdf.Root.StructTreeRoot = root
        pdf.Root.MarkInfo = pikepdf.Dictionary(Marked=True)
        page.StructParents = 0
        page.Contents = pdf.make_stream(b'/P <</MCID 0>> BDC 0 g 20 20 40 40 re f EMC')
        schema = ('<pdfaid:rev>2020</pdfaid:rev>' if part == 4 else '')
        schema += ('<pdfaid:conformance>' + conformance + '</pdfaid:conformance>') if conformance else ''
        metadata = f'''<?xpacket begin="\ufeff" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/"
xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:dc="http://purl.org/dc/elements/1.1/">
<pdfaid:part>{part}</pdfaid:part>{schema}
<xmp:CreateDate>2026-10-20T12:00:00Z</xmp:CreateDate>
<xmp:ModifyDate>2026-10-20T12:00:00Z</xmp:ModifyDate>
<xmp:MetadataDate>2026-10-20T12:00:00Z</xmp:MetadataDate>
<dc:title><rdf:Alt><rdf:li xml:lang="x-default">Independent geometric control</rdf:li></rdf:Alt></dc:title>
</rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end="w"?>'''
        pdf.Root.Metadata = pdf.make_stream(metadata.encode('utf-8'))
        pdf.Root.Metadata.Type = pikepdf.Name.Metadata
        pdf.Root.Metadata.Subtype = pikepdf.Name.XML
        if part == 4 and conformance == 'F':
            payload = pdf.make_stream(b'profile control\n')
            payload.Type = pikepdf.Name.EmbeddedFile
            payload.Subtype = pikepdf.Name('/text/plain')
            payload.Params = pikepdf.Dictionary(ModDate='D:20261020120000Z', Size=16)
            spec = pdf.make_indirect(pikepdf.Dictionary(
                Type=pikepdf.Name.Filespec, F='control.txt', UF='control.txt', Desc='Profile control',
                AFRelationship=pikepdf.Name.Data, EF=pikepdf.Dictionary(F=payload, UF=payload)))
            pdf.Root.Names = pikepdf.Dictionary(EmbeddedFiles=pikepdf.Dictionary(Names=pikepdf.Array(['control.txt', spec])))
            pdf.Root.AF = pikepdf.Array([spec])
        if action == 'javascript':
            pdf.Root.OpenAction = pdf.make_indirect(pikepdf.Dictionary(S=pikepdf.Name.JavaScript, JS='void(0)'))
        elif action == 'launch':
            # Prohibited PDAction predicates: PDF/A-3 6.5.1 test 1;
            # PDF/A-4 6.6.1 test 1. JavaScript is permitted by part 4.
            pdf.Root.OpenAction = pdf.make_indirect(pikepdf.Dictionary(S=pikepdf.Name.Launch, F='never-executed.txt'))
        pdf.save(path, force_version='2.0' if part == 4 else '1.7', static_id=True)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    cases = []
    for part, conformance in [(3, 'A'), (3, 'U'), (4, ''), (4, 'F'), (4, 'E')]:
        label = str(part) + conformance.lower()
        for action in (None, 'javascript', 'launch'):
            path = OUT / (label + '-' + (action or 'valid') + '.pdf')
            build(path, part, conformance, action)
            cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                              profile=f'PDF/A-{part}{conformance} validation profile',
                              expected_pdfa='FAIL' if action == 'launch' or (action == 'javascript' and part == 3) else 'PASS',
                              expected_failure_clause=('6.5.1' if part == 3 else '6.6.1')
                              if action == 'launch' or (action == 'javascript' and part == 3) else None))
    (OUT / 'cases.json').write_text(json.dumps(dict(scope='Upstream PDF/A controls; not complete EA-PDF documents', cases=cases), indent=2) + '\n')
    print('Built', len(cases), 'independent upstream controls.')


if __name__ == '__main__':
    main()
