"""Add isolated object-metadata cases and record independent decoded-stream hashes."""
import hashlib
import json
from pathlib import Path
import sys
import pikepdf

source, destination, expected_path = map(Path, sys.argv[1:])
with pikepdf.open(source) as pdf:
    pdf.pages[0].obj.Metadata = pdf.make_stream(
        b'<?xml version="1.0" encoding="UTF-8"?>\n'
        b'<x xmlns="urn:test">Page metadata &lt;script&gt;</x>\n')
    pdf.Root.MetadataProbe = pikepdf.Dictionary(Metadata=pdf.make_stream(
        b'\xff\xfe' + ('<extra>Original UTF16 bytes' + 'x' * 300000 + 'END</extra>').encode('utf-16-le')))
    pdf.save(destination)
with pikepdf.open(destination) as pdf:
    expected, seen = {}, set()
    def walk(obj):
        if not isinstance(obj, (pikepdf.Dictionary, pikepdf.Stream, pikepdf.Array)):
            return
        if obj.is_indirect:
            if obj.objgen in seen:
                return
            seen.add(obj.objgen)
        if isinstance(obj, pikepdf.Array):
            for value in obj:
                walk(value)
        else:
            if '/Metadata' in obj:
                stream = obj.Metadata
                expected[str(stream.objgen)] = hashlib.sha256(stream.read_bytes()).hexdigest()
            for _, value in obj.items():
                walk(value)
    walk(pdf.Root)
expected_path.write_text(json.dumps(expected, indent=2) + '\n')
