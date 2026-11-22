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


def build_layout_guard(base_repository, destination):
    """Compile the layout guard against the verified base artifacts, offline."""
    guard = LOCK['layout_guard']
    group = Path('io/github/openhtmltopdf')
    base = guard['base_version']
    version = LOCK['version']
    layer = Path('com/openhtmltopdf/layout/Layer.java')

    def artifact(module, revision, suffix):
        return group/module/revision/(module+'-'+revision+suffix)

    def version_metadata(name, data):
        if name.endswith(('/pom.xml', '/pom.properties', '.pom')):
            return data.replace(base.encode(), version.encode())
        if name == 'META-INF/MANIFEST.MF':
            return data.replace(base.replace('-eapdf.', '.eapdf').encode(),
                                version.replace('-eapdf.', '.eapdf').encode())
        return data

    with tempfile.TemporaryDirectory(prefix='eapdf-openhtml-layout-') as work:
        work = Path(work)
        core = 'openhtmltopdf-core'
        source = work/layer
        source.parent.mkdir(parents=True)
        with zipfile.ZipFile(base_repository/artifact(core, base, '-sources.jar')) as jar:
            source.write_bytes(jar.read(layer.as_posix()).replace(b'\r\n', b'\n'))
        run(['git', 'apply', str(ROOT/guard['patch']['path'])], work)
        classes = work/'classes'
        run(['javac', '--release', '8', '-Xlint:-options', '-encoding', 'UTF-8', '-g',
             '-cp', str(base_repository/artifact(core, base, '.jar')), '-d', str(classes), str(source)])

        for module in (core, 'openhtmltopdf-pdfbox'):
            for suffix in ('.jar', '-sources.jar', '.pom'):
                original = base_repository/artifact(module, base, suffix)
                output = destination/artifact(module, version, suffix)
                output.parent.mkdir(parents=True, exist_ok=True)
                if suffix == '.pom':
                    output.write_bytes(version_metadata(output.name, original.read_bytes()))
                    continue
                with zipfile.ZipFile(original) as jar:
                    files = {name: version_metadata(name, jar.read(name)) for name in jar.namelist() if not name.endswith('/')}
                if module == core and suffix == '.jar':
                    for item in classes.rglob('*.class'):
                        files[item.relative_to(classes).as_posix()] = item.read_bytes()
                elif module == core:
                    files[layer.as_posix()] = source.read_bytes()
                with zipfile.ZipFile(output, 'w') as jar:
                    for name, data in files.items():
                        jar.writestr(name, data)
                normalize_jar(output)


def verify_layout_guard(base_repository):
    with tempfile.TemporaryDirectory(prefix='eapdf-openhtml-layout-verify-') as work:
        destination = Path(work)
        build_layout_guard(base_repository, destination)
        for item in LOCK['artifacts']:
            relative = Path(item['path']).relative_to('third_party/maven')
            if checksum(destination/relative) != item['sha256']:
                raise SystemExit('Rebuilt layout guard differs: '+str(relative))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    rebuild = parser.add_mutually_exclusive_group()
    rebuild.add_argument('--rebuild', action='store_true')
    rebuild.add_argument('--rebuild-layout-guard', action='store_true')
    args = parser.parse_args()
    guard = LOCK['layout_guard']
    for item in LOCK['patches'] + guard['base_artifacts'] + [guard['patch']] + LOCK['artifacts']:
        path = ROOT/item['path']
        if checksum(path) != item['sha256']:
            raise SystemExit('Checksum mismatch: '+item['path'])
    if args.rebuild_layout_guard:
        verify_layout_guard(ROOT/'third_party/maven')
        print('Offline OpenHTMLtoPDF layout guard rebuild verified.')
        return
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
        run(['mvn', '-B', '-pl', 'openhtmltopdf-pdfbox', '-am', '-Drevision='+guard['base_version'],
             '-Dmaven.repo.local='+str(repository), '-Dproject.build.outputTimestamp='+LOCK['output_timestamp'],
             '-Dmaven.javadoc.skip=true', 'install'], source)
        mismatches = []
        for item in guard['base_artifacts']:
            relative = Path(item['path']).relative_to('third_party/maven')
            if relative.suffix == '.jar': normalize_jar(repository/relative)
            if checksum(repository/relative) != item['sha256']:
                mismatches.append(str(relative))
        if mismatches:
            raise SystemExit('Rebuilt artifact hashes differ (check the documented JDK/toolchain): '+', '.join(mismatches))
        verify_layout_guard(repository)
        print('Clean source rebuild, upstream tests, patch commits and artifact hashes verified.')

if __name__ == '__main__':
    main()
