"""Verify C02 on the actual 71-message PST, retaining V85's remaining limits."""
from collections import Counter
from email import policy
from email.parser import BytesParser
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
import pikepdf
import validate_eapdf as validator

os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT), GOMAXPROCS='2')
JAR = Path('/tmp/eapdf-creator-02-build/emailtopdfa-1.4.0.jar')
sha = lambda data: hashlib.sha256(data).hexdigest()
baseline = json.loads((HERE / 'before-inputs.json').read_text())
for name in ('validate_eapdf.py', 'validator_rule_text.json', 'validator_web.py', 'in/email/test.pst'):
    assert sha((ROOT / name).read_bytes()) == baseline[name], name

def source_payloads(path):
    with pikepdf.Pdf.open(path) as pdf:
        return {item['filename']: item['bytes'] for item in validator.get_af_specs(pdf)
                if item['afrelationship'] == 'Source'}

def mime_payloads(message):
    """Compare decoded payloads and MIME semantics, excluding generated boundaries."""
    rows = []
    for part in message.walk():
        parameters = sorted((key.lower(), value) for key, value in
                            (part.get_params() or [])[1:] if key.lower() != 'boundary')
        payload = part.get_payload(decode=True)
        rows.append(dict(type=part.get_content_type(), parameters=parameters,
                         disposition=part.get_content_disposition(), filename=part.get_filename(),
                         content_id=str(part.get('Content-ID', '')),
                         payload_sha256=None if payload is None else sha(payload)))
    return rows

results = []
for profile in ('1m', '1mi'):
    destination = HERE / 'pst' / profile
    destination.mkdir(parents=True, exist_ok=True)
    path = destination / f'sample_{profile}.pdf'
    command = ['java', '-Djava.awt.headless=true', '-Xmx512m', '-cp', str(JAR), 'eapdf.EmailToPDFA',
               '--out', str(destination), '--archive-profile', profile[1:], '--archive-output', str(path),
               str(ROOT / 'in/email/test.pst')]
    generated = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=600)
    (destination / 'generation.log').write_text(generated.stdout + generated.stderr)
    assert generated.returncode == 0, profile
    report = validator.validate_one(str(path), html_path=str(destination / 'validation.html'))
    (destination / 'validation.json').write_text(json.dumps(report, indent=2, default=str) + '\n')
    iso = next(item for item in report['items'] if item['code'] == 'ISO32005_UPSTREAM')
    failures = [item for item in report['items'] if item['level'] == 'FAIL']
    reasons = Counter(item['message'] for item in report['items']
                      if item['code'] == 'VALIDATION_INCOMPLETE' and item['level'] == 'WARN')
    expected = Counter({message: 1 for message in (
        'Some Content Set tag counterparts remain unavailable',
        'Some Art Content Set counterparts remain unavailable',
        'Message content/GUID ownership remains unavailable',
        'Content Group type correspondence remains unavailable',
        'Some active subtype properties could not be compared')})
    if profile == '1mi':
        expected['Attachment native-byte consistency could not be fully verified'] = 1
    assert report['summary']['status'] == 'INCOMPLETE' and reasons == expected, (profile, reasons)
    assert report['pdfa']['status'] == iso['level'] == 'PASS'
    assert iso['details']['profile_verified'] and not failures
    row = dict(profile=profile, file=str(path.relative_to(ROOT)), sha256=sha(path.read_bytes()),
               status=report['summary']['status'], pdfa=report['pdfa']['status'], iso=iso['level'],
               failures=failures, incomplete_reasons=dict(reasons))
    if profile == '1m':
        # Retain the comparison baseline after the repaired samples are published.
        previous_sample = ROOT / 'docs/improvement/creator-samples-c01/reviewed/candidates/1m/sample_1m.pdf'
        assert sha(previous_sample.read_bytes()) == baseline['examples/sample_1m.pdf']
        old = source_payloads(previous_sample)
        new = source_payloads(path)
        assert set(old) == set(new) and len(new) == 71
        comparisons = []
        removed = Counter()
        for filename, original in old.items():
            previous = BytesParser(policy=policy.default).parsebytes(original)
            current = BytesParser(policy=policy.default).parsebytes(new[filename])
            for field in ('From', 'To', 'Subject', 'Date', 'Message-ID', 'In-Reply-To', 'References'):
                assert previous.get_all(field) == current.get_all(field), (filename, field)
            for field in ('Cc', 'Bcc'):
                values = previous.get_all(field)
                if values and all(str(value) == '\0' for value in values):
                    assert current.get_all(field) is None, (filename, field)
                    removed[field] += 1
                else:
                    assert values == current.get_all(field), (filename, field)
                assert all('\0' not in str(value) for value in current.get_all(field, []))
            previous_payloads, current_payloads = mime_payloads(previous), mime_payloads(current)
            assert previous_payloads == current_payloads, filename
            comparisons.append(dict(filename=filename, previous_sha256=sha(original),
                                    new_sha256=sha(new[filename]), decoded_mime_parts=current_payloads))
        assert removed == Counter(Cc=59, Bcc=71), removed
        row.update(messages_compared=71, fictitious_headers_removed=dict(removed),
                   other_headers_and_decoded_payloads_preserved=True)
        (HERE / 'pst/source-comparison.json').write_text(json.dumps(comparisons, indent=2) + '\n')
    results.append(row)
    (HERE / 'pst-results.json').write_text(json.dumps(results, indent=2) + '\n')
    print(json.dumps(row), flush=True)
print('Both actual PST archives retain only their reviewed V85 limitations.', flush=True)
