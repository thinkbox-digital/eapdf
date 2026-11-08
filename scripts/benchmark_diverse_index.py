#!/usr/bin/env python3
"""Measure conversion of deterministic real-mailbox subsets; never changes the input."""
import argparse
import hashlib
import json
import mailbox
import os
from pathlib import Path
import subprocess
import time


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--creator', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--counts', default='100,1000')
    parser.add_argument('--heap', default='2g')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    source_hash = sha(args.source)
    box = mailbox.mbox(args.source, create=False)
    keys = list(box.keys())
    config = json.loads((root / 'config/eapdf_config.json').read_text())
    config.update(creator='EA-PDF diverse benchmark')
    config['accessibility']['addPdfUaFeatures'] = True
    config['premis']['enabled'] = False
    config['findingAid'].update(pdfEnabled=False, excelEnabled=False)
    config['signing']['enabled'] = False
    config['searchIndex'].update(enabled=True, fullTextEnabled=True)
    config_path = output / 'config.json'
    config_path.write_text(json.dumps(config, indent=2) + '\n')
    reports = []
    try:
        for count in map(int, args.counts.split(',')):
            if count > len(keys):
                raise ValueError('Not enough real messages; repeated copies are not substituted')
            case = output / str(count)
            case.mkdir()
            source = case / 'subset.mbox'
            message_hashes = []
            with source.open('wb') as stream:
                for key in keys[:count]:
                    raw = box.get_bytes(key, from_=True)
                    message_hashes.append(hashlib.sha256(raw).hexdigest())
                    stream.write(raw)
                    if not raw.endswith(b'\n'): stream.write(b'\n')
                    stream.write(b'\n')
            generated = case / 'generated'
            generated.mkdir()
            pdf = generated / f'mbox_diverse_{count}_1ci.pdf'
            command = ['/usr/bin/time', '-v', '-o', str(case / 'resources.txt'),
                       'java', '-Xmx' + args.heap, '-XX:ActiveProcessorCount=2', '-Djava.awt.headless=true',
                       '-jar', str(args.creator.resolve()), str(source), '--jobs', '2', '--out', str(generated),
                       '--archive-profile', 'ci', '--archive-output', str(pdf)]
            start = time.monotonic()
            with (case / 'conversion.log').open('w') as log:
                subprocess.run(command, cwd=root, env=dict(os.environ, EAPDF_CONFIG=str(config_path)),
                               stdout=log, stderr=subprocess.STDOUT, check=True, timeout=1800)
            elapsed = time.monotonic() - start
            progress = json.loads((generated / 'progress.json').read_text())
            if progress['emails_discovered'] != count or progress['state'] != 'COMPLETED':
                raise RuntimeError('Incomplete conversion or wrong message count')
            report = {'messages': count, 'sourceSha256': source_hash, 'subsetSha256': sha(source),
                      'uniqueSourceMessages': len(set(message_hashes)), 'sourceMessageHashes': message_hashes,
                      'creatorSha256': sha(args.creator), 'seconds': elapsed, 'pdfBytes': pdf.stat().st_size,
                      'pdfSha256': sha(pdf), 'pdf': str(pdf), 'resources': (case / 'resources.txt').read_text()}
            reports.append(report)
            (output / 'results.json').write_text(json.dumps(reports, indent=2) + '\n')
            print(json.dumps({k: v for k, v in report.items() if k not in ('sourceMessageHashes', 'resources')}), flush=True)
    finally:
        box.close()
        if sha(args.source) != source_hash: raise RuntimeError('Original mailbox changed')


if __name__ == '__main__':
    main()
