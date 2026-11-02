"""Publish the accepted V18 checkpoint in the local restart documents."""
from pathlib import Path
import hashlib
import json

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
evidence=json.loads((HERE/'evidence.json').read_text())
assert evidence['verification']['unit_tests']['passed']==404
assert evidence['verification']['entry_points']['checks']==8
updated=[]
for name in ('docs/DEVELOPMENT_STATUS.md','docs/EA_PDF_IMPROVEMENT_PLAN.md','docs/EA_PDF_WORK_LOG.md',
             'docs/VALIDATOR_COVERAGE.md','fixed.md','unfixed.md'):
    path=ROOT/name;text=path.read_text()
    link=('improvement/' if name.startswith('docs/') else 'docs/improvement/')+'validator-18/evidence.json'
    checkpoint=('**V18 accepted — 2026-10-20:** Attachment source comparisons no longer depend on XMP record order. '
        '**404 unit passes / 35 skips; six PDF cases, twelve generated profiles, 156 custom comparisons and eight CLI/web-worker checks match.** '
        'All 150 prior fixture bytes and custom outcomes are unchanged. Creator unchanged. Native occurrence assignment and eleven review questions remain open. '
        '[Evidence]('+link+').\n\n')
    if '**V18 accepted — 2026-10-20:**' not in text:
        first,rest=text.split('\n',1);text=first+'\n\n'+checkpoint+rest.lstrip('\n')
    if name=='docs/EA_PDF_WORK_LOG.md' and '### V18 accepted — unordered source attachments' not in text:
        section=('### V18 accepted — unordered source attachments\n\n'
            'Recovered thread `01a1596d-69b2-73f0-8dc0-215b9a291ea9` and continued its interrupted validator review. '
            'Table 19 records are unordered. Compatible source occurrences are now assigned before diagnostic pairing, '
            'so optional checksums and zero-byte placeholders cannot consume an occurrence needed by another valid record. '
            'Equivalent assignments establish field agreement, not unique occurrence identity.\n\n'
            'Final inputs are frozen in `.eapdf-verification-v18-final`. The source matcher visits candidates as needed; '
            'a 200-record probe reduced peak traced Python allocation from 387,276 to 51,864 bytes. '
            'The initial eager-candidate run, wrong container-mount attempt and Docker thread-limit report remain retained separately. '
            'Final independent PDF, profile and entry-point gates pass. Two absent-checksum controls retain INCOMPLETE native associations. '
            '[Review](improvement/validator-18/README.md), [evidence](improvement/validator-18/evidence.json).\n\n'
            'Question 8 concerns coverage of EA-PDF\'s dated 2024 PDF/A-4 draft, rather than a free choice of editions. '
            '[The evidence checklist and unsent maintainer question](improvement/pdfa4-edition-coverage.md) record how to resolve it. '
            'Broader validator coverage, native occurrence assignment and original-input completeness remain open; B13 stays deferred.\n\n')
        marker='### V17 accepted'
        assert marker in text
        text=text.replace(marker,section+marker,1)
    path.write_text(text);updated.append(name)
readme=HERE/'README.md'
text=readme.read_text()
text=text.replace('The first unit attempt predates the','The initial eager-candidate results remain in `initial-eager`. A final worker\nretry sets `GOMAXPROCS=2` after Docker could not allocate a thread; its failed\nreport is retained as `placeholder-first-worker-thread-error.json`. The first\nunit attempt predates the')
readme.write_text(text)
updated.extend(['docs/improvement/validator-18/README.md','docs/improvement/pdfa4-edition-coverage.md'])
manifest=json.loads((HERE/'verification-inputs-final.json').read_text())
overlay={name:dict(before=manifest.get(name),after=hashlib.sha256((ROOT/name).read_bytes()).hexdigest()) for name in updated}
(HERE/'documentation-overlay.json').write_text(json.dumps(overlay,indent=2)+'\n')
print('Updated',len(updated),'local checkpoint/support documents after V18 acceptance.')
