"""Build independently specified source-value recommendation cases (EA-PDF 9.7)."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from xml.etree import ElementTree as ET

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import validate_eapdf as validator
from build_eapdf_rule_fixtures import structure_elements


def properties(element):
    attribute = element.A
    if isinstance(attribute, pikepdf.Array):
        attribute = next(a for a in attribute if isinstance(a, pikepdf.Dictionary)
                         and a.get('/O') == pikepdf.Name.UserProperties)
    return attribute.P


def variant(seed, output, case):
    with pikepdf.Pdf.open(seed) as document:
        metadata = ET.fromstring(document.Root.Metadata.read_bytes())
        ns = {'m': 'http://www.pdfa.org/eapdf/ns/meta/', 'r': 'http://www.w3.org/1999/02/22-rdf-syntax-ns#'}
        owner = next(n for n in metadata.findall('.//m:email/r:Bag/r:li', ns)
                     if n.findtext('m:Subject', namespaces=ns) == 'Source Alpha')
        guid = owner.findtext('m:Mail_GUID', namespaces=ns)
        elements = list(structure_elements(document))
        message = next(e for e in elements if str(e.S) == '/Mail_Message' and any(
            str(p.N) == 'Mail_GUID' and str(p.V) == guid for p in properties(e)))
        assert any(str(p.N) == 'Mail_MessageID' and str(p.V) == '<shared@example.invalid>'
                   for p in properties(message))
        if case.get('property'):
            values = properties(message)
            prop = next((p for p in values if str(p.N) == case['property']), None)
            if prop is None:
                prop = pikepdf.Dictionary(N=case['property'])
                values.append(prop)
            prop.V = case['value']
        if case['array_form']:
            root = document.Root.StructTreeRoot.K
            if not isinstance(root, pikepdf.Array):
                document.Root.StructTreeRoot.K = pikepdf.Array([root])
            for element in elements:
                attribute = element.get('/A')
                if isinstance(attribute, pikepdf.Dictionary):
                    element.A = pikepdf.Array([attribute, 0])
        document.save(output)
        return guid


def main():
    inputs = ROOT / 'tests/fixtures/eapdf/batch04'
    output = Path(os.environ.get('EAPDF_SOURCE_FIXTURE_DIR', ROOT / 'docs/improvement/batch-04-corpus')).resolve()
    output.mkdir(parents=True, exist_ok=True)
    version = (ROOT / 'VERSION').read_text().strip()
    classpath = os.environ.get('EAPDF_GENERATOR_CLASSPATH', str(ROOT / 'target' / f'emailtopdfa-{version}.jar'))
    config = output / 'config.json'
    config.write_text(json.dumps({'branding': {'enabled': False}, 'premis': {'enabled': False},
                                 'findingAid': {'pdfEnabled': False}}))
    seeds = {}
    for profile in ('m', 'mi'):
        path = output / 'seed' / profile / 'archive.pdf'
        command = ['java', '-Djava.awt.headless=true', '-Xmx256m', '-cp', classpath,
                   'eapdf.EmailToPDFA', '--out', str(path.parent), '--archive-profile', profile,
                   '--archive-output', str(path), str(inputs / 'sources')]
        process = subprocess.run(command, cwd=ROOT, env=dict(os.environ, EAPDF_CONFIG=str(config)),
                                 capture_output=True, text=True, timeout=90)
        (output / f'generation-{profile}.log').write_text(process.stdout + process.stderr)
        if process.returncode:
            raise RuntimeError(f'Seed generation failed: {profile}')
        seeds[profile] = path
    results = []
    for case in json.loads((inputs / 'cases.json').read_text()):
        pdf = output / (case['id'] + '.pdf')
        expected_owner = variant(seeds[case['profile']], pdf, case)
        report = validator.validate_one(str(pdf), html_path=str(output / (case['id'] + '.html')))
        (output / (case['id'] + '.json')).write_text(json.dumps(report, default=str, indent=2))
        assert report['pdfa']['status'] == report['summary']['status'] == 'PASS', (case['id'], report['summary'])
        assert not any(i['level'] == 'FAIL' for i in report['items']), case['id']
        findings = [i for i in report['items'] if i['code'] == 'USERPROPS_VALUES_UNMODIFIED']
        rank = {'N/A': 0, 'PASS': 1, 'WARN': 2, 'FAIL': 3}
        level = max((i['level'] for i in findings), key=rank.get)
        assert level == case['expected_recommendation'], (case['id'], findings)
        changes = [i['details'] for i in findings if i['level'] == 'WARN']
        if case['expected_recommendation'] == 'WARN':
            assert changes and all(d['name'] == case['property'] and d['Mail_GUID'] == expected_owner
                                   and d['comparison'] == 'different' for d in changes), (case['id'], changes)
        assert not any(isinstance(i.get('details'), dict) and i['details'].get('state') == 'unverified'
                       for i in report['items']), case['id']
        results.append({'case': case['id'], 'sha256': hashlib.sha256(pdf.read_bytes()).hexdigest(),
                        'pdfa': 'PASS', 'eapdf': 'PASS', 'recommendation': level,
                        'changes': changes, 'expected_result_observed': True})
        print(case['id'], 'PDF/A PASS EA-PDF PASS recommendation', level, flush=True)
    (output / 'results.json').write_text(json.dumps(results, indent=2) + '\n')


if __name__ == '__main__':
    main()
