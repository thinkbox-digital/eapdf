"""Record the V19 checkpoint after its acceptance gate succeeds."""
from pathlib import Path
import hashlib
import json

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
evidence=json.loads((HERE/'evidence.json').read_text())
assert evidence['verification']['unit_tests']['passed']==412
assert evidence['verification']['entry_points']['checks']==8
updated=[]
for name in ('docs/DEVELOPMENT_STATUS.md','docs/EA_PDF_IMPROVEMENT_PLAN.md','docs/EA_PDF_WORK_LOG.md',
             'docs/VALIDATOR_COVERAGE.md','fixed.md','unfixed.md'):
    path=ROOT/name;text=path.read_text()
    link=('improvement/' if name.startswith('docs/') else 'docs/improvement/')+'validator-19/evidence.json'
    checkpoint=('**V19 accepted — 2026-10-20:** Equivalent attachment metadata now permits stored-byte/media comparisons without assigning an owner. '
        '**412 unit passes / 36 skips; eight PDF cases, twelve generated profiles, 164 custom comparisons and eight CLI/web-worker checks match.** '
        'All 156 prior fixture bytes and custom outcomes are unchanged. Creator unchanged. Eleven review questions and unique occurrence assignment remain open. '
        '[Evidence]('+link+').\n\n')
    if '**V19 accepted — 2026-10-20:**' not in text:
        first,rest=text.split('\n',1);text=first+'\n\n'+checkpoint+rest.lstrip('\n')
    if name=='docs/EA_PDF_WORK_LOG.md' and '### V19 accepted — equivalent attachment comparisons' not in text:
        section=('### V19 accepted — equivalent attachment comparisons\n\n'
            'Multiple attachment records can describe the same stored payload. V19 checks every candidate when its valid '
            'size, checksum presence/value and media expectations agree with the others. Equivalent candidates return '
            'shared media evidence without an arbitrary GUID, Message-ID or occurrence identity. Different expectations '
            'and distinct stored observations remain unavailable; body-owner and source checks retain their existing scope.\n\n'
            'All eight new PDFs were INCOMPLETE under V18. V19 verifies three valid cases and detects a wrong size and a '
            'wrong media type. Three genuinely ambiguous cases remain INCOMPLETE. Every new PDF passes the exercised '
            'independent PDF/A and upstream ISO gates. Frozen inputs: `.eapdf-verification-v19`. '
            '[Review](improvement/validator-19/README.md), [evidence](improvement/validator-19/evidence.json).\n\n'
            'Continue validator ownership/occurrence and mixed-rule coverage review against `validator_rule_text.json`. '
            'Original-inventory completeness, unique assignment and eleven expert/dependency questions remain open. '
            'B13 stays deferred. Java source/JAR are unchanged; no new Maven or full packaged-suite gate is claimed.\n\n')
        marker='### V18 accepted'
        assert marker in text
        text=text.replace(marker,section+marker,1)
    path.write_text(text);updated.append(name)
manifest=json.loads((HERE/'verification-inputs.json').read_text())
overlay={name:dict(before=manifest.get(name),after=hashlib.sha256((ROOT/name).read_bytes()).hexdigest()) for name in updated}
(HERE/'documentation-overlay.json').write_text(json.dumps(overlay,indent=2)+'\n')
print('Updated six local checkpoint documents after V19 acceptance.')
