"""Update restart notes after V17 acceptance."""
from pathlib import Path
import json

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
evidence=json.loads((HERE/'evidence.json').read_text())
assert evidence['verification']['unit_tests']['passed']==396
assert evidence['verification']['cli_worker_checks']['cases']==16

for name in ('docs/DEVELOPMENT_STATUS.md','docs/EA_PDF_IMPROVEMENT_PLAN.md',
             'docs/EA_PDF_WORK_LOG.md','docs/VALIDATOR_COVERAGE.md','fixed.md','unfixed.md'):
    path=ROOT/name
    text=path.read_text()
    assert '**V17 accepted' not in text
    text=text.replace(' A separate Message-ID-only owner-routing false failure is confirmed and is the next scoped repair; no V17 acceptance is claimed.','')
    prefix='improvement/' if name.startswith('docs/') else 'docs/improvement/'
    checkpoint=('**V17 accepted — 2026-10-20:** Fixed Message-ID attachment ownership and missing/zero attachment counts. '
        '**396 unit passes / 34 skips; nine PDF cases, twelve generated profiles, 150 custom comparisons and sixteen CLI/web-worker checks match.** '
        'All 141 older PDF files and custom results are unchanged. Java creator unchanged. '
        f'Eleven expert/dependency questions remain. [Evidence]({prefix}validator-17/evidence.json).\n\n')
    first,rest=text.split('\n\n',1)
    path.write_text(first+'\n\n'+checkpoint+rest)

path=ROOT/'docs/EA_PDF_WORK_LOG.md'
text=path.read_text()
start=text.index('### Accepted V16; continue Message-ID owner routing')
end=text.index('### Accepted V15',start)
text=text[:start]+'''### V17 accepted — attachment owners and counts

Attachment Message-ID now resolves to one email XMP owner before body or source
matching. Identifier case and punctuation are preserved. A supplied GUID takes
priority; optional DPM Message-ID values may be absent. Ambiguous owners remain
unavailable. Owned attachment metadata requires a present, positive count,
including without a source email. It does not prove the exact total.

Final code, rules and tests are frozen in `.eapdf-verification-v17-final`.
All final gates pass. The initial snapshot and logs predate the count repair
and remain separate history. [Review](improvement/validator-17/README.md),
[evidence](improvement/validator-17/evidence.json). No new Java build is claimed.

Continue source/attachment ownership and mixed-rule coverage review against
`validator_rule_text.json`. Eleven expert/dependency questions, global occurrence
assignment and original-input completeness remain open. B13 stays deferred.
Keep output plain and brief, as requested by the user and saved in `AGENTS.md`.

### V16 accepted — filename evidence

V16 preserves exact PDF/MIME filename text and excludes Alternative files from
native annotation matching. Its frozen checks all pass.
[Review](improvement/validator-16/README.md), [evidence](improvement/validator-16/evidence.json).

'''+text[end:]
path.write_text(text)

path=ROOT/'docs/EA_PDF_IMPROVEMENT_PLAN.md'
text=path.read_text().replace('V14/V15 close the confirmed native-byte/role and supplied-identity defects.',
    'V14–V17 fix confirmed attachment byte, role, identity, filename, owner and count defects.')
text=text.replace('[latest evidence](improvement/validator-15/evidence.json)',
    '[latest evidence](improvement/validator-17/evidence.json)')
path.write_text(text)

path=ROOT/'docs/VALIDATOR_COVERAGE.md'
text=path.read_text().replace('portable case-sensitive filenames, ownership, visibility and CropBox containment are checked.',
    'literal original filenames, unique XMP ownership, visibility and CropBox containment are checked. '
    'Owned attachment records require a positive email attachment count. This is a lower bound, not an exact inventory. '
    'Alternative representations do not satisfy native attachment annotations.')
path.write_text(text)

path=HERE/'README.md'
text=path.read_text().replace('Only files ending in `final`\nand the final snapshot support this acceptance.',
    'The final log/report set\nand final snapshot support this acceptance.')
path.write_text(text)
print('Updated V17 restart notes and saved the plain-language preference.')
