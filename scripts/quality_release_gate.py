#!/usr/bin/env python3
"""Build/test both Java tools, compare clean builds and check real conversions. No publishing."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]

def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def check_skips(name, skipped):
    allowed = json.loads((ROOT / 'regression/quality/allowed-skips.json').read_text())[name]
    unexpected = set(skipped) - set(allowed)
    if unexpected:
        raise RuntimeError('Unexpected skipped tests: ' + ', '.join(sorted(unexpected)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True, help='New directory for retained evidence')
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    summary = {'status': 'RUNNING', 'scope': 'Linux Java builds, public small EML/MSG/MBOX fidelity/visual acceptance, Java EA-PDF/veraPDF validation',
               'excluded': ['RealWorldInputTests: optional local corpus includes large PST scans', 'GUI runtime tests', 'Cross-platform runtime certification'], 'steps': [], 'tests': {}, 'artifacts': {}}
    def run(name, command, env=None):
        print(name, flush=True)
        with (output / (name + '.log')).open('w') as log:
            result = subprocess.run([str(x) for x in command], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=1800, env=env)
        summary['steps'].append({'name': name, 'exit_code': result.returncode})
        if result.returncode:
            raise RuntimeError(f'{name} failed; see {output / (name + ".log")}')
    def reports(name, build):
        suites = list((build / 'surefire-reports').glob('TEST-*.xml'))
        if not suites:
            raise RuntimeError('No test results: ' + name)
        tests = failures = errors = 0
        skipped = []
        for path in suites:
            root = ET.parse(path).getroot()
            tests += int(root.get('tests', 0)); failures += int(root.get('failures', 0)); errors += int(root.get('errors', 0))
            for case in root.findall('testcase'):
                if case.find('skipped') is not None:
                    skipped.append(case.get('classname') + '.' + case.get('name'))
        summary['tests'][name] = dict(tests=tests, failures=failures, errors=errors, skipped=skipped)
        shutil.copytree(build / 'surefire-reports', output / (name + '-tests'))
        check_skips(name, skipped)
        if failures or errors:
            raise RuntimeError('Test failures: ' + name)
        if name == 'creator':
            for suite, minimum in {'ConversionProgressTests': 7, 'PayloadFingerprintTests': 9, 'PstDatePrecisionTests': 2, 'AttachedMessageEncodingTests': 2, 'ConversionDiagnosticTests': 5, 'SmallInputReliabilityTests': 5}.items():
                required = build / f'surefire-reports/TEST-eapdf.{suite}.xml'
                root = ET.parse(required).getroot()
                if int(root.get('tests', 0)) < minimum or int(root.get('skipped', 0)):
                    raise RuntimeError('Required quality tests missing or skipped: ' + suite)
    try:
        run('viewer-check', ['npm', '--prefix', 'browser-viewer', 'run', 'check'])
        run('viewer-chromium', ['npm', '--prefix', 'browser-viewer', 'test'])
        run('viewer-firefox', ['npm', '--prefix', 'browser-viewer', 'run', 'test:firefox'], dict(os.environ, M5_UI='1'))
        run('viewer-archive-memory', ['npm', '--prefix', 'browser-viewer', 'run', 'test:archive-memory'])
        run('python-regressions', [sys.executable, '-B', '-m', 'unittest', 'discover', '-s', 'regression', '-v'])
        run('oracle-tests', [sys.executable, '-m', 'unittest', 'discover', '-s', 'regression/quality', '-p', 'test_gate.py'])
        with tempfile.TemporaryDirectory(prefix='eapdf-quality-gate-') as temporary:
            temporary = Path(temporary)
            version = ET.parse(ROOT / 'pom.xml').findtext('{http://maven.apache.org/POM/4.0.0}version')
            outputs = {}
            for name, pom in [('creator', ROOT / 'pom.xml'), ('validator', ROOT / 'validator-desktop/pom.xml')]:
                build = temporary / name
                command = ['mvn', '-B', '-f', pom, '-Deapdf.build.directory=' + str(build)]
                if name == 'creator':
                    command += ['-Dtest=!RealWorldInputTests']
                run(name, command + ['verify'])
                reports(name, build)
                rebuilt = temporary / (name + '-repeat')
                run(name + '-repeat', ['mvn', '-B', '-f', pom, '-Deapdf.build.directory=' + str(rebuilt), '-DskipTests', 'package'])
                names = [f'emailtopdfa-{version}.jar', f'emailtopdfa-{version}-gui.jar'] if name == 'creator' else [f'eapdf-validator-{version}.jar']
                for filename in names:
                    digest = sha(build / filename)
                    if digest != sha(rebuilt / filename):
                        raise RuntimeError('Clean builds differ: ' + filename)
                    summary['artifacts'][filename] = digest
                outputs[name] = build / names[0]
            run('conversion-quality', [sys.executable, ROOT / 'scripts/check_conversion_quality.py', '--creator', outputs['creator'], '--validator', outputs['validator'], '--output', output / 'conversions'])
            # Preserve exactly the tested binaries for local review; no dist/release mutation.
            artifacts = output / 'artifacts'; artifacts.mkdir()
            for name, path in outputs.items():
                shutil.copy2(path, artifacts / path.name)
            shutil.copy2(temporary / 'creator' / f'emailtopdfa-{version}-gui.jar', artifacts / f'emailtopdfa-{version}-gui.jar')
        summary['status'] = 'PASS'
    except Exception as error:
        summary['status'] = 'FAIL'; summary['error'] = str(error)
        raise
    finally:
        (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')

if __name__ == '__main__':
    main()
