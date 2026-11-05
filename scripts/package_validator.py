#!/usr/bin/env python3
"""Build, verify and atomically install the self-contained Java 21 validator."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from datetime import datetime, timezone
from zipfile import ZipFile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]

def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def replace_file(source, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    handle, pending = tempfile.mkstemp(prefix='.' + destination.name + '-', dir=destination.parent)
    try:
        with os.fdopen(handle, 'wb') as output, source.open('rb') as input:
            shutil.copyfileobj(input, output)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(pending, 0o644)
        os.replace(pending, destination)
    finally:
        Path(pending).unlink(missing_ok=True)

def main():
    with tempfile.TemporaryDirectory(prefix='eapdf-validator-package-', dir='/tmp') as scratch:
        scratch = Path(scratch)
        subprocess.run(['mvn', '-B', '-f', str(ROOT/'validator-desktop/pom.xml'),
                        '-Deapdf.build.directory=' + str(scratch/'build'), 'verify'], cwd=ROOT, check=True)
        built = scratch/'build/eapdf-validator.jar'
        with ZipFile(built) as jar:
            if jar.testzip() is not None:
                raise RuntimeError('Validator ZIP CRC verification failed')
            manifest = jar.read('META-INF/MANIFEST.MF').decode('utf-8').replace('\r\n', '\n')
            if '\nMain-Class: eapdf.ValidatorGui\n' not in '\n' + manifest:
                raise RuntimeError('Validator executable manifest is missing or incorrect')
        destination = ROOT/'dist/eapdf-validator.jar'
        checksum = sha(built)
        replace_file(built, destination)
        if sha(destination) != checksum:
            raise RuntimeError('Published validator differs from the verified build')
        checksum_file = scratch/'checksum'
        checksum_file.write_text(checksum + '  eapdf-validator.jar\n')
        replace_file(checksum_file, destination.with_suffix('.jar.sha256'))
        paths = [ROOT/'validator-desktop/pom.xml', ROOT/'validator_rule_text.json',
                 ROOT/'config/verapdf/ISO-32005.xml', Path(__file__).resolve()]
        paths += sorted((ROOT/'validator-desktop/src/main').rglob('*'))
        ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
        version = ET.parse(ROOT/'validator-desktop/pom.xml').findtext('m:version', namespaces=ns)
        data = {'date': datetime.now(timezone.utc).isoformat(), 'version': version,
                'scope': 'Verified executable packaging; large-file acceptance is documented separately.',
                'main_class': 'eapdf.ValidatorGui', 'java_release': 21,
                'jars': {destination.name: checksum},
                'source_hashes': {str(path.relative_to(ROOT)): sha(path) for path in paths if path.is_file()}}
        metadata = scratch/'validator-build.json'
        metadata.write_text(json.dumps(data, indent=2) + '\n')
        replace_file(metadata, ROOT/'dist/validator-build.json')
        print('Verified executable validator: ' + str(destination))
        print('SHA-256: ' + checksum)

if __name__ == '__main__':
    main()
