#!/usr/bin/env python3
"""Independent real-email oracle and visual regression check; never updates baselines."""
import argparse
from collections import Counter
from datetime import datetime
from email import policy
from email.parser import BytesParser
from email.utils import getaddresses, parsedate_to_datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET
import pymupdf as fitz

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import quality_formats

MAIL = '{http://www.pdfa.org/eapdf/ns/meta/}'
FOAF = '{http://xmlns.com/foaf/0.1/}'
RDF = '{http://www.w3.org/1999/02/22-rdf-syntax-ns#}'

def require(condition, message):
    if not condition:
        raise AssertionError(message)

def digest(data):
    return hashlib.sha256(data).hexdigest()

def normalized(text):
    return ' '.join(text.split())

def check_original(case):
    if 'messages' in case:
        return quality_formats.check_original(ROOT / case['input'], case)
    data = (ROOT / case['input']).read_bytes()
    require(digest(data) == case['sha256'], 'Original source changed: ' + case['input'])
    mail = BytesParser(policy=policy.default).parsebytes(data)
    require(str(mail['Subject']) == case['subject'], 'Original subject disagrees with oracle')
    require(str(mail['Message-ID']) == case['message_id'], 'Original Message-ID disagrees with oracle')
    require(parsedate_to_datetime(str(mail['Date'])) == datetime.fromisoformat(case['sent']), 'Original date disagrees with oracle')
    for field in ('from', 'to'):
        addresses = [address.lower() for _, address in getaddresses(mail.get_all(field, []))]
        require(addresses == case[field], 'Original recipients disagree with oracle: ' + field)
    attachments = Counter((p.get_filename(), digest(p.get_payload(decode=True))) for p in mail.iter_attachments())
    require(attachments == Counter((a['filename'], a['sha256']) for a in case['attachments']), 'Original attachments disagree with oracle')
    return data

def check_pdf(path, case):
    """Inspect final bytes with a different PDF engine; expectations never come from creator metadata."""
    if 'messages' in case:
        return quality_formats.check_pdf(path, case)
    with fitz.open(path) as doc:
        xml = ET.fromstring(doc.get_xml_metadata())
        def values(name):
            return [e.text or '' for e in xml.iter(MAIL + name)]
        require(values('Subject') == [case['subject']], 'Subject lost or changed')
        require(values('messageid') == [case['message_id']], 'Message-ID lost or changed')
        require([datetime.fromisoformat(v) for v in values('Sent')] == [datetime.fromisoformat(case['sent'])], 'Date lost or changed')
        for field in ('from', 'to'):
            agents = []
            for container in xml.iter(MAIL + field.title()):
                for node in container.iter():
                    if FOAF + 'mbox' in node.attrib:
                        agents.append(node.attrib[FOAF + 'mbox'].removeprefix('mailto:').lower())
                    if node.tag == FOAF + 'mbox':
                        agents.append(node.get(RDF + 'resource', node.text or '').removeprefix('mailto:').lower())
            require(Counter(agents) == Counter(case[field]), 'Recipients lost or changed: ' + field)
        require(values('NumberAttachments') == [str(len(case['attachments']))], 'Attachment count changed')
        text = normalized(' '.join(page.get_text() for page in doc))
        for phrase in case['body_phrases']:
            require(normalized(phrase) in text, 'Body text missing: ' + phrase)
        require(case['subject'] in text, 'Subject is not searchable')
        sources = [digest(doc.embfile_get(name)) for name in doc.embfile_names()]
        require(sources.count(case['sha256']) == 1, 'Exact original source missing or duplicated')
        attachments = Counter()
        for page in doc:
            for annotation in page.annots() or ():
                if annotation.type[0] == fitz.PDF_ANNOT_FILE_ATTACHMENT:
                    attachments[(annotation.file_info['filename'], digest(annotation.get_file()))] += 1
            # Inspect displayed text geometry independently of PDFBox's text extraction.
            for block in page.get_text('dict')['blocks']:
                for line in block.get('lines', []):
                    for span in line['spans']:
                        box = fitz.Rect(span['bbox'])
                        require((page.rect + (-1, -1, 1, 1)).contains(box), 'Text outside page: ' + span['text'])
        require(attachments == Counter((a['filename'], a['sha256']) for a in case['attachments']), 'Native attachment bytes/names/count changed')
        return len(doc)

def compare_visual(pdf, baseline, output, masks=None):
    with fitz.open(pdf) as doc:
        require(len(list(baseline.glob('page-*.png'))) == len(doc), 'Visual baseline page count changed or missing')
        for number, page in enumerate(doc, 1):
            for rectangle in (masks or {}).get(str(number), []):
                page.draw_rect(fitz.Rect(rectangle), color=None, fill=(1, 1, 1), overlay=True)
            actual = page.get_pixmap(dpi=72, colorspace=fitz.csGRAY, alpha=False)
            actual.save(output / f'page-{number}.png')
            reference = fitz.Pixmap(str(baseline / f'page-{number}.png'))
            require((actual.width, actual.height, actual.n) == (reference.width, reference.height, reference.n), 'Page dimensions changed')
            differences = sum(abs(a - b) > 16 for a, b in zip(actual.samples, reference.samples))
            fraction = differences / len(actual.samples)
            require(fraction <= 0.001, f'Page {number}: visual change {fraction:.3%} exceeds 0.1%; review required')

def run(command, log, env=None):
    with log.open('w') as stream:
        subprocess.run([str(x) for x in command], cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT, check=True, timeout=600)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--creator', type=Path, required=True)
    parser.add_argument('--validator', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output = args.output.resolve()
    # A fresh evidence directory prevents stale PDFs/reports from satisfying the gate.
    args.output.mkdir(parents=True, exist_ok=False)
    results = {'status': 'RUNNING', 'scope': 'Public small EML, MSG and MBOX fidelity, visual checks and standalone Java validation', 'cases': []}
    try:
        corpus = json.loads((ROOT / 'regression/quality/corpus.json').read_text())
        require(fitz.__version__ == corpus['renderer_version'], 'Wrong rasterizer version; install regression/quality/requirements.txt')
        for case in corpus['cases']:
            original = check_original(case)
            name = Path(case['input']).stem
            destination = args.output / name
            destination.mkdir()
            environment = dict(os.environ, EAPDF_CONFIG=str(ROOT / 'regression/quality/config.json'), EAPDF_WORKER_HEAP_BYTES=str(768 * 1024 * 1024))
            command = ['java', '-Xmx768m', '-Duser.timezone=' + corpus['timezone'], '-jar', args.creator.resolve(), ROOT / case['input'], '--out', destination, '--jobs', '2']
            if case.get('archive_profile'):
                command += ['--archive-profile', case['archive_profile']]
            run(command, destination / 'conversion.log', environment)
            if case.get('required_warning'):
                require(case['required_warning'] in (destination / 'conversion.log').read_text(), 'Known precision loss was not reported')
            pdfs = list(destination.glob('*.pdf'))
            require(len(pdfs) == 1, 'Unexpected output PDF count')
            pdf = pdfs[0]
            pages = check_pdf(pdf, case)
            progress = json.loads((destination / 'progress.json').read_text())
            require(progress['state'] == 'COMPLETED', 'Publication did not complete')
            if case.get('required_warning'):
                require(any(d['code'] == 'DATE_PRECISION_REDUCED' and case['required_warning'] in d['details']
                            for d in progress.get('conversion_diagnostics', [])), 'Missing structured precision diagnostic')
            receipt, = progress['creation_receipts']
            require(receipt['publication'] == 'PUBLISHED' and receipt['sha256'] == digest(pdf.read_bytes()), 'Receipt does not identify final published bytes')
            require(receipt['messages'] == len(quality_formats.messages(case)), 'Receipt message count differs from independent source expectations')
            require(receipt['creation_checks'] == receipt['preservation_comparison'] == 'PASS', 'Creation checks incomplete')
            require(receipt['pdfa_validation'] == receipt['eapdf_validation'] == 'NOT_RUN', 'Creator claimed independent validation')
            # Publish the regression artifact with its export profile; receipts
            # above still verify the creator's original publication transaction.
            profile_suffix = '_1' + case.get('archive_profile', 's')
            if not pdf.stem.endswith(profile_suffix):
                named = pdf.with_name(pdf.stem + profile_suffix + pdf.suffix)
                require(not named.exists(), 'Profile-named output already exists')
                pdf = pdf.rename(named)
            report = destination / 'validation.json'
            before = digest(pdf.read_bytes())
            run(['java', '-Xmx768m', '-cp', args.validator.resolve(), 'eapdf.JavaValidation', pdf, report], destination / 'validation.log')
            checked = json.loads(report.read_text())
            require(checked['summary']['status'] == checked['pdfa']['status'] == 'PASS', 'EA-PDF or PDF/A validation failed')
            require(checked['pdfa']['ran'] and checked['pdfa']['compliant'], 'veraPDF did not establish compliance')
            require(checked['summary']['rulebook_sha256'] == digest((ROOT / 'validator_rule_text.json').read_bytes()), 'Validator used a stale rulebook')
            require(before == digest(pdf.read_bytes()), 'PDF changed during validation')
            require(original == (ROOT / case['input']).read_bytes(), 'Original changed during conversion')
            compare_visual(pdf, ROOT / 'regression/quality/visual' / name, destination, case.get('visual_masks'))
            results['cases'].append({'input': case['input'], 'source_sha256': case['sha256'], 'pdf_sha256': before, 'pages': pages, 'message_count': len(quality_formats.messages(case)), 'known_limitations': case.get('known_limitations', []), 'source_representation': case.get('source_representation', 'Original source bytes embedded'), 'status': 'PASS', 'validation_report': str(report.relative_to(args.output)), 'rulebook_sha256': checked['summary']['rulebook_sha256']})
        require(results['cases'], 'No cases were checked')
        results['status'] = 'PASS'
    except Exception as error:
        results['status'] = 'FAIL'
        results['error'] = str(error)
        raise
    finally:
        (args.output / 'summary.json').write_text(json.dumps(results, indent=2) + '\n')

if __name__ == '__main__':
    main()
