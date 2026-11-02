"""Authored MBOX bytes and assembly-time entry oracles; no application imports."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
ENVELOPE = b'From sender@example.invalid Fri Oct 16 12:00:00 2026\n'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def message(letter, body, length=None):
    headers = (f'From: Sender <sender@example.invalid>\nTo: Reader <reader@example.invalid>\n'
               f'Date: Fri, 16 Oct 2026 12:00:00 +0000\nMessage-ID: <mailbox-{letter.lower()}@example.invalid>\n'
               f'Subject: Mailbox {letter}\nContent-Type: text/plain; charset=UTF-8\n').encode()
    if length is not None:
        headers += f'Content-Length: {length}\n'.encode()
    return headers + b'\n' + body


def assemble(name, fmt, newline=b'\n', length_framed=False, binary=False, identical=False):
    entries = []
    mailbox = bytearray()
    bodies = [b'Body A.\n>From quoted once\n>>From quoted twice\n\n', 'Body B: café.\n'.encode(), b'Last C without final newline']
    if length_framed:
        bodies[0] = b'Body A.\n' + ENVELOPE + b'Subject: body-only marker\n\n>From literal or quoted\n'
    if binary:
        bodies = [bytes(range(256))]
    if identical:
        bodies = [b'Identical message.\n', b'Identical message.\n']
    for index, body in enumerate(bodies, 1):
        letter = 'A' if identical else chr(64 + index)
        if not binary:
            body = body.replace(b'\n', newline)
        raw = message(letter, body, len(body) if length_framed else None)
        if newline != b'\n':
            headers, body = raw.split(b'\n\n', 1)
            raw = headers.replace(b'\n', newline) + newline * 2 + body
        quoting = ('mboxo' if fmt == 'mboxcl' else 'mboxcl2') if length_framed else ('mboxo' if fmt == 'mboxo' else 'mboxrd')
        # Explicit authored expected body transformations, independent of the parser.
        exported = raw
        if not binary and quoting != 'mboxcl2':
            exported = exported.replace(newline + b'>From quoted once', newline + b'From quoted once')
            if quoting == 'mboxrd':
                exported = exported.replace(newline + b'>>From quoted twice', newline + b'>From quoted twice')
            exported = exported.replace(newline + b'>From literal or quoted', newline + b'From literal or quoted')
        envelope = ENVELOPE.replace(b'\n', newline)
        envelope_offset = len(mailbox)
        mailbox.extend(envelope)
        message_offset = len(mailbox)
        mailbox.extend(raw)
        padding = newline * 2 if length_framed else b''
        mailbox.extend(padding)
        export_name = f'{name}-{index}.eml'
        (ROOT / export_name).write_bytes(exported)
        entries.append(dict(ordinal=index,envelopeOffset=envelope_offset,messageOffset=message_offset,
                            rawSize=len(raw),rawSha256=sha(raw),exportedSize=len(exported),exportedSha256=sha(exported),
                            paddingSize=len(padding),framing='Content-Length' if length_framed else 'separator',quoting=quoting,
                            exportFile=export_name,subject='Mailbox ' + letter))
    (ROOT / (name + '.mbox')).write_bytes(mailbox)
    return dict(id=name,input=name+'.mbox',format=fmt,containerSize=len(mailbox),containerSha256=sha(mailbox),entries=entries,
                reader=True if not binary else False)


def main():
    cases = [assemble('rd-lf','mboxrd'),assemble('rd-crlf','mboxrd',b'\r\n'),assemble('rd-cr','mboxrd',b'\r'),
             assemble('o-lf','mboxo'),assemble('cl-lf','mboxcl',length_framed=True),
             assemble('cl2-lf','mboxcl2',length_framed=True),assemble('auto-cl2','auto',length_framed=True),
             assemble('identical','mboxrd',identical=True),assemble('binary-cl2','mboxcl2',length_framed=True,binary=True)]
    (ROOT / 'inputs.json').write_text(json.dumps(cases,indent=2)+'\n')
    first = (ROOT / 'first.eml').read_bytes()
    (ROOT / 'missing-header-separator.mbox').write_bytes(ENVELOPE + first.replace(b'\n\n', b'\n', 1) + ENVELOPE + first)


if __name__ == '__main__':
    main()
