"""Record V20 acceptance and refresh the current coverage summary."""
from pathlib import Path
import hashlib
import json
import re

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
evidence=json.loads((HERE/'evidence.json').read_text())
assert evidence['verification']['unit_tests']['passed']==421
assert evidence['verification']['entry_points']['checks']==8
updated=[]
for name in ('docs/DEVELOPMENT_STATUS.md','docs/EA_PDF_IMPROVEMENT_PLAN.md','docs/EA_PDF_WORK_LOG.md',
             'docs/VALIDATOR_COVERAGE.md','fixed.md','unfixed.md'):
    path=ROOT/name;text=path.read_text()
    link=('improvement/' if name.startswith('docs/') else 'docs/improvement/')+'validator-20/evidence.json'
    checkpoint=('**V20 accepted — 2026-10-20:** Media comparisons preserve malformed parameter text and quoted spaces. '
        '**421 unit passes / 37 skips; ten PDF cases, twelve generated profiles, 174 custom comparisons and eight CLI/web-worker checks match.** '
        'Five altered values now fail; two preserved-source cases now pass. All 164 prior fixture bytes and custom outcomes are unchanged. '
        'Creator unchanged; eleven review questions remain open. [Evidence]('+link+').\n\n')
    if '**V20 accepted — 2026-10-20:**' not in text:
        first,rest=text.split('\n',1);text=first+'\n\n'+checkpoint+rest.lstrip('\n')
    if name=='docs/EA_PDF_WORK_LOG.md' and '### V20 accepted — original media parameters' not in text:
        section=('### V20 accepted — original media parameters\n\n'
            'MIME recovery could drop malformed parameter text, while source summaries could normalize away quoted spaces. '
            'V20 compares exact preserved text when recovery reports invalid parameter syntax and reads source Content-Type '
            'through raw headers. Valid UTF-8, legal folding/quoting, RFC 2231 forms and MIME body defaults retain controls. '
            'Matching malformed free-text fields remain permitted.\n\n'
            'Ten independent PDF probes preserve identical before/after bytes. Three malformed native/XMP mismatches and two '
            'altered-source parameter values now fail; two preserved-source cases now pass. Three positive controls retain PASS. '
            'Every PDF passes the exercised PDF/A and upstream ISO checks. Inputs: `.eapdf-verification-v20`. '
            '[Review](improvement/validator-20/README.md), [evidence](improvement/validator-20/evidence.json).\n\n'
            'Continue ownership/occurrence and shared-rule coverage review. This repair does not establish exhaustive MIME '
            'semantic or original-inventory coverage. Eleven expert/dependency questions remain open; B13 stays deferred. '
            'Java source/JAR are unchanged; no new Maven or full packaged-suite gate is claimed.\n\n')
        marker='### V19 accepted';assert marker in text
        text=text.replace(marker,section+marker,1)
    if name=='docs/EA_PDF_IMPROVEMENT_PLAN.md':
        text=re.sub(r'^Current checkpoint: .*$',
            'Current checkpoint: **301 specification entries; 224 validator check codes; zero unmapped mandatory/conditional file entries; eleven open questions**. '
            'Scoped validator repairs are accepted through V20. Mapping is established; full implementation coverage remains unproven. '
            '[Work log](EA_PDF_WORK_LOG.md), [latest evidence](improvement/validator-20/evidence.json).',text,flags=re.M)
        text=re.sub(r'^Current evidence: .*$',
            'Current evidence: [requirements](VALIDATION_REQUIREMENTS.md) and [supporting audit](improvement/specification-audit/README.md). '
            'The specification has 97 PDF pages, 46 numbered sections and 19 tables. The audit contains 301 entries mapped to '
            '224 check codes. Eleven questions remain open; the full requirements gate is unfinished. Earlier counts remain in dated checkpoints.',text,flags=re.M)
        text=text.replace('The new 297-record requirements/guidance inventory','The current 301-entry requirements/guidance inventory')
        text=re.sub(r'^Date: 2026-10-20\. Status: .*$',
            'Date: 2026-10-20. Status: targeted validator repairs are accepted through V20. Creator improvements follow validator work. '
            'Earlier preservation batches B01–B12 have accepted evidence; B13 remains deferred. See [the work log](EA_PDF_WORK_LOG.md).',text,flags=re.M)
        if '## Remaining coverage review' not in text:
            remaining=('## Remaining coverage review\n\n'
                '- Review attachment/source occurrence assignment where candidates differ.\n'
                '- Finish runtime coverage of checks shared with veraPDF.\n'
                '- Verify positive, negative and unavailable-evidence cases for the remaining reviewed obligations.\n'
                '- Resolve eleven interpretation/dependency questions using the cited sources and expert decisions.\n\n'
                'Creator repairs follow this validator phase. Broader input-inventory, rendering, accessibility, interoperability '
                'and release acceptance work retains its documented boundaries. The remaining repair count is still unknown.\n\n')
            text=text.replace('## Starting point',remaining+'## Starting point',1)
    path.write_text(text);updated.append(name)
manifest=json.loads((HERE/'verification-inputs.json').read_text())
overlay={name:dict(before=manifest.get(name),after=hashlib.sha256((ROOT/name).read_bytes()).hexdigest()) for name in updated}
(HERE/'documentation-overlay.json').write_text(json.dumps(overlay,indent=2)+'\n')
print('Updated six checkpoint documents and the current coverage summary.')
