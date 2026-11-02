"""Generate a supporting exit checklist from authority and observed evidence.

No execution count or static test reference is promoted into normative coverage.
"""
import ast
from collections import Counter,defaultdict
import csv
import hashlib
import json
from pathlib import Path
from eapdf_rule_review import REVIEW_PATH, load_review_rules
import sys

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/improvement/coverage-exit-review'
sys.path.insert(0,str(ROOT/'scripts'))
from build_eapdf_review_document import verify_profile_evidence


def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def read(path):return json.loads(path.read_text())
def write(name,value):(OUT/name).write_text(json.dumps(value,indent=2,ensure_ascii=False)+'\n')
def levels(observations,code,production_only=True):
    return sorted(level for level,sites in observations.get(code,{}).items()
                  if any(not production_only or s['kind']=='production' for s in sites))


def main():
    rules=load_review_rules();review=rules['VALIDATION_INCOMPLETE']['specification_review']
    census=read(OUT/'execution-census.json');manifest=read(ROOT/'docs/improvement/validator-23/verification-inputs.json')
    for name,value in census['inputs'].items():assert digest(ROOT/name)==value==manifest[name],name
    index=verify_profile_evidence(rules)
    inventory=review['inventory_records'];by_id={r['id']:r for r in inventory}
    assert len(rules)==224 and len(inventory)==len(by_id)==301
    assert len(census['fixtures'])==202 and (census['unit']['run'],len(census['unit']['skipped']))==(487,40)
    questions=[]
    findings={r['id']:r for r in review['findings'] if r['status']=='open'}
    for q in review['expert_questions']:
        f=findings[q['finding_id']]
        codes=set(f['affected_rule_codes'])
        for ident in q['requirement_ids']:codes.update(by_id[ident]['candidate_rule_codes'])
        questions.append(dict(id=q['finding_id'],title=q['title'],question=q['question'],
            interim_boundary=q['interim_boundary'],requirement_ids=q['requirement_ids'],rule_codes=sorted(codes),
            exit_evidence='Cited authoritative interpretation/erratum or explicitly recorded project decision; update authority and targeted positive/negative/permitted-form fixtures.'))
    assert len(questions)==len(findings)==11
    literal_refs=defaultdict(list)
    class Literals(ast.NodeVisitor):
        def __init__(self,path):self.path=path;self.context='<module>'
        def visit_FunctionDef(self,node):
            previous=self.context;self.context=node.name;self.generic_visit(node);self.context=previous
        visit_AsyncFunctionDef=visit_FunctionDef
        def visit_Constant(self,node):
            if isinstance(node.value,str) and node.value in rules:
                literal_refs[node.value].append(dict(file=str(self.path.relative_to(ROOT)),line=node.lineno,function=self.context))
    for path in sorted((ROOT/'tests').glob('test_*.py')):Literals(path).visit(ast.parse(path.read_text()))
    raw_reports=[];pipeline=defaultdict(lambda:defaultdict(list));profiles=Counter();iso=Counter()
    base=ROOT/'docs/improvement/validator-23'
    paths=sorted((base/'profiles').rglob('*.json'))+[p for p in sorted((base/'pdf-reports').glob('*.json')) if p.stem.endswith(('-after','-cli','-web-worker'))]
    assert len(paths)==29
    for path in paths:
        data=read(path);pdfa=data['pdfa'];upstream=next(i for i in data['items'] if i['code']=='ISO32005_UPSTREAM')
        assert pdfa['status']=='PASS' and pdfa['ran'] and pdfa['compliant'] and pdfa['profile_allowed']
        assert upstream['details']['profile_verified']
        relative=str(path.relative_to(ROOT));profiles[pdfa['profile_name']]+=1;iso[upstream['level']]+=1
        raw_reports.append(dict(file=relative,sha256=digest(path),entry=('generated-profile' if 'profiles' in path.parts else path.stem.rsplit('-',1)[-1]),
            pdfa_profile=pdfa['profile_name'],pdfa_status=pdfa['status'],iso_level=upstream['level'],iso_profile_verified=True))
        for item in data['items']:
            if item['code'] in rules and relative not in pipeline[item['code']][item['level']]:pipeline[item['code']][item['level']].append(relative)
    tree=ast.parse((ROOT/'validate_eapdf.py').read_text())
    allowed=next(ast.literal_eval(n.value) for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='ALLOWED_VERAPDF_PROFILES' for t in n.targets))
    missing_profiles=sorted(allowed-set(profiles))
    by_family=defaultdict(list)
    for group in review['coverage_families']:
        for code in group['rule_codes']:by_family[code].append(group['id'])
    matrix=[]
    for code,rule in sorted(rules.items(),key=lambda x:x[1]['rule_number']):
        scope=rule['validation_scope'];owner=scope['decision']
        family=scope.get('coverage_family') or (by_family[code][0] if by_family[code] else
            'operational' if owner=='operational_only' else 'delegated' if owner=='delegate_to_verapdf' else
            'logical_structure' if code in ('MAIL_CONTENTSET_CHILDREN','LOGICAL_TS32005_DECLARATION') else 'metadata_and_identification')
        unit=levels(census['observations']['unit'],code);fixture=levels(census['observations']['fixture'],code)
        observed=set(unit+fixture)-{'N/A'}
        gates=['G1','G6']
        if owner in ('delegate_to_verapdf','split_custom_and_verapdf','split_custom_declaration_and_external_structure'):gates.append('G2')
        if owner not in ('delegate_to_verapdf','operational_only'):gates.append('G3')
        if family in ('source_and_page_content','source_asset_xmp','message_and_attachment_xmp','embedded_files'):gates.append('G4')
        if owner=='operational_only':gates.append('G5')
        matrix.append(dict(code=code,number=rule['rule_number'],family=family,ownership=owner,
            requirement_ids=rule['requirements_review']['requirement_ids'],
            open_questions=[q['id'] for q in questions if code in q['rule_codes']],
            unit_production_levels=unit,fixture_production_levels=fixture,
            unit_emissions=census['observations']['unit'].get(code,{}),fixture_emissions=census['observations']['fixture'].get(code,{}),
            current_complete_pipeline_levels=sorted(pipeline[code]),complete_pipeline_reports=dict(pipeline[code]),
            test_literal_references=literal_refs[code],
            review_signal=('delegated: inspect upstream evidence' if owner=='delegate_to_verapdf' else
                'operational: inspect status transitions' if owner=='operational_only' else
                'no non-NA production result observed: inspect applicability/fixtures' if not observed else
                'no observed FAIL: inspect obligation-level negative cases or justified exception' if 'FAIL' not in observed else
                'observed results: review assertion, permitted-form and unavailable-evidence coverage'),
            exit_gates=sorted(set(gates)),closure='Pending obligation-level review; execution/literal references do not certify coverage'))
    lookup={r['code']:r for r in matrix}
    obligations=[]
    for row in inventory:
        codes=row['candidate_rule_codes'];assert set(codes)<=set(rules)
        combined=sorted(set(level for code in codes for level in lookup[code]['unit_production_levels']+lookup[code]['fixture_production_levels']))
        obligations.append(dict(id=row['id'],section=row['section'],table=row['table'],printed_pages=row['printed_pages'],
            strength=row['strength'],scope=row['scope'],applies_when=row['applies_when'],conformance_effect=row['assessment']['conformance_effect'],
            rule_codes=codes,evidence_needed=row['evidence_needed'],observed_production_levels=combined,
            open_questions=[q['id'] for q in questions if row['id'] in q['requirement_ids'] or q['id'] in row['issues']],
            closure='Review mapped assertion and expected results; combined code levels alone do not establish coverage'))
    mandatory=[r for r in obligations if r['scope']=='file' and r['conformance_effect']=='mandatory_when_applicable']
    assert not [r for r in mandatory if not r['rule_codes']]
    local=[r for r in matrix if r['ownership'] not in ('delegate_to_verapdf','operational_only')]
    no_observed=[r['code'] for r in local if not ((set(r['unit_production_levels'])|set(r['fixture_production_levels']))-{'N/A'})]
    no_negative=[r['code'] for r in local if 'FAIL' not in set(r['unit_production_levels']+r['fixture_production_levels'])]
    summary=dict(rule_codes=len(matrix),specification_entries=len(obligations),mandatory_file_entries=len(mandatory),unmapped_mandatory_file_entries=0,
        ownership=dict(Counter(r['ownership'] for r in matrix)),unit_run=487,unit_passed=447,unit_skipped=40,
        custom_fixtures=202,current_complete_pipeline_reports=len(raw_reports),actual_pdfa_profiles=dict(profiles),missing_current_profile_execution=missing_profiles,
        actual_iso_outcomes=dict(iso),open_questions=len(questions),
        local_assertion_codes=len(local),local_codes_without_non_na_production_observation=no_observed,
        local_codes_without_observed_fail=no_negative,
        cautions=['No observed FAIL is a review signal, not a proven defect: companion codes and recommendation semantics matter.',
                  'Unit observations may use mocks. Fallback, delegation and direct synthetic findings are not custom implementation proof.',
                  'Current complete-pipeline evidence is the V23 accepted cohort; older scoped evidence remains historical.',
                  'No completion percentage or remaining defect count is inferred.'])
    write('rule-matrix.json',dict(authority='validator_rule_text.json',view_only=True,rows=matrix))
    write('obligation-matrix.json',dict(authority='VALIDATION_INCOMPLETE.specification_review.inventory_records in validator-rule-review.json',view_only=True,rows=obligations))
    write('questions.json',dict(authority='VALIDATION_INCOMPLETE.specification_review in validator-rule-review.json',view_only=True,rows=questions))
    write('pipeline-evidence.json',dict(source='V23 accepted current runtime/rules snapshot',reports=raw_reports,expected_profiles=sorted(allowed),missing_profiles=missing_profiles))
    write('summary.json',summary)
    with (OUT/'rule-matrix.csv').open('w',newline='') as stream:
        columns=['number','code','family','ownership','requirement_ids','open_questions','unit_production_levels','fixture_production_levels','current_complete_pipeline_levels','review_signal','exit_gates']
        writer=csv.DictWriter(stream,fieldnames=columns);writer.writeheader()
        for row in matrix:writer.writerow({k:'; '.join(row[k]) if isinstance(row[k],list) else row[k] for k in columns})
    with (OUT/'obligation-matrix.csv').open('w',newline='') as stream:
        columns=['id','section','table','printed_pages','strength','scope','applies_when','conformance_effect','rule_codes','evidence_needed','observed_production_levels','open_questions']
        writer=csv.DictWriter(stream,fieldnames=columns);writer.writeheader()
        for row in obligations:writer.writerow({k:'; '.join(map(str,row[k])) if isinstance(row[k],list) else row[k] for k in columns})
    lines=['# Rule review queues','','These are evidence review queues, not a defect count. Full data: [CSV](rule-matrix.csv), [JSON](rule-matrix.json).','',
        '## Local assertions with no observed non-N/A production result','']
    lines+=['- `'+code+'`' for code in no_observed] or ['None in the measured unit/custom-fixture cohort.']
    lines+=['','## Local assertions without an observed FAIL','','Review companion codes, strength and applicability before requesting a negative fixture.','']
    lines+=['- `'+code+'`' for code in no_negative]
    lines+=['','## Family review inventory','','| Family | Codes | Shared/custom splits | Without observed FAIL |','| --- | ---: | ---: | ---: |']
    for family in sorted({r['family'] for r in matrix}):
        group=[r for r in matrix if r['family']==family]
        lines.append('| '+family+' | '+str(len(group))+' | '+str(sum(r['ownership'].startswith('split_') for r in group))+' | '+str(sum(r['code'] in no_negative for r in group))+' |')
    (OUT/'review-queues.md').write_text('\n'.join(lines)+'\n')
    inputs={str(p.relative_to(ROOT)):digest(p) for p in [ROOT/'validate_eapdf.py',ROOT/'validator_rule_text.json',REVIEW_PATH,ROOT/'scripts/collect_eapdf_coverage_census.py',Path(__file__),OUT/'execution-census.json',ROOT/'docs/improvement/validator-23/evidence.json',ROOT/'docs/improvement/validator-23/verification-inputs.json']}
    inputs.update({r['file']:r['sha256'] for r in raw_reports})
    artifacts={p.name:digest(p) for p in sorted(OUT.iterdir()) if p.is_file() and p.name not in ('evidence.json','README.md','census.log','build.log')}
    write('evidence.json',dict(scope='Bounded whole-validator evidence census and exit checklist; no conformance certification',inputs=inputs,artifacts=artifacts,
        summary=summary,static_profile_references_verified=sorted(index),runtime_and_rules_changed=False))
    print(json.dumps(summary,indent=2))


if __name__=='__main__':main()
