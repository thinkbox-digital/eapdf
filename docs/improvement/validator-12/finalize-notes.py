"""Finalize current notes only after the recorded verification gates complete."""
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent


def result(path):
    text = path.read_text()
    match = re.search(r'Ran (\d+) tests in ([\d.]+)s\s+OK(?: \(skipped=(\d+)\))?', text)
    assert match, path
    count, seconds, skips = match.groups()
    skips = int(skips or 0)
    return {'run': int(count), 'passed': int(count)-skips, 'skipped': skips, 'seconds': float(seconds)}


full = result(HERE.parent / 'validator-11/full-tests.log')
unit = result(HERE / 'unit-tests.log')
corpus = json.loads((HERE / 'retained-corpus-results.json').read_text())
assert len(corpus) == 98 and all(row['pdfa'] == 'PASS' and row['failures'] == row['expected']
    and row['status'] == row['expected_status'] and row.get('clauses_match', True) for row in corpus)
entries = json.loads((HERE / 'entry-points.json').read_text())
assert len(entries) == 6 and all(row['pdfa'] == 'PASS' and row['iso'] == 'PASS' for row in entries)
for name in ('docs/EA_PDF_WORK_LOG.md', 'docs/EA_PDF_IMPROVEMENT_PLAN.md',
             'docs/DEVELOPMENT_STATUS.md', 'docs/VALIDATOR_COVERAGE.md', 'fixed.md', 'unfixed.md'):
    path = ROOT / name
    prefix = 'improvement/' if name.startswith('docs/') else 'docs/improvement/'
    text = path.read_text()
    paragraph = (f'**V07–V12 / creator structure — 2026-10-19:** Declaration namespace/schema, verified upstream ISO execution '
        '(1,636 unchanged rules), Table 18/15 semantic values and required metadata repairs are implemented. '
        'The debugger-confirmed creator structure defect is repaired with unchanged image/painting bytes. '
        f'**161 Java tests pass; frozen V11 packaged suite: {full["passed"]} pass / {full["skipped"]} optional schema skip.** '
        f'The subsequent V12 source-asset matching repair has **{unit["passed"]} current unit passes / {unit["skipped"]} integration/schema skips; '
        'all 98 retained PDF outcomes and six actual CLI/worker checks match expectations.** '
        'All six saved profiles pass complete validation. V12 targeted acceptance is recorded separately from the V11 full suite. '
        'The audit has **301 records / 224 codes / 11 open expert/dependency questions**; complete coverage is not claimed. '
        f'[Current evidence]({prefix}validator-12/evidence.json), [V11 full gate]({prefix}validator-11/evidence.json), '
        f'[creator diagnosis]({prefix}creator-iso-structure/README.md).')
    start = text.index('**V07–V')
    end = text.index('\n\n', start)
    text = text[:start] + paragraph + text[end:]
    if name == 'docs/EA_PDF_WORK_LOG.md':
        start = text.index('### Active continuation —') if '### Active continuation —' in text else text.index('### Accepted V11 and V12')
        end = text.index('### V06 accepted;', start)
        text = text[:start] + f'''### Accepted V11 and V12 source-asset follow-up

The frozen V11 packaged suite completed **{full['run']} tests: {full['passed']} pass, {full['skipped']} optional official PREMIS schema skip**, in {full['seconds']} seconds. Its 92 retained cases match exact custom/upstream outcomes. V07–V11 and the confirmed creator repair have this cumulative gate; original interrupted/failed V07–V10 logs retain their own outcomes.

V12 independently reproduced a source-asset false failure: reversing an unordered Bag with colliding filename tails selected the wrong Source before considering its unique checksum. The targeted repair prioritizes checksum evidence, keeps ambiguous association unavailable and preserves independently provable checksum/cardinality failures. Final source is `.eapdf-verification-v12`; **{unit['passed']} current unit passes / {unit['skipped']} skips, all 98 retained complete-pipeline outcomes and six CLI/isolated-worker checks match**. This targeted V12 gate is separate from V11's full packaged run. See [V12 evidence](improvement/validator-12/evidence.json).

The creator JAR remains `.eapdf-build-creator-structure/emailtopdfa-1.4.0.jar`, SHA-256 `272ad741590552489dfb6d096f7ee4b01035fb3faf1c76d817394c0c2ad5e16a`. Its offline Maven package passed 161 Java tests. All six saved samples and configured collection sidecars pass complete validation. The referenced StructureProbe and IntelliJ trace establish the mixed-Div producing path; image bytes, figure alternatives and page painting bytes are unchanged by the repair.

Next: continue bounded source/attachment ownership and implementation-coverage review against the authoritative inventory. The static mixed-fragment review is supporting evidence, not branch coverage or certification. Eleven expert/dependency questions remain explicit; do not infer new normative answers. Broader preservation B13 remains deferred. No verification command remains running when this checkpoint is finalized; no commit, deployment or external publication occurred.

Keep builds/tests parallel with development where independent, using immutable snapshots and persistent report paths. Do not modify target/ or debug directories, CLI arguments or debug output.

''' + text[end:]
    path.write_text(text)

for batch in ('07', '08', '09', '10', '11'):
    path = HERE.parent / ('validator-' + batch) / 'README.md'
    text = path.read_text()
    marker = '## Cumulative acceptance and historical boundaries'
    if marker not in text:
        text += f'''\n## Cumulative acceptance and historical boundaries

The frozen V11 full packaged suite completed {full['run']} tests: {full['passed']}
passes and {full['skipped']} optional PREMIS schema skip. All 92 retained cases meet exact
custom/upstream outcomes. This is the cumulative V07–V11 gate with the corrected
complete creator artifact. [Machine-readable evidence](evidence.json) retains
each earlier run's own outcome; earlier running/pending notes above describe
that historical boundary. V08's original creator/packaging failures and V10's
original URI-size failure remain recorded as failures, not passing full runs.

V12's subsequent asset-association repair has its own [targeted acceptance](../validator-12/evidence.json).
'''
    path.write_text(text)
path = HERE.parent / 'creator-iso-structure/README.md'
text = path.read_text().replace('Final broad acceptance and saved-sample regeneration run independently; their\nlogs are retained here and in [V10](../validator-10/README.md).',
    f'Saved-sample regeneration completed with all six profiles passing. The frozen\nV11 full packaged suite completed with {full["passed"]} passes and {full["skipped"]} optional schema skip.\nLogs and [evidence](evidence.json) retain the source/artifact boundaries.')
path.write_text(text)

path = ROOT / 'docs/improvement/known-next-gaps.json'
data = json.loads(path.read_text())
data.setdefault('requirements_audit_initial_checkpoint', data['requirements_audit_checkpoint'])
data['requirements_audit_checkpoint'] = {'status': 'Scoped repairs accepted; complete requirements/implementation coverage remains unclaimed.',
    'records': 301, 'rule_codes': 224, 'open_expert_dependency_questions': 11,
    'unmapped_mandatory_conditional_file_records': 0, 'runtime_unchanged': False,
    'validator_logic_unchanged': False, 'creator_unchanged': False,
    'authoritative_rule_document': 'validator_rule_text.json', 'current_evidence': 'docs/improvement/validator-12/evidence.json',
    'custom_scope': 'Only uncovered EA-PDF assertions; generic predicates stay upstream.',
    'next': 'Bounded source/attachment ownership and implementation-coverage review; obtain expert/dependency dispositions without inventing requirements.'}
data['current_acceptance'] = {'packaged_v11': full, 'unit_v12': unit, 'retained_v12_outcomes': 98,
    'cli_worker_v12_checks': 6, 'boundary': 'The full packaged run predates V12; V12 has targeted complete-pipeline acceptance.'}
data['other_remaining_review'] = [row.replace('Expand the register beyond this first subset and obtain the full ISO/TS 32005 text.',
    'Review remaining implementation and source-ownership coverage against the 301-record inventory; eleven expert/dependency questions remain, including inherited-standard edition boundaries.')
    for row in data['other_remaining_review']]
path.write_text(json.dumps(data, indent=2) + '\n')
print('Finalized current notes with completed, separately bounded gates.')
