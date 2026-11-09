"""Read-only independent inventory for optional private Portfolio regressions."""
import hashlib
import json
import sys
import pikepdf

with pikepdf.Pdf.open(sys.argv[1]) as pdf:
    collection = pdf.Root.get('/Collection')
    if collection is None:
        raise ValueError('Sample has no Collection dictionary')
    folders, seen = [], set()
    pending = [(collection.get('/Folders'), None)]
    while pending:
        folder, parent = pending.pop()
        if folder is None:
            continue
        identity = folder.objgen
        if identity in seen:
            raise ValueError('Cyclic sample folder hierarchy')
        seen.add(identity)
        number = int(folder['/ID'])
        folders.append(dict(number=number, name=str(folder.get('/Name', '')), parent=parent))
        pending.extend([(folder.get('/Next'), parent), (folder.get('/Child'), number)])
    members = []
    for key, spec in pdf.attachments.items():
        stream = spec.get_file()
        data = stream.read_bytes()
        members.append(dict(key=key, name=spec.filename, bytes=len(data), sha256=hashlib.sha256(data).hexdigest()))
    split = collection.get('/Split')
    print(json.dumps(dict(members=members, folders=folders, initial=str(collection.get('/D', '')),
                         view=str(collection.get('/View', '/D'))[1:],
                         split=str(split.get('/Direction', ''))[1:] if split else None,
                         position=float(split['/Position']) if split and '/Position' in split else None)))
