"""Keep prior bytes/outcomes and verify independently reviewed version and schema name cases."""
import hashlib
import importlib.util
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SNAPSHOT = ROOT / '.eapdf-verification-v56'
sys.path.insert(0, str(SNAPSHOT))
import validate_eapdf as after
spec = importlib.util.spec_from_file_location('before', ROOT / '.eapdf-verification-v55-r2/validate_eapdf.py')
before = importlib.util.module_from_spec(spec)
spec.loader.exec_module(before)
assert hashlib.sha256(Path(before.__file__).read_bytes()).digest() == hashlib.sha256((HERE / 'before-validate_eapdf.py').read_bytes()).digest()
prior = {r['file']: r for r in json.loads((ROOT / 'docs/improvement/validator-55/custom-regressions.json').read_text())}
assert len(prior) == 506
checkpoint_path = HERE / 'custom-regressions-checkpoint.json'
checkpoint_key = hashlib.sha256((HERE / 'verification-inputs.json').read_bytes()).hexdigest()
checkpoint = json.loads(checkpoint_path.read_text()) if checkpoint_path.exists() else dict(inputs_sha256=checkpoint_key, rows=[])
assert checkpoint['inputs_sha256'] == checkpoint_key
cached = {row['file']: row for row in checkpoint['rows']}
rows = []
for manifest in sorted((SNAPSHOT / 'tests/fixtures/eapdf').rglob('expectations.json')):
    for case in json.loads(manifest.read_text())['cases']:
        path = manifest.parent / case['file']
        relative = str(path.relative_to(SNAPSHOT))
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        assert digest == case['sha256']
        row = dict(file=relative, sha256=digest)
        if relative in cached:
            assert cached[relative]['sha256'] == digest
            row = dict(cached[relative])
        else:
            report = after.validate(str(path))
            row['after'] = dict(status=report.summary()['status'], failures=sorted({i['code'] for i in report.items if i['level'] == 'FAIL'}))
            row['observed_levels'] = {code: sorted({item['level'] for item in report.items if item['code'] == code}) for code in sorted(report.seen_codes)}
        assert row['after'] == dict(status=case['expected_status'], failures=sorted(set(case['expected_failures']) - {'ISO32005_UPSTREAM'})), row
        if relative in prior:
            assert digest == prior[relative]['sha256'], row
            expected_prior = prior[relative]['after']
            assert row['after'] == expected_prior, row
            row['before'] = prior[relative]['after']
            row['title_before'] = prior[relative]['observed_levels']['XMP_TITLE']
            row['recommendation_before'] = prior[relative]['observed_levels']['MAIL_CONTENTSET_SUBTYPE_ATTRIBUTE_RECOMMENDED']
            row['before_evidence'] = 'Accepted V55 exact-byte outcome'
        else:
            previous = before.validate(str(path))
            row['title_before'] = sorted({i['level'] for i in previous.items if i['code'] == 'XMP_TITLE'})
            row['before'] = dict(status=previous.summary()['status'], failures=sorted({i['code'] for i in previous.items if i['level'] == 'FAIL'}))
            row['recommendation_before'] = sorted({i['level'] for i in previous.items if i['code'] == 'MAIL_CONTENTSET_SUBTYPE_ATTRIBUTE_RECOMMENDED'})
        if 'expected_description_levels' in case:
            assert row['observed_levels']['DPM_DESC_REQUIRED']==case['expected_description_levels'],row
        if 'expected_art' in case:
            assert row['observed_levels']['MAIL_CONTENTSET_ART_ONLY']==[case['expected_art']],row
        if 'expected_parent_level' in case:
            assert row['observed_levels']['MAIL_CONTENTSET_NESTED_UNDER_MESSAGE']==[case['expected_parent_level']],row
        if 'expected_coverage' in case:
            assert row['observed_levels']['CONTENT_SET_MAIL_CONTENTSET_TAGGED']==[case['expected_coverage']],row
        row['recommendation_after'] = row['observed_levels']['MAIL_CONTENTSET_SUBTYPE_ATTRIBUTE_RECOMMENDED']
        if 'expected_recommendation' in case:
            assert row['recommendation_after'] == [case['expected_recommendation']], row
        row['title_after'] = row['observed_levels']['XMP_TITLE']
        if 'expected_title' in case:
            assert row['title_after'] == [case['expected_title']], row
        rows.append(row)
        checkpoint['rows'] = rows + [entry for key, entry in cached.items() if key not in {r['file'] for r in rows}]
        checkpoint_path.write_text(json.dumps(checkpoint, indent=2) + '\n')
        if len(rows) % 50 == 0:
            print('Verified', len(rows), 'custom outcomes', flush=True)
assert len(rows) == 514
assert sum(r['before'] != r['after'] for r in rows) == 3
(HERE / 'custom-regressions.json').write_text(json.dumps(rows, indent=2) + '\n')
print('514 custom cases match; all 506 prior hashes/statuses/failure sets retained; three decoder exceptions now preserve unavailable description evidence and independent failures.',flush=True)
