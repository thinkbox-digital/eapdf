"""Collection filename controls from EA-PDF 9.5 and ISO 32000-2 pp454-455."""
import hashlib
import json
from pathlib import Path

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/fixtures/eapdf/validator-collection-names'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    seed = ROOT / 'tests/fixtures/eapdf/validator-booleans/collection-false.pdf'
    unique = ['COLLECTION_FILENAMES_UNIQUE_CASEFOLD']
    invalid = ['COLLECTION_FILENAMES_VALID']
    recipes = [
        ('folder-spaces', [], ['a  b', 'A B'], 0, False, []),
        ('folder-identical', [], ['same', 'same'], 0, False, unique),
        ('folder-case', [], ['one', 'ONE'], 0, False, unique),
        ('empty-file', [''], [], 0, False, invalid),
        ('long-file', ['a' + ' ' * 255], [], 0, False, invalid),
        ('long-folder', [], ['a' + ' ' * 255], 0, False, invalid),
        ('whitespace-names', [' '], [' '], 0, False, []),
        ('shared-case-aliases', ['A.pdf', 'a.pdf'], [], 0, True, unique),
        ('shared-distinct-aliases', ['a  b.pdf', 'A B.pdf'], [], 0, True, []),
        ('implicit-root-alias', ['A.pdf', '<7>a.pdf'], [], 7, False, unique),
        ('linefeed-name', ['<1>a\nb.pdf', '<1>A B.pdf'], [], 0, False, []),
        ('nonascii-prefix', ['<١>a.pdf'], [], 0, False, invalid),
        ('numeric-folder', [], [123], 0, False, invalid),
        ('different-folders', ['<1>a.pdf', '<2>A.pdf'], ['one', 'two'], 0, True, [])]
    cases = []
    for name, keys, folders, root_id, shared, failures in recipes:
        with pikepdf.Pdf.open(seed) as pdf:
            root = pdf.Root.Collection.Folders
            root.ID = root_id
            original_child = root.Child
            original = next(iter(pdf.attachments.values())).obj
            if folders:
                previous = None
                for index, folder_name in enumerate(folders, 2):
                    folder = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Folder,
                        ID=index, Name=folder_name, Parent=root))
                    if previous is None:
                        original_child.Next = folder
                    else:
                        previous.Next = folder
                    previous = folder
            if keys:
                entries = pikepdf.Array()
                for index, key in enumerate(sorted(keys, key=lambda k: bytes(pikepdf.String(k)))):
                    spec = original if shared or index == 0 else pdf.make_indirect(pikepdf.Dictionary(original))
                    entries.extend([key, spec])
                pdf.Root.Names.EmbeddedFiles = pikepdf.Dictionary(Names=entries)
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                          expected_status='FAIL' if failures else 'INCOMPLETE',
                          expected_pipeline_status='FAIL' if failures else 'PASS', expected_failures=failures,
                          file_keys=keys, added_folder_names=folders, root_folder_id=root_id,
                          shared_filespec=shared))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF 9.5 printed p68; ISO 32000-2:2020 Table 159 and following text printed pp454-455',
        seed=str(seed.relative_to(ROOT)), seed_sha256=hashlib.sha256(seed.read_bytes()).hexdigest(),
        boundary='Exact text and per-folder naming scope; no claim of full Collection folder graph validation; custom-only child PDF/A remains skipped',
        cases=cases), indent=2, ensure_ascii=False) + '\n')
    print('Built fourteen independent Collection naming controls.')


if __name__ == '__main__':
    main()
