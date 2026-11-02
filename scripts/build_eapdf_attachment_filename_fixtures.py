"""Table 19 original filenames versus permitted native PDF filenames."""
import hashlib
import json
from pathlib import Path
import sys
from xml.etree import ElementTree as ET

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import validate_eapdf as v


def omit_source(pdf, root):
    # The retained control has a flat name tree. Keep every native payload.
    removed_annotations=[annotation for page in pdf.pages for annotation in page.obj.get('/Annots', [])
                         if str(annotation.get('/FS', {}).get('/AFRelationship')) == '/Source']
    removed_ids={annotation.objgen for annotation in removed_annotations}
    removed_parent_keys={int(annotation.StructParent) for annotation in removed_annotations}
    names = pdf.Root.Names.EmbeddedFiles.Names
    assert not pdf.Root.Names.EmbeddedFiles.get('/Kids')
    pdf.Root.Names.EmbeddedFiles.Names = pikepdf.Array(
        item for index in range(0, len(names), 2) if str(names[index+1].get('/AFRelationship')) != '/Source'
        for item in (names[index], names[index+1]))
    for page in pdf.pages:
        if page.obj.get('/Annots') is not None:
            page.Annots = pikepdf.Array(annotation for annotation in page.Annots
                if str(annotation.get('/FS', {}).get('/AFRelationship')) != '/Source')
    for obj in pdf.objects:
        if isinstance(obj, pikepdf.Dictionary) and str(obj.get('/Type')) == '/StructElem':
            children = obj.get('/K')
            if isinstance(children, pikepdf.Array):
                kept = [child for child in children if not (isinstance(child, pikepdf.Dictionary)
                    and str(child.get('/Type')) == '/OBJR' and child.get('/Obj') is not None
                    and child.Obj.objgen in removed_ids)]
                if len(kept) != len(children):
                    assert kept, 'The retained control keeps its source-label MCID.'
                    obj.K = pikepdf.Array(kept)
        if isinstance(obj, pikepdf.Dictionary) and isinstance(obj.get('/AF'), pikepdf.Array):
            kept = [spec for spec in obj.AF if str(spec.get('/AFRelationship')) != '/Source']
            if kept:
                obj.AF = pikepdf.Array(kept)
            else:
                del obj.AF
    parent_tree = pdf.Root.StructTreeRoot.ParentTree
    assert parent_tree.get('/Kids') is None
    numbers = parent_tree.Nums
    parent_tree.Nums = pikepdf.Array(item for index in range(0, len(numbers), 2)
        if int(numbers[index]) not in removed_parent_keys for item in (numbers[index], numbers[index+1]))
    for description in root.iter(v.qn(v.NS_RDF, 'Description')):
        for child in list(description):
            if child.tag == v.qn(v.NS_PDFMAILMETA, 'assets'):
                description.remove(child)
    root.find('.//' + v.qn(v.NS_PDFMAILID, 'conformance')).text = 'si'
    assert not any(file['afrelationship'] == 'Source' for file in v.get_af_specs(pdf))


def main():
    output = ROOT / 'tests/fixtures/eapdf/validator-attachment-filenames'
    source = output / '_source.pdf'
    placeholder = output / '_placeholder-source.pdf'
    assert source.exists() and placeholder.exists()
    recipes = [
        ('valid', False, False, False, None, [], 'PASS'),
        ('stored-name-differs', True, False, False, None, [], 'PASS'),
        ('stored-name-no-checksum', True, True, False, None, [], 'PASS'),
        ('source-absent-original-no-checksum', False, True, True, None, [], 'PASS'),
        ('source-absent-alias-checksum', True, False, True, None, [], 'PASS'),
        ('source-absent-alias-no-checksum', True, True, True, None, [], 'INCOMPLETE'),
        ('placeholder-alias-no-checksum', True, True, False, 'placeholder', [], 'PASS'),
        ('large-size-mismatch', False, True, True, 'large', ['ATTACHMENT_ANNOTATIONS_PRESENT', 'ATTACHMENT_XMP_MATCH'], 'FAIL'),
        ('long-zero-size', False, True, True, 'zeros', [], 'PASS'),
        ('renamed-wrong-checksum', True, False, False, 'checksum', ['ATTACHMENT_ANNOTATIONS_PRESENT', 'ATTACHMENT_XMP_MATCH'], 'FAIL'),
        ('renamed-wrong-size', True, False, False, 'size', ['ATTACHMENT_ANNOTATIONS_PRESENT', 'ATTACHMENT_XMP_MATCH'], 'FAIL'),
        ('renamed-wrong-type', True, False, False, 'type', ['ATTACHMENT_ANNOTATIONS_PRESENT', 'ATTACHMENT_XMP_CONTENTTYPE_MATCH', 'EMBEDDED_SUBTYPE_MATCH_XMP'], 'FAIL')]
    cases = []
    for name, rename, remove_checksum, isolated, mutation, failures, status in recipes:
        with pikepdf.open(placeholder if mutation == 'placeholder' else source) as pdf:
            root, namespaces, _ = v.parse_xmp(v.read_metadata_stream(pdf), v.Report('parse'))
            for prefix, uri in namespaces.items():
                ET.register_namespace(prefix, uri)
            bag = root.find('.//' + v.qn(v.NS_PDFMAILMETA, 'attachments')).find(v.qn(v.NS_RDF, 'Bag'))
            assert len(bag) == 1
            row = bag[0]
            if isolated:
                omit_source(pdf, root)
            if rename:
                annotations = [annotation for annotation in v.collect_file_attachment_annotations(pdf)
                               if annotation['file']['afrelationship'] != 'Source']
                assert len(annotations) == 1
                spec = pdf.get_object(annotations[0]['fs_objgen'])
                spec.F = pikepdf.String('stored-safe.pptx')
                spec.UF = pikepdf.String('stored-safe.pptx')
            if remove_checksum:
                field = row.find(v.qn(v.NS_PDFMAILMETA, 'CheckSum'))
                if field is not None:
                    row.remove(field)
            if mutation in ('large', 'zeros'):
                field = row.find(v.qn(v.NS_PDFMAILMETA, 'SizeInBytes'))
                field.text = '9' * 5000 if mutation == 'large' else '+' + '0' * 5000 + field.text
            elif mutation in ('checksum', 'size', 'type'):
                field = {'checksum': 'CheckSum', 'size': 'SizeInBytes', 'type': 'Content-Type'}[mutation]
                row.find(v.qn(v.NS_PDFMAILMETA, field)).text = {'checksum': '0' * 32, 'size': '1', 'type': 'application/octet-stream'}[mutation]
            packet = ET.tostring(root, encoding='utf-8')
            for prefix, uri in (('pdfmail', v.NS_PDFMAIL), ('pdfmailid', v.NS_PDFMAILID), ('pdfmailmeta', v.NS_PDFMAILMETA)):
                if ('xmlns:' + prefix + '=').encode() not in packet:
                    root.set('xmlns:' + prefix, uri)
            pdf.Root.Metadata.write(ET.tostring(root, encoding='utf-8'))
            path = output / (name + '.pdf')
            pdf.save(path, fix_metadata_version=False)
        cases.append({'file': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
            'expected_failures': sorted(failures), 'expected_status': status,
            'required_unavailable_codes': ['ATTACHMENT_ANNOTATIONS_PRESENT', 'VALIDATION_INCOMPLETE'] if status == 'INCOMPLETE' else []})
    (output / 'expectations.json').write_text(json.dumps({
        'authority': ['EA-PDF 9.8.4 Table 19 Filename/SizeInBytes/CheckSum', 'EA-PDF 9.3 visible native attachment annotations'],
        'sources': {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in (source, placeholder)},
        'cases': cases}, indent=2) + '\n')
    print('Wrote', len(cases), 'original/native attachment filename probes')


if __name__ == '__main__':
    main()
