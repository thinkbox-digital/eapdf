"""Deterministic, original structural controls. These are NOT conforming EA-PDFs.

No converter is invoked or modified. All text/payloads are synthetic Apache-2.0
test content; real email PDFs are referenced in place, never copied here.
"""
import argparse
import hashlib
import io
from pathlib import Path
import xml.etree.ElementTree as ET

import pikepdf
from inspect_pdf import RDF, META, IDENT, PDFA


def make(profile='s', mutation=None):
    pdf = pikepdf.Pdf.new()
    n = 5 if mutation == 'reversed-body-order' else 2 if profile in ['m', 'mi'] else 1
    pages = [pdf.add_blank_page(page_size=(200, 200)).obj for _ in range(n)]
    rootpart = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.DPartRoot))
    top = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.DPart, Parent=rootpart))
    rootpart.DPartRootNode = top
    pdf.Root.DPartRoot = rootpart
    xml = ET.Element('{adobe:ns:meta/}xmpmeta')
    desc = ET.SubElement(ET.SubElement(xml, f'{{{RDF}}}RDF'), f'{{{RDF}}}Description')
    def field(parent, namespace, name, value):
        child = ET.SubElement(parent, f'{{{namespace}}}{name}')
        child.text = str(value)
        return child
    field(desc, IDENT, 'version', '1')
    field(desc, IDENT, 'rev', '2024')
    field(desc, IDENT, 'conformance', 'future-profile' if mutation == 'unknown-profile' else profile)
    if mutation == 'conflicting-profile':
        field(desc, IDENT, 'conformance', 'mi')
    field(desc, PDFA, 'part', '4')  # Declaration only. No PDF/A conformance assertion.
    field(desc, PDFA, 'conformance', 'F')
    bags = {key: ET.SubElement(ET.SubElement(desc, f'{{{META}}}{key}'), f'{{{RDF}}}Bag')
            for key in ['email', 'assets', 'attachments']}
    def record(kind, values):
        li = ET.SubElement(bags[kind], f'{{{RDF}}}li', {f'{{{RDF}}}parseType': 'Resource'})
        for key, val in values.items():
            field(li, META, key, val)
        return li
    members = []
    if profile in ['c', 'ci']:
        leaf = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.DPart, Parent=top, Start=pages[0],
            DPM=pikepdf.Dictionary(Mail_ContentSetType=pikepdf.Name.FrontMatter, Mail_Desc='Container front matter')))
        members.append(leaf)
        folders = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Folder, ID=0, Name='Root'))
        pdf.Root.Collection = pikepdf.Dictionary(Type=pikepdf.Name.Collection, Folders=folders, View=pikepdf.Name.T)
        if mutation == 'folder-cycle':
            child = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Folder, ID=1, Name='Child', Parent=folders))
            folders.Child = child
            child.Next = child
    else:
        message_pages = [pages[1], pages[3], pages[4]] if mutation == 'reversed-body-order' else pages
        for i, page in enumerate(message_pages):
            guid = 'same-guid' if mutation == 'duplicate-guid' else f'guid-{i}'
            values = {'Mail_GUID': guid, 'messageid': f'<message-{i}@example.invalid>'}
            if mutation == 'missing-guid':
                del values['Mail_GUID']
            rec = record('email', values)
            if mutation == 'uri-guid':
                el = rec.find(f'{{{META}}}Mail_GUID'); el.text = None; el.set(f'{{{RDF}}}resource', 'urn:guid:0')
            dpm = pikepdf.Dictionary(Mail_MessageID=f'<message-{i}@example.invalid>')
            if mutation != 'missing-guid':
                dpm.Mail_GUID = pikepdf.Name('/guid-0') if mutation == 'name-guid' else pikepdf.String(guid)
            part = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.DPart, Parent=top, DPM=dpm))
            leaf = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.DPart, Parent=part, Start=page,
                DPM=pikepdf.Dictionary(Mail_ContentSetType=pikepdf.Name.BodyRendering, Mail_Subtype=pikepdf.Name('/text/plain; charset=UTF-8'), Mail_Desc='Synthetic body')))
            if mutation == 'reversed-body-order' and i == 0:
                leaf.End = pages[2]
            if mutation == 'reversed-range' and i == 0:
                leaf.Start, leaf.End = pages[1], pages[0]
            if mutation == 'invalid-page-ref':
                leaf.Start = pdf.Root
            part.DParts = pikepdf.Array([pikepdf.Array([leaf])])
            members.append(part)
            page.DPart = leaf
    if mutation == 'reversed-body-order':
        members.reverse()
    top.DParts = pikepdf.Array([pikepdf.Array(members)])
    if mutation == 'dpart-cycle':
        top.DParts.append(top)
    names = []
    def add_file(name, payload, mime='text/plain', annotation=False, af=False):
        stream = pikepdf.Stream(pdf, payload)
        stream.Type, stream.Subtype = pikepdf.Name.EmbeddedFile, pikepdf.Name('/' + mime)
        stream.Params = pikepdf.Dictionary(Size=len(payload), CheckSum=pikepdf.String(hashlib.md5(payload, usedforsecurity=False).digest()))
        spec = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Filespec, F=name, UF=name,
            EF=pikepdf.Dictionary(F=stream, UF=stream), AFRelationship=pikepdf.Name.Source if af else pikepdf.Name.Data))
        if annotation:
            annot = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Annot, Subtype=pikepdf.Name.FileAttachment,
                Rect=pikepdf.Array([0, 0, 10, 10]), FS=spec, Contents='Synthetic file'))
            pages[0].Annots = pikepdf.Array([annot])
        elif af:
            pdf.Root.TestObject = pikepdf.Dictionary(AF=pikepdf.Array([spec]))
        else:
            names.extend([pikepdf.String(name), spec])
        return spec, stream
    if mutation == 'corrupt-child':
        add_file('corrupt.pdf', b'not a PDF', 'application/pdf')
    elif mutation == 'nested-mixed':
        add_file('archive.pdf', make('m'), 'application/pdf')
        add_file('nested.pdf', make('ci', 'nested-leaf'), 'application/pdf')
    elif mutation == 'nested-leaf':
        add_file('leaf.pdf', make('si'), 'application/pdf')
    elif profile not in ['c', 'ci']:
        payload = b'' if profile in ['si', 'mi'] else b'Original M1 reference attachment.\n'
        af = mutation == 'object-af-only'
        spec, stream = add_file('fixture.txt', payload, annotation=not af, af=af)
        values = {'Filename': 'fixture.txt', 'SizeInBytes': len(payload), 'Content-Type': 'text/plain',
                  'CheckSum': hashlib.md5(payload, usedforsecurity=False).hexdigest(),
                  'Mail_GUID': 'same-guid' if mutation == 'duplicate-guid' else 'guid-0'}
        if af:
            values = {'Filename': 'fixture.txt', 'SizeInBytes': len(payload), 'ContentType': 'text/plain', 'Checksum': values['CheckSum']}
        attachment = record('assets' if af else 'attachments', values)
        if mutation == 'uri-checksum':
            checksum = attachment.find(f'{{{META}}}CheckSum')
            checksum.text = None
            checksum.set(f'{{{RDF}}}resource', 'urn:checksum:synthetic')
        if mutation == 'duplicate-files':
            add_file('alias.txt', payload)
        if mutation == 'missing-stream':
            del spec.EF
        if mutation == 'bad-checksum':
            stream.Params.CheckSum = pikepdf.String(bytes(16))
        if mutation == 'two-streams':
            alternate = pikepdf.Stream(pdf, b'Alternate EF payload.\n')
            alternate.Subtype = pikepdf.Name('/text/plain')
            spec.EF.UF = alternate
    if names:
        pairs = sorted(zip(names[0::2], names[1::2]), key=lambda pair: str(pair[0]))
        pdf.Root.Names = pikepdf.Dictionary(EmbeddedFiles=pikepdf.Dictionary(Names=pikepdf.Array([v for pair in pairs for v in pair])))
    xmlbytes = ET.tostring(xml, encoding='utf-8')
    if mutation == 'malformed-xmp':
        xmlbytes = b'<not-closed'
    pdf.Root.Metadata = pikepdf.Stream(pdf, xmlbytes)
    pdf.Root.Metadata.Type, pdf.Root.Metadata.Subtype = pikepdf.Name.Metadata, pikepdf.Name.XML
    output = io.BytesIO()
    pdf.save(output, deterministic_id=True, min_version='2.0', fix_metadata_version=False,
             object_stream_mode=pikepdf.ObjectStreamMode.disable)
    pdf.close()
    return output.getvalue()


CONTROLS = {
    's-missing-guid': ('s', 'missing-guid'), 'si-name-guid': ('si', 'name-guid'),
    'm-duplicate-guid': ('m', 'duplicate-guid'), 'mi-reversed-range': ('mi', 'reversed-range'),
    'c-folder-cycle': ('c', 'folder-cycle'), 'ci-corrupt-child': ('ci', 'corrupt-child'),
    'c-nested-mixed': ('c', 'nested-mixed'), 's-unknown-profile': ('s', 'unknown-profile'),
    's-conflicting-profile': ('s', 'conflicting-profile'), 's-uri-guid': ('s', 'uri-guid'),
    's-duplicate-files': ('s', 'duplicate-files'), 's-object-af-only': ('s', 'object-af-only'),
    's-two-streams': ('s', 'two-streams'), 's-bad-checksum': ('s', 'bad-checksum'),
    's-missing-stream': ('s', 'missing-stream'), 's-malformed-xmp': ('s', 'malformed-xmp'),
    's-dpart-cycle': ('s', 'dpart-cycle'), 's-invalid-page-ref': ('s', 'invalid-page-ref'),
    'existing-reversed-order': ('m', 'reversed-body-order'),
    'existing-uri-checksum': ('s', 'uri-checksum'),
}
if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    for name, (profile, mutation) in CONTROLS.items():
        (args.output / (name + '.pdf')).write_bytes(make(profile, mutation))
    print(f'Wrote {len(CONTROLS)} deterministic structural controls (not conformance-certified PDFs).')
