#!/usr/bin/env python3
"""Publish content-free comparison evidence after both full corpus runs finish."""
import sys
sys.dont_write_bytecode = True

import argparse
import gzip
import hashlib
import importlib.metadata
import platform
import os
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from comparison_report import report
from compare_existing import PINS, sha

ROOT = Path(__file__).resolve().parents[2]

def write(baseline, revision, jar, build, destination):
    old, new = report(baseline), report(revision)
    for data in (old, new):
        if data['missing'] or data['completed'] != data['corpus_cases'] or data['summary']['execution_errors']:
            raise RuntimeError('The corpus comparison did not complete successfully')
    if json.loads((baseline / 'inputs.json').read_text()) != json.loads((revision / 'inputs.json').read_text()):
        raise RuntimeError('Different input corpora')
    engines = json.loads((revision / 'engines.json').read_text())
    if engines['jar_sha256'] != sha(jar):
        raise RuntimeError('The deliverable JAR differs from the compared JAR')
    for name, expected in PINS.items():
        if sha(ROOT / name) != expected:
            raise RuntimeError('Frozen input changed: ' + name)
    suites = [ET.parse(path).getroot() for path in (build / 'surefire-reports').glob('TEST-*.xml')]
    tests = {key: sum(int(suite.get(key, '0')) for suite in suites) for key in ('tests', 'failures', 'errors', 'skipped')}
    if not suites or tests['failures'] or tests['errors'] or tests['skipped']:
        raise RuntimeError('The complete desktop test run did not pass')
    isolated_path = revision / 'isolated-result.json'
    if not isolated_path.exists():
        isolated_path = baseline / 'isolated-result.json'
    isolated = json.loads(isolated_path.read_text())
    if isolated['jar_sha256'] != sha(jar):
        raise RuntimeError('The standalone verification used a different JAR')
    total = new['parent_and_child_comparisons']
    summary = new['summary']
    parity_gates = {
        'all_rule_outcomes_match': summary['rule_worst_level_matches'] == summary['rule_comparisons'],
        'all_overall_results_match': summary['status_matches'] == total,
        'all_upstream_results_match': summary['pdfa_exact_matches'] == summary['iso_exact_matches'] == total,
        'all_upstream_diagnostics_match': summary['pdfa_diagnostic_matches'] == summary['iso_diagnostic_matches'] == total,
        'no_missed_or_extra_failures': summary['missed_failure_pairs'] == summary['extra_failure_pairs'] == 0,
        'no_execution_or_inspection_errors': summary['execution_errors'] == summary['java_inspection_errors'] == 0,
        'same_embedded_child_inventory': summary['cases_with_child_inventory_difference'] == 0,
        'all_desktop_checks_pass': tests['failures'] == tests['errors'] == tests['skipped'] == 0,
        'standalone_jar_passes': isolated.get('overall') == 'PASS' and isolated.get('ea_failures') == 0
    }
    complete = not new['port_guard_retained'] and all(parity_gates.values())
    if not new['port_guard_retained'] and not complete:
        raise RuntimeError('A completed port must pass every parity gate: ' + str(parity_gates))
    destination.mkdir(parents=True, exist_ok=True)
    for name, data in (('baseline-comparison.json.gz', old), ('comparison.json.gz', new)):
        # No raw email/report details: only paths, hashes, rule levels and upstream rule identities.
        with gzip.open(destination / name, 'wt', encoding='utf-8') as stream:
            json.dump(data, stream, separators=(',', ':'))
    (destination / 'rules.json').write_text(json.dumps(new['rules'], indent=2) + '\n')
    evidence = {'complete_corpus_comparison': True, 'baseline_summary': old['summary'],
                'final_summary': new['summary'], 'parents': new['completed'],
                'matched_child_comparisons': new['parent_and_child_comparisons'] - new['completed'],
                'rules': new['rule_count'], 'baseline_jar_sha256': json.loads((baseline / 'comparison.json').read_text())['jar_sha256'],
                'final_jar_sha256': sha(jar), 'final_jar_bytes': jar.stat().st_size,
                'frozen_hashes_unchanged': PINS, 'specification_sha256': sha(ROOT / 'docs/reference/EA-PDF-v1.pdf'),
                'reference_environment': {'python': platform.python_version(),
                    'pikepdf': importlib.metadata.version('pikepdf'), 'pypdf': importlib.metadata.version('pypdf'),
                    'tree_items': 100000, 'tree_depth': 256,
                    'child_environment': {name: os.environ.get(name, default) for name, default in
                        [('VALIDATOR_CHILD_MAX_DEPTH', '8'), ('VALIDATOR_CHILD_MAX_COUNT', '256'),
                         ('VALIDATOR_CHILD_MAX_BYTES', str(256 * 1024 * 1024)), ('VERAPDF_TIMEOUT', '120')]}},
                'tests': tests, 'isolated_run': isolated,
                'port_complete': complete, 'overall_pass_enabled': complete, 'parity_gates': parity_gates,
                'raw_reports': {'baseline': str(baseline), 'final': str(revision)},
                'source_hashes': {str(path.relative_to(ROOT)): sha(path) for path in sorted((ROOT / 'validator-desktop').rglob('*'))
                                  if path.is_file() and '__pycache__' not in path.parts}}
    (destination / 'evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
    lines = ['# Java/web comparison results', '',
             f"Compared **{new['completed']} parent PDFs**, **{evidence['matched_child_comparisons']} matched embedded-file reports**, and all **{len(new['rules'])} active rule codes**.", '',
             ('**Full Java/web rule-outcome parity verified. Overall PASS is enabled when required comparisons complete.**' if complete else '**The Java port remains incomplete. Overall PASS is disabled.**'), '',
             'Counts below are comparisons against V85, not independent proof of which engine correctly applies every requirement.', '',
             '| Comparison | Initial Java | Repaired Java |', '| --- | ---: | ---: |']
    for title, key in [('Matching overall results', 'status_matches'),
                       ('Matching rule/file outcomes', 'rule_worst_level_matches'),
                       ('Rule/file pairs with V85 FAIL but no Java FAIL', 'missed_failure_pairs'),
                       ('Rule/file pairs with Java FAIL but no V85 FAIL', 'extra_failure_pairs'),
                       ('Rule codes with at least one missed failure', 'rules_with_missed_failures'),
                       ('Rule codes with at least one Java-only failure', 'rules_with_extra_failures'),
                       ('Matching PDF/A results and failing rule identifiers', 'pdfa_exact_matches'),
                       ('Matching selected ISO results and failing rule identifiers', 'iso_exact_matches'),
                       ('Matching PDF/A object locations and error text', 'pdfa_diagnostic_matches'),
                       ('Matching selected ISO object locations and error text', 'iso_diagnostic_matches'),
                       ('Uncaught comparison execution errors', 'execution_errors'),
                       ('Caught Java inspection errors', 'java_inspection_errors'),
                       ('Parent files with different checked-child inventories', 'cases_with_child_inventory_difference')]:
        lines.append(f"| {title} | {old['summary'][key]} | {new['summary'][key]} |")
    lines += ['', 'Each rule outcome is compared in every parent and matched child report. Finding wording and occurrence counts can differ because observations are aggregated differently; the complete evidence retains item-level counts.', '',
              'The comparison establishes parity with the existing web engine, not independent standards certification. Existing unavailable-evidence and inspection-budget limits remain.', '',
              '## Remaining failure differences', '',
              '| Rule | Web FAIL without Java FAIL | Java FAIL without web FAIL |', '| --- | ---: | ---: |']
    for row in sorted((row for row in new['rules'] if row['missed_failures'] or row['extra_failures']), key=lambda r: -(r['missed_failures'] + r['extra_failures']))[:25]:
        lines.append(f"| {row['rule']} | {row['missed_failures']} | {row['extra_failures']} |")
    if not summary['missed_failure_pairs'] and not summary['extra_failure_pairs']: lines += ['| None | 0 | 0 |']
    lines += ['', '## Examples and protected references', '',
              '| File | V85 | Java | V85 PDF/A | Java PDF/A |', '| --- | --- | --- | --- | --- |']
    for case in new['cases']:
        if case['location'].startswith(('examples/', 'output/')):
            a, b = case['python_pdfa'] or {}, case['java_pdfa'] or {}
            lines.append(f"| {case['location']} | {case['python_status']} | {case['java_status']} | {a.get('status')} | {b.get('status')} |")
    lines += ['', f"## All {len(new['rules'])} active rule codes", '',
              '“Same level” counts maximum-severity agreement across parent and matched child reports. “Different item counts” includes differences caused by aggregation; the full evidence records the counts.', '',
              '| Rule | Same level / comparisons | V85-only FAIL | Java-only FAIL | Different item counts |',
              '| --- | ---: | ---: | ---: | ---: |']
    for row in new['rules']:
        lines.append(f"| {row['rule']} | {row['worst_level_matches']} / {row['comparisons']} | {row['missed_failures']} | {row['extra_failures']} | {row['item_count_differences']} |")
    lines += ['', '## Evidence', '',
              '- [Method, repairs and GUI references](README.md)',
              '- [Hashes and build results](evidence.json)',
              '- [Reviewed rule matrix with example paths](rules.json)',
              '- [Complete repaired comparison, compressed JSON](comparison.json.gz)',
              '- [Complete initial comparison, compressed JSON](baseline-comparison.json.gz)', '']
    (destination / 'RESULTS.md').write_text('\n'.join(lines))
    print(json.dumps(evidence, indent=2))

if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('baseline', type=Path); p.add_argument('revision', type=Path)
    p.add_argument('jar', type=Path); p.add_argument('build', type=Path); p.add_argument('destination', type=Path)
    a = p.parse_args(); write(a.baseline, a.revision, a.jar, a.build, a.destination)
