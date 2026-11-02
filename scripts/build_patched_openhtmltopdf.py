#!/usr/bin/env python3
"""Verify vendored dependencies, or rebuild them from the pinned source and patches."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile
from datetime import datetime

ROOT = Path(__file__).resolve().parents[1]
LOCK = json.loads((ROOT/'third_party/openhtmltopdf/lock.json').read_text())

def checksum(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def normalize_jar(path):
    """Normalize packaging only; retain every class/resource/source byte unchanged."""
    date = datetime.fromisoformat(LOCK['output_timestamp'].replace('Z', '+00:00'))
    stamp = (date.year, date.month, date.day, date.hour, date.minute, date.second)
    with zipfile.ZipFile(path) as archive:
        files = {n: archive.read(n) for n in archive.namelist() if not n.endswith('/')}
    name = 'META-INF/MANIFEST.MF'
    if name in files:
        lines = []; dropping = False
        for line in files[name].decode('utf-8').splitlines():
            if line.startswith(' '):
                if not dropping: lines.append(line)
                continue
            field = line.partition(':')[0]
            dropping = field in {'Built-By', 'Build-Jdk', 'Bnd-LastModified'}
            if field == 'Built-By': lines.append('Built-By: EA-PDF integration build')
            elif field == 'Bnd-LastModified': lines.append('Bnd-LastModified: '+str(int(date.timestamp()*1000)))
            elif not dropping: lines.append(line)
        while lines and not lines[-1]: lines.pop()
        files[name] = ('\r\n'.join(lines)+'\r\n\r\n').encode('utf-8')
    pending = path.with_suffix('.normalized')
    with zipfile.ZipFile(pending, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name in sorted(files, key=lambda n: (n != 'META-INF/MANIFEST.MF', n)):
            info = zipfile.ZipInfo(name, stamp); info.create_system = 3
            info.external_attr = 0o100644 << 16; info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, files[name], compresslevel=9)
    pending.replace(path)

def run(args, cwd=None):
    subprocess.run(args, cwd=cwd, check=True)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--rebuild', action='store_true')
    args = parser.parse_args()
    for item in LOCK['patches'] + LOCK['artifacts']:
        path = ROOT/item['path']
        if checksum(path) != item['sha256']:
            raise SystemExit('Checksum mismatch: '+item['path'])
    if not args.rebuild:
        print('Patched OpenHTMLtoPDF artifacts and patches match lock.json.')
        return
    with tempfile.TemporaryDirectory(prefix='eapdf-openhtml-rebuild-') as scratch:
        scratch = Path(scratch); source = scratch/'source'; repository = scratch/'m2'
        run(['git', 'clone', '--filter=blob:none', '--no-checkout', LOCK['upstream_url'], str(source)])
        run(['git', 'checkout', '--detach', LOCK['upstream_commit']], source)
        run(['git', '-c', 'user.name=EA-PDF integration build', '-c', 'user.email=build@localhost',
             'am', '--committer-date-is-author-date', *[str(ROOT/p['path']) for p in LOCK['patches']]], source)
        commits = subprocess.check_output(['git', 'rev-list', '--reverse', LOCK['upstream_commit']+'..HEAD'], cwd=source, text=True).splitlines()
        if commits != LOCK['applied_commits']:
            raise SystemExit('Applied patch commit identifiers differ from lock.json')
        run(['mvn', '-B', '-pl', 'openhtmltopdf-pdfbox', '-am', '-Drevision='+LOCK['version'],
             '-Dmaven.repo.local='+str(repository), '-Dproject.build.outputTimestamp='+LOCK['output_timestamp'],
             '-Dmaven.javadoc.skip=true', 'install'], source)
        mismatches = []
        for item in LOCK['artifacts']:
            relative = Path(item['path']).relative_to('third_party/maven')
            if relative.suffix == '.jar': normalize_jar(repository/relative)
            if checksum(repository/relative) != item['sha256']:
                mismatches.append(str(relative))
        if mismatches:
            raise SystemExit('Rebuilt artifact hashes differ (check the documented JDK/toolchain): '+', '.join(mismatches))
        print('Clean source rebuild, upstream tests, patch commits and artifact hashes verified.')

if __name__ == '__main__':
    main()
