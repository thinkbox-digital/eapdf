"""Load runtime rules and separate review records for development tools only.

The creator and validators must not depend on the review file.
"""
import copy
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REVIEW_PATH = ROOT / 'docs/improvement/specification-audit/validator-rule-review.json'


def merge_review(runtime, review):
    """Reconstruct the reviewed document without allowing duplicate leaf values."""
    result = copy.deepcopy(runtime)
    for key, value in review.items():
        if key not in result:
            result[key] = copy.deepcopy(value)
        elif isinstance(result[key], dict) and isinstance(value, dict):
            result[key] = merge_review(result[key], value)
        else:
            raise ValueError('Runtime and review data overlap: ' + key)
    return result


def load_review_rules(runtime_path=None, review_path=None):
    runtime = json.loads(Path(runtime_path or ROOT / 'validator_rule_text.json').read_text(encoding='utf-8'))
    review = json.loads(Path(review_path or REVIEW_PATH).read_text(encoding='utf-8'))
    if review.get('format_version') != 1 or set(runtime) != set(review['rules']):
        raise ValueError('Runtime and review rule inventories differ')
    return {code: merge_review(rule, review['rules'][code]) for code, rule in runtime.items()}
