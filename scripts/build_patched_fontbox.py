#!/usr/bin/env python3
"""Verify or reproduce the narrow FontBox 3.0.8 backport (Git, JDK 21, Python 3)."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
from urllib.request import urlopen
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LOCK = json.loads((ROOT / 'third_party/fontbox/lock.json').read_text())
SOURCES = ['org/apache/fontbox/ttf/CmapSubtable.java',
           'org/apache/fontbox/cff/Type1CharStringParser.java',
           'org/apache/fontbox/cff/Type2CharStringParser.java']


def digest(data):
    return hashlib.sha256(data).hexdigest()


def archive(path, files):
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as output:
        for name in sorted(files):
            entry = zipfile.ZipInfo(name, (2026, 11, 17, 0, 0, 0))
            entry.create_system = 3
            entry.external_attr = 0o100644 << 16
            entry.compress_type = zipfile.ZIP_DEFLATED
            output.writestr(entry, files[name], compresslevel=9)


def build(destination):
    with tempfile.TemporaryDirectory(prefix='eapdf-fontbox-') as temporary:
        temporary = Path(temporary)
        inputs = {}
        for item in LOCK['inputs']:
            with urlopen(item['url'], timeout=60) as response:
                data = response.read()
            if digest(data) != item['sha256']:
                raise SystemExit('Upstream checksum mismatch: ' + item['name'])
            path = temporary / item['name']
            path.write_bytes(data)
            inputs[item['name']] = path
        source = temporary / 'source'
        with zipfile.ZipFile(inputs['fontbox-3.0.8-sources.jar']) as jar:
            files = {name: jar.read(name) for name in jar.namelist() if not name.endswith('/')}
        for name in SOURCES:
            path = source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(files[name].replace(b'\r\n', b'\n'))
        for item in LOCK['patches']:
            subprocess.run(['git', 'apply', '--unsafe-paths', '--directory=' + str(source),
                            str(ROOT / item['path'])], check=True)
        classes = temporary / 'classes'
        classes.mkdir()
        subprocess.run(['javac', '--release', '8', '-encoding', 'UTF-8', '-g',
                        '-cp', ':'.join(str(path) for name, path in inputs.items() if name.endswith('.jar')),
                        '-d', str(classes), *[str(source / name) for name in SOURCES]], check=True)
        pom = inputs['fontbox-3.0.8.pom'].read_bytes().replace(b'<artifactId>fontbox</artifactId>',
            b'<artifactId>fontbox</artifactId>\n  <version>3.0.8-eapdf.1</version>', 1)
        pom = pom.replace(b'<version>${project.version}</version>', b'<version>3.0.8</version>')
        for suffix in ['.jar', '-sources.jar']:
            with zipfile.ZipFile(inputs['fontbox-3.0.8' + suffix]) as jar:
                contents = {name: jar.read(name) for name in jar.namelist() if not name.endswith('/')}
            if suffix == '.jar':
                for path in classes.rglob('*.class'):
                    contents[path.relative_to(classes).as_posix()] = path.read_bytes()
            else:
                for name in SOURCES:
                    contents[name] = (source / name).read_bytes()
            for name in list(contents):
                if name.endswith('/pom.properties'):
                    contents[name] = b'groupId=org.apache.pdfbox\nartifactId=fontbox\nversion=3.0.8-eapdf.1\n'
                elif name.endswith('/pom.xml'):
                    contents[name] = pom
            if 'META-INF/MANIFEST.MF' in contents:
                contents['META-INF/MANIFEST.MF'] = contents['META-INF/MANIFEST.MF'].replace(
                    b'Bundle-Version: 3.0.8', b'Bundle-Version: 3.0.8.eapdf1')
            contents['META-INF/eapdf-fontbox-backport.json'] = json.dumps({
                'version': LOCK['version'], 'upstream_commits': LOCK['upstream_commits'],
                'patches': LOCK['patches']}, indent=2).encode() + b'\n'
            archive(destination / ('fontbox-' + LOCK['version'] + suffix), contents)
        (destination / ('fontbox-' + LOCK['version'] + '.pom')).write_bytes(pom)


def verify_shaded(path):
    jar_name = f"fontbox-{LOCK['version']}.jar"
    reference = next(ROOT / item['path'] for item in LOCK['artifacts'] if Path(item['path']).name == jar_name)
    class_names = tuple(name.removesuffix('.java') for name in SOURCES)
    entries = {name + '.class' for name in class_names}
    entries.update({'META-INF/eapdf-fontbox-backport.json',
                    'META-INF/maven/org.apache.pdfbox/fontbox/pom.properties',
                    'META-INF/maven/org.apache.pdfbox/fontbox/pom.xml'})
    with zipfile.ZipFile(reference) as expected, zipfile.ZipFile(path) as package:
        companion_prefixes = tuple(name + '$' for name in class_names)
        entries.update(name for name in expected.namelist()
                       if name.startswith(companion_prefixes) and name.endswith('.class'))
        counts = Counter(package.namelist())
        for entry in sorted(entries):
            if counts[entry] != 1 or package.read(entry) != expected.read(entry):
                raise SystemExit(f'{path}: {entry} does not match the locked FontBox backport exactly once')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--rebuild', action='store_true')
    parser.add_argument('--verify-shaded', metavar='JAR', type=Path,
                        help='Check the actual shaded FontBox classes and provenance against the locked backport')
    args = parser.parse_args()
    for item in LOCK['patches'] + LOCK['artifacts']:
        if digest((ROOT / item['path']).read_bytes()) != item['sha256']:
            raise SystemExit('Checksum mismatch: ' + item['path'])
    if args.rebuild:
        with tempfile.TemporaryDirectory(prefix='eapdf-fontbox-verify-') as temporary:
            build(Path(temporary))
            for item in LOCK['artifacts']:
                if digest((Path(temporary) / Path(item['path']).name).read_bytes()) != item['sha256']:
                    raise SystemExit('Rebuild mismatch: ' + item['path'])
    if args.verify_shaded:
        verify_shaded(args.verify_shaded)
        print(f'FontBox backport verified in {args.verify_shaded}.')
    print('FontBox backport patches and artifacts verified.')


if __name__ == '__main__':
    main()
