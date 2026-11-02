"""Refresh the observation review queue from recorded V60 executions.

This reads evidence; it does not infer missing requirements or certify coverage.
The V23 census remains an immutable historical cohort.
"""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SNAPSHOT = ROOT / '.eapdf-verification-v60'
digest = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
rules = json.loads((SNAPSHOT / 'validator_rule_text.json').read_text())
custom = json.loads((HERE / 'custom-regressions.json').read_text())
pipeline = json.loads((HERE / 'pdf-results.json').read_text())
assert len(custom) == 548 and len(pipeline) == 30
observations = []
for case in custom:
    assert digest(SNAPSHOT / case['file']) == case['sha256']
    observations.append(dict(file=case['file'], scope='custom-only', levels=case['observed_levels']))
for row in pipeline:
    if row['entry'] != 'after':
        continue
    path = HERE / 'pdf-reports' / (row['file'] + '-after.json')
    report = json.loads(path.read_text())
    levels = {}
    for item in report['items']:
        levels.setdefault(item['code'], set()).add(item['level'])
    observations.append(dict(file=str(path.relative_to(ROOT)), scope='complete-pipeline',
                             levels={code: sorted(values) for code, values in levels.items()}))
assert sum(o['scope'] == 'complete-pipeline' for o in observations) == 8
inventory = rules['VALIDATION_INCOMPLETE']['specification_review']['inventory_records']
rows = []
for code, rule in rules.items():
    evidence = {scope: {} for scope in ('custom-only', 'complete-pipeline')}
    for observation in observations:
        for level in observation['levels'].get(code, []):
            entry = evidence[observation['scope']].setdefault(level, dict(count=0, examples=[]))
            entry['count'] += 1
            if len(entry['examples']) < 3:
                entry['examples'].append(observation['file'])
    records = [record for record in inventory if code in record['candidate_rule_codes']]
    owner = rule['validation_scope']['decision']
    local = owner in ('retain_custom_eapdf_requirement', 'split_custom_and_verapdf',
                      'split_custom_declaration_and_external_structure')
    levels = set().union(*(set(group) for group in evidence.values()))
    rows.append(dict(code=code, owner=owner, family=rule['validation_scope'].get('coverage_family'),
                     local_assertion=local, requirement_ids=[record['id'] for record in records],
                     mandatory_file_mapping=any(record['scope'] == 'file' and
                         record.get('assessment', {}).get('conformance_effect') == 'mandatory_when_applicable'
                         for record in records),
                     strengths=sorted({record['strength'] for record in records}),
                     observations=evidence, observed_non_na=bool(levels - {'N/A'}),
                     observed_fail='FAIL' in levels))
summary = dict(rule_codes=len(rows), requirements=len(inventory), custom_pdf_cases=548,
               complete_pipeline_controls=8, local_assertions=sum(r['local_assertion'] for r in rows),
               local_without_non_na=[r['code'] for r in rows if r['local_assertion'] and not r['observed_non_na']],
               local_without_fail=[r['code'] for r in rows if r['local_assertion'] and not r['observed_fail']],
               mandatory_file_local_without_fail=[r['code'] for r in rows if r['local_assertion'] and
                   r['mandatory_file_mapping'] and not r['observed_fail']])
output = dict(authority='.eapdf-verification-v60/validator_rule_text.json', view_only=True,
              boundary='Recorded code-level observations, not branch coverage, obligation certification or a defect count. '
                       'A PASS on a broad code does not establish every linked fragment. Recommendations, '
                       'manual evidence, companion codes, applicability and delegated predicates need separate review. '
                       'Focused unit assertions and historical pipeline cohorts are not counted in this view.',
              inputs={str(p.relative_to(ROOT)): digest(p) for p in
                      [SNAPSHOT / 'validate_eapdf.py', SNAPSHOT / 'validator_rule_text.json',
                       HERE / 'verification-inputs.json', HERE / 'custom-regressions.json', HERE / 'pdf-results.json']},
              summary=summary, rows=rows)
(HERE / 'observation-matrix.json').write_text(json.dumps(output, indent=2) + '\n')
lines = ['# V60 observation review', '', output['boundary'], '',
         f"{len(inventory)} requirement records; {len(rows)} codes; 548 custom PDFs and 8 complete-pipeline controls.", '',
         'The earlier V23 matrices remain historical. This view reads frozen V60 execution evidence.', '']
for title, key in [('Local assertions without a non-N/A observation', 'local_without_non_na'),
                   ('Local mandatory file mappings without an observed FAIL', 'mandatory_file_local_without_fail'),
                   ('All local assertions without an observed FAIL', 'local_without_fail')]:
    lines.extend(['## ' + title, ''])
    lines.extend('- `' + code + '`' for code in summary[key])
    if not summary[key]:
        lines.append('None in this execution cohort.')
    lines.append('')
(HERE / 'observation-review.md').write_text('\n'.join(lines))
print(json.dumps(summary, indent=2))
