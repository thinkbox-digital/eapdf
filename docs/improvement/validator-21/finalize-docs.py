"""Record the accepted V21 field-comparison checkpoint and remaining review."""
from pathlib import Path
import hashlib
import json

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
evidence=json.loads((HERE/'evidence.json').read_text())
assert evidence['verification']['unit_tests']['passed']==430
assert evidence['verification']['pdf_probes']['cases']==12
assert evidence['verification']['entry_points']['checks']==8
updated={}
for name in ('docs/DEVELOPMENT_STATUS.md','docs/EA_PDF_IMPROVEMENT_PLAN.md','docs/EA_PDF_WORK_LOG.md',
             'docs/VALIDATOR_COVERAGE.md','fixed.md','unfixed.md'):
    path=ROOT/name;text=path.read_text();before=hashlib.sha256(path.read_bytes()).hexdigest()
    prefix='improvement/' if name.startswith('docs/') else 'docs/improvement/'
    checkpoint=('**V21 accepted — 2026-10-20:** Ambiguous native associations no longer hide a size/checksum contradicted by every possible payload. '
        '**430 unit passes / 38 skips; twelve PDF cases, twelve generated profiles, 184 custom comparisons and eight CLI/web-worker checks match.** '
        'Five new negative probes and two retained cases now fail; all 174 prior PDF hashes and 172 other prior custom outcomes are unchanged. '
        'Association remains unavailable; no owner or occurrence is selected. Creator unchanged; eleven questions remain open. '
        '[Evidence]('+prefix+'validator-21/evidence.json).\n\n')
    if '**V21 accepted — 2026-10-20:**' not in text:
        first,rest=text.split('\n',1);text=first+'\n\n'+checkpoint+rest.lstrip('\n')
    if name=='docs/EA_PDF_WORK_LOG.md' and '### V21 accepted — ambiguous native-byte contradictions' not in text:
        section=('### V21 accepted — ambiguous native-byte contradictions\n\n'
            'Reviewed EA-PDF 7.5 printed p44 and 9.8.4 Table 19 printed pp89–90. A valid supplied SizeInBytes or optional CheckSum '
            'fails when every possible native candidate has an available contradicting observation for that field. A matching or '
            'unreadable candidate blocks that field failure. Existing payload/name association candidates, Source/Alternative exclusions '
            'and unavailable-association reporting are retained; neither size nor checksum selects an owner or occurrence.\n\n'
            'Ten new PDF probes include five negative cases, four unavailable controls and one equivalent-metadata positive. '
            'Two retained cases now fail: `isolated-ambiguous-size.pdf` declares size 8 against 7-byte candidates; '
            '`ambiguous-records.pdf` declares size 1 against a 49,338-byte candidate. Both retain unavailable association warnings. '
            'All twelve before/after probe PDFs pass independent PDF/A and verified upstream ISO checks on identical bytes. '
            'All 174 prior PDF hashes and 172 other prior custom outcomes are unchanged.\n\n'
            'Final inputs: `.eapdf-verification-v21-final`. The initial frozen inputs are retained; only the historical stream-fixture '
            'expectation manifest differs. Initial failed expectation assertions are retained and reviewed. Runtime, configuration and '
            'PDF bytes are identical across the snapshots; final units and custom comparisons use the final inputs. '
            '[Review](improvement/validator-21/README.md), [evidence](improvement/validator-21/evidence.json), '
            '[input boundary](improvement/validator-21/initial-to-final-inputs.json).\n\n'
            'Continue attachment/source occurrence and shared-rule runtime coverage review. Ambiguous media comparisons, original '
            'inventory completeness and eleven expert/dependency questions remain open. Java source/JAR are unchanged; no new Maven '
            'or complete packaged-suite gate is claimed. B13 stays deferred.\n\n')
        marker='### V20 accepted';assert marker in text;text=text.replace(marker,section+marker,1)
    if name=='docs/EA_PDF_IMPROVEMENT_PLAN.md':
        text=text.replace('targeted validator repairs are accepted through V20.','targeted validator repairs are accepted through V21.')
        text=text.replace('Scoped validator repairs are accepted through V20.','Scoped validator repairs are accepted through V21.')
        text=text.replace('[latest evidence](improvement/validator-20/evidence.json)','[latest evidence](improvement/validator-21/evidence.json)')
        text=text.replace('- Review attachment/source occurrence assignment where candidates differ.',
            '- Continue attachment/source occurrence assignment where candidates differ. V21 rejects size/checksum values contradicted by every possible native payload; association and ambiguous media comparisons remain open.')
    path.write_text(text)
    updated[name]=dict(before=before,after=hashlib.sha256(path.read_bytes()).hexdigest())
(HERE/'documentation-overlay.json').write_text(json.dumps(updated,indent=2)+'\n')
print('Updated six V21 checkpoint documents; remaining coverage review stays explicit.')
