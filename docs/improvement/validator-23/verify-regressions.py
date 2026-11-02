"""Require parameter-only reporting changes against accepted V22 outcomes."""
from pathlib import Path
import hashlib
import json
import runpy

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
FROZEN=ROOT/'.eapdf-verification-v23'
runpy.run_path(str(HERE/'compare-corpus.py'),run_name='__main__')
rows=json.loads((HERE/'custom-regressions.json').read_text());assert len(rows)==202
assert all(r['before']==r['after'] for r in rows)
expected={'original-path.pdf','original-padding.pdf','native-double-spaces.pdf','source-absent-alias-no-checksum.pdf',
          'renamed-wrong-checksum.pdf','unknown-alias.pdf','unknown-alias-parameters.pdf','unknown-alias-malformed.pdf',
          'mixed-verified-and-unknown.pdf','mixed-bare-and-unknown-parameters.pdf'}
changed={Path(r['file']).name for r in rows if r['before_media_rules']!=r['after_media_rules']}
assert changed==expected,changed
for row in rows:
    assert row['before_media_rules']['EMBEDDED_SUBTYPE_MATCH_XMP']==row['after_media_rules']['EMBEDDED_SUBTYPE_MATCH_XMP']
    if Path(row['file']).name in expected:
        assert row['after_media_rules']['SOURCE_MEDIATYPE_PARAMETERS_MATCH']=='WARN'
        assert row['before_media_rules']['SOURCE_MEDIATYPE_PARAMETERS_MATCH']==('PASS' if Path(row['file']).name=='mixed-verified-and-unknown.pdf' else 'N/A')
lookup={r['file']:r for r in rows}
prior=json.loads((ROOT/'docs/improvement/validator-22/custom-regressions.json').read_text());assert len(prior)==194
for old in prior:
    current=lookup[old['file']]
    assert current['before']==current['after']==old['after'],current
    assert current['before_media_rules']==old['after_media_rules'],current
    assert hashlib.sha256((ROOT/'.eapdf-verification-v22'/old['file']).read_bytes()).hexdigest()==current['sha256']
for manifest in sorted((FROZEN/'tests/fixtures/eapdf').rglob('expectations.json')):
    for case in json.loads(manifest.read_text())['cases']:
        if 'expected_parameter_level' not in case:continue
        row=lookup[str((manifest.parent/case['file']).relative_to(FROZEN))]
        assert row['sha256']==case['sha256']
        assert row['after_media_rules']['SOURCE_MEDIATYPE_PARAMETERS_MATCH']==case['expected_parameter_level'],row
print('202 outcomes verified; all 194 prior PDF hashes and status/failure outcomes unchanged; ten parameter-rule reporting corrections only.',flush=True)
