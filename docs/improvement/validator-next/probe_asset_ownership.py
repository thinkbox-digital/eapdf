"""Independent path-collision probe; run without changing validator inputs."""
from pathlib import Path
import json
import os
import subprocess
import sys
from xml.etree import ElementTree as ET

import pikepdf

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))
import validate_eapdf as v

stage = Path(__file__).with_name('asset-ownership')
stage.mkdir(exist_ok=True)
inputs = stage / 'inputs'
for index in (1, 2):
    path = inputs / str(index) / 'message.eml'
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes((f'From: sender{index}@example.org\r\nTo: recipient@example.org\r\n'
        f'Date: Tue, 28 Jan 2003 21:20:54 -0500\r\nMessage-ID: <{index}@example.org>\r\n'
        f'Subject: Message {index}\r\nContent-Type: text/plain\r\n\r\nBody {index}.\r\n').encode())
config = stage / 'config.json'
config.write_text(json.dumps({'branding': {'enabled': False}, 'premis': {'enabled': False}}))
source = stage / 'source.pdf'
if not source.exists():
    jar = os.environ['EAPDF_GENERATOR_CLASSPATH']
    result = subprocess.run(['java', '-Xmx512m', '-Djava.awt.headless=true', '-cp', jar,
        'eapdf.EmailToPDFA', '--archive-profile', 'm', '--archive-output', str(source),
        '--out', str(stage / 'out'), str(inputs)], cwd=ROOT,
        env=dict(os.environ, EAPDF_CONFIG=str(config)), capture_output=True, text=True, timeout=180)
    (stage / 'generation.log').write_text(result.stdout + result.stderr)
    assert result.returncode == 0, result.stdout + result.stderr
with pikepdf.open(source) as pdf:
    root, namespaces, _ = v.parse_xmp(v.read_metadata_stream(pdf), v.Report('parse'))
    for prefix, uri in namespaces.items():
        ET.register_namespace(prefix, uri)
    bag = root.find('.//' + v.qn(v.NS_PDFMAILMETA, 'assets')).find(v.qn(v.NS_RDF, 'Bag'))
    assert len(bag) == 2
    original = [child.find(v.qn(v.NS_PDFMAILMETA, 'Filename')).text for child in bag]
    # The Bag is unordered; reversing it must not change source attribution.
    bag[:] = list(reversed(list(bag)))
    packet = ET.tostring(root, encoding='utf-8')
    for prefix, uri in (('pdfmail', v.NS_PDFMAIL), ('pdfmailid', v.NS_PDFMAILID), ('pdfmailmeta', v.NS_PDFMAILMETA)):
        if ('xmlns:' + prefix + '=').encode() not in packet:
            root.set('xmlns:' + prefix, uri)
    pdf.Root.Metadata.write(ET.tostring(root, encoding='utf-8'))
    reversed_pdf = stage / 'reversed-bag.pdf'
    pdf.save(reversed_pdf, fix_metadata_version=False)
collision_paths = []
for reverse in (False, True):
    with pikepdf.open(source) as pdf:
        root, namespaces, _ = v.parse_xmp(v.read_metadata_stream(pdf), v.Report('parse'))
        for prefix, uri in namespaces.items():
            ET.register_namespace(prefix, uri)
        bag = root.find('.//' + v.qn(v.NS_PDFMAILMETA, 'assets')).find(v.qn(v.NS_RDF, 'Bag'))
        for index, child in enumerate(bag, start=1):
            child.find(v.qn(v.NS_PDFMAILMETA, 'Filename')).text = f'folder-{index}/message.eml'
        specs = [spec for spec in pdf.Root.AF if str(spec.AFRelationship) == '/Source']
        assert len(specs) == 2
        for index, spec in enumerate(specs):
            name = 'message.eml' if index == 0 else 'message_2.eml'
            spec.F = pikepdf.String(name)
            spec.UF = pikepdf.String(name)
        if reverse:
            bag[:] = list(reversed(list(bag)))
        packet = ET.tostring(root, encoding='utf-8')
        for prefix, uri in (('pdfmail', v.NS_PDFMAIL), ('pdfmailid', v.NS_PDFMAILID), ('pdfmailmeta', v.NS_PDFMAILMETA)):
            if ('xmlns:' + prefix + '=').encode() not in packet:
                root.set('xmlns:' + prefix, uri)
        pdf.Root.Metadata.write(ET.tostring(root, encoding='utf-8'))
        path = stage / ('collision-reversed.pdf' if reverse else 'collision-forward.pdf')
        pdf.save(path, fix_metadata_version=False)
        collision_paths.append(path)
rows = []
for path in (source, reversed_pdf, *collision_paths):
    result = v.validate_one(str(path), write_html=False)
    (stage / (path.stem + '.json')).write_text(json.dumps(result, indent=2, default=str))
    rows.append({'file': path.name, 'source_asset_filenames': original,
        'pdfa': result['pdfa']['status'], 'status': result['summary']['status'],
        'failures': sorted({r['code'] for r in result['items'] if r['level'] == 'FAIL'})})
(stage / 'results.json').write_text(json.dumps(rows, indent=2) + '\n')
print(json.dumps(rows, indent=2))
