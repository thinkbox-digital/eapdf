"""Independent, fixture-scoped source and multi-message PDF checks (no creator code)."""
from contextlib import closing
from collections import Counter
from datetime import datetime
from email import policy
from email.parser import BytesParser, Parser
from email.utils import getaddresses, parsedate_to_datetime
import hashlib
import mailbox
from pathlib import Path
import xml.etree.ElementTree as ET
import pymupdf as fitz
import olefile
import pikepdf

MAIL = '{http://www.pdfa.org/eapdf/ns/meta/}'
FOAF = '{http://xmlns.com/foaf/0.1/}'
RDF = '{http://www.w3.org/1999/02/22-rdf-syntax-ns#}'

def require(ok, why):
    if not ok:
        raise AssertionError(why)

def digest(data):
    return hashlib.sha256(data).hexdigest()

def normalized(text):
    return ' '.join(text.split())

def messages(case):
    return case.get('messages', [case])

def mime_headers(mail, expected):
    require(str(mail.get('Subject', '')).strip() == expected['subject'], 'Source subject changed')
    require(str(mail.get('Message-ID', '')).strip() == expected['message_id'], 'Source Message-ID changed')
    require(parsedate_to_datetime(str(mail['Date'])) == datetime.fromisoformat(expected['sent']), 'Source sent date changed')
    for field in ('from', 'to', 'cc', 'bcc'):
        if field not in expected:
            continue
        actual = [address.lower() for _, address in getaddresses(mail.get_all(field, []))]
        # Non-SMTP original authors are checked through explicit raw evidence instead.
        if field == 'from' and expected.get('raw_from_contains'):
            require(expected['raw_from_contains'] in str(mail.get('From', '')), 'Source non-SMTP author changed')
        else:
            require(Counter(actual) == Counter(expected[field]), 'Source recipients changed: ' + field)

def mime_body(mail):
    return normalized(' '.join(part.get_content() for part in mail.walk()
        if part.get_content_type() == 'text/plain' and not part.get_filename()))

def check_attachment_payloads(actual, expected):
    require(Counter(name for name, _ in actual) == Counter(a['filename'] for a in expected), 'Native attachment names/count changed')
    remaining = list(actual)
    for item in expected:
        candidates = [(i, data) for i, (name, data) in enumerate(remaining) if name == item['filename']]
        if 'sha256' in item:
            matched = next((i for i, data in candidates if digest(data) == item['sha256']), None)
            require(matched is not None, 'Native attachment bytes changed: ' + item['filename'])
        else:
            require(len(candidates) == 1, 'Ambiguous attached-email occurrence')
            matched, data = candidates[0]
            mail = BytesParser(policy=policy.default).parsebytes(data)
            mime_headers(mail, item['message'])
            for phrase in item['message']['body_phrases']:
                require(normalized(phrase) in mime_body(mail), 'Attached email body missing: ' + phrase)
            check_attachment_payloads([(part.get_filename(), part.get_payload(decode=True)) for part in mail.iter_attachments()], item['message']['attachments'])
        remaining.pop(matched)

def ole_text(storage, base, tag):
    for kind, encoding in [('001F', 'utf-16-le'), ('001E', 'cp1252')]:
        path = base + ['__substg1.0_' + tag + kind]
        if storage.exists(path):
            return storage.openstream(path).read().decode(encoding).rstrip('\0')
    return ''

def check_original(path, case):
    data = path.read_bytes()
    require(digest(data) == case['sha256'], 'Original source changed: ' + str(path))
    kind = case.get('format', 'eml')
    if kind == 'msg':
        expected, = messages(case)
        with olefile.OleFileIO(path) as storage:
            mail = Parser(policy=policy.default).parsestr(ole_text(storage, [], '007D'))
            mime_headers(mail, expected)
            require(ole_text(storage, [], '0037') == expected['subject'], 'Native MSG subject changed')
            body = normalized(ole_text(storage, [], '1000'))
            for phrase in expected['body_phrases']:
                require(normalized(phrase) in body, 'Native MSG body disagrees with oracle')
            attachments = []
            for folder in sorted({tuple(p[:1]) for p in storage.listdir() if p[0].startswith('__attach')}):
                base = list(folder)
                name = ole_text(storage, base, '3707') or ole_text(storage, base, '3704')
                attachments.append((name, storage.openstream(base + ['__substg1.0_37010102']).read()))
            check_attachment_payloads(attachments, expected['attachments'])
    elif kind == 'mbox':
        with closing(mailbox.mbox(path, factory=lambda f: BytesParser(policy=policy.default).parse(f), create=False)) as box:
            items = list(box)
        require(len(items) == len(messages(case)), 'Original MBOX message count changed')
        for mail, expected in zip(items, messages(case)):
            mime_headers(mail, expected)
            require(digest(normalized(mime_body(mail)).encode()) == expected['plain_body_sha256'], 'Original MBOX body changed')
            check_attachment_payloads([(p.get_filename(), p.get_payload(decode=True)) for p in mail.iter_attachments()], expected['attachments'])
    elif kind == 'pst':
        # Fixed expectations were established using libpff + libpst and reviewed
        # native properties. The exact source hash pins that independent examination.
        require(case.get('provenance') and case.get('native_message_count') == len(messages(case)), 'PST oracle lacks independent provenance/count')
    else:
        mail = BytesParser(policy=policy.default).parsebytes(data)
        mime_headers(mail, case)
        check_attachment_payloads([(p.get_filename(), p.get_payload(decode=True)) for p in mail.iter_attachments()], case['attachments'])
    return data

def page_owner(page):
    part = page.obj.get('/DPart')
    seen = set()
    for _ in range(64):
        if part is None:
            return None, None
        key = part.objgen
        require(key not in seen, 'Cyclic message ownership')
        seen.add(key)
        properties = part.get('/DPM', {})
        if properties.get('/Mail_GUID') is not None:
            return str(properties['/Mail_GUID']), str(properties.get('/Mail_MessageID', ''))
        part = part.get('/Parent')
    raise AssertionError('Message ownership nesting exceeds fixture limit')

def check_pdf(path, case):
    expected = {m['message_id']: m for m in messages(case)}
    require(len(expected) == len(messages(case)), 'Duplicate expected identity')
    with fitz.open(path) as doc, pikepdf.open(path) as objects:
        xml = ET.fromstring(doc.get_xml_metadata())
        entries = xml.findall('.//' + MAIL + 'email/' + RDF + 'Bag/' + RDF + 'li')
        require(len(entries) == len(expected), 'Message count changed')
        records = {}
        for entry in entries:
            identifier = entry.findtext(MAIL + 'messageid', '')
            require(identifier in expected and identifier not in records, 'Missing, extra or duplicate message identity')
            records[identifier] = entry
        require(set(records) == set(expected), 'Missing message identity')
        pages, attachments = {}, {}
        guids = {entry.findtext(MAIL + 'Mail_GUID'): identifier for identifier, entry in records.items()}
        require(None not in guids and len(guids) == len(records), 'Missing or duplicate message GUID')
        for number, page in enumerate(doc):
            guid, identifier = page_owner(objects.pages[number])
            if guid is not None:
                require(guid in guids and guids[guid] == identifier, 'Page message identity/ownership changed')
                pages.setdefault(identifier, []).append(number)
            for annotation in page.annots() or ():
                if annotation.type[0] == fitz.PDF_ANNOT_FILE_ATTACHMENT:
                    require(identifier in expected, 'Attachment has no message owner')
                    attachments.setdefault(identifier, []).append((annotation.file_info['filename'], annotation.get_file()))
            for block in page.get_text('dict')['blocks']:
                for line in block.get('lines', []):
                    for span in line['spans']:
                        require((page.rect + (-1, -1, 1, 1)).contains(fitz.Rect(span['bbox'])), 'Text outside page: ' + span['text'])
        for identifier, item in expected.items():
            entry = records[identifier]
            require(entry.findtext(MAIL + 'Subject', '') == item['subject'], 'Subject lost or changed')
            require(datetime.fromisoformat(entry.findtext(MAIL + 'Sent')) == datetime.fromisoformat(item['sent']), 'Sent date lost or changed')
            for field in ('from', 'to', 'cc', 'bcc'):
                if field not in item:
                    continue
                agents = []
                for container in entry.findall(MAIL + field.title()):
                    for node in container.iter():
                        if FOAF + 'mbox' in node.attrib:
                            agents.append(node.attrib[FOAF + 'mbox'].removeprefix('mailto:').lower())
                        if node.tag == FOAF + 'mbox':
                            agents.append(node.get(RDF + 'resource', node.text or '').removeprefix('mailto:').lower())
                require(Counter(agents) == Counter(item[field]), 'Recipients lost or changed: ' + field)
            if item.get('raw_from_contains'):
                require(item['raw_from_contains'] in entry.findtext(MAIL + 'Raw-From', ''), 'Non-SMTP author lost')
            require(entry.findtext(MAIL + 'NumberAttachments') == str(len(item['attachments'])), 'Attachment count changed')
            require(identifier in pages, 'Message has no owned pages')
            text = normalized(' '.join(doc[i].get_text() for i in pages[identifier]))
            require(normalized(item['subject']) in text, 'Subject is not searchable on its own pages')
            for phrase in item['body_phrases']:
                require(normalized(phrase) in text, 'Body text missing from message: ' + phrase)
            if 'folder' in item:
                require('Folder: ' + normalized(item['folder']) in text, 'Message folder membership changed')
            check_attachment_payloads(attachments.get(identifier, []), item['attachments'])
        embedded = [(name, doc.embfile_get(name)) for name in doc.embfile_names()]
        if case.get('format', 'eml') in ('eml', 'msg'):
            require(sum(digest(data) == case['sha256'] for _, data in embedded) == 1, 'Exact original source missing or duplicated')
        else:
            sources = [BytesParser(policy=policy.default).parsebytes(data) for name, data in embedded if name.lower().endswith('.eml')]
            require(Counter(str(m.get('Message-ID', '')).strip() for m in sources) == Counter(expected.keys()), 'Derivative source message set changed')
            for mail in sources:
                item = expected[str(mail['Message-ID']).strip()]
                mime_headers(mail, item)
                body = mime_body(mail)
                if 'plain_body_sha256' in item:
                    require(digest(body.encode()) == item['plain_body_sha256'], 'Derivative source body changed')
                for phrase in item['body_phrases']:
                    require(normalized(phrase) in body, 'Derivative source body missing: ' + phrase)
                # Attached PST emails are reconstructions: compare their fields and
                # nested native bytes, not an invented hash of an original EML.
                actual = []
                for part in mail.iter_attachments():
                    if part.get_content_type() == 'message/rfc822':
                        child = part.get_payload()[0]
                        require(part.get('Content-Transfer-Encoding', '7bit').lower() in ('7bit', '8bit', 'binary'), 'Invalid attached-message transfer encoding')
                        data = child.as_bytes()
                    else:
                        data = part.get_payload(decode=True)
                    actual.append((part.get_filename(), data))
                check_attachment_payloads(actual, item['attachments'])
        return len(doc)
