"""Independent source/RF controls for branches absent from the V64 cohort."""
import hashlib
import io
import json
from pathlib import Path
import re
from xml.etree import ElementTree as ET

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/fixtures/eapdf/validator-coverage-branches'
SEED = ROOT / 'tests/fixtures/eapdf/validator-contentset-parent/nested-single.pdf'
RDF = 'http://www.w3.org/1999/02/22-rdf-syntax-ns#'
META = 'http://www.pdfa.org/eapdf/ns/meta/'
FOAF = 'http://xmlns.com/foaf/0.1/'
qn = lambda namespace, name: '{' + namespace + '}' + name


def main():
    OUT.mkdir(exist_ok=True)
    cases = []
    for name in ('raw-recipients', 'missing-raw-recipients', 'wrong-raw-cc', 'wrong-raw-bcc',
                 'invalid-date-raw', 'invalid-date-missing-raw', 'rf-valid', 'rf-invalid-key'):
        with pikepdf.open(SEED) as pdf:
            packet = pdf.Root.Metadata.read_bytes()
            for _, (prefix, uri) in ET.iterparse(io.BytesIO(packet), events=['start-ns']):
                ET.register_namespace(prefix, uri)
            root = ET.fromstring(packet)
            email = root.find('.//' + qn(META, 'email') + '/' + qn(RDF, 'Bag') + '/' + qn(RDF, 'li'))
            source = next(spec for spec in pdf.Root.AF if spec.AFRelationship == pikepdf.Name.Source)
            if name.startswith('rf-'):
                data = b'Original companion bytes\n'
                companion = pdf.make_stream(data)
                companion.Type = pikepdf.Name.EmbeddedFile
                companion.Subtype = pikepdf.Name('/text/plain')
                companion.Params = pikepdf.Dictionary(Size=len(data), CheckSum=pikepdf.String(hashlib.md5(data).digest()))
                source.RF = pikepdf.Dictionary()
                source.RF['/UF' if name == 'rf-valid' else '/DOS'] = pikepdf.Array(['companion.txt', companion])
                if name == 'rf-valid':
                    assets = root.find('.//' + qn(META, 'assets') + '/' + qn(RDF, 'Bag'))
                    item = ET.SubElement(assets, qn(RDF, 'li'), {qn(RDF, 'parseType'): 'Resource'})
                    for key, value in {'Filename': 'companion.txt', 'SizeInBytes': str(len(data)),
                                       'ContentType': 'text/plain', 'Checksum': hashlib.md5(data).hexdigest()}.items():
                        ET.SubElement(item, qn(META, key)).text = value
            else:
                original = source.EF.F.read_bytes()
                if name.startswith('invalid-date'):
                    data, count = re.subn(br'(?im)^Date:[^\r\n]*', b'Date: not a normalizable date', original, count=1)
                    assert count == 1
                    if name == 'invalid-date-raw':
                        ET.SubElement(email, qn(META, 'Raw-Sent')).text = 'not a normalizable date'
                else:
                    data = b'Cc: carbon@example.org\r\nBcc: blind@example.org\r\n' + original
                    for field, mailbox in [('Cc', 'carbon@example.org'), ('Bcc', 'blind@example.org')]:
                        prop = ET.SubElement(email, qn(META, field))
                        item = ET.SubElement(ET.SubElement(prop, qn(RDF, 'Seq')), qn(RDF, 'li'))
                        ET.SubElement(item, qn(RDF, 'Description'), {qn(FOAF, 'mbox'): 'mailto:' + mailbox})
                        if name != 'missing-raw-recipients':
                            ET.SubElement(email, qn(META, 'Raw-' + field)).text = (
                                'different@example.org' if name == 'wrong-raw-' + field.lower() else mailbox)
                old_checksum = hashlib.md5(original).hexdigest()
                source.EF.F.write(data)
                source.EF.F.Params.Size = len(data)
                source.EF.F.Params.CheckSum = pikepdf.String(hashlib.md5(data).digest())
                email.find(qn(META, 'SizeInBytes')).text = str(len(data))
                for asset in root.findall('.//' + qn(META, 'assets') + '/' + qn(RDF, 'Bag') + '/' + qn(RDF, 'li')):
                    checksum = asset.find(qn(META, 'Checksum'))
                    if checksum is not None and checksum.text == old_checksum:
                        checksum.text = hashlib.md5(data).hexdigest()
                        asset.find(qn(META, 'SizeInBytes')).text = str(len(data))
            # Preserve required prefix declarations even when no property uses them.
            serialized = ET.tostring(root, encoding='utf-8')
            for prefix, uri in [('pdfmail', 'http://www.pdfa.org/eapdf/'),
                                ('pdfmailid', 'http://www.pdfa.org/eapdf/ns/id/'), ('pdfmailmeta', META)]:
                if ('xmlns:' + prefix + '=').encode() not in serialized:
                    root.set('xmlns:' + prefix, uri)
            pdf.Root.Metadata.write(ET.tostring(root, encoding='utf-8'))
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        failures = (['MESSAGE_RAW_CC_XMP'] if name == 'wrong-raw-cc' else
                    ['MESSAGE_RAW_BCC_XMP'] if name == 'wrong-raw-bcc' else
                    ['MESSAGE_RAW_SENT_REQUIRED_IF_INVALID'] if name == 'invalid-date-missing-raw' else
                    ['SOURCE_FILE_SET'] if name == 'rf-invalid-key' else [])
        levels = ({'SOURCE_FILE_SET': 'FAIL' if failures else 'PASS'} if name.startswith('rf-') else
                  {'MESSAGE_RAW_SENT_REQUIRED_IF_INVALID': 'FAIL' if failures else 'PASS'} if name.startswith('invalid-date') else
                  {'MESSAGE_RAW_CC_XMP': 'FAIL' if name == 'wrong-raw-cc' else 'N/A' if name == 'missing-raw-recipients' else 'PASS',
                   'MESSAGE_RAW_BCC_XMP': 'FAIL' if name == 'wrong-raw-bcc' else 'N/A' if name == 'missing-raw-recipients' else 'PASS'})
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                          expected_status='FAIL' if failures else 'PASS', expected_failures=failures,
                          expected_levels=levels))
    for linked in (False, True):
        name = 'url-linked' if linked else 'url-unlinked'
        with pikepdf.open(SEED) as pdf:
            page = pdf.pages[0]
            font = page.Resources.Font.F1
            glyphs = {}
            for first, last, base in re.findall(rb'<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>', font.ToUnicode.read_bytes()):
                for cid in range(int(first, 16), int(last, 16) + 1):
                    glyphs[chr(int(base, 16) + cid - int(first, 16))] = cid.to_bytes(2, 'big')
            url = 'www.us.ibm.com'
            encoded = b''.join(glyphs[char] for char in url + ' ')
            instructions = list(pikepdf.parse_content_stream(page))
            active_font = None
            for index, instruction in enumerate(instructions):
                if str(instruction.operator) == 'Tf':
                    active_font = instruction.operands[0]
                if str(instruction.operator) == 'Tj' and active_font == pikepdf.Name.F1:
                    instructions[index] = pikepdf.ContentStreamInstruction([pikepdf.String(encoded)], pikepdf.Operator('Tj'))
                    break
            else:
                raise AssertionError('No F1 text object')
            page.Contents.write(pikepdf.unparse_content_stream(instructions))
            if linked:
                annotation = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Annot, Subtype=pikepdf.Name.Link,
                    Rect=[50, 700, 160, 715], F=4, Border=[0, 0, 0], Contents=url,
                    A=pikepdf.Dictionary(S=pikepdf.Name.URI, URI='http://' + url)))
                page.obj.Annots.append(annotation)
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                          expected_status='PASS', expected_failures=[],
                          expected_levels={'ACTIONABLE_URLS_LINK_ANNOTATIONS': 'PASS' if linked else 'WARN'}))
    # These two are bounded PDF/A-3 collection controls, not complete EA-PDFs.
    # Missing EA-PDF navigation/DPart/member objects remain explicit failures.
    for correct in (True, False):
        name = 'collection-extension-valid' if correct else 'collection-extension-six'
        with pikepdf.open(ROOT / 'tests/fixtures/eapdf/upstream-profile-controls/3u-valid.pdf') as pdf, \
                pikepdf.open(ROOT / 'tests/fixtures/eapdf/validator-docinfo-crosswalk/full-3u.pdf') as metadata_source, \
                pikepdf.open(ROOT / 'tests/fixtures/eapdf/validator-booleans/collection-false.pdf') as collection_source:
            text = metadata_source.Root.Metadata.read_bytes().decode()
            for field in ('email', 'attachments', 'assets'):
                text = re.sub(r'<pdfmailmeta:' + field + r'>.*?</pdfmailmeta:' + field + '>', '', text, flags=re.S)
            text = text.replace('<pdfmailid:conformance>s</pdfmailid:conformance>', '<pdfmailid:conformance>ci</pdfmailid:conformance>')
            pdf.Root.Metadata.write(text.encode())
            for key, value in metadata_source.docinfo.items():
                pdf.docinfo[key] = str(value)
            pdf.Root.Collection = pdf.copy_foreign(collection_source.make_indirect(collection_source.Root.Collection))
            pdf.Root.Extensions = pikepdf.Dictionary(ADBE=pikepdf.Dictionary(
                BaseVersion=pikepdf.Name('/1.7'), ExtensionLevel=5 if correct else pikepdf.String('5')))
            child = pdf.Root.StructTreeRoot.K
            document = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,
                S=pikepdf.Name.Document, P=pdf.Root.StructTreeRoot, K=child, Pg=pdf.pages[0].obj))
            child.P = document
            pdf.Root.StructTreeRoot.K = document
            path = OUT / (name + '.pdf')
            pdf.save(path, force_version=('1.7', 5), static_id=True, fix_metadata_version=False)
        if not correct:
            data = path.read_bytes()
            assert data.count(b'/ExtensionLevel 5') == 1
            path.write_bytes(data.replace(b'/ExtensionLevel 5', b'/ExtensionLevel 6'))
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                          expected_status='FAIL', expected_levels={'COLLECTION_EXTENSIONS': 'PASS' if correct else 'FAIL'},
                          expected_failures=sorted(['ATTACHMENT_XMP', 'COLLECTION_CHILD_VALIDATION', 'DPARTROOT_PRESENT',
                              'DPM_FRONTMATTER_REQUIRED_CONTAINER', 'OUTLINES_PRESENT', 'PAGEMODE_PRESENT',
                              'PROFILE_NFSM_CONTAINER', 'PROFILE_PAGEMODE_CONTAINER', 'VIEWER_PREFS']
                              + ([] if correct else ['COLLECTION_EXTENSIONS'])),
                          expected_iso='N/A',
                          boundary='PDF/A-3 collection dictionary control; deliberately omits EA-PDF navigation, DPart tree and members.'))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF1.0 Table18 raw header fields; sections9.1/9.4 original source file sets and RF',
        seed=str(SEED.relative_to(ROOT)), seed_sha256=hashlib.sha256(SEED.read_bytes()).hexdigest(),
        boundary='Branch observations; no complete source inventory, mail parser or file-set completeness certification.',
        cases=cases), indent=2) + '\n')


if __name__ == '__main__':
    main()
