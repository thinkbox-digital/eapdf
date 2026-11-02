"""Independent object inspection of retained page-scope operand expectations.

Does not import the validator or infer expectations from its findings. Frozen
PDF bytes are preserved. Form-scope correctness has separate focused tests.
"""
import hashlib
import io
import json
from pathlib import Path
import sys

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/improvement/validator-24'
CODE = 'CUSTOM_TAG_OPERAND_MATCH'


def inspect(pdf):
    owners = {}
    stream_pages = {}
    for page in pdf.pages:
        streams = page.obj.get('/Contents', [])
        for stream in streams if isinstance(streams, pikepdf.Array) else [streams]:
            stream_pages[stream.objgen] = page.obj.objgen
    def walk(element, inherited=None):
        page = element.get('/Pg', inherited)
        tag = str(element.get('/S', '')).lstrip('/')
        children = element.get('/K', [])
        for child in children if isinstance(children, pikepdf.Array) else [children]:
            if isinstance(child, pikepdf.Dictionary) and '/S' in child:
                walk(child, page)
            elif isinstance(child, int) and not isinstance(child, bool):
                owners[(page.objgen, child)] = tag
            elif isinstance(child, pikepdf.Dictionary) and child.get('/Type') == pikepdf.Name.MCR:
                target = child.get('/Stm', child.get('/Pg', page))
                scope = stream_pages.get(target.objgen, target.objgen)
                if tag.startswith('Mail_'):
                    assert scope in {p.obj.objgen for p in pdf.pages}, 'Use separate Form oracle for custom Form owners'
                owners[(scope, int(child.MCID))] = tag
    roots = pdf.Root.get('/StructTreeRoot', {}).get('/K', [])
    for root in roots if isinstance(roots, (pikepdf.Array, list)) else [roots]:
        walk(root)
    mismatches = []
    for number, page in enumerate(pdf.pages, 1):
        for instruction in pikepdf.parse_content_stream(page):
            if str(instruction.operator) != 'BDC':
                continue
            tag, props = instruction.operands
            if isinstance(props, pikepdf.Name):
                props = page.Resources.Properties[str(props)]
            if '/MCID' not in props:
                continue
            mcid = int(props.MCID)
            expected = owners.get((page.obj.objgen, mcid))
            actual = str(tag).lstrip('/')
            if expected is not None and (expected.startswith('Mail_') or actual.startswith('Mail_')) and expected != actual:
                mismatches.append(dict(page=number, mcid=mcid, actual=actual, expected=expected))
    return mismatches


def inspect_children(pdf, depth=0):
    assert depth < 10
    records = []
    if '/Collection' not in pdf.Root:
        return records
    for name in pdf.attachments:
        attachment = pdf.attachments[name]
        if str(attachment.obj.get('/AFRelationship')) in ('/Source', '/Alternative', '/Supplement'):
            continue
        data = attachment.get_file().read_bytes()
        if not data.startswith(b'%PDF-'):
            continue
        with pikepdf.Pdf.open(io.BytesIO(data)) as child:
            mismatches = inspect(child)
            descendants = inspect_children(child, depth + 1)
        if mismatches or descendants:
            records.append(dict(child=name, sha256=hashlib.sha256(data).hexdigest(),
                                mismatches=mismatches, children=descendants))
    return records


def main():
    update = '--update-expectations' in sys.argv
    records = []
    for manifest in sorted((ROOT / 'tests/fixtures/eapdf').rglob('expectations.json')):
        if manifest.parent.name == 'validator-operands':
            continue
        data = json.loads(manifest.read_text())
        relative = str(manifest.relative_to(ROOT))
        backup = OUT / 'before-expectations' / manifest.parent.name / manifest.name
        if update and not backup.exists():
            backup.parent.mkdir(parents=True, exist_ok=True)
            backup.write_bytes(manifest.read_bytes())
        for case in data['cases']:
            path = manifest.parent / case['file']
            assert hashlib.sha256(path.read_bytes()).hexdigest() == case['sha256']
            with pikepdf.Pdf.open(path) as pdf:
                mismatches = inspect(pdf)
                children = inspect_children(pdf)
            records.append(dict(file=str(path.relative_to(ROOT)), sha256=case['sha256'], mismatches=mismatches, children=children))
            if update and (mismatches or children):
                case['expected_failures'] = sorted(set(case['expected_failures']) | ({CODE} if mismatches else set())
                                                  | ({'COLLECTION_CHILD_VALIDATION'} if children else set()))
                case['expected_status'] = 'FAIL'
                case['operand_review'] = 'EA-PDF 9.7 p75: independent page/MCID inspection in docs/improvement/validator-24/retained-operand-review.json'
        if update:
            manifest.write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n')
    assert len(records) == 202
    (OUT / 'retained-operand-review.json').write_text(json.dumps(records, indent=2) + '\n')
    print('Reviewed', len(records), 'unchanged PDFs;', sum(bool(r['mismatches']) for r in records),
          'have proven operand mismatches;', sum(bool(r['children']) for r in records), 'have affected children.')


if __name__ == '__main__':
    main()
