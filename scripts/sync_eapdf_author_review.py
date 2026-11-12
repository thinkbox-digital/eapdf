"""Rebuild the current author review while preserving original source exhibits."""
from collections import Counter
from copy import deepcopy
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
from zipfile import ZipFile
from docx import Document
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.text.paragraph import Paragraph
from lxml import etree

ROOT = Path(__file__).resolve().parents[1]
AUDIT = ROOT / 'docs/improvement/specification-audit'
REFINEMENT = ROOT / 'docs/improvement/rule-refinement-2026-10-31'
PATH = ROOT / 'docs/specification-review/EA_PDF_Rule_Review_for_Specification_Authors.docx'
RULE = re.compile(r'Rule (\d+) — (\w+)')
DATE = '2026-10-31'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def exhibits(document):
    return {'quotations': Counter(p.text for p in document.paragraphs if p.style.name == 'Source Quotation'),
            'tables': Counter(etree.tostring(t._tbl, method='c14n') for t in document.tables)}


def parts(document):
    blocks, tables = {}, []
    active, in_tables = None, False
    for element in document.element.body:
        p = Paragraph(element, document) if element.tag == qn('w:p') else None
        match = RULE.fullmatch(p.text) if p is not None else None
        if match:
            active = match[2]
            blocks[active] = []
        elif p is not None and p.style.name == 'Heading 1' and p.text.startswith('Appendix A'):
            active, in_tables = None, True
        elif p is not None and p.style.name == 'Heading 1' and p.text.startswith('Appendix B'):
            in_tables = False
        if active:
            blocks[active].append(element)
        elif in_tables and element.tag != qn('w:sectPr'):
            tables.append(element)
    fragments = {}
    for code, elements in blocks.items():
        fragment, inside = [], False
        for element in elements:
            p = Paragraph(element, document) if element.tag == qn('w:p') else None
            if p is not None and p.text == 'Relevant source quotations':
                inside = True
                continue
            if p is not None and p.style.name == 'Heading 3' and p.text == 'Author review note':
                inside = False
            if inside:
                fragment.append(element)
        fragments[code] = fragment
    return fragments, tables


def append_exhibit(document, element):
    copied = deepcopy(element)
    for tag in ('w:bookmarkStart', 'w:bookmarkEnd'):
        for bookmark in list(copied.iter(qn(tag))):
            bookmark.getparent().remove(bookmark)
    document.element.body.insert_element_before(copied, 'w:sectPr')


def heading(document, text, level=1):
    p = document.add_heading(text, level=level)
    if level == 1:
        p.paragraph_format.page_break_before = True
    return p


def review_note(rule, row):
    check = rule['validation_scope'].get('check', {})
    notes = []
    if check:
        notes.append('This numbered rule reports only the predicate above. A full linked source clause may support other separately numbered rules.')
    if check.get('strength') == 'SHOULD':
        notes.append('This is a recommendation. Its omission, disagreement or unavailable evidence alone is not a mandatory file-format failure.')
    notes.extend(dict.fromkeys(a['local_interpretation'] for a in rule['requirements_review']['assertions'] if a.get('local_interpretation')))
    issues = row.get('requirements_review', {}).get('issue_ids', [])
    if issues:
        notes.append('Related interpretation questions: ' + ', '.join(issues) + '. These remain project interpretations, not adopted errata.')
    if not notes:
        notes.append('Review the stated condition against the cited source. The source quotation is authoritative; the rule summary and this note are project explanations.')
    return ' '.join(notes)


def main():
    rule_path, review_path = ROOT/'validator_rule_text.json', AUDIT/'validator-rule-review.json'
    evidence_path = AUDIT/'author-review-source-evidence.json'
    rules, review = json.loads(rule_path.read_text()), json.loads(review_path.read_text())
    migration = json.loads((REFINEMENT/'top-level-rule-migration.json').read_text())
    project_issues = json.loads((REFINEMENT/'project-issues-review.json').read_text())
    evidence = json.loads(evidence_path.read_text())
    old = {r['rule_code']: r for r in evidence['rules']}
    inventory = review['rules']['VALIDATION_INCOMPLETE']['specification_review']
    records = {r['id']: r for r in inventory['inventory_records']}
    ancestors = {n['code']: m['old_code'] for m in migration['mapping'] for n in m['new_rules']}
    document = Document(PATH)
    previous_sha = digest(PATH)
    before = exhibits(document)
    fragments, original_tables = parts(document)
    assert fragments and original_tables
    with ZipFile(PATH) as archive:
        images = {n: archive.read(n) for n in archive.namelist() if n.startswith('word/media/')}
    for element in list(document.element.body):
        if element.tag != qn('w:sectPr'):
            document.element.body.remove(element)
    document.add_paragraph('EA-PDF validation rules', style='Title')
    document.add_paragraph('Specification author review', style='Subtitle')
    document.add_paragraph(f'{len(rules)} rules · specification-page order · 31 October 2026')
    document.add_paragraph('Purpose: help the specification authors review the accuracy, conditions and completeness of the rulebook against the published EA-PDF specification. Prepared by Thinkbox.DIGITAL.')
    heading(document, 'How to read this document').paragraph_format.page_break_before = False
    for text in [
        'Each numbered entry reproduces one current JSON rule. Numbers follow the first applicable specification page, with related predicates together. Operational diagnostics without a specification page appear last.',
        'Starting requirement: the finished file must pass veraPDF for an EA-PDF-permitted PDF/A level. Adding EA-PDF data after a PDF/A check requires validation of the finished bytes. Applicable ISO/TS 32005 checks remain separate. This review does not duplicate the PDF/A rulebook.',
        'Each entry contains the rule text, its source reference and original quotations. Specific interpretations are retained where needed; they are project decisions, not adopted errata. Detailed applicability and evidence mappings remain in the JSON review records.',
        'All nineteen original source tables remain in Appendix A. Appendices B–E record coverage limits, source identities, interpretation questions and the project GitHub issues. Use the Word navigation pane to move between rule and appendix headings.',
        'Source: PDF Association, EA-PDF 1.0 Specification, February 2025, 97 PDF pages. Copyright 2025 PDF Association, CC BY 4.0. Add seven to a printed page number to get the physical PDF page.'
    ]:
        document.add_paragraph(text)
    updated = []
    for code, rule in rules.items():
        parent = code if code in fragments else ancestors.get(code)
        assert parent in fragments, (code, parent)
        selected = [parent]
        covered = set(old[parent].get('mapped_requirement_ids', old[parent]['requirements_review']['requirement_ids']))
        for ident in rule['requirements_review']['requirement_ids']:
            if ident in covered:
                continue
            candidates = [k for k,r in old.items() if k in fragments and ident in r.get('mapped_requirement_ids', r['requirements_review']['requirement_ids'])]
            if candidates:
                chosen = min(candidates, key=lambda k: (len(old[k].get('source_ids', [])), old[k]['rule_number']))
                if chosen not in selected:
                    selected.append(chosen)
                covered.update(old[chosen].get('mapped_requirement_ids', old[chosen]['requirements_review']['requirement_ids']))
        heading(document, f"Rule {rule['rule_number']} — {code}")
        document.add_heading('Rule Text', level=3)
        document.add_paragraph(rule['spec_excerpt'], style='Current Rule Text')
        document.add_paragraph('Source Reference: ' + rule['spec_section'])
        # Conditions already appear in the rule and its quotation. Keep only
        # substantive interpretations not already stated in the rule text.
        interpretations = list(dict.fromkeys(a['local_interpretation']
            for a in rule['requirements_review']['assertions']
            if a.get('local_interpretation') and a['local_interpretation'].casefold()
            not in rule['spec_excerpt'].casefold()))
        note = ' '.join(interpretations)
        for interpretation in interpretations:
            document.add_paragraph('Interpretation: ' + interpretation)
        document.add_heading('Relevant source quotations', level=3)
        for name in selected:
            for element in fragments[name]:
                append_exhibit(document, element)
        updated.append(dict(rule, rule_code=code, source_ids=list(dict.fromkeys(s for n in selected for s in old[n].get('source_ids', []))),
                            author_review_note=note, mapped_requirement_ids=rule['requirements_review']['requirement_ids'],
                            diagnostic_or_policy=rule['validation_scope']['decision']=='operational_only'))
    for element in original_tables:
        append_exhibit(document, element)
    # Cloned source fragments may repeat old bookmark IDs. Restore one unique
    # destination per source table after removing the duplicate source markers.
    for paragraph in document.paragraphs:
        match = re.fullmatch(r'Appendix A — Source Table (\d+)', paragraph.text)
        if match:
            number = int(match[1])
            start, end = OxmlElement('w:bookmarkStart'), OxmlElement('w:bookmarkEnd')
            start.set(qn('w:id'), str(number))
            start.set(qn('w:name'), f'table_{number:02d}')
            end.set(qn('w:id'), str(number))
            paragraph._p.insert(1 if paragraph._p.pPr is not None else 0, start)
            paragraph._p.append(end)
    links = [element.get(qn('w:anchor')) for element in document.element.iter(qn('w:hyperlink'))
             if element.get(qn('w:anchor'))]
    targets = [element.get(qn('w:name')) for element in document.element.iter(qn('w:bookmarkStart'))]
    assert set(links) <= set(targets), 'Unresolved source-table hyperlink'
    assert len(targets) == len(set(targets)) == 19
    heading(document, 'Appendix B — Completeness and coverage')
    document.add_paragraph(f'The current rulebook has {len(rules)} numbered rules linked to {len(records)} reviewed source records. Source records include recommendations, creator duties, software behaviour and definitions; they are not all mandatory PDF tests.')
    document.add_paragraph('The registry audit verifies numbering, source identity, source-page order, conditional obligations and mappings. It does not prove every sentence was extracted perfectly or every permitted representation is implemented. PDF/A is delegated to veraPDF; applicable ISO/TS 32005 checks remain separate.')
    document.add_paragraph('Original-input completeness and historical provenance cannot be inferred solely from consistent PDF metadata. Unknown association is not a proven mismatch or a successful comparison. Exact implementation results and skipped tests are recorded in docs/DEVELOPMENT_STATUS.md and docs/improvement/rule-refinement-2026-10-31/.')
    heading(document, 'Source records outside the active custom rule list', 2)
    for row in records.values():
        if not any(code in rules for code in row.get('candidate_rule_codes', [])):
            document.add_paragraph(f"{row['id']} — {row['scope']}; {row['strength']}. {row['assertion']}")
            if row.get('upstream_rule_codes'):
                document.add_paragraph('Delegated prerequisite: ' + ', '.join(row['upstream_rule_codes']))
    heading(document, 'Appendix C — Source and verification record')
    sources = ['validator_rule_text.json','docs/improvement/specification-audit/validator-rule-review.json',
               'docs/reference/EA-PDF-v1.pdf','docs/reference/ISO_32000-2_sponsored-ec2.pdf',
               'docs/improvement/rule-refinement-2026-10-31/top-level-rule-migration.json',
               'docs/improvement/rule-refinement-2026-10-31/project-issues.json',
               'docs/improvement/rule-refinement-2026-10-31/project-issues-review.json']
    for name in sources:
        document.add_paragraph(name)
        document.add_paragraph('SHA-256: ' + digest(ROOT/name))
    document.add_paragraph('Original quotations, editable source-table XML and image bytes were preserved. Reordering and splitting may repeat an exhibit. Linked source wording can support several independently reported rules.')
    heading(document, 'Appendix D — Reexamination decisions')
    document.add_paragraph('These project interpretations are retained for author review, not presented as published errata. The current rulebook and 31 October refinement take precedence over older runtime descriptions. Published inconsistencies still need authoritative clarification.')
    decisions = inventory['reexamination']['decisions']
    for decision in decisions:
        heading(document, f"Finding {decision['finding']}: {decision['question']}", 2)
        document.add_paragraph(decision['assumption'])
    heading(document, 'Appendix E — Project GitHub issues')
    document.add_paragraph(project_issues['scope'])
    document.add_paragraph(project_issues['authority'])
    document.add_paragraph('Issue references use an older numbering. The current rule numbers below follow specification pages. This review supersedes the implementation status in the 29 October issue review; it preserves the original discussions as evidence.')
    for item in project_issues['findings']:
        heading(document, item['topic'], 2)
        document.add_paragraph(item['url'])
        document.add_paragraph('Current rules: ' + (', '.join(str(r['number']) + ' ' + r['code'] for r in item['rules']) or 'Delegated prerequisite or interoperability evidence'))
        document.add_paragraph('Finding: ' + item['decision'])
        document.add_paragraph('Specification: ' + item['source'])
        document.add_paragraph('Verification boundary: ' + item['evidence'])
    for section in document.sections:
        for p in section.footer.paragraphs:
            for run in p.runs:
                run.text = re.sub(r'2026-10-(?:26|27)', DATE, run.text)
    document.core_properties.modified = datetime(2026,10,31,tzinfo=timezone.utc)
    after = exhibits(document)
    assert not (before['quotations'] - after['quotations']), 'Source quotation lost'
    assert not (before['tables'] - after['tables']), 'Source table lost or altered'
    assert [p.text for p in document.paragraphs if p.style.name=='Current Rule Text'] == [r['spec_excerpt'] for r in rules.values()]
    document.save(PATH)
    checked = Document(PATH)
    assert exhibits(checked) == after
    with ZipFile(PATH) as archive:
        assert images == {n:archive.read(n) for n in archive.namelist() if n.startswith('word/media/')}
        assert archive.testzip() is None
    evidence.update(generated_date=DATE, input_sha256={n:digest(ROOT/n) for n in sources}, rules=updated,
                    reexamination_decisions=decisions, migration='docs/improvement/rule-refinement-2026-10-31/top-level-rule-migration.json')
    evidence['coverage'].update(active_rules=len(rules), reviewed_obligations=len(records), specification_order_verified=True, implementation_completeness_verified=False)
    evidence_path.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n')
    verification = {'document':PATH.name,'document_sha256':digest(PATH),'previous_document_sha256':previous_sha,
                    'json_rule_count':len(rules),'document_rule_count':len(updated),
                    'rule_code_and_number_order_exact':[RULE.fullmatch(p.text)[2] for p in checked.paragraphs if RULE.fullmatch(p.text)]==list(rules),
                    'rule_assertions_exact':True,'source_quotations_preserved':sum(before['quotations'].values()),
                    'source_tables_preserved':sum(before['tables'].values()),'current_source_table_occurrences':sum(after['tables'].values()),
                    'source_images_unchanged':len(images),'reexamination_decisions':len(decisions),'specification_page_order_verified':True,
                    'input_hashes_match':True,'internal_links_resolved':len(links),'source_table_bookmarks':len(targets),'normative_or_implementation_completeness_certified':False,'project_issue_topics_reviewed':len(project_issues['findings']),'render_check':'pending'}
    assert verification['rule_code_and_number_order_exact']
    (AUDIT/'author-review-verification.json').write_text(json.dumps(verification,indent=2)+'\n')
    print(f'Synchronized {len(rules)} page-ordered Word rules; original source exhibits preserved.')


if __name__ == '__main__':
    main()
