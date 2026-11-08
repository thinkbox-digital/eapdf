#!/usr/bin/env python3
"""Validate the four generated signed-index fixtures with the standalone validator."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixtures', type=Path, required=True)
    parser.add_argument('--validator', type=Path, required=True)
    args = parser.parse_args()
    results = []
    for profile in ('1m', '1mi', '1c', '1ci'):
        pdf = args.fixtures.resolve() / f'mbox_sample_{profile}.pdf'
        before = hashlib.sha256(pdf.read_bytes()).hexdigest()
        report = pdf.with_suffix('.validation.json')
        with pdf.with_suffix('.validation.log').open('w') as log:
            subprocess.run(['java', '-Xmx768m', '-XX:ActiveProcessorCount=2', '-cp',
                            str(args.validator.resolve()), 'eapdf.JavaValidation', str(pdf), str(report)],
                           stdout=log, stderr=subprocess.STDOUT, check=True, timeout=300)
        data = json.loads(report.read_text())
        if not (data['summary']['status'] == data['pdfa']['status'] == 'PASS'
                and data['pdfa']['ran'] and data['pdfa']['compliant']):
            raise RuntimeError(f'Signed-index conformance failed: {profile}')
        if hashlib.sha256(pdf.read_bytes()).hexdigest() != before:
            raise RuntimeError(f'Validator changed fixture: {profile}')
        if not (pdf.parent / 'revisions' / pdf.name).is_file():
            raise RuntimeError(f'Missing incremental-update negative control: {profile}')
        results.append({'profile': profile, 'sha256': before, 'status': 'PASS',
                        'warnings': data['summary']['warn']})
    (args.fixtures / 'compliance.json').write_text(json.dumps(results, indent=2) + '\n')
    print('Signed-index conformance: all four profiles PASS')


if __name__ == '__main__':
    main()
