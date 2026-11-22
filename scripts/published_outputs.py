"""Read complete creator batches and enumerate their flat compatibility outputs."""

import json
import os
import re
import stat
from pathlib import Path
from uuid import UUID


PUBLICATION_DIRECTORY = '.eapdf-publication'


def walk_output_files(directory):
    """Visit ordinary output files without descending into retained generations."""
    for folder, directories, names in os.walk(directory):
        if PUBLICATION_DIRECTORY in directories:
            directories.remove(PUBLICATION_DIRECTORY)
        for name in names:
            if name != PUBLICATION_DIRECTORY:
                yield Path(folder) / name


def latest_batch_pdfs(directory):
    """Resolve a fresh job's pointer once and return its PDF paths and expected hashes."""
    publication = directory / PUBLICATION_DIRECTORY
    pointer = publication / 'current.json'
    try:
        if not stat.S_ISDIR(publication.lstat().st_mode):
            raise ValueError(f'Invalid publication directory {publication}')
        if not stat.S_ISREG(pointer.lstat().st_mode):
            raise ValueError(f'Invalid batch manifest {pointer}')
    except FileNotFoundError:
        # Older creators and one-file jobs publish ordinary paths only.
        return {path: None for path in sorted(walk_output_files(directory))
                if path.is_file() and path.suffix.lower() == '.pdf'}

    manifest = json.loads(pointer.read_bytes())
    if not isinstance(manifest, dict) or type(manifest.get('schemaVersion')) is not int or manifest['schemaVersion'] != 1:
        raise ValueError(f'Unsupported batch manifest {pointer}')
    generation = manifest.get('generation')
    if not isinstance(generation, str) or str(UUID(generation)) != generation:
        raise ValueError(f'Invalid generation in {pointer}')
    directory = publication / generation
    if not stat.S_ISDIR(directory.lstat().st_mode):
        raise ValueError(f'Invalid generation directory {directory}')
    members = manifest.get('files')
    if not isinstance(members, list) or not members:
        raise ValueError(f'No members in {pointer}')

    outputs = {}
    for member in members:
        if not isinstance(member, dict) or not isinstance(member.get('path'), str):
            raise ValueError(f'Invalid member in {pointer}')
        relative = member['path']
        slot, _, name = relative.partition('/')
        if (not re.fullmatch(r'[0-9]{6,}', slot) or name in ('', '.', '..')
                or any(char in name for char in ('/', '\\', ':', '\x00'))):
            raise ValueError(f'Invalid member path in {pointer}')
        size, digest = member.get('size'), member.get('sha256')
        if type(size) is not int or size < 0 or not isinstance(digest, str) or not re.fullmatch(r'[0-9a-f]{64}', digest):
            raise ValueError(f'Invalid member size or hash in {pointer}')
        folder = directory / slot
        if not stat.S_ISDIR(folder.lstat().st_mode):
            raise ValueError(f'Invalid member directory {folder}')
        path = folder / name
        details = path.lstat()
        if path in outputs or not stat.S_ISREG(details.st_mode) or details.st_size != size:
            raise ValueError(f'Duplicate or invalid batch member {path}')
        outputs[path] = digest

    return {path: outputs[path] for path in sorted(outputs, key=lambda path: path.name)
            if path.suffix.lower() == '.pdf'}
