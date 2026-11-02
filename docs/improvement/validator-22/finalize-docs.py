"""Record the accepted V22 checkpoint while preserving open coverage boundaries."""
from pathlib import Path
import hashlib
import json

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
evidence=json.loads((HERE/'evidence.json').read_text())
assert evidence['verification']['unit_tests']['passed']==440
assert evidence['verification']['pdf_probes']['cases']==12
assert evidence['verification']['entry_points']['checks']==8
updated={}
for name in ('docs/DEVELOPMENT_STATUS.md','docs/EA_PDF_IMPROVEMENT_PLAN.md','docs/EA_PDF_WORK_LOG.md',
             'docs/VALIDATOR_COVERAGE.md','fixed.md','unfixed.md'):
    path=ROOT/name;text=path.read_text();before=hashlib.sha256(path.read_bytes()).hexdigest()
    prefix='improvement/' if name.startswith('docs/') else 'docs/improvement/'
    checkpoint=('**V22 accepted — 2026-10-20:** Ambiguous native associations no longer hide universally contradicted media types or parameters. '
        '**440 unit passes / 39 skips; twelve PDF cases, twelve generated profiles, 194 custom comparisons and eight CLI/web-worker checks match.** '
        'Six new probes and two retained cases gain proved media/parameter failures; two compatible-parameter controls now report WARN instead of N/A. '
        'All 184 prior PDF hashes and 182 other prior status/failure outcomes are unchanged. '
        'Occurrence assignment remains open; creator unchanged; eleven questions remain. '
        '[Evidence]('+prefix+'validator-22/evidence.json).\n\n')
    if '**V22 accepted — 2026-10-20:**' not in text:
        first,rest=text.split('\n',1);text=first+'\n\n'+checkpoint+rest.lstrip('\n')
    if name=='docs/EA_PDF_WORK_LOG.md' and '### V22 accepted — ambiguous media contradictions' not in text:
        section=('### V22 accepted — ambiguous media contradictions\n\n'
            'Reviewed EA-PDF 7.5 printed p44, 9.4 printed pp66–67 and Table 19 printed pp89–90. '
            'For unavailable associations with nonempty candidate sets, a supplied media type or parameters fails only when every '
            'possible native reconstruction contradicts that field. Parameters are compared independently of the bare type. '
            'A compatible or unreadable candidate blocks the universal field failure. Ambiguous declared-parameter comparisons '
            'without a proved mismatch now remain WARN/unavailable and cannot suppress a separate definite mismatch.\n\n'
            'Six new negative probes and retained `isolated-ambiguous-media.pdf` change from INCOMPLETE to FAIL. '
            'Retained `ambiguous-records.pdf` keeps its size failure and gains the media failure. Two compatible-parameter '
            'controls improve from N/A to WARN without changing overall status. Legal MIME/encoded equivalence, exact malformed '
            'text including semicolon spaces, unknown reconstructions, schema-invalid declarations, Source/Alternative exclusions '
            'and mixed verified/unavailable parameter findings have controls. All twelve identical-byte before/after PDFs pass '
            'independent PDF/A and verified upstream ISO checks; actual CLI and isolated-worker outcomes match. '
            'All 184 prior PDF hashes and 182 other prior status/failure outcomes and media-rule severities are unchanged.\n\n'
            'Inputs: `.eapdf-verification-v22`. [Review](improvement/validator-22/README.md), '
            '[evidence](improvement/validator-22/evidence.json). Java sources/JAR are unchanged; no new Maven or complete packaged-suite '
            'acceptance is claimed. Continue occurrence/ownership and shared-rule runtime review; unresolved candidate comparisons, '
            'original inventory completeness and eleven expert/dependency questions remain open. B13 stays deferred.\n\n')
        marker='### V21 accepted';assert marker in text;text=text.replace(marker,section+marker,1)
    if name=='docs/EA_PDF_IMPROVEMENT_PLAN.md':
        text=text.replace('targeted validator repairs are accepted through V21.','targeted validator repairs are accepted through V22.')
        text=text.replace('Scoped validator repairs are accepted through V21.','Scoped validator repairs are accepted through V22.')
        text=text.replace('[latest evidence](improvement/validator-21/evidence.json)','[latest evidence](improvement/validator-22/evidence.json)')
        text=text.replace('V21 rejects size/checksum values contradicted by every possible native payload; association and ambiguous media comparisons remain open.',
            'V21/V22 reject size/checksum/media/parameter values contradicted by every possible native payload. Compatible or unreadable candidates retain unavailable comparisons; occurrence assignment remains open.')
    path.write_text(text)
    updated[name]=dict(before=before,after=hashlib.sha256(path.read_bytes()).hexdigest())
(HERE/'documentation-overlay.json').write_text(json.dumps(updated,indent=2)+'\n')
print('Updated six V22 checkpoint documents; remaining review stays explicit.')
