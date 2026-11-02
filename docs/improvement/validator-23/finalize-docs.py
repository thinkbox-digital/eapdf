"""Record V23 acceptance and keep remaining validator review explicit."""
from pathlib import Path
import hashlib
import json

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
evidence=json.loads((HERE/'evidence.json').read_text())
assert evidence['verification']['unit_tests']['passed']==447
assert evidence['verification']['custom_comparisons']['parameter_reporting_changes']==10
assert evidence['verification']['entry_points']['checks']==8
updated={}
for name in ('docs/DEVELOPMENT_STATUS.md','docs/EA_PDF_IMPROVEMENT_PLAN.md','docs/EA_PDF_WORK_LOG.md',
             'docs/VALIDATOR_COVERAGE.md','fixed.md','unfixed.md'):
    path=ROOT/name;text=path.read_text();before=hashlib.sha256(path.read_bytes()).hexdigest()
    prefix='improvement/' if name.startswith('docs/') else 'docs/improvement/'
    checkpoint=('**V23 accepted — 2026-10-20:** Declared attachment parameters without a native association remain WARN/unavailable, including mixed verified/unmatched records. '
        '**447 unit passes / 40 skips; nine PDF cases, twelve generated profiles, 202 custom comparisons and eight CLI/web-worker checks match.** '
        'Ten parameter-rule results improve: nine N/A-to-WARN and one PASS-to-WARN. All 194 prior PDF hashes and status/failure outcomes are unchanged. '
        'No native-copy requirement is added; creator unchanged; eleven questions remain open. '
        '[Evidence]('+prefix+'validator-23/evidence.json).\n\n')
    if '**V23 accepted — 2026-10-20:**' not in text:
        first,rest=text.split('\n',1);text=first+'\n\n'+checkpoint+rest.lstrip('\n')
    if name=='docs/EA_PDF_WORK_LOG.md' and '### V23 accepted — unmatched parameter evidence' not in text:
        section=('### V23 accepted — unmatched parameter evidence\n\n'
            'Reviewed EA-PDF 9.4 pp66–67, Table 19 pp89–90 and 7.5 p44. Within the existing native-file comparison scope, '
            'a normal attachment Content-Type declaring parameters remains unavailable when no native payload association is established. '
            'A separate verified record cannot establish parameter agreement for an unmatched declaration. The parameter rule now reports '
            'WARN rather than N/A or aggregate PASS; definite mismatches retain FAIL. Candidate selection and native-file scope are unchanged.\n\n'
            'Eight new PDF probes cover unmatched aliases, malformed text, mixed verified/unavailable or failed/unavailable records, '
            'a checksum-supported alias, bare declarations and an Alternative-only inventory including the archive index workbook. '
            'The retained `unknown-alias.pdf` is independently checked too. All nine identical-byte before/after PDFs pass PDF/A and '
            'verified upstream ISO checks, and eight real CLI/worker outcomes match. Ten parameter-rule results improve: '
            'nine N/A-to-WARN and one PASS-to-WARN. Overall statuses and failure codes are unchanged across all 202 cases; '
            'all 194 prior PDF hashes remain unchanged.\n\n'
            'Inputs: `.eapdf-verification-v23`. [Review](improvement/validator-23/README.md), '
            '[evidence](improvement/validator-23/evidence.json). No owner or alias is guessed and no additional native-copy requirement '
            'is imposed on absent/Source-only/Alternative-only inventories. Java sources/JAR are unchanged; no new Maven or complete '
            'packaged-suite gate is claimed. Source-asset association, occurrence assignment, shared-rule runtime coverage, '
            'original-inventory completeness and eleven expert/dependency questions remain open. B13 stays deferred.\n\n')
        marker='### V22 accepted';assert marker in text;text=text.replace(marker,section+marker,1)
    if name=='docs/EA_PDF_IMPROVEMENT_PLAN.md':
        text=text.replace('targeted validator repairs are accepted through V22.','targeted validator repairs are accepted through V23.')
        text=text.replace('Scoped validator repairs are accepted through V22.','Scoped validator repairs are accepted through V23.')
        text=text.replace('[latest evidence](improvement/validator-22/evidence.json)','[latest evidence](improvement/validator-23/evidence.json)')
        text=text.replace('Compatible or unreadable candidates retain unavailable comparisons; occurrence assignment remains open.',
            'Compatible or unreadable candidates retain unavailable comparisons. V23 also keeps declared parameters unavailable when existing native files cannot be associated; occurrence and source-asset assignment remain open.')
    path.write_text(text)
    updated[name]=dict(before=before,after=hashlib.sha256(path.read_bytes()).hexdigest())
(HERE/'documentation-overlay.json').write_text(json.dumps(updated,indent=2)+'\n')
print('Updated six V23 checkpoint documents; remaining review stays explicit.')
