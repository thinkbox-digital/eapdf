"""Record bounded static routing review plus observed retained-corpus evidence."""
import ast
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
source = ROOT / '.eapdf-verification-v12'
rules = json.loads((source / 'validator_rule_text.json').read_text())
tree = ast.parse((source / 'validate_eapdf.py').read_text())
functions = {}
for node in ast.walk(tree):
    if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
        functions[node.name] = {value.value for value in ast.walk(node)
                                if isinstance(value, ast.Constant) and isinstance(value.value, str)}
reports = list((HERE / 'retained-corpus').glob('*.json'))
assert len(reports) == 98, len(reports)
observed = {}
for path in reports:
    for item in json.loads(path.read_text())['items']:
        observed.setdefault(item['code'], {}).setdefault(item['level'], set()).add(path.name)
rows = []
for code, rule in rules.items():
    scope = rule.get('validation_scope', {})
    if scope.get('decision') != 'split_custom_and_verapdf':
        continue
    assert scope['covered_fragment'] and scope['uncovered_fragment']
    assert scope['coverage_family'] in {'metadata_and_identification', 'document_parts',
        'source_asset_xmp', 'message_and_attachment_xmp'}
    rows.append({'code': code, 'family': scope['coverage_family'],
        'covered_upstream': scope['covered_fragment'], 'custom_fragment': scope['uncovered_fragment'],
        'profile_conditions': scope['profile_conditions'],
        'functions_containing_literal_code': sorted(name for name, values in functions.items() if code in values),
        'observed_levels': {level: {'reports': len(paths), 'examples': sorted(paths)[:3]}
                            for level, paths in sorted(observed.get(code, {}).items())},
        'review': 'Custom assertions concern EA-PDF presence/schema/identity/association, source comparisons or unavailable provenance; generic RDF serialization and declared PDF/A type checks stay upstream.'})
assert len(rows) == 59
result = {'scope': 'Static routing review of all 59 mixed codes at frozen V12',
    'engine_sha256': hashlib.sha256((source / 'validate_eapdf.py').read_bytes()).hexdigest(),
    'authority_sha256': hashlib.sha256((source / 'validator_rule_text.json').read_bytes()).hexdigest(),
    'retained_reports': len(reports), 'codes_observed': sum(bool(row['observed_levels']) for row in rows),
    'codes_with_recorded_failures': sum('FAIL' in row['observed_levels'] for row in rows),
    'rows': rows, 'limits': ['Observed results are not branch coverage or proof of every requirement.',
        'Reports include applicability/finalization rows; a recorded level does not prove that its checking branch executed.',
        'Conditional codes without observations remain explicitly unverified by this corpus.',
        'Eleven expert/dependency questions, arbitrary source associations and completeness remain open.',
        'No upstream predicate was copied, weakened or removed during V12.']}
(HERE / 'mixed-fragment-review.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps({key: result[key] for key in ('scope', 'retained_reports', 'codes_observed', 'codes_with_recorded_failures')}, indent=2))
