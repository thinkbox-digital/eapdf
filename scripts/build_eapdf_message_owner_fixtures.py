"""Actual content ownership versus global GUID membership and record ordering."""
import hashlib
import json
from pathlib import Path
import sys
from xml.etree import ElementTree as ET

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import validate_eapdf as v

OUT = ROOT / 'tests/fixtures/eapdf/validator-message-owners'
MULTI = ROOT / 'tests/fixtures/eapdf/validator-dpart-object-ranges/multi-ordered.pdf'
SINGLE = ROOT / 'tests/fixtures/eapdf/validator-operands/valid.pdf'


def structure_elements(pdf):
    pending = [pdf.Root.StructTreeRoot.K]
    elements = []
    while pending:
        element = pending.pop()
        if not isinstance(element, pikepdf.Dictionary):
            continue
        elements.append(element)
        kids = element.get('/K', [])
        pending.extend(list(kids) if isinstance(kids, pikepdf.Array) else [kids])
    return elements


def guid_property(message):
    return next(prop for prop in message.A.P if str(prop.N) == 'Mail_GUID')


def standard_header_tags(pdf, elements):
    fields = ('Mail_Field', 'Mail_FieldName', 'Mail_FieldValue')
    for element in elements:
        tag = str(element.get('/S', '')).lstrip('/')
        if tag in fields:
            mapped = element.NS.RoleMapNS['/' + tag]
            element.S, element.NS = mapped[0], mapped[1]
            if '/A' in element:
                del element.A
    for page in pdf.pages:
        instructions = []
        for instruction in pikepdf.parse_content_stream(page):
            if str(instruction.operator) == 'BDC':
                tag, props = instruction.operands
                if str(tag).lstrip('/') in fields:
                    instruction = pikepdf.ContentStreamInstruction(
                        [pdf.Root.StructTreeRoot.RoleMap[str(tag)], props], instruction.operator)
            instructions.append(instruction)
        page.Contents = pdf.make_stream(pikepdf.unparse_content_stream(instructions))


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    cases = []
    for name in ('valid-multi', 'valid-single', 'swapped-structure-guids', 'duplicate-structure-guid',
                 'rotated-structure-guids', 'reversed-structure-order', 'reversed-xmp-order',
                 'swapped-dpm-guids', 'standard-header-tags'):
        seed = SINGLE if name == 'valid-single' else MULTI
        with pikepdf.open(seed) as pdf:
            elements = structure_elements(pdf)
            messages = [e for e in elements if e.get('/S') == pikepdf.Name('/Mail_Message')]
            values = [guid_property(e).V for e in messages]
            if name == 'swapped-structure-guids':
                guid_property(messages[0]).V, guid_property(messages[1]).V = values[1], values[0]
            elif name == 'duplicate-structure-guid':
                guid_property(messages[1]).V = values[0]
            elif name == 'rotated-structure-guids':
                for message, value in zip(messages, values[1:] + values[:1]):
                    guid_property(message).V = value
            elif name == 'reversed-structure-order':
                parent = messages[0].P
                parent.K = pikepdf.Array(list(reversed(list(parent.K))))
            elif name == 'reversed-xmp-order':
                xroot, namespaces, _ = v.parse_xmp(v.read_metadata_stream(pdf), v.Report('parse'))
                for prefix, uri in namespaces.items():
                    ET.register_namespace(prefix, uri)
                bag = xroot.find('.//' + v.qn(v.NS_PDFMAILMETA, 'email') + '/' + v.qn(v.NS_RDF, 'Bag'))
                assert bag is not None
                bag[:] = list(reversed(list(bag)))
                packet = ET.tostring(xroot, encoding='utf-8')
                for prefix, uri in (('pdfmail', v.NS_PDFMAIL), ('pdfmailid', v.NS_PDFMAILID),
                                    ('pdfmailmeta', v.NS_PDFMAILMETA)):
                    if ('xmlns:' + prefix + '=').encode() not in packet:
                        xroot.set('xmlns:' + prefix, uri)
                pdf.Root.Metadata.write(ET.tostring(xroot, encoding='utf-8'))
            elif name == 'swapped-dpm-guids':
                _, _, nodes = v.get_dpart_nodes(pdf)
                parts = [item['node'].DPM for item in nodes
                         if item['child_count'] and '/Mail_GUID' in item['node'].get('/DPM', {})]
                first, second = parts[0].Mail_GUID, parts[1].Mail_GUID
                parts[0].Mail_GUID, parts[1].Mail_GUID = second, first
            elif name == 'standard-header-tags':
                standard_header_tags(pdf, elements)
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        failures = ['MAIL_MESSAGE_GUID_MATCH_DPM_XMP'] if name in (
            'swapped-structure-guids', 'duplicate-structure-guid', 'rotated-structure-guids') else (
            ['ATTACHMENT_ANNOTATIONS_PRESENT', 'MAIL_MESSAGE_GUID_MATCH_DPM_XMP']
            if name == 'swapped-dpm-guids' else [])
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                          expected_status='FAIL' if failures else 'PASS', expected_failures=failures))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF9.6/9.7 Tables7/13: combined email representation and GUID consistency; explicitly reviewed owner correspondence inference',
        source_files={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in (SINGLE, MULTI)},
        boundary='Ownership follows verified content locations and DPart ancestry, not attribute values or structure/XMP list order. Custom header field vocabulary is optional. Original-source fidelity remains separate.',
        cases=cases), indent=2) + '\n')
    print('Built nine message content/GUID ownership controls.')


if __name__ == '__main__':
    main()
