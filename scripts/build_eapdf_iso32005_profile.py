"""Select unchanged ISO 32005 rules from the pinned upstream composite profile.

PDFUA_2 remains the verifier's model/flavour selector. Every PDF/UA predicate is
removed; this local selection is not an official standalone published profile.
"""
from collections import Counter
import hashlib
import json
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NAMESPACE = 'http://www.verapdf.org/ValidationProfile'
NS = {'v': NAMESPACE}
PROFILE_NAME = 'EA-PDF ISO 32005 upstream rules (veraPDF 1.31.224)'
SOURCE = ROOT / 'config/verapdf/upstream/PDFUA-2-ISO32005.xml'
OUTPUT = ROOT / 'config/verapdf/ISO-32005.xml'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def rule_bytes(rule):
    return ET.canonicalize(ET.tostring(rule, encoding='unicode'))


def build():
    source_hash = '7f7bb7be37c7f89026ce4e5733341d339a92d8cb004349055aac685d1765655b'
    assert digest(SOURCE) == source_hash, 'Unreviewed upstream profile bytes'
    ET.register_namespace('', NAMESPACE)
    profile = ET.fromstring(SOURCE.read_bytes())
    rules = profile.find('v:rules', NS)
    counts = Counter(rule.find('v:id', NS).get('specification') for rule in rules)
    assert counts == {'ISO_32005': 1636, 'ISO_14289_2': 92}, counts
    selected = [rule for rule in rules if rule.find('v:id', NS).get('specification') == 'ISO_32005']
    expected = [rule_bytes(rule) for rule in selected]
    for rule in list(rules):
        if rule not in selected:
            rules.remove(rule)
    profile.find('v:details/v:name', NS).text = PROFILE_NAME
    profile.find('v:details/v:description', NS).text = (
        'Local selection of all 1636 unchanged ISO_32005 predicates from the pinned veraPDF '
        'PDFUA-2-ISO32005 profile; all 92 ISO_14289_2 predicates excluded. '
        'PDFUA_2 is a model selector, not a requirement to conform to PDF/UA.')
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(profile).write(OUTPUT, encoding='utf-8', xml_declaration=True)
    actual = ET.fromstring(OUTPUT.read_bytes()).find('v:rules', NS)
    assert [rule_bytes(rule) for rule in actual] == expected, 'Upstream rule changed during selection'
    evidence = dict(source=str(SOURCE.relative_to(ROOT)), source_sha256=digest(SOURCE),
        upstream_profile='PDFUA-2-ISO32005', upstream_version='1.31.224',
        source_rules_by_specification=dict(counts), selected_specification='ISO_32005',
        selected_rules=len(selected), excluded_pdfua_rules=counts['ISO_14289_2'],
        unchanged_rule_payload_sha256=hashlib.sha256(''.join(expected).encode()).hexdigest(),
        profile=str(OUTPUT.relative_to(ROOT)), profile_sha256=digest(OUTPUT),
        profile_name=PROFILE_NAME, model_selector=profile.get('flavour'),
        status='Local selection of unchanged upstream rules; not an officially published standalone profile')
    evidence['reported_versions'] = {'apps': '1.31.224', 'core': '1.31.56', 'validation-model': '1.31.221'}
    (OUTPUT.with_suffix('.json')).write_text(json.dumps(evidence, indent=2) + '\n')
    print(json.dumps(evidence, indent=2))
    return evidence


if __name__ == '__main__':
    build()
