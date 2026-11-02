#!/usr/bin/env python3
"""Rebuild the bundled specification page excerpts from the pinned local PDF.

Development tool only. The JAR reads JSON and needs neither Python nor the PDF.
Page bodies preserve extracted wording; layout whitespace is simplified.
Reviewed summaries remain explicitly separate from specification quotations.
"""
import sys
sys.dont_write_bytecode = True

import hashlib
import json
from pathlib import Path
from pypdf import PdfReader

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'docs/reference/EA-PDF-v1.pdf'
EXPECTED = 'b89e7471afd3aabaa4e58bd6ca1f3241378c1596cf528023e740d9e79183dbae'

def main():
    digest = hashlib.sha256(SOURCE.read_bytes()).hexdigest()
    if digest != EXPECTED:
        raise RuntimeError('The reviewed specification PDF has changed')
    inventory = json.loads((ROOT / 'docs/improvement/specification-audit/requirements.json').read_text())
    if inventory['specification_sha256'] != digest:
        raise RuntimeError('The requirement mapping uses another specification')
    rules = json.loads((ROOT / 'validator_rule_text.json').read_text())
    reader = PdfReader(SOURCE)
    records = inventory['records']
    pages = {}
    for record in records:
        for printed, physical in zip(record['printed_pages'], record['pdf_pages'], strict=True):
            if reader.page_labels[physical - 1] != str(printed):
                raise RuntimeError(f'Page label mismatch: {printed}/{physical}')
            if str(physical) in pages:
                continue
            lines = reader.pages[physical - 1].extract_text(extraction_mode='layout').splitlines()
            # Drop running header/footer, preserving actual body text and table rows.
            lines = [line.rstrip() for line in lines if line.strip() != 'PDF Association'
                     and not ('© 2025 PDF Association' in line and 'Specification' in line)]
            pages[str(physical)] = {'printed_page': printed, 'pdf_page': physical,
                                    'text': '\n'.join(lines).strip()}
    references = {}
    for code, rule in rules.items():
        ids = rule.get('requirements_review', {}).get('requirement_ids', [])
        linked = [r for r in records if r['id'] in ids]
        if not linked:
            linked = [r for r in records if code in r['candidate_rule_codes']]
        if not linked:
            raise RuntimeError('No reviewed mapping for ' + code)
        references[code] = {'summary': rule.get('spec_excerpt', ''),
                            'prerequisite': rule.get('validation_scope', {}).get('prerequisite', 'none'),
                            'requirements': [{key: r[key] for key in
                                ('id', 'section', 'printed_pages', 'pdf_pages', 'table', 'assertion',
                                 'strength', 'scope', 'applies_when', 'evidence_needed', 'local_interpretation') if key in r}
                                for r in linked]}
        check = rule.get('validation_scope', {}).get('check')
        if check is not None:
            references[code]['check'] = check
        references[code]['source_order'] = rule['validation_scope']['source_order']
    references['PDFA_FIRST_PASS'] = references['PDFA_PART']
    references['ISO32005_UPSTREAM'] = {
        'summary': 'The validation pipeline runs the selected upstream ISO/TS 32005 profile when applicable.',
        'requirements': [{key: r[key] for key in
                          ('id', 'section', 'printed_pages', 'pdf_pages', 'table', 'assertion')}
                         for r in records if 'ISO32005_UPSTREAM' in r['candidate_rule_codes']
                         or 'ISO32005_UPSTREAM' in r.get('upstream_rule_codes', [])]}
    result = {'title': 'EA-PDF 1.0 Specification', 'source_sha256': digest,
              'rules_sha256': hashlib.sha256((ROOT / 'validator_rule_text.json').read_bytes()).hexdigest(),
              'extraction_note': 'Specification page excerpts, with surrounding context. Layout whitespace is simplified; running headers and footers are omitted. Reviewed summaries are paraphrases, not quotations.',
              'rules': references, 'pages': pages}
    target = ROOT / 'validator-desktop/src/main/resources/validator/specification-references.json'
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(f'{len(references)} rule references; {len(pages)} specification pages; {target.stat().st_size} bytes')

if __name__ == '__main__':
    main()
