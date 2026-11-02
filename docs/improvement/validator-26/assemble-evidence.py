"""Assert V26 parameter/association reporting and unchanged conformance bounds."""
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SNAPSHOT = ROOT / '.eapdf-verification-v26'
read = lambda name: json.loads((HERE / name).read_text())
digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
manifest = read('verification-inputs.json')
for name, expected in manifest.items():
    assert digest(SNAPSHOT / name) == expected, name
    if name in ('validate_eapdf.py', 'validator_rule_text.json', 'validator_web.py') or name.startswith('tests/'):
        assert digest(ROOT / name) == expected, name
assert re.search(r'Ran 535 tests in [0-9.]+s\s+OK \(skipped=40\)', (HERE / 'unit-tests.log').read_text())
rows = read('custom-regressions.json')
assert len(rows) == 224 and all(r['before'] == r['after'] for r in rows)
prior = {r['file']: r for r in json.loads((ROOT / 'docs/improvement/validator-25/custom-regressions.json').read_text())}
assert len(prior) == 215
assert all(r['sha256'] == prior[r['file']]['sha256'] and r['after'] == prior[r['file']]['after'] for r in rows if r['file'] in prior)
changed = [r for r in rows if r['before_media_rules'] != r['after_media_rules']]
assert len(changed) == 10
params = 'SOURCE_MEDIATYPE_PARAMETERS_MATCH'
media = 'EMBEDDED_SUBTYPE_MATCH_XMP'
assert sum(r['before_media_rules'][params] != r['after_media_rules'][params] for r in rows) == 6
assert all(r['after_media_rules'][params] == 'WARN' for r in rows if r['before_media_rules'][params] != r['after_media_rules'][params])
assert sum((r['before_media_rules'][media], r['after_media_rules'][media]) == ('WARN', 'PASS') for r in rows) == 4
assert sum(r['after_media_rules'][media] == 'WARN' and r['before_media_rules'][media] in ('PASS', 'N/A') for r in rows) == 2
pdfs = read('pdf-results.json')
assert len(pdfs) == 42 and all(r['pdfa'] == r['iso'] == 'PASS' and r['profile_verified'] for r in pdfs)
assert sum(r['entry'] in ('before', 'after') for r in pdfs) == 22
assert sum(r['entry'] in ('cli', 'web-worker') for r in pdfs) == 8
profiles = [r for r in pdfs if r['entry'] == 'retained-profile']
assert len(profiles) == 12 and all(r['status'] == 'FAIL' for r in profiles)
old = json.loads((ROOT / 'docs/improvement/validator-24/evidence.json').read_text())
generated = {r['mode'] + '-' + r['profile']: r for r in old['creator']['generated']}
assert all(r['sha256'] == generated[r['file']]['sha256'] for r in profiles)
rules = json.loads((SNAPSHOT / 'validator_rule_text.json').read_text())
assert len(rules) == 224 and len(rules['VALIDATION_INCOMPLETE']['specification_review']['expert_questions']) == 11
evidence = dict(scope='V26 source parameter evidence, known checksum/name associations and unavailable acceptance',
    verification=dict(unit_tests=dict(run=535, passed=495, skipped=40, log='unit-tests.log'), focused_source_tests=10,
                      custom_comparisons=dict(cases=224, prior_pdf_hashes_and_outcomes_unchanged=215,
                                              status_or_failure_changes=0, media_reporting_rows_changed=10,
                                              parameter_warn_corrections=6, known_subtype_confirmations=4,
                                              unavailable_subtype_corrections=2, results='custom-regressions.json'),
                      pdf_probes=dict(cases=11, new=9, retained=2, identical_bytes_before_after=True, pdfa_passes=11, iso_passes=11),
                      entry_points=dict(cli=4, isolated_workers=4, all_expected_outcomes=True),
                      retained_creator_profiles=dict(cases=12, pdfa_passes=12, iso_passes=12,
                                                      unchanged_operand_or_child_failures=12, creator_acceptance=False)),
    inputs=dict(snapshot='.eapdf-verification-v26', manifest='verification-inputs.json', files=len(manifest),
                runtime_archive='runtime-inputs.zip', runtime_archive_sha256=digest(HERE / 'runtime-inputs.zip')),
    creator=dict(unchanged=True, jar_sha256=old['creator']['jar_sha256'], new_java_build=False, reused_exact_profile_outputs=True),
    limits=['No new source-copy requirement is imposed on absent Source inventories.',
            'Source associations use checksum/name evidence, never the tested size or media value. Ambiguous occurrences remain unresolved.',
            'Exact source filename preservation remains a confirmed next review item; see next-source-filename-probe.json.',
            'Creator conformance remains failed on V24 operands. No Java or full packaged acceptance is claimed.',
            'Forty unit/schema skips, remaining shared/obligation coverage and eleven decisions remain explicit.'])
evidence['artifacts'] = {str(p.relative_to(HERE)): digest(p) for p in sorted(HERE.rglob('*'))
                         if p.is_file() and p.name != 'evidence.json' and p.suffix != '.zip'}
(HERE / 'evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
print('V26 accepted: six parameter corrections, four known subtype confirmations, two unavailable subtype corrections; creator remains failed.')
