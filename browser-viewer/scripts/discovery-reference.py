"""Independent MuPDF filespec inventory; includes files absent from the name tree."""
import hashlib, json, sys
from pathlib import Path
import fitz
report = json.loads(Path(sys.argv[1]).read_text())
results = []
for item in report['files']:
    path = Path(item['file'])
    doc = fitz.open(path)
    files = []
    unused_slots = []
    for ref in range(1, doc.xref_length()):
        try:
            kind = doc.xref_get_key(ref, 'Type')
        except fitz.mupdf.FzErrorFormat as error:
            if 'cannot find object in xref' not in str(error):
                raise
            unused_slots.append(ref)
            continue
        if kind != ('name', '/Filespec'):
            continue
        streams = set()
        for key in ['F', 'UF', 'DOS', 'Mac', 'Unix']:
            kind, value = doc.xref_get_key(ref, 'EF/' + key)
            if kind == 'xref':
                streams.add(int(value.split()[0]))
        kind, name = doc.xref_get_key(ref, 'UF')
        if kind != 'string':
            kind, name = doc.xref_get_key(ref, 'F')
        for stream in streams:
            data = doc.xref_stream(stream)
            files.append({'name': name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(), 'filespec': ref, 'stream': stream})
    results.append({'file': str(path.resolve()), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'files': files, 'unused_xref_slots': unused_slots})
    Path(sys.argv[2]).write_text(json.dumps(results, indent=2) + '\n')
    print(path.name, len(files), flush=True)
