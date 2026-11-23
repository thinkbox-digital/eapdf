#!/usr/bin/env python3
"""Verify or reproduce the PDFBox 3.0.8 backport of PDFBOX-6268 (Git, JDK 21, Python 3)."""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
from urllib.request import urlopen
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LOCK = json.loads((ROOT / 'third_party/pdfbox/lock.json').read_text())
UPSTREAM = '3.0.8'
VERSION = LOCK['version']
SOURCES = ('org/apache/pdfbox/cos/COSDocument.java',
           'org/apache/pdfbox/pdfparser/BaseParser.java')
MAVEN_METADATA = 'META-INF/maven/org.apache.pdfbox/pdfbox'
MARKER = 'META-INF/eapdf-pdfbox-backport.json'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def archive(path, files):
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as output:
        for name in sorted(files):
            entry = zipfile.ZipInfo(name, (2026, 11, 23, 0, 0, 0))
            entry.create_system = 3
            entry.external_attr = 0o100644 << 16
            entry.compress_type = zipfile.ZIP_DEFLATED
            output.writestr(entry, files[name], compresslevel=9)


def build(destination):
    with tempfile.TemporaryDirectory(prefix='eapdf-pdfbox-') as temporary:
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
        source = temporary / 'pdfbox/src/main/java'
        with zipfile.ZipFile(inputs[f'pdfbox-{UPSTREAM}-sources.jar']) as jar:
            for name in SOURCES:
                path = source / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(jar.read(name).replace(b'\r\n', b'\n'))
        for patch in LOCK['patches']:
            subprocess.run(['git', 'apply', '--include=pdfbox/src/main/java/**',
                            str(ROOT / patch['path'])], cwd=temporary, check=True)

        classes = temporary / 'classes'
        classes.mkdir()
        classpath = os.pathsep.join(str(path) for name, path in inputs.items()
                                    if name.endswith('.jar') and not name.endswith('-sources.jar'))
        subprocess.run(['javac', '--release', '8', '-encoding', 'UTF-8', '-g', '-cp', classpath,
                        '-d', str(classes), *[str(source / name) for name in SOURCES]], check=True)

        pom = inputs[f'pdfbox-{UPSTREAM}.pom'].read_bytes()
        pom = pom.replace(b'<artifactId>pdfbox</artifactId>',
                          f'<artifactId>pdfbox</artifactId>\n    <version>{VERSION}</version>'.encode(), 1)
        pom = pom.replace(b'<version>${project.version}</version>', f'<version>{UPSTREAM}</version>'.encode())
        provenance = json.dumps({
            'version': VERSION, 'upstream_commits': LOCK['upstream_commits'],
            'patches': LOCK['patches']}, indent=2).encode() + b'\n'
        for suffix in ('.jar', '-sources.jar'):
            with zipfile.ZipFile(inputs[f'pdfbox-{UPSTREAM}{suffix}']) as jar:
                contents = {name: jar.read(name) for name in jar.namelist() if not name.endswith('/')}
            if suffix == '.jar':
                for path in classes.rglob('*.class'):
                    contents[path.relative_to(classes).as_posix()] = path.read_bytes()
                contents['META-INF/MANIFEST.MF'] = contents['META-INF/MANIFEST.MF'].replace(
                    f'Bundle-Version: {UPSTREAM}\r\n'.encode(), f'Bundle-Version: {UPSTREAM}.eapdf1\r\n'.encode())
            else:
                for name in SOURCES:
                    contents[name] = (source / name).read_bytes()
            if f'{MAVEN_METADATA}/pom.properties' in contents:
                contents[f'{MAVEN_METADATA}/pom.properties'] = (
                    f'groupId=org.apache.pdfbox\nartifactId=pdfbox\nversion={VERSION}\n'.encode())
            if f'{MAVEN_METADATA}/pom.xml' in contents:
                contents[f'{MAVEN_METADATA}/pom.xml'] = pom
            contents[MARKER] = provenance
            archive(destination / f'pdfbox-{VERSION}{suffix}', contents)
        (destination / f'pdfbox-{VERSION}.pom').write_bytes(pom)


def verify_shaded(path):
    jar_name = f'pdfbox-{VERSION}.jar'
    reference = next(ROOT / item['path'] for item in LOCK['artifacts'] if Path(item['path']).name == jar_name)
    class_names = tuple(name.removesuffix('.java') for name in SOURCES)
    entries = {name + '.class' for name in class_names}
    entries.update({MARKER, f'{MAVEN_METADATA}/pom.properties', f'{MAVEN_METADATA}/pom.xml'})
    with zipfile.ZipFile(reference) as expected, zipfile.ZipFile(path) as package:
        companion_prefixes = tuple(name + '$' for name in class_names)
        entries.update(name for name in expected.namelist()
                       if name.startswith(companion_prefixes) and name.endswith('.class'))
        counts = Counter(package.namelist())
        for entry in sorted(entries):
            if counts[entry] != 1 or package.read(entry) != expected.read(entry):
                raise SystemExit(f'{path}: {entry} does not match the locked PDFBox backport exactly once')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--rebuild', action='store_true')
    parser.add_argument('--verify-shaded', metavar='JAR', type=Path,
                        help='Check the shaded PDFBox classes and provenance against the locked backport')
    args = parser.parse_args()
    for item in LOCK['patches'] + LOCK['artifacts']:
        if digest((ROOT / item['path']).read_bytes()) != item['sha256']:
            raise SystemExit('Checksum mismatch: ' + item['path'])
    if args.rebuild:
        with tempfile.TemporaryDirectory(prefix='eapdf-pdfbox-verify-') as temporary:
            build(Path(temporary))
            for item in LOCK['artifacts']:
                if digest((Path(temporary) / Path(item['path']).name).read_bytes()) != item['sha256']:
                    raise SystemExit('Rebuild mismatch: ' + item['path'])
    if args.verify_shaded:
        verify_shaded(args.verify_shaded)
        print(f'PDFBox backport verified in {args.verify_shaded}.')
    print('PDFBox backport patch and artifacts verified.')


if __name__ == '__main__':
    main()
