"""Require exact reviewed changes against the accepted V21 fixture outcomes."""
from pathlib import Path
import hashlib
import json
import runpy

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
FROZEN=ROOT/'.eapdf-verification-v22'
runpy.run_path(str(HERE/'compare-corpus.py'),run_name='__main__')
rows=json.loads((HERE/'custom-regressions.json').read_text());assert len(rows)==194
changed={Path(r['file']).name for r in rows if r['before']!=r['after']}
expected={'one-type-wrong.pdf','all-parameters-wrong.pdf','distinct-types-impossible.pdf',
          'distinct-parameters-wrong.pdf','malformed-parameters-wrong.pdf','matching-parameters-wrong-type.pdf',
          'isolated-ambiguous-media.pdf','ambiguous-records.pdf'}
assert changed==expected,changed
severity_changed={Path(r['file']).name for r in rows if r['before_media_rules']!=r['after_media_rules']}
assert severity_changed==expected|{'parameters-compatible.pdf','malformed-parameters-compatible.pdf'},severity_changed
lookup={r['file']:r for r in rows}
prior=json.loads((ROOT/'docs/improvement/validator-21/custom-regressions.json').read_text());assert len(prior)==184
for old in prior:
    current=lookup[old['file']]
    assert current['before']==old['after'],current
    assert hashlib.sha256((ROOT/'.eapdf-verification-v21-final'/old['file']).read_bytes()).hexdigest()==current['sha256']
    if Path(old['file']).name not in ('isolated-ambiguous-media.pdf','ambiguous-records.pdf'):
        assert current['after']==old['after'],current
        assert current['before_media_rules']==current['after_media_rules'],current
for folder,names in [('validator-ambiguous-media',None),('validator-attachment-equivalence',{'isolated-ambiguous-media.pdf'}),
                     ('validator-attachment-streams',{'ambiguous-records.pdf'})]:
    directory=FROZEN/'tests/fixtures/eapdf'/folder
    for case in json.loads((directory/'expectations.json').read_text())['cases']:
        if names is not None and case['file'] not in names:continue
        row=lookup[str((directory/case['file']).relative_to(FROZEN))]
        assert row['sha256']==case['sha256']
        assert row['after']==dict(status=case['expected_status'],failures=sorted(case['expected_failures'])),row
        if case.get('required_parameter_warning'):assert row['after_media_rules']['SOURCE_MEDIATYPE_PARAMETERS_MATCH']=='WARN'
print('194 outcomes verified; all 184 prior PDF hashes unchanged; 182 prior status/failure outcomes unchanged; eight reviewed changes and two unavailable-parameter improvements.',flush=True)
