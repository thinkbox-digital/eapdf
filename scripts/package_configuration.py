"""Build a deterministic public configuration bundle from an explicit allowlist.

Local credentials, token caches and signing keys are never collected. Inspect
the selected public configuration before publication; this does not redact it.
"""
import argparse
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PUBLIC_FILES = (
    'README.md', 'eapdf_config.json', 'credentials.example.json',
    'ea-pdf-linux.json', 'ea-pdf-fop.xconf',
    'verapdf/README.md', 'verapdf/ISO-32005.xml', 'verapdf/ISO-32005.json',
)


def package(destination, configuration=ROOT / 'config'):
    configuration = configuration.resolve()
    entries = []
    for name in PUBLIC_FILES:
        path = configuration / name
        if path.is_symlink() or not path.resolve().is_relative_to(configuration):
            raise ValueError(f'Public configuration must be a regular in-tree file: {name}')
        entries.append((name, path.read_bytes()))
    # Read all inputs before creating the output, so missing files leave no ZIP.
    with zipfile.ZipFile(destination, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in entries:
            entry = zipfile.ZipInfo('config/' + name, (2000, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, data, compresslevel=9)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    package(args.output)
