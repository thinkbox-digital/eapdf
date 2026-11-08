"""M1 development oracle using qpdf/pikepdf, never imported or shipped by the viewer.

Inspects trusted, hash-pinned fixtures. This is an identity/asset projection, not
an EA-PDF validator or a production parser for hostile PDFs. See inspection-schema.md.
"""
import argparse
import hashlib
import io
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

import pikepdf

RDF = 'http://www.w3.org/1999/02/22-rdf-syntax-ns#'
META = 'http://www.pdfa.org/eapdf/ns/meta/'
IDENT = 'http://www.pdfa.org/eapdf/ns/id/'
PDFA = 'http://www.aiim.org/pdfa/ns/id/'
LIMITS = dict(maxFileBytes=32 * 1024**2, maxStreamBytes=16 * 1024**2,
              maxDecodedBytes=64 * 1024**2, maxMetadataBytes=2 * 1024**2,
              maxObjects=100000, maxParts=10000, maxTreeDepth=64,
              maxChildDepth=4, maxChildren=32)
FIELDS = {'Mail_GUID', 'messageid', 'Message-ID', 'Filename', 'SizeInBytes',
          'ContentType', 'Content-Type', 'CheckSum', 'Checksum', 'NumberMessages', 'NumberAttachments'}


class ResourceLimitError(ValueError):
    pass


class UnsupportedInput(ValueError):
    pass


def digest(data):
    return hashlib.sha256(data).hexdigest()


def error_text(error):
    # qpdf includes Python's process-specific BytesIO repr in parse errors.
    return re.sub(r'<_io.BytesIO object at 0x[0-9a-fA-F]+>', '<in-memory PDF>', str(error))


def observation(value=None, *, state=None, basis='observed', raw=None, evidence=None, reason=None, candidates=None):
    state = state or ('present' if value is not None else 'missing')
    return dict(state=state, value=value if state == 'present' else None,
                basis=basis if state == 'present' else 'none', raw=raw or [], evidence=evidence or [],
                reason=reason if state == 'present' else (reason or 'No value was observed.'), candidates=candidates or [])


def value(item):
    return item['value'] if item['state'] == 'present' else None


def raw_pdf(item):
    kind, text, data = 'pdf-null', None, None
    if isinstance(item, pikepdf.String):
        kind, data = 'pdf-string', bytes(item).hex()
        try:
            text = str(item)
        except (UnicodeError, ValueError):
            pass
    elif isinstance(item, pikepdf.Name):
        kind, data = 'pdf-name', bytes(item).hex()
        try:
            text = bytes(item).decode('utf-8')
        except UnicodeError:
            pass
    elif isinstance(item, bool):
        kind, text = 'pdf-boolean', str(item).lower()
    elif isinstance(item, int):
        kind, text = 'pdf-integer', str(item)
    elif isinstance(item, pikepdf.Stream):
        kind = 'pdf-stream'
    elif isinstance(item, pikepdf.Dictionary):
        kind = 'pdf-dictionary'
    elif isinstance(item, pikepdf.Array):
        kind = 'pdf-array'
    elif item is not None:
        kind, text = 'pdf-real', str(item)
    return dict(kind=kind, text=text, bytesHex=data)


class Inspector:
    def __init__(self, data, limits=None, budget=None, ancestors=(), depth=0):
        self.data = data
        self.limits = dict(LIMITS, **(limits or {}))
        self.budget = budget if budget is not None else {'decoded': 0, 'children': 0}
        self.docid = 'sha256:' + digest(data)
        self.ancestors, self.depth = ancestors, depth
        self.diagnostics = []
        self.stream_data = {}

    def locator(self, item, path):
        objnum, generation = getattr(item, 'objgen', (0, 0))
        return dict(documentId=self.docid, objectNumber=objnum or None,
                    generation=generation if objnum else None,
                    path=f'object:{objnum}:{generation}' if objnum else path)

    def fieldloc(self, owner, key):
        return dict(owner, path=owner['path'] + '/' + key)

    def identity(self, loc):
        return loc['documentId'] + '#' + loc['path']

    def diagnostic(self, code, state, message, *locs):
        self.diagnostics.append(dict(code=code, state=state, message=message, evidence=list(locs)))

    def field(self, item, loc, kind='text'):
        if item is None:
            return observation(evidence=[loc])
        raw = [raw_pdf(item)]
        accepted = ((kind == 'text' and isinstance(item, pikepdf.String)) or
                    (kind == 'name' and isinstance(item, pikepdf.Name)) or
                    (kind == 'number' and isinstance(item, int) and not isinstance(item, bool) and item >= 0))
        if kind == 'number' and accepted and item > 9007199254740991:
            return observation(state='unsupported', raw=raw, evidence=[loc], reason='Integer exceeds the JSON/JavaScript safe integer range.')
        if not accepted or (kind != 'number' and raw[0]['text'] is None):
            self.diagnostic('INVALID_VALUE_TYPE', 'invalid', f'Expected a readable PDF {kind} value.', loc)
            return observation(state='invalid', raw=raw, evidence=[loc], reason=f'Expected PDF {kind}; actual type retained.')
        normalized = item if kind == 'number' else raw[0]['text'][1:] if kind == 'name' else raw[0]['text']
        return observation(normalized, raw=raw, evidence=[loc])

    def links(self, ids, loc, reason, unique=False):
        ids = sorted(set(ids))
        if unique and len(ids) > 1:
            return observation(state='ambiguous', candidates=[[i] for i in ids], evidence=[loc], reason=reason)
        if not ids:
            return observation(state='unavailable', evidence=[loc], reason=reason)
        return observation(ids, basis='derived', evidence=[loc], reason=reason)

    def stream_bytes(self, stream, maximum):
        key = getattr(stream, 'objgen', (0, 0))
        if key in self.stream_data:
            data = self.stream_data[key]
        else:
            # qpdf allocates the decoded buffer first. This trusted-fixture oracle
            # does not claim a production decompression-memory sandbox.
            data = stream.read_bytes()
            if len(data) > maximum or self.budget['decoded'] + len(data) > self.limits['maxDecodedBytes']:
                raise ResourceLimitError('Decoded stream byte budget exceeded')
            self.budget['decoded'] += len(data)
            self.stream_data[key] = data
        if len(data) > maximum:
            raise ResourceLimitError('Stream exceeds this operation byte budget')
        return data

    def xmp_scalar(self, nodes, loc):
        raws, vals = [], []
        for node in nodes:
            if isinstance(node, str):
                raws.append(dict(kind='xmp-attribute', text=node, bytesHex=None))
                vals.append(node)
            elif len(node) or node.get(f'{{{RDF}}}resource') is not None:
                raws.append(dict(kind='xmp-structure', text=None, bytesHex=None))
            else:
                vals.append(node.text or '')
                raws.append(dict(kind='xmp-text', text=node.text or '', bytesHex=None))
        if len(vals) != len(nodes):
            self.diagnostic('XMP_VALUE_NOT_TEXT', 'invalid', 'A scalar property contains a URI or structured value.', loc)
            return observation(state='invalid', raw=raws, evidence=[loc], reason='Simple XMP text required; no URI coercion.')
        if len(set(vals)) > 1:
            self.diagnostic('XMP_VALUE_AMBIGUOUS', 'ambiguous', 'Conflicting scalar declarations were retained.', loc)
            return observation(state='ambiguous', candidates=sorted(set(vals)), raw=raws, evidence=[loc], reason='Conflicting XMP values.')
        return observation(vals[0] if vals else None, raw=raws, evidence=[loc])

    def metadata(self, pdf):
        stream = pdf.Root.get('/Metadata')
        loc = self.locator(stream, 'catalog/Metadata')
        records, descriptions = [], []
        state = 'missing'
        if stream is not None:
            try:
                data = self.stream_bytes(stream, self.limits['maxMetadataBytes'])
                if b'<!DOCTYPE' in data.upper() or b'<!ENTITY' in data.upper():
                    raise UnsupportedInput('DTD/entity declarations are not supported')
                root = ET.fromstring(data)
                descriptions = root.findall(f'.//{{{RDF}}}RDF/{{{RDF}}}Description')
                if root.tag == f'{{{RDF}}}RDF':
                    descriptions = root.findall(f'{{{RDF}}}Description')
                if not descriptions:
                    raise ValueError('No RDF description found')
                state = 'present'
            except (ValueError, ET.ParseError, pikepdf.PdfError, AttributeError) as error:
                state = 'unavailable' if isinstance(error, ResourceLimitError) else 'unsupported' if isinstance(error, UnsupportedInput) else 'invalid'
                self.diagnostic('METADATA_UNREADABLE', state, str(error), loc)
        def declaration(ns, name):
            field = self.fieldloc(loc, f'{{{ns}}}{name}')
            if state in ('invalid', 'unavailable', 'unsupported') and not descriptions:
                return observation(state='unavailable', evidence=[field], reason='Catalog metadata could not be inspected.')
            values = []
            for desc in descriptions:
                values.extend(desc.findall(f'{{{ns}}}{name}'))
                if f'{{{ns}}}{name}' in desc.attrib:
                    values.append(desc.attrib[f'{{{ns}}}{name}'])
            return self.xmp_scalar(values, field)
        for container, kind in [('email', 'email'), ('assets', 'source-asset'), ('attachments', 'attachment')]:
            ordinal = 0
            for desc in descriptions:
                for prop in desc.findall(f'{{{META}}}{container}'):
                    bags = prop.findall(f'{{{RDF}}}Bag')
                    if len(bags) != 1:
                        self.diagnostic('XMP_RECORD_STRUCTURE', 'unsupported', 'Expected an RDF Bag record container.', loc)
                        state = 'unsupported'
                        continue
                    for li in bags[0]:
                        recordloc = self.fieldloc(loc, f'{container}[{ordinal}]')
                        ordinal += 1
                        record = li.find(f'{{{RDF}}}Description')
                        if record is None:
                            record = li
                        groups = {}
                        for name, val in record.attrib.items():
                            if name.startswith('{' + META + '}'):
                                groups.setdefault(name.split('}', 1)[1], []).append(val)
                        for node in record:
                            if node.tag.startswith('{' + META + '}'):
                                groups.setdefault(node.tag.split('}', 1)[1], []).append(node)
                        records.append(dict(id=self.identity(recordloc), kind=kind, locator=recordloc,
                            fields={k: self.xmp_scalar(v, self.fieldloc(recordloc, k)) for k, v in sorted(groups.items()) if k in FIELDS},
                            uninspectedFields=sorted(k for k in groups if k not in FIELDS),
                            owners=observation(state='not-evaluated', reason='Ownership has not been examined.'),
                            files=observation(state='not-evaluated', reason='Physical file matching has not been examined.')))
        return records, declaration, state

    def record_field(self, record, *names):
        found = [record['fields'][n] for n in names if n in record['fields']]
        if not found:
            return observation(evidence=[record['locator']])
        if len(found) == 1:
            return found[0]
        vals = [value(f) for f in found]
        if all(v is not None for v in vals) and len(set(vals)) == 1:
            return observation(vals[0], raw=[r for f in found for r in f['raw']], evidence=[record['locator']])
        return observation(state='ambiguous', raw=[r for f in found for r in f['raw']], evidence=[record['locator']],
                           candidates=sorted(set(v for v in vals if v is not None)), reason='Identity aliases disagree or cannot be read.')

    def owners(self, guid, mid, messages, loc, allow_mid=False):
        if value(guid):
            matches = [m['id'] for m in messages if value(m['mailGuid']) == value(guid)]
        elif guid['state'] != 'missing':
            return observation(state='unavailable', evidence=[loc], reason='Unusable GUID blocks identity fallback.')
        elif allow_mid and value(mid):
            matches = [m['id'] for m in messages if value(m['internetMessageId']) == value(mid)]
        else:
            return observation(state='unavailable', evidence=[loc], reason='No usable explicit email identity; no sole-message guess.')
        result = self.links(matches, loc, 'Exact, case-sensitive declared identity matching; GUID takes precedence.', unique=True)
        if result['state'] == 'ambiguous':
            self.diagnostic('AMBIGUOUS_MESSAGE_IDENTITY', 'ambiguous', 'More than one email record matches this identity.', loc)
        return result

    def parts(self, pdf, messages):
        root = pdf.Root.get('/DPartRoot')
        if root is None:
            return [], 'missing'
        page_map = {page.obj.objgen: index for index, page in enumerate(pdf.pages)}
        found, seen, state = [], set(), 'present'
        def flatten(items, depth=0):
            if depth > self.limits['maxTreeDepth']:
                raise ValueError('DParts array nesting limit')
            if isinstance(items, pikepdf.Array):
                for item in items:
                    yield from flatten(item, depth + 1)
            elif items is not None:
                yield items
        def walk(node, parent, inherited, path, depth):
            nonlocal state
            loc = self.locator(node, path)
            identity = self.identity(loc)
            if identity in seen or depth > self.limits['maxTreeDepth'] or len(found) >= self.limits['maxParts']:
                state = 'invalid' if identity in seen else 'unavailable'
                self.diagnostic('DPART_TRAVERSAL_STOPPED', state, 'Repeated part or traversal budget reached.', loc)
                return
            if not isinstance(node, pikepdf.Dictionary):
                state = 'invalid'
                self.diagnostic('DPART_INVALID_NODE', 'invalid', 'DPart node is not a dictionary.', loc)
                return
            seen.add(identity)
            declared = node.get('/Parent')
            parentid = self.identity(self.locator(declared, path + '/Parent')) if declared is not None else None
            expected = parent['id'] if parent else self.identity(self.locator(root, 'catalog/DPartRoot'))
            if parentid != expected:
                state = 'invalid'
                self.diagnostic('DPART_PARENT_MISMATCH', 'invalid', 'Declared parent differs from the traversed tree.', loc)
                inherited = observation(state='unavailable', reason='Ancestry is inconsistent.', evidence=[loc])
            dpm = node.get('/DPM', {})
            fields = {}
            if not isinstance(dpm, (pikepdf.Dictionary, dict)):
                self.diagnostic('DPM_UNSUPPORTED_VALUE', 'unsupported', 'DPM is not a dictionary.', loc)
                state = 'unsupported'
            else:
                for key, item in sorted(dpm.items()):
                    if isinstance(item, (pikepdf.Name, pikepdf.String)):
                        fields[key[1:]] = self.field(item, self.fieldloc(loc, 'DPM' + key), 'name' if isinstance(item, pikepdf.Name) else 'text')
                    else:
                        fields[key[1:]] = observation(state='unsupported', raw=[raw_pdf(item)], evidence=[loc], reason='This reference projection retains only scalar text/name DPM values.')
            guid = fields.get('Mail_GUID', observation())
            mid = fields.get('Mail_MessageID', observation())
            # Names are not valid identity strings, even when their displayed text matches.
            for name, obs in [('Mail_GUID', guid), ('Mail_MessageID', mid)]:
                if obs['state'] == 'present' and obs['raw'][0]['kind'] != 'pdf-string':
                    fields[name] = observation(state='invalid', raw=obs['raw'], evidence=obs['evidence'], reason='Identity must be a PDF string.')
                    self.diagnostic('INVALID_MESSAGE_IDENTITY', 'invalid', 'DPM email identity is not a PDF string.', loc)
            guid, mid = fields.get('Mail_GUID', observation()), fields.get('Mail_MessageID', observation())
            owners = self.owners(guid, mid, messages, loc) if guid['state'] != 'missing' or mid['state'] != 'missing' else inherited
            refs = []
            for key in ['/Start', '/End']:
                target = node.get(key)
                refs.append(observation([self.identity(self.locator(target, path + key))] if target is not None else None,
                                        evidence=[self.fieldloc(loc, key[1:])]))
            start, end = node.get('/Start'), node.get('/End', node.get('/Start'))
            rng = observation(reason='No explicit page range on this node.')
            if start is not None or end is not None:
                a, b = page_map.get(getattr(start, 'objgen', None)), page_map.get(getattr(end, 'objgen', None))
                if a is None or b is None or a > b:
                    rng = observation(state='invalid', evidence=[loc], reason='Range is reversed or does not refer to pages in this document.')
                    state = 'invalid'
                    self.diagnostic('INVALID_PAGE_RANGE', 'invalid', rng['reason'], loc)
                else:
                    rng = observation(dict(indexBase=0, endInclusive=True, start=a, end=b), basis='derived', evidence=[loc],
                                      reason='Page references resolved; absent End defaults to Start.')
            part = dict(id=identity, locator=loc, parentId=parent['id'] if parent else None,
                        declaredParent=observation([parentid] if parentid else None, evidence=[loc]), childIds=[], dpm=fields,
                        startReference=refs[0], endReference=refs[1], pageRange=rng, messageIds=owners)
            found.append(part)
            if parent:
                parent['childIds'].append(identity)
            for index, child in enumerate(flatten(node.get('/DParts'))):
                walk(child, part, owners, path + f'/DParts[{index}]', depth + 1)
        try:
            walk(root.get('/DPartRootNode'), None, observation(reason='Document-wide part has no email owner.'), 'catalog/DPartRootNode', 0)
        except (ValueError, AttributeError, pikepdf.PdfError) as error:
            state = 'unavailable'
            self.diagnostic('DPART_TRAVERSAL_STOPPED', state, str(error), self.locator(root, 'catalog/DPartRoot'))
        return found, state

    def files(self, pdf):
        found, state = {}, 'present'
        def add(spec, kind, owner, page=None, key=None):
            nonlocal state
            loc = self.locator(spec, owner['path'] + '/Filespec')
            if not isinstance(spec, pikepdf.Dictionary):
                state = 'invalid'
                self.diagnostic('INVALID_FILESPEC', 'invalid', 'Association does not reference a file specification dictionary.', owner)
                return
            ident = self.identity(loc)
            if ident not in found:
                streams = {}
                ef = spec.get('/EF')
                if isinstance(ef, pikepdf.Dictionary):
                    for efkey, stream in sorted(ef.items()):
                        streamloc = self.locator(stream, loc['path'] + '/EF' + efkey)
                        sid = self.identity(streamloc)
                        if sid in streams:
                            streams[sid]['efKeys'].append(efkey)
                            continue
                        params = stream.get('/Params', {}) if isinstance(stream, pikepdf.Stream) else {}
                        checksum = params.get('/CheckSum') if isinstance(params, (dict, pikepdf.Dictionary)) else None
                        declared = observation(evidence=[streamloc])
                        if checksum is not None:
                            valid = isinstance(checksum, pikepdf.String) and len(bytes(checksum)) == 16
                            declared = observation(bytes(checksum).hex() if valid else None, state='present' if valid else 'invalid',
                                raw=[raw_pdf(checksum)], evidence=[streamloc], reason=None if valid else 'PDF CheckSum must contain 16 bytes.')
                        streamrow = dict(locator=streamloc, efKeys=[efkey],
                            mimeType=self.field(stream.get('/Subtype'), streamloc, 'name') if isinstance(stream, pikepdf.Stream) else observation(),
                            mediaTypeParameters=self.field(stream.get('/Mail_MediaTypeParameters'), streamloc) if isinstance(stream, pikepdf.Stream) else observation(),
                            declaredSize=self.field(params.get('/Size'), streamloc, 'number') if isinstance(params, (dict, pikepdf.Dictionary)) else observation(state='invalid'),
                            declaredMd5=declared, decodedSize=observation(), computedMd5=observation(), sha256=observation(), extraction='unavailable')
                        try:
                            data = self.stream_bytes(stream, self.limits['maxStreamBytes'])
                            md5 = hashlib.md5(data, usedforsecurity=False).hexdigest()
                            for field, v in [('decodedSize', len(data)), ('computedMd5', md5), ('sha256', digest(data))]:
                                streamrow[field] = observation(v, basis='derived', evidence=[streamloc], reason='Computed from decoded embedded bytes.')
                            streamrow['extraction'] = 'present'
                            if value(declared) and value(declared) != md5:
                                self.diagnostic('CHECKSUM_MISMATCH', 'invalid', 'Declared MD5 differs from decoded bytes; both retained.', streamloc)
                        except (ValueError, AttributeError, pikepdf.PdfError) as error:
                            streamrow['extraction'] = 'unavailable'
                            for field in ['decodedSize', 'computedMd5', 'sha256']:
                                streamrow[field] = observation(state='unavailable', evidence=[streamloc], reason='Embedded byte inspection failed or exceeded a limit.')
                            self.diagnostic('STREAM_UNAVAILABLE', 'unavailable', str(error), streamloc)
                        streams[sid] = streamrow
                found[ident] = dict(id=ident, locator=loc, filename=self.field(spec.get('/F'), self.fieldloc(loc, 'F')),
                    unicodeFilename=self.field(spec.get('/UF'), self.fieldloc(loc, 'UF')),
                    afRelationship=self.field(spec.get('/AFRelationship'), self.fieldloc(loc, 'AFRelationship'), 'name'),
                    origins=[], embedding=('missing' if ef is None else 'present'
                        if isinstance(ef, pikepdf.Dictionary) and streams and all(isinstance(s, pikepdf.Stream) for _, s in ef.items()) else 'invalid'),
                    streams=list(streams.values()), roles=observation(state='unavailable'),
                    owners=observation(state='unavailable', reason='No proven email ownership.'), metadataRecordIds=[])
            origin = dict(kind=kind, locator=owner, pageIndex=page, nameTreeKey=self.field(key, owner) if key is not None else observation())
            if origin not in found[ident]['origins']:
                found[ident]['origins'].append(origin)
        names = pdf.Root.get('/Names', {})
        tree = names.get('/EmbeddedFiles') if isinstance(names, (dict, pikepdf.Dictionary)) else None
        seen = set()
        def name_tree(node, path, depth):
            loc = self.locator(node, path)
            ident = self.identity(loc)
            if ident in seen or depth > self.limits['maxTreeDepth']:
                raise ValueError('Repeated or too-deep EmbeddedFiles name tree')
            seen.add(ident)
            entries = node.get('/Names', [])
            if len(entries) % 2:
                raise ValueError('Odd number of EmbeddedFiles name-tree entries')
            for i in range(0, len(entries), 2):
                add(entries[i + 1], 'name-tree', self.fieldloc(loc, f'Names[{i}]'), key=entries[i])
            for i, child in enumerate(node.get('/Kids', [])):
                name_tree(child, path + f'/Kids[{i}]', depth + 1)
        try:
            if tree is not None:
                name_tree(tree, 'catalog/Names/EmbeddedFiles', 0)
            for index, page in enumerate(pdf.pages):
                for ai, annot in enumerate(page.obj.get('/Annots', [])):
                    if annot.get('/Subtype') == pikepdf.Name.FileAttachment:
                        add(annot.get('/FS'), 'annotation', self.locator(annot, f'page[{index}]/Annots[{ai}]'), page=index)
            pending, visited, count = [(pdf.Root, 'catalog', 0)], set(), 0
            while pending:
                obj, path, depth = pending.pop()
                if not isinstance(obj, (pikepdf.Dictionary, pikepdf.Stream, pikepdf.Array)):
                    continue
                loc = self.locator(obj, path)
                ident = self.identity(loc)
                if ident in visited:
                    continue
                visited.add(ident)
                count += 1
                if count > self.limits['maxObjects'] or depth > self.limits['maxTreeDepth']:
                    raise ValueError('Reachable object traversal budget exceeded')
                if isinstance(obj, pikepdf.Array):
                    pending.extend((v, path + f'[{i}]', depth + 1) for i, v in reversed(list(enumerate(obj))))
                else:
                    af = obj.get('/AF')
                    if isinstance(af, pikepdf.Array):
                        for i, spec in enumerate(af):
                            add(spec, 'catalog-af' if obj.objgen == pdf.Root.objgen else 'object-af', self.fieldloc(loc, f'AF[{i}]'))
                    elif af is not None:
                        self.diagnostic('INVALID_AF_ARRAY', 'invalid', 'AF value is not an array.', loc)
                        state = 'invalid'
                    pending.extend((v, loc['path'] + k, depth + 1) for k, v in sorted(obj.items(), reverse=True))
        except (ValueError, AttributeError, TypeError, pikepdf.PdfError) as error:
            state = 'unavailable'
            self.diagnostic('FILE_DISCOVERY_INCOMPLETE', 'unavailable', str(error), self.locator(pdf.Root, 'catalog'))
        return sorted(found.values(), key=lambda f: f['id']), state

    def bind_files(self, files, records, messages, parts):
        for record in records:
            if record['kind'] == 'email':
                record['owners'] = observation([record['id']], basis='derived', evidence=[record['locator']])
                record['files'] = observation(state='not-evaluated', reason='No original-source parser or sole-message association is used.')
                continue
            record['owners'] = (self.owners(self.record_field(record, 'Mail_GUID'), self.record_field(record, 'Message-ID'),
                                 messages, record['locator'], allow_mid=True) if record['kind'] == 'attachment' else
                                observation(state='unavailable', reason='Table 16 has no mandatory message identity link.', evidence=[record['locator']]))
            checksum = self.record_field(record, 'CheckSum', 'Checksum')
            size = value(self.record_field(record, 'SizeInBytes'))
            filename = value(self.record_field(record, 'Filename'))
            matches = []
            for file in files:
                for stream in file['streams']:
                    if size is None or not re.fullmatch(r'\+?\d+', size) or int(size) != value(stream['decodedSize']):
                        continue
                    if value(checksum) is not None:
                        match = re.fullmatch(r'[0-9a-fA-F]{32}', value(checksum)) and value(checksum).lower() == value(stream['computedMd5'])
                    else:
                        # We retain possible matches, but filename/size without a
                        # checksum is never promoted to a confirmed file binding.
                        match = filename in [value(file['filename']), value(file['unicodeFilename'])]
                    if match:
                        matches.append(file['id'])
                        if record['id'] not in file['metadataRecordIds']:
                            file['metadataRecordIds'].append(record['id'])
                        break
            binding = self.links(matches, record['locator'], 'Decoded MD5 and byte length match; duplicate physical files remain ambiguous.', unique=True)
            if matches and value(checksum) is None:
                binding = observation(state='unavailable', candidates=[[i] for i in sorted(set(matches))], evidence=[record['locator']],
                                      reason='Filename and byte length are candidates only; no declared checksum binds this record to a stream.')
            record['files'] = binding
            if binding['state'] == 'ambiguous':
                self.diagnostic('AMBIGUOUS_FILE_BINDING', 'ambiguous', 'Multiple physical file specifications match one metadata record.', record['locator'])
        by_record = {r['id']: r for r in records}
        for file in files:
            roles = sorted({by_record[r]['kind'] for r in file['metadataRecordIds'] if value(by_record[r]['files']) == [file['id']]})
            file['roles'] = self.links(roles, file['locator'], 'Derived only from uniquely byte-matched metadata records.')
            owners, unresolved = set(), False
            explicit_parts = [p for o in file['origins'] if o['kind'] == 'object-af' for p in parts
                if p['locator']['objectNumber'] is not None and
                (p['locator']['objectNumber'], p['locator']['generation']) == (o['locator']['objectNumber'], o['locator']['generation'])]
            if explicit_parts:
                ids = {i for p in explicit_parts for i in (value(p['messageIds']) or [])}
                file['owners'] = self.links(ids, file['locator'], 'Explicit DPart associated file.') if all(value(p['messageIds']) for p in explicit_parts) else observation(state='unavailable')
                continue
            contexts = [o for o in file['origins'] if o['kind'] == 'annotation']
            for origin in contexts:
                candidates = set()
                valid = True
                for part in parts:
                    rng = value(part['pageRange'])
                    if part['pageRange']['state'] == 'invalid':
                        valid = False
                    if rng and rng['start'] <= origin['pageIndex'] <= rng['end']:
                        ids = value(part['messageIds'])
                        if ids is None:
                            valid = False
                        else:
                            candidates.update(ids)
                if not valid or len(candidates) != 1:
                    unresolved = True
                owners.update(candidates)
            if contexts:
                file['owners'] = (observation(state='ambiguous' if len(owners) > 1 else 'unavailable', candidates=[[i] for i in sorted(owners)],
                    evidence=[o['locator'] for o in contexts], reason='Not every annotation page has a unique inspected DPart owner.') if unresolved else
                    self.links(owners, file['locator'], 'Every annotation context resolves to one email; shared files can have several explicit owners.'))
            else:
                for rid in file['metadataRecordIds']:
                    record = by_record[rid]
                    if value(record['files']) == [file['id']] and value(record['owners']):
                        owners.update(value(record['owners']))
                if owners:
                    file['owners'] = self.links(owners, file['locator'], 'Unique byte binding to metadata with an explicit email identity.')

    def collection(self, pdf, files):
        obj = pdf.Root.get('/Collection')
        if obj is None:
            return observation(reason='Catalog has no Collection entry.'), 'missing'
        loc = self.locator(obj, 'catalog/Collection')
        if not isinstance(obj, pikepdf.Dictionary):
            self.diagnostic('INVALID_COLLECTION', 'invalid', 'Catalog Collection is not a dictionary.', loc)
            return observation(state='invalid', evidence=[loc]), 'invalid'
        folders, seen, state = [], set(), 'present'
        def walk(node, parent, path, depth):
            nonlocal state
            while node is not None:
                floc = self.locator(node, path)
                ident = self.identity(floc)
                if ident in seen or depth > self.limits['maxTreeDepth'] or len(seen) >= self.limits['maxObjects']:
                    state = 'invalid' if ident in seen else 'unavailable'
                    self.diagnostic('COLLECTION_FOLDER_TRAVERSAL_STOPPED', state, 'Repeated folder or traversal limit reached.', floc)
                    return
                seen.add(ident)
                row = dict(id=ident, locator=floc, folderNumber=self.field(node.get('/ID'), floc, 'number'),
                           name=self.field(node.get('/Name'), floc), parentId=parent['id'] if parent else None, childIds=[])
                folders.append(row)
                if parent:
                    parent['childIds'].append(ident)
                walk(node.get('/Child'), row, path + '/Child', depth + 1)
                node, path = node.get('/Next'), path + '/Next'
        try:
            walk(obj.get('/Folders'), None, loc['path'] + '/Folders', 0)
        except (ValueError, AttributeError, pikepdf.PdfError) as error:
            state = 'invalid'
            self.diagnostic('INVALID_COLLECTION_FOLDER', state, str(error), loc)
        entries, children = [], []
        for file in files:
            origins = [o for o in file['origins'] if o['kind'] == 'name-tree']
            if not origins:
                continue
            for origin in origins:
                key = value(origin['nameTreeKey'])
                match = re.match(r'^<(\d+)>', key or '')
                foldernum = int(match[1]) if match else value(folders[0]['folderNumber']) if folders else None
                ids = [f['id'] for f in folders if value(f['folderNumber']) == foldernum]
                binding = self.links(ids, origin['locator'], 'Folder number encoded in the name-tree key; otherwise the collection root.', unique=True)
                if not folders and not match:
                    binding = observation([], basis='derived', reason='Implicit root; no Folders dictionary.')
                entries.append(dict(fileId=file['id'], nameTreeKey=origin['nameTreeKey'], folderIds=binding))
            for stream in file['streams']:
                if value(stream['mimeType']) != 'application/pdf':
                    continue
                child = dict(fileId=file['id'], stream=stream['locator'], documentId=None, state='unavailable', reason=None, inspection=None)
                try:
                    if self.depth >= self.limits['maxChildDepth'] or self.budget['children'] >= self.limits['maxChildren']:
                        raise ValueError('Embedded child budget reached')
                    sloc = stream['locator']
                    data = self.stream_data.get((sloc['objectNumber'], sloc['generation']))
                    if data is None:
                        raise ValueError('Embedded PDF bytes unavailable')
                    child['documentId'] = 'sha256:' + digest(data)
                    if child['documentId'] in (*self.ancestors, self.docid):
                        raise ValueError('Embedded document ancestor cycle')
                    self.budget['children'] += 1
                    result = Inspector(data, self.limits, self.budget, (*self.ancestors, self.docid), self.depth + 1).inspect()
                    child.update(state='present', inspection=result)
                except (ValueError, pikepdf.PdfError) as error:
                    child['state'] = 'invalid' if isinstance(error, pikepdf.PdfError) else 'unavailable'
                    child['reason'] = error_text(error)
                    self.diagnostic('COLLECTION_CHILD_UNAVAILABLE', child['state'], error_text(error), stream['locator'])
                children.append(child)
        schema = obj.get('/Schema', {})
        node = dict(locator=loc, view=self.field(obj.get('/View'), loc, 'name'),
                    schemaKeys=sorted(schema.keys()) if isinstance(schema, (dict, pikepdf.Dictionary)) else [],
                    folders=folders, entries=entries, children=children)
        return observation(node, evidence=[loc]), state

    def inspect(self):
        if len(self.data) > self.limits['maxFileBytes']:
            raise ValueError('Input file byte limit exceeded')
        with pikepdf.open(io.BytesIO(self.data), attempt_recovery=False) as pdf:
            records, declaration, meta_state = self.metadata(pdf)
            messages = [dict(id=r['id'], documentId=self.docid, metadataRecordId=r['id'],
                mailGuid=self.record_field(r, 'Mail_GUID'), internetMessageId=self.record_field(r, 'messageid', 'Message-ID'),
                partIds=observation(), sourceAssetIds=observation(state='not-evaluated', reason='Original email parsing is outside this reference projection.'),
                attachmentRecordIds=[]) for r in records if r['kind'] == 'email']
            parts, part_state = self.parts(pdf, messages)
            files, file_state = self.files(pdf)
            self.bind_files(files, records, messages, parts)
            if value(declaration(IDENT, 'conformance')) in ['s', 'si'] and len(messages) == 1:
                for file in files:
                    if not value(file['owners']) and file['owners']['state'] != 'ambiguous' and any(o['kind'] == 'catalog-af' for o in file['origins']) and not any(o['kind'] in ['annotation', 'object-af'] for o in file['origins']):
                        file['owners'] = observation([messages[0]['id']], basis='derived', evidence=[file['locator']], reason='Catalog association in a single-email document.')
            for message in messages:
                sources = [f for f in files if value(f['afRelationship']) == 'Source' and message['id'] in (value(f['owners']) or [])]
                if sources:
                    message['sourceAssetIds'] = observation([f['id'] for f in sources], basis='derived', evidence=[f['locator'] for f in sources])
                ids = [p['id'] for p in parts if message['id'] in (value(p['messageIds']) or [])]
                message['partIds'] = observation(ids, basis='derived', reason='Only explicitly GUID-bound DParts; collection summaries need not own outer pages.')
                message['attachmentRecordIds'] = [r['id'] for r in records if r['kind'] == 'attachment' and message['id'] in (value(r['owners']) or [])]
            collection, collection_state = self.collection(pdf, files)
            headerloc = dict(documentId=self.docid, objectNumber=None, generation=None, path='header/PDF-version')
            header = str(pdf.pdf_version)
            catalog = self.field(pdf.Root.get('/Version'), self.locator(pdf.Root, 'catalog'), 'name')
            effective = observation(header, basis='derived', evidence=[headerloc], reason='Header version unless a valid higher catalog override exists.')
            if catalog['state'] not in ['present', 'missing']:
                effective = observation(state='invalid', reason='Catalog PDF version has an invalid type.',
                                        raw=catalog['raw'], evidence=catalog['evidence'])
            if value(catalog):
                if re.fullmatch(r'\d\.\d', value(catalog)):
                    effective['value'] = max(header, value(catalog), key=lambda v: tuple(map(int, v.split('.'))))
                else:
                    effective = observation(state='invalid', reason='Catalog PDF version is not recognizable.', raw=catalog['raw'], evidence=catalog['evidence'])
            profile = declaration(IDENT, 'conformance')
            if value(profile) is not None and value(profile) not in ['s', 'si', 'm', 'mi', 'c', 'ci']:
                self.diagnostic('UNKNOWN_PROFILE', 'unsupported', 'Unrecognized profile declaration; no profile was guessed.', *profile['evidence'])
                profile = observation(state='unsupported', raw=profile['raw'], evidence=profile['evidence'], reason='Profile is outside the known six-profile vocabulary.')
            return dict(schemaVersion=1, documentId=self.docid, source=dict(sha256=digest(self.data), byteLength=len(self.data)),
                identification=dict(pdfHeaderVersion=observation(header, raw=[dict(kind='header', text=header, bytesHex=None)], evidence=[headerloc]),
                    pdfCatalogVersion=catalog, effectivePdfVersion=effective,
                    pdfaPart=declaration(PDFA, 'part'), pdfaConformance=declaration(PDFA, 'conformance'), pdfaRevision=declaration(PDFA, 'rev'),
                    eapdfVersion=declaration(IDENT, 'version'), eapdfRevision=declaration(IDENT, 'rev'), declaredProfile=profile, validationStatus='not-evaluated'),
                pageCount=len(pdf.pages), parts=parts, messages=messages, metadataRecords=records, files=files, collection=collection,
                diagnostics=self.diagnostics, limits=self.limits,
                coverage=dict(metadata=meta_state, documentParts=part_state, associatedFiles=file_state, collection=collection_state,
                    fullEmailHeaders='not-evaluated', logicalStructure='not-evaluated', sourceEmailParsing='not-evaluated',
                    contentStreamAssociations='not-evaluated', conformance='not-evaluated'))


def inspect_path(path, limits=None):
    path = Path(path)
    maximum = (limits or {}).get('maxFileBytes', LIMITS['maxFileBytes'])
    if path.stat().st_size > maximum:
        raise ValueError('Input file byte limit exceeded')
    return Inspector(path.read_bytes(), limits).inspect()


def read_embedded_bytes(path, request):
    """Separate bounded development operation; no binary bytes enter inspection JSON.

    Direct file-spec locators require a future safe path resolver and are rejected.
    No destination filename or path is interpreted; callers receive bytes only.
    """
    maximum = request['maxBytes']
    if isinstance(maximum, bool) or not isinstance(maximum, int) or not 0 <= maximum <= LIMITS['maxStreamBytes']:
        raise ValueError('Invalid extraction byte allowance')
    path = Path(path)
    if path.stat().st_size > LIMITS['maxFileBytes']:
        raise ResourceLimitError('Input file byte limit exceeded')
    data = path.read_bytes()
    docid = 'sha256:' + digest(data)
    if request['documentId'] != docid or request['stream']['documentId'] != docid:
        raise ValueError('Locator belongs to different document bytes')
    match = re.fullmatch(re.escape(docid) + r'#object:(\d+):(\d+)', request['fileId'])
    if not match:
        raise ValueError('Only indirect file-spec locators are supported by this reference operation')
    with pikepdf.open(io.BytesIO(data), attempt_recovery=False) as pdf:
        spec = pdf.get_object((int(match[1]), int(match[2])))
        streamloc = request['stream']
        expected = (streamloc['objectNumber'], streamloc['generation'])
        if streamloc['path'] != f'object:{expected[0]}:{expected[1]}':
            raise ValueError('Stream locator path and object identity disagree')
        ef = spec.get('/EF') if isinstance(spec, pikepdf.Dictionary) else None
        candidates = [s for _, s in ef.items() if isinstance(s, pikepdf.Stream) and s.objgen == expected] if isinstance(ef, pikepdf.Dictionary) else []
        if not candidates:
            raise ValueError('Stream is not an EF target of the requested file specification')
        payload = candidates[0].read_bytes()
        if len(payload) > maximum:
            raise ResourceLimitError('Decoded payload exceeds extraction byte allowance')
        return payload


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('pdf', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.write_text(json.dumps(inspect_path(args.pdf), indent=2, ensure_ascii=False) + '\n')
