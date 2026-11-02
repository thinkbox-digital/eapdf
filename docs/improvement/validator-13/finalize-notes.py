"""Publish local completion notes after all scoped V13 gates have completed."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
evidence = json.loads((HERE / 'evidence.json').read_text())
verification = evidence['verification']
unit = verification['current_unit']
assert verification['retained_corpus']['exact_outcomes'] == 108
assert len(verification['profiles']) == 12
assert verification['cli_worker_checks']['cases'] == 8
for name in ('docs/EA_PDF_WORK_LOG.md', 'docs/EA_PDF_IMPROVEMENT_PLAN.md',
             'docs/DEVELOPMENT_STATUS.md', 'docs/VALIDATOR_COVERAGE.md', 'fixed.md', 'unfixed.md'):
    path = ROOT / name
    text = path.read_text()
    prefix = 'improvement/' if name.startswith('docs/') else 'docs/improvement/'
    start = text.index('**V07–V')
    end = text.index('\n\n', start)
    paragraph = (f'**V07–V13 / creator structure — 2026-10-19:** Declaration/schema, verified upstream ISO execution, metadata '
        'and source-asset repairs are accepted. V13 fixes false attachment-annotation failures when the permitted native PDF filename '
        'differs from the original MIME filename; unavailable association stays INCOMPLETE. '
        f'**{unit["passed"]} current unit passes / {unit["skipped"]} integration/schema skips; all 108 retained PDF outcomes, '
        '12 generated normal/accessibility profiles and eight actual CLI/worker checks match.** '
        'The unchanged creator JAR has 161 Java passes; the historical V11 full packaged suite has 375 passes / one optional PREMIS skip. '
        'These are separately bounded gates. The audit has **301 records / 224 codes / 11 open expert/dependency questions**; '
        f'full implementation coverage is not claimed. [Current evidence]({prefix}validator-13/evidence.json), '
        f'[creator diagnosis]({prefix}creator-iso-structure/README.md).')
    text = text[:start] + paragraph + text[end:]
    if name == 'docs/EA_PDF_WORK_LOG.md':
        heading = '### Accepted V13 — original and native attachment filenames'
        section = f'''{heading}

Table 19 keeps the original case-sensitive MIME attachment filename and permits a different PDF embedded filename. The generated-name alternative applies when the original has no filename/name parameter. The validator previously rejected permitted stored names even with an exact checksum. V13 now accepts declared or bounded owner/source-assisted payload evidence and actual empty policy placeholders. It keeps unknown source-omitted aliases unavailable/INCOMPLETE and preserves supplied checksum, size, media type, stream parameter, visibility and page-boundary checks.

Final inputs are frozen in `.eapdf-verification-v13`. **{unit['run']} current tests: {unit['passed']} passes / {unit['skipped']} expected integration/schema skips.** Both generated-profile integration tests pass, covering all twelve normal/accessibility reports and contained email validation. All **108 retained complete-pipeline cases** meet exact PDF/A/custom/ISO outcomes, including the explicit INCOMPLETE case. Eight actual CLI/isolated-worker checks agree. Exact-byte before results, ten new PDFs, source/native hash observations and the initial fixture construction correction are retained in [V13 evidence](improvement/validator-13/evidence.json).

The initial source-omission recipe left dangling OBJR/annotation ParentTree entries; three fixtures correctly failed STRUCTURE_CORRESPONDENCE. The corrected recipe removes only those references and preserves the rendered-label MCID ownership. Keep initial failure logs separate from final acceptance.

No Java/CLI/debug behavior or creator artifact changed in V13. The historical V11 full packaged suite remains 375 passes / one optional PREMIS schema skip; V13 has the separately recorded current unit/corpus/profile/entry-point gate. Eleven expert/dependency questions, broader ownership/occurrence assurance and the inherited-standard edition boundaries remain open. Full coverage and certification are not claimed; preservation B13 remains deferred. All verification lanes have completed; no commit, deployment or external publication occurred.

Keep independent development/build/test work parallel using immutable source snapshots and persistent evidence paths. Continue only from confirmed reviewed requirements; do not invent answers to the open interpretation questions.

'''
        marker = '### Accepted V11 and V12 source-asset follow-up'
        if heading not in text:
            text = text.replace(marker, section + '### Historical V11 and V12 source-asset acceptance', 1)
    path.write_text(text)
path = HERE / 'README.md'
text = path.read_text()
if '## Completed acceptance' not in text:
    text += f'''\n## Completed acceptance

Frozen V13: {unit['passed']} unit passes / {unit['skipped']} expected integration/schema skips;
all 108 retained exact outcomes; all twelve normal/accessibility profile reports;
all eight actual CLI/worker checks. Two profile integration tests pass. Source,
test and fixture hashes still match the freeze. Exact-byte old-engine results
and `association-observations.json` retain the supporting observations.

No V13 full packaged-suite run is claimed. The linked V11 full suite and Java
package keep their historical boundaries. The expert/dependency and coverage
limits listed above remain open.
'''
    path.write_text(text)
path = ROOT / 'docs/improvement/known-next-gaps.json'
data = json.loads(path.read_text())
data['requirements_audit_checkpoint']['current_evidence'] = 'docs/improvement/validator-13/evidence.json'
data['current_acceptance'] = {'current_unit_v13': unit, 'retained_v13_outcomes': 108,
    'normal_accessibility_profile_reports': 12, 'cli_worker_v13_checks': 8,
    'historical_packaged_v11': verification['prior_full_packaged_v11'],
    'boundary': evidence['acceptance_boundary']}
data['attachment_filename_history'] = {'before': 'Permitted native PDF names caused false missing-annotation failures, even with matching payload checksums.',
    'current': 'V13 verifies declared/source-assisted alias association, handles empty placeholders and leaves unknown source-omitted aliases unavailable.',
    'limits': 'Original filename case remains checked against sources; no completeness, occurrence identity or arbitrary global assignment proof.'}
path.write_text(json.dumps(data, indent=2) + '\n')
print('Finalized V13 local notes with completed, separately bounded gates.')
