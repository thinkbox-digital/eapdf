#!/usr/bin/env python3
"""Analyze all saved real Java/V85 reports without hiding failures behind totals."""
import sys
sys.dont_write_bytecode = True

import collections
import gzip
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LEVELS = {'MISSING': -1, 'N/A': 0, 'PASS': 1, 'WARN': 2, 'FAIL': 3}

def read(path):
    with gzip.open(path, 'rt') as stream:
        return json.load(stream)

def levels(result, rules):
    found = collections.defaultdict(collections.Counter)
    for item in result.get('items', []):
        if item['code'] in rules:
            found[item['code']][item['level']] += 1
    return found

def worst(counts):
    return max(counts, key=lambda x: LEVELS[x], default='MISSING')

def upstream(result):
    if not result:
        return None
    failures = []
    for item in result.get('rule_summaries', result.get('findings', [])):
        failures.append((item.get('specification'), item.get('clause'),
                         str(item.get('testNumber', item.get('test')))))
    return {'status': result.get('status'), 'ran': result.get('ran'),
            'compliant': result.get('compliant'), 'profile_allowed': result.get('profile_allowed'),
            'profile_name': result.get('profile_name'), 'job_end_status': result.get('job_end_status'),
            'failed_rules': result.get('failed_rules'), 'failed_rule_ids': sorted(set(failures))}

def diagnostics(result):
    rows = []
    for rule in (result or {}).get('rule_summaries', []):
        for check in rule.get('checks', []):
            if str(check.get('status', '')).lower() not in ('failed', 'fail'):
                continue
            rows.append((rule.get('specification'), rule.get('clause'), str(rule.get('testNumber')),
                         check.get('context') or '', check.get('errorMessage') or ''))
    for check in (result or {}).get('findings', []):
        rows.append((check.get('specification'), check.get('clause'), str(check.get('test')),
                     check.get('context') or '', check.get('error') or ''))
    return sorted(rows)

def iso_results(result):
    return [item['details'] for item in result.get('items', [])
            if item['code'] == 'ISO32005_UPSTREAM' and isinstance(item.get('details'), dict)
            and 'status' in item['details']]

def iso(result):
    return [upstream(item.get('details')) for item in result.get('items', [])
            if item['code'] == 'ISO32005_UPSTREAM' and isinstance(item.get('details'), dict)
            and 'status' in item['details']]

def report(directory):
    directory = Path(directory)
    inputs = json.loads((directory / 'inputs.json').read_text())
    rules = json.loads((ROOT / 'validator_rule_text.json').read_text())
    matrix = {code: {'rule': code, 'reference': rule['spec_section'], 'comparisons': 0,
                    'worst_level_matches': 0, 'python_levels': {}, 'java_levels': {},
                    'level_transitions': {}, 'missed_failures': 0, 'extra_failures': 0,
                    'item_count_differences': 0, 'examples': []} for code, rule in rules.items()}
    cases, missing = [], []
    def compare(old, new, location):
        x, y = levels(old, rules), levels(new, rules)
        differences, item_counts = {}, {}
        for code, row in matrix.items():
            a, b = worst(x[code]), worst(y[code]); row['comparisons'] += 1
            for key, value in (('python_levels', a), ('java_levels', b), ('level_transitions', a + ' → ' + b)):
                row[key][value] = row[key].get(value, 0) + 1
            if a == b: row['worst_level_matches'] += 1
            if x[code] != y[code]:
                row['item_count_differences'] += 1
                item_counts[code] = {'python': dict(x[code]), 'java': dict(y[code])}
            if a != b:
                differences[code] = {'python': a, 'java': b}
                row['missed_failures'] += a == 'FAIL' and b != 'FAIL'
                row['extra_failures'] += b == 'FAIL' and a != 'FAIL'
                if len(row['examples']) < 5: row['examples'].append({'location': location, 'python': a, 'java': b})
        extra = lambda result: dict(collections.Counter(item['code'] for item in result.get('items', []) if item['code'] not in rules))
        old_pdfa, new_pdfa, old_iso, new_iso = upstream(old.get('pdfa')), upstream(new.get('pdfa')), iso(old), iso(new)
        return {'location': location, 'python_status': old.get('summary', {}).get('status'),
                'java_status': new.get('summary', {}).get('status'),
                'java_port_complete': new.get('summary', {}).get('java_port_complete', False), 'differences': differences,
                'item_level_count_differences': item_counts,
                'pdfa_diagnostics_equal': diagnostics(old.get('pdfa')) == diagnostics(new.get('pdfa')),
                'iso_diagnostics_equal': [diagnostics(x) for x in iso_results(old)] == [diagnostics(x) for x in iso_results(new)],
                'python_error': old.get('comparison_error'), 'java_error': new.get('comparison_error'),
                'python_extra_diagnostics': extra(old), 'java_extra_diagnostics': extra(new),
                'pdfa_equal': old_pdfa == new_pdfa, 'python_pdfa': old_pdfa, 'java_pdfa': new_pdfa,
                'iso_equal': old_iso == new_iso, 'python_iso': old_iso, 'java_iso': new_iso}
    for row in inputs:
        paths = [directory / (row['id'] + '.' + engine + '.json.gz') for engine in ('python', 'java')]
        if not all(path.exists() for path in paths): missing.append(row['path']); continue
        if hashlib.sha256(Path(row['absolute_path']).read_bytes()).hexdigest() != row['sha256']:
            raise RuntimeError('Corpus changed during comparison: ' + row['path'])
        old, new = map(read, paths)
        case = compare(old, new, row['path']); case['sha256'] = row['sha256']
        a, b = old.get('comparison_children', {}), new.get('comparison_children', {})
        case['python_only_children'] = sorted(set(a) - set(b)); case['java_only_children'] = sorted(set(b) - set(a))
        case['children'] = [compare(a[key], b[key], row['path'] + '::child-sha256=' + key) for key in sorted(set(a) & set(b))]
        cases.append(case)
    all_results = [result for case in cases for result in [case, *case['children']]]
    result = {'scope': 'All 798 saved EA-PDF fixtures, eight example PDFs, and both protected reference PDFs. Every run uses real upstream PDF/A and applicable selected ISO checks. Matched cached children are compared by SHA-256.',
              'corpus_cases': len(inputs), 'completed': len(cases), 'missing': missing,
              'parent_and_child_comparisons': len(all_results), 'rule_count': len(rules),
              'port_guard_retained': any(not row['java_port_complete'] for row in all_results),
              'comparison_policy': 'Compare every rule maximum severity and item-level severity counts; record absent rules explicitly. Compare upstream status, profile, compliance, run/job status and complete failing rule identifiers. Text/JSON schemas differ and are not equated. No mismatch is waived as conformance.',
              'summary': {'status_matches': sum(r['python_status'] == r['java_status'] for r in all_results),
                          'pdfa_exact_matches': sum(r['pdfa_equal'] for r in all_results),
                          'iso_exact_matches': sum(r['iso_equal'] for r in all_results),
                          'pdfa_diagnostic_matches': sum(r['pdfa_diagnostics_equal'] for r in all_results),
                          'iso_diagnostic_matches': sum(r['iso_diagnostics_equal'] for r in all_results),
                          'rule_worst_level_matches': sum(r['worst_level_matches'] for r in matrix.values()),
                          'rule_comparisons': sum(r['comparisons'] for r in matrix.values()),
                          'missed_failure_pairs': sum(r['missed_failures'] for r in matrix.values()),
                          'extra_failure_pairs': sum(r['extra_failures'] for r in matrix.values()),
                          'rules_with_missed_failures': sum(bool(r['missed_failures']) for r in matrix.values()),
                          'rules_with_extra_failures': sum(bool(r['extra_failures']) for r in matrix.values()),
                          'execution_errors': sum(bool(r['python_error'] or r['java_error']) for r in all_results),
                          'java_inspection_errors': sum(r['java_extra_diagnostics'].get('VALIDATION_ERROR', 0) for r in all_results),
                          'cases_with_child_inventory_difference': sum(bool(r['python_only_children'] or r['java_only_children']) for r in cases)},
              'rules': list(matrix.values()), 'cases': cases}
    (directory / 'full-comparison.json').write_text(json.dumps(result, indent=2) + '\n')
    return result

if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    result = report(parser.parse_args().directory)
    print(json.dumps({key: value for key, value in result.items() if key not in ('rules', 'cases', 'missing')}, indent=2))
