"""Publish the local restart checkpoint only after V15 evidence passes assembly."""
from pathlib import Path
import json

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
e=json.loads((HERE/'evidence.json').read_text())
assert e['verification']['unit_tests']['passed']==381
assert e['verification']['cli_worker_checks']['cases']==16
summary='''**V15 accepted — 2026-10-20:** A supplied attachment `Mail_GUID` must retain its normal text type when `Message-ID` permits omission. **381 current unit passes / 32 skips; eight exact-byte before/after PDF/A/custom/ISO probes, all 133 custom-only fixture comparisons and sixteen CLI/isolated-worker checks match.** V14's separately bounded 125 complete-pipeline cases and twelve generated profiles are unchanged prior evidence. Creator source/JAR is unchanged; its 161 Java passes remain historical. Eleven expert/dependency questions and broader ownership/occurrence review remain open; full coverage is not claimed. [Evidence](improvement/validator-15/evidence.json).\n\n'''
for name in ('docs/DEVELOPMENT_STATUS.md','docs/EA_PDF_WORK_LOG.md','docs/EA_PDF_IMPROVEMENT_PLAN.md','fixed.md','unfixed.md'):
    p=ROOT/name;s=p.read_text();pos=s.index('\n')+1
    prefix=summary.replace('(improvement/', '(docs/improvement/') if '/' not in name else summary
    s=s[:pos]+'\n'+prefix+s[pos:].lstrip('\n')
    s=s.replace(" V15's supplied-identity schema repair is in verification; no final V15 gate is claimed here.",'')
    p.write_text(s)
p=ROOT/'docs/EA_PDF_WORK_LOG.md';s=p.read_text()
s=s.replace('### Accepted V14; V15 supplied-identity verification running','### Historical recovery boundary: V14 accepted; V15 initially in verification')
resume='''### Accepted V15 — supplied conditional attachment identity

The final application/rules/tests and corrected fixtures are frozen in `.eapdf-verification-v15-final`. **413 current tests: 381 pass, 32 expected integration/schema skips.** Eight final identical-byte before/after probes all pass independent PDF/A and upstream ISO; the three malformed supplied-GUID forms now fail exactly rule 199. Five legal representations remain accepted. All **133 custom-only fixture comparisons** match the reviewed delta: only those three new failures change. All **sixteen actual CLI/isolated-worker checks** match, including the collection/Alternative controls.

The V14 engine accepted the three malformed records. V15 changes only the supplied `Mail_GUID` type branch and authoritative rule 199 description; creator source/resources/JAR and upstream adapter/profile are unchanged. All 125 inherited V14 fixture bytes and custom outcomes match. Its twelve generated profiles remain a separate prior gate; no fresh V15 twelve-profile/full packaged-suite/Java run is claimed. See [evidence](improvement/validator-15/evidence.json), [review](improvement/validator-15/README.md) and [bounded metadata inspection](improvement/validator-15/metadata-review.json).

Initial malformed attribute serialization and the worker Docker-client thread-limit failure are retained separately. The corrected attribute recipe uses an inner RDF Description node; seven other original PDF byte streams are preserved. Final worker checks run after larger lanes finish with `GOMAXPROCS=2` for the Docker client, retaining the configured validation limits. A final supporting fixture citation was corrected to collection sections 7.6/9.5 without application or PDF-byte changes.

The two confirmed repairs from this continuation are accepted. Continue only against reviewed requirements; eleven explicit interpretation/dependency questions, global owner/occurrence assurance, original-source completeness and inherited-edition equivalence remain open. The supporting metadata inspection is not exhaustive branch coverage or certification. Broader preservation B13 remains deferred. No commit, deployment or external publication occurred.

'''
s=s.replace('## Resume here\n\n','## Resume here\n\n'+resume,1);p.write_text(s)
p=ROOT/'docs/EA_PDF_IMPROVEMENT_PLAN.md';s=p.read_text().replace('Date: 2026-10-19. Status: scoped validator improvements','Date: 2026-10-20. Status: scoped validator improvements',1)
old='Current checkpoint: **301 records; 220 code ownership dispositions; 11 explicit questions remain**. The user authorized validator improvements on 2026-10-19 after resolving `Mail_ContentField`→`Mail_Field`; unresolved unrelated questions remain explicit rather than blocking every scoped repair. The first Table 11/reporting repair is verified. Six mandatory/conditional file records still have no executed-rule mapping. [Current work log](EA_PDF_WORK_LOG.md), [evidence](improvement/validator-01-evidence.json).'
new='Current checkpoint: **301 records; 224 code ownership dispositions; zero unmapped mandatory/conditional file records; 11 explicit questions remain**. The user authorized scoped validator repairs after resolving `Mail_ContentField`→`Mail_Field`. V14/V15 close the confirmed native-byte/role and supplied-identity defects. Mapping coverage is not implementation completeness. Broader ownership/occurrence review and the expert/dependency questions remain open. [Current work log](EA_PDF_WORK_LOG.md), [latest evidence](improvement/validator-15/evidence.json).'
assert old in s;s=s.replace(old,new);p.write_text(s)
p=ROOT/'docs/DEVELOPMENT_STATUS.md';s=p.read_text();old='| **P0 — begin the improvement plan** | Start steps 1 and 2 together: repair array handling, make rule applicability explicit, and establish the first reviewed valid/invalid fixtures and requirement records. See [the work order](EA_PDF_IMPROVEMENT_PLAN.md). |'
new='| **P0 — continue reviewed coverage/ownership work** | The confirmed V14/V15 repairs are accepted. Continue from the current work log against authoritative requirements; retain the eleven expert/dependency questions and broader owner/occurrence boundaries. Earlier array/applicability and first-fixture work is historical. See [the work order](EA_PDF_IMPROVEMENT_PLAN.md). |'
assert old in s;p.write_text(s.replace(old,new))
for version in ('14','15'):
    p=ROOT/'docs/improvement'/('validator-'+version)/'README.md';s=p.read_text()
    if version=='14':
        s=s.replace('Run `assemble-evidence.py` only after all lanes finish. Its assertions verify', 'All final lanes completed. `assemble-evidence.py` verifies')
    else:
        s=s.replace('Run\n`assemble-evidence.py` after the remaining entry-point lane finishes. It requires', 'All final lanes completed.\n`assemble-evidence.py` verifies')
    p.write_text(s)
print('Updated development status, improvement plan, work log and repair ledgers.')
