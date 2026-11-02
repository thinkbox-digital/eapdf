"""Bound reuse of the twelve retained creator runs by native object inspection."""
import hashlib
import json
from pathlib import Path
import pikepdf
ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
old = json.loads((ROOT / 'docs/improvement/validator-30/retained-name-preconditions.json').read_text())
rows = []
required = dict(To='S', From='S', Sent='D', Subject='S', **{'Message-ID': 'S'}, Cc='S', Bcc='S', Size='Size', Attachments='N')
for entry in old['rows']:
    key = entry['profile']
    mode, profile = key.split('-', 1)
    source_report = ROOT / 'docs/improvement/validator-24' / ('accessibility-profiles' if mode == 'accessibility' else 'profiles') / (profile + '.json')
    # Use the exact authoritative V24 report path, rather than infer directory names.
    diagnosis = json.loads((ROOT / 'docs/improvement/validator-24/profile-diagnosis.json').read_text())
    record = next(r for r in diagnosis['profiles'] if r['mode'] == mode and r['profile'] == profile)
    report = json.loads((ROOT / record['report']).read_text())
    path = Path(report['summary']['file'])
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    assert digest == entry['sha256']
    with pikepdf.Pdf.open(path) as pdf:
        header = str(pdf.pdf_version)
        version = pdf.Root.get('/Version')
        assert header in ('1.7', '2.0')
        assert version is None or isinstance(version, pikepdf.Name)
        catalog = str(version).lstrip('/') if version is not None else ''
        assert not catalog or catalog == header  # The precedence correction is inactive on these inputs.
        subtypes = {}
        if profile in ('1c', '1ci'):
            for name, expected in required.items():
                value = pdf.Root.Collection.Schema['/' + name].Subtype
                assert isinstance(value, pikepdf.Name) and value == pikepdf.Name('/' + expected)
                subtypes[name] = str(value)
    rows.append(dict(profile=key, sha256=digest, header=header, catalog=catalog,
                     required_subtypes=subtypes, added_guards=True,
                     reused_v29_pipeline_outcome=entry['reused_v29_pipeline_outcome']))
assert len(rows) == 12 and all(r['reused_v29_pipeline_outcome']['status'] == 'FAIL' for r in rows)
(HERE / 'retained-name-preconditions.json').write_text(json.dumps(dict(
    boundary='Independent header/name/subtype checks; twelve exact V29 engine runs reused via V30. No new creator engine execution.', rows=rows), indent=2) + '\n')
print('All twelve retained creator inputs satisfy the added guards and have unchanged effective versions; known conformance failures remain.')
