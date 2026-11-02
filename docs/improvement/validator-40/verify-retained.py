"""Reuse fresh V39 upstream receipts after native raw-name guards/current custom runs."""
import hashlib
import io
import json
from pathlib import Path
import sys

import pikepdf

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / '.eapdf-verification-v40'))
import validate_eapdf as v

digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
prior = json.loads((ROOT / 'docs/improvement/validator-39/pdf-results.json').read_text())
profiles = json.loads((ROOT / 'docs/improvement/validator-24/profile-diagnosis.json').read_text())['profiles']


def native_guard(data, depth=0):
    assert depth < 8
    count = 0
    messages = 0
    children = []
    with pikepdf.open(io.BytesIO(data)) as pdf:
        roots = pdf.Root.get('/StructTreeRoot', {}).get('/K', [])
        pending = list(roots) if isinstance(roots, pikepdf.Array) else [roots]
        while pending:
            element = pending.pop()
            if not isinstance(element, pikepdf.Dictionary) or '/S' not in element:
                continue
            if str(element.S).startswith('/Mail_'):
                # These retained creator files use direct attributes, not classes.
                assert element.get('/C') is None
                value = element.get('/A', [])
                attrs = list(value) if isinstance(value, pikepdf.Array) else [value]
                active_names = []
                for attr in attrs:
                    if not isinstance(attr, pikepdf.Dictionary) or attr.get('/O') != pikepdf.Name.UserProperties:
                        continue
                    properties = attr.P
                    assert isinstance(properties, pikepdf.Array)
                    active_names = []
                    for prop in properties:
                        name = prop.N
                        assert isinstance(name, pikepdf.String) and all(byte < 128 for byte in bytes(name))
                        assert str(name) == ' '.join(str(name).split())
                        active_names.append(str(name))
                        count += 1
                if element.S == pikepdf.Name('/Mail_Message'):
                    assert 'Mail_GUID' in active_names
                    messages += 1
            kids = element.get('/K', [])
            pending.extend(list(kids) if isinstance(kids, pikepdf.Array) else [kids])
        if '/Collection' in pdf.Root:
            for name in pdf.attachments:
                attachment = pdf.attachments[name]
                if str(attachment.obj.get('/AFRelationship')) in ('/Source', '/Alternative', '/Supplement'):
                    continue
                child = attachment.get_file().read_bytes()
                if child.startswith(b'%PDF-'):
                    children.append(native_guard(child, depth + 1))
    return dict(sha256=hashlib.sha256(data).hexdigest(), raw_ascii_and_exact_name_guards_pass=True,
                names_checked=count, required_message_names_checked=messages, children=children)


rows = []
for profile in profiles:
    key = profile['mode'] + '-' + profile['profile']
    receipt = next(row for row in prior if row['file'] == key and row['entry'] == 'retained-profile')
    source = Path(json.loads((ROOT / profile['report']).read_text())['summary']['file'])
    assert digest(source) == receipt['sha256']
    native = native_guard(source.read_bytes())
    report = v.validate(str(source))
    failures = sorted({item['code'] for item in report.items if item['level'] == 'FAIL'})
    assert report.summary()['status'] == 'FAIL' and failures == profile['failures']
    assert not any(item['code'] in ('USERPROPS_NAME_ASCII', 'MAIL_MESSAGE_GUID_ATTRIBUTE')
                   and item['level'] == 'FAIL' for item in report.items)
    saved = ROOT / 'docs/improvement/validator-39/pdf-reports' / (key + '-retained-profile.json')
    rows.append(dict(profile=key, sha256=digest(source), native_guards=native,
                     current_custom_status='FAIL', current_custom_failures=failures,
                     reused_upstream_report=str(saved.relative_to(ROOT)), reused_report_sha256=digest(saved),
                     pdfa=receipt['pdfa'], iso=receipt['iso'], profile_verified=receipt['profile_verified']))
assert len(rows) == 12
(HERE / 'retained-profile-preconditions.json').write_text(json.dumps(dict(
    boundary='Fresh V39 upstream reports reused on exact original hashes after independent raw-ASCII/exact-name guards, including embedded collection children, and twelve current custom runs. No new creator engine run/build/sample generation.',
    rows=rows), indent=2) + '\n')
print('Twelve exact creator hashes and current custom failures match; native raw-name guards, including children, permit reuse of fresh V39 upstream receipts.')
