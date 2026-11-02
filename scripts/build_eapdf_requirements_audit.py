"""Generate supporting audit views from authoritative validator_rule_text.json.

Edit runtime rules in that JSON and review records in validator-rule-review.json, then regenerate these
views. Mechanical traceability checks do not certify implementation coverage.
"""
from collections import Counter
import ast
import hashlib
import json
from pathlib import Path
from eapdf_rule_review import REVIEW_PATH, load_review_rules
import re
import subprocess
import sys
from xml.etree import ElementTree as ET
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import validate_eapdf as validator
from build_eapdf_review_document import generate_review_views

OUT = ROOT / 'docs/improvement/specification-audit'
AUTHORITY = ROOT / 'validator_rule_text.json'
PIPELINE_RESULT_CODES = {'PDFA_FIRST_PASS', 'EAPDF_NOT_EVALUATED'}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(name, value):
    (OUT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    rules = load_review_rules()
    review = rules['VALIDATION_INCOMPLETE']['specification_review']
    rows = review['inventory_records']
    clauses = review['clause_inventory']
    tables = review['table_inventory']
    findings = review['findings']
    ids = [row['id'] for row in rows]
    assert len(ids) == len(set(ids))
    numbers = [rule['rule_number'] for rule in rules.values()]
    assert len(numbers) == len(set(numbers))
    assert set(rules) == set(validator.ALL_RULE_CODES)
    assert all(rule['rule_name'] == code and rule['spec_excerpt'] for code, rule in rules.items())
    issue_ids = {issue['id'] for issue in findings}
    assert len(issue_ids) == len(findings)
    reverse = {code: [] for code in rules}
    for row in rows:
        assert set(row['candidate_rule_codes']) <= set(rules), row['id']
        assert set(row['issues']) <= issue_ids, row['id']
        for code in row['candidate_rule_codes']:
            reverse[code].append(row['id'])
    assert all(reverse.values()), [code for code, links in reverse.items() if not links]
    for code, rule in rules.items():
        assert sorted(rule['requirements_review']['requirement_ids']) == sorted(reverse[code]), code
    assert len({c['section'] for c in clauses}) == len(clauses)
    assert [table['table'] for table in tables] == list(range(1,20))
    for value in clauses + tables:
        assert set(value['requirement_ids']) <= set(ids)
    assert all(table['requirement_ids'] for table in tables)
    specification = ROOT / review['specification_source']
    assert digest(specification) == review['specification_sha256']
    text = subprocess.check_output(['pdftotext','-layout',str(specification),'-'], text=True)
    pages = text.split('\f')
    if not pages[-1].strip():
        pages.pop()
    assert len(pages) == 97
    (OUT / 'specification-layout.txt').write_text(text)
    write_json('page-index.json', [dict(pdf_page=index, printed_page=index-7 if index>7 else None,
        text_sha256=hashlib.sha256(page.encode()).hexdigest(),
        requirement_ids=[row['id'] for row in rows if index in row['pdf_pages']],
        clause_sections=[c['section'] for c in clauses if index-7 in c['printed_pages']],
        classification='front matter' if index<=7 else 'Local text reviewed; interpretations/dependencies remain open')
        for index,page in enumerate(pages,1)])
    docx = ROOT / 'docs/reference/Validation requirements.docx'
    with ZipFile(docx) as archive:
        document = ET.fromstring(archive.read('word/document.xml'))
    ns = {'w':'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}
    paragraphs = [''.join(node.text or '' for node in para.findall('.//w:t',ns))
                  for para in document.findall('.//w:p',ns)]
    (OUT / 'starting-validation-requirements.txt').write_text('\n'.join(paragraphs)+'\n')
    sections = []
    for number,paragraph in enumerate(paragraphs,1):
        match = re.match(r'^(\d+)\. ',paragraph)
        if match:
            sections.append(dict(section=int(match[1]), title=paragraph, first_paragraph=number,
                                 last_paragraph=number, paragraphs=[]))
        assert sections
        sections[-1]['paragraphs'].append(paragraph)
        sections[-1]['last_paragraph'] = number
    assert [section['section'] for section in sections] == list(range(1,12))
    section_rules = {
        1: lambda row: row['section'] in ('9.8','9.8.1'),
        2: lambda row: row['section'] in ('3.2','9.1') and ('PDFA' in row['id'] or row['table']==2),
        3: lambda row: row['section'] in ('9.1','9.2','9.6'),
        4: lambda row: row['section']=='9.5', 5: lambda row: row['section']=='9.6',
        6: lambda row: row['section']=='9.7' and row['table']!=12,
        7: lambda row: row['table']==12, 8: lambda row: row['section']=='9.8.2',
        9: lambda row: row['section']=='9.8.3', 10: lambda row: row['section']=='9.8.4',
        11: lambda row: row['scope'] in ('diagnostic','inherited') or row['id'] in ('TAGGING-OPTIONAL','SCOPE-POLICY')}
    for section in sections:
        section['reviewed_requirement_ids'] = [row['id'] for row in rows if section_rules[section['section']](row)]
        section['status'] = 'Historical supporting summary, not authoritative rule list'
    write_json('starting-requirements-crosswalk.json',sections)
    for printed,label in [(9,'profile-matrix'),(76,'table-11'),(79,'table-13')]:
        subprocess.run(['pdftoppm','-f',str(printed+7),'-l',str(printed+7),'-scale-to','1500',
                        '-png','-singlefile',str(specification),str(OUT/label)],check=True)
    syntax = ast.parse((ROOT/'validate_eapdf.py').read_text())
    occurrences = {code: [] for code in rules}
    def walk(node,function='<module>'):
        if isinstance(node,(ast.FunctionDef,ast.AsyncFunctionDef)):
            function = node.name
        if isinstance(node,ast.Constant) and isinstance(node.value,str) and node.value in occurrences:
            occurrences[node.value].append(dict(line=node.lineno,enclosing_function=function))
        for child in ast.iter_child_nodes(node):
            walk(child,function)
    walk(syntax)
    extra_emitted = set()
    for node in ast.walk(syntax):
        if isinstance(node,ast.Call) and isinstance(node.func,ast.Attribute) and node.func.attr in ('ok','fail','warn','na','add'):
            index = 1 if node.func.attr=='add' else 0
            if len(node.args)>index and isinstance(node.args[index],ast.Constant):
                value = node.args[index].value
                if isinstance(value,str) and value.isupper() and value not in rules:
                    extra_emitted.add(value)
    # Retired rule codes still identify delegated/operational pipeline results;
    # they must be accounted for without reintroducing them as active EA-PDF rules.
    retired = set(json.loads(REVIEW_PATH.read_text())['removed_rules'])
    assert not extra_emitted - retired - PIPELINE_RESULT_CODES, extra_emitted - retired - PIPELINE_RESULT_CODES
    generate_review_views(rules, review)
    before = json.loads((OUT/'before-authoritative-rule-text.json').read_text())
    crosswalk = [dict(code=code, authoritative_source='validator_rule_text.json',
        current_rule_text={key:value for key,value in rule.items() if key!='specification_review'},
        before_rule_text=before.get(code), requirement_ids=reverse[code],
        issue_ids=[issue['id'] for issue in findings if code in issue['affected_rule_codes']],
        code_literal_references=occurrences[code], implementation_status='Not certified; static references are not execution/test evidence')
        for code,rule in rules.items()]
    write_json('requirements.json',dict(authoritative_source='validator-rule-review.json / VALIDATION_INCOMPLETE.specification_review.inventory_records',
        view_only=True,scope='Generated supporting view, not a parallel rule authority',
        specification_sha256=review['specification_sha256'],records=rows))
    for name,value in [('clause-inventory.json',clauses),('table-inventory.json',tables),
                       ('findings.json',findings),('rule-crosswalk.json',crosswalk)]:
        write_json(name,value)
    paths = [specification,docx,AUTHORITY,REVIEW_PATH,ROOT/'validate_eapdf.py',Path(__file__).resolve(),
             ROOT/'scripts/build_eapdf_review_document.py',
             ROOT/'scripts/build_eapdf_rule_browser.py',
             ROOT/'scripts/eapdf_rule_browser_template.html',
             ROOT/'docs/improvement/verify-rule-browser.py',
             OUT/'review-questions.md',
             ROOT/'docs/improvement/verapdf-coverage/profile-rule-index.json',
             ROOT/'docs/improvement/verapdf-coverage/bundled-profiles.json',
             ROOT/'docs/improvement/requirements.json',ROOT/'docs/reference/ISO_32000-2_sponsored-ec2.pdf',
             ROOT/'docs/reference/ISO+19005-4-2020.pdf']
    write_json('sources.json',dict(audit_date='2026-10-31',authoritative_rule_document='validator_rule_text.json',
        sources={str(path.relative_to(ROOT)):dict(sha256=digest(path),bytes=path.stat().st_size) for path in paths},
        authority='EA-PDF is the specification authority. validator_rule_text.json is the authoritative local validation-rule document. DOCX and generated views are supporting material.',
        dependency_status=review['dependency_status'],
        official_reference_pages=['https://pdfa.org/resource/ea-pdf/','https://pdfa.org/resource/iso-32005/','https://pdfa.org/resource/iso-19005-4-pdf-a-4/']))
    runtime_baseline = json.loads((OUT/'runtime-baseline.json').read_text())
    counts = dict(authoritative_rule_document='validator_rule_text.json',local_pdf_pages=len(pages),clauses=len(clauses),
        tables=len(tables),inventory_records=len(rows),records_by_strength=dict(Counter(row['strength'] for row in rows)),
        records_by_scope=dict(Counter(row['scope'] for row in rows)),current_rule_codes=len(rules),
        rule_description_records=len(rules),before_rule_description_records=len(before),starting_docx_sections=len(sections),
        rules_without_description=[],unlinked_rule_codes=[],extra_literal_emitted_codes=[],
        pipeline_result_codes=sorted(extra_emitted & PIPELINE_RESULT_CODES),
        explicit_rule_predicates=sum('check' in rule['validation_scope'] for rule in rules.values()),
        open_findings=sum(issue['status']=='open' for issue in findings),
        ownership_dispositions=dict(Counter(rule['validation_scope']['decision'] for rule in rules.values())),
        expert_questions=len(review['expert_questions']),
        open_expert_questions=sum(q.get('status','open')=='open' for q in review['expert_questions']),
        locally_resolved_questions=sum(q.get('status')=='locally_resolved' for q in review['expert_questions']),
        representation_contracts=len(review['representation_contracts']),
        resolved_requirement_findings=sum(issue['status']!='open' for issue in findings),
        findings_by_kind=dict(Counter(issue['kind'] for issue in findings)),
        full_requirements_gate=review['full_requirements_gate'],
        authoritative_rule_descriptions_updated=True,
        validator_logic_changed=digest(ROOT/'validate_eapdf.py')!=runtime_baseline['validate_eapdf.py'],
        creator_changed=any(digest(ROOT/path)!=value for path,value in runtime_baseline.items()
                           if path.startswith('src/main/java/')))
    write_json('summary.json',counts)
    lines = ['# EA-PDF requirement inventory — generated view','',
             '**Sources: validator_rule_text.json and validator-rule-review.json. Edit the relevant file and regenerate this supporting view.**','',
             'Includes guidance, permitted forms, repeated obligations and diagnostics. Candidate links do not certify implementation.','',
             '| ID | Clause / printed page | Strength | Scope / condition | Reviewed assertion | Candidate rules |',
             '| --- | --- | --- | --- | --- | --- |']
    for row in rows:
        lines.append('| '+' | '.join([row['id'],row['section']+' / '+','.join(map(str,row['printed_pages'])),
            row['strength'],row['scope']+': '+row['applies_when'],row['assertion'],
            ', '.join(row['candidate_rule_codes']) or 'No dedicated mapped rule'])+' |')
    (OUT/'requirements.md').write_text('\n'.join(lines)+'\n')
    write_json('artifact-hashes.json',{str(path.relative_to(ROOT)):digest(path) for path in sorted(OUT.iterdir())
        if path.is_file() and path.name not in ('artifact-hashes.json','verification.log')})
    print(json.dumps(counts,indent=2))


if __name__=='__main__':
    main()
