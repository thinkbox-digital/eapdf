#!/usr/bin/env python3
"""Build the one-page architecture PDF from current project versions and pins."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib.colors import HexColor
from reportlab.lib.utils import simpleSplit

ROOT = Path(__file__).resolve().parents[1]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}


def dependencies(path):
    root = ET.parse(path).getroot()
    return {item.findtext('m:artifactId', namespaces=NS): item.findtext('m:version', namespaces=NS)
            for item in root.findall('.//m:dependency', NS)}


def build(path):
    version = (ROOT / 'VERSION').read_text().strip()
    reader = json.loads((ROOT / 'browser-viewer/package.json').read_text())
    java = dependencies(ROOT / 'pom.xml')
    vera = dependencies(ROOT / 'validator-desktop/pom.xml')
    rules = len(json.loads((ROOT / 'validator_rule_text.json').read_text()))
    pdfmetrics.registerFont(TTFont('Arch', str(ROOT / 'src/main/resources/assets/DejaVuSans.ttf')))
    pdfmetrics.registerFont(TTFont('ArchBold', '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'))
    pdf = canvas.Canvas(str(path), pagesize=(1200, 750), invariant=1, pageCompression=1)
    pdf.setTitle('EA-PDF Software Architecture')
    pdf.setAuthor('Thinkbox.DIGITAL')
    pdf.setSubject('Creator, standalone validation, and full/Lite readers; current product and dependency overview')
    pdf.setCreator('EA-PDF architecture diagram builder')
    pdf.setKeywords('EA-PDF, architecture, creator, validator, viewer, Lite')
    blue = HexColor('#163f58'); ink = HexColor('#233b4a'); border = HexColor('#cbd9e2')

    def text(x, y, value, size=11, bold=False, color=ink):
        pdf.setFont('ArchBold' if bold else 'Arch', size)
        pdf.setFillColor(color); pdf.drawString(x, y, value)

    def card(x, y, width, height, heading, blocks):
        pdf.setStrokeColor(border); pdf.setFillColor(HexColor('#f7fafc'))
        pdf.roundRect(x, y, width, height, 7, fill=1, stroke=1)
        text(x + 15, y + height - 25, heading, 14, True, blue)
        cursor = y + height - 47
        for label, body in blocks:
            if label:
                text(x + 15, cursor, label, 10.8, True); cursor -= 17
            for line in simpleSplit(body, 'Arch', 10.5, width - 30):
                text(x + 15, cursor, line, 10.5); cursor -= 15
            cursor -= 12
        assert cursor >= y + 3, (heading, 'Text exceeds card height', cursor, y)

    text(40, 707, 'EA-PDF SOFTWARE ARCHITECTURE', 24, True, blue)
    text(40, 683, 'Thinkbox.DIGITAL  |  Product and dependency overview  |  13 November 2026', 11)
    width = (1120 - 32) / 3
    xs = [40, 40 + width + 16, 40 + 2 * (width + 16)]
    card(xs[0], 418, width, 244, 'EA-PDF CREATOR', [
        ('Java 21+  |  Desktop and command line', version),
        ('Email to preservation PDF', 'EML, MSG, PST, MBOX and Gmail. PDF 2.0 / PDF/A-4f, email metadata, document parts and attachments.'),
        ('Output choices', 'Single messages, combined archives or collections. Optional accessibility features, search indexes and signing.')])
    card(xs[1], 418, width, 244, 'STANDALONE VALIDATOR', [
        ('Java 21+  |  Desktop and command line', version),
        ('Checks', f'{rules} project rule definitions; PDF/A validation; selected ISO 32005 structure rules.'),
        ('Scope', 'Embedded PDFs, attachments and source consistency. Reports PASS, FAIL or INCOMPLETE. No server required.')])
    card(xs[2], 418, width, 244, 'FULL AND LITE READERS', [
        ('Chromium and Firefox  |  Local files', reader['version']),
        ('Shared reading experience', 'PDF pages, message/folder navigation, attachments, text search, filtering and CSV/XMP export.'),
        ('Lite', 'Same layout; no Conversations, Portfolio view, embedded indexes or large-archive optimizations. 32 MiB / 2,048 pages.')])

    card(xs[0], 122, width, 274, 'CREATOR LIBRARIES', [
        ('PDF creation and metadata', f"PDFBox / XMPBox {java['pdfbox']}. OpenHTMLtoPDF {java['openhtmltopdf-pdfbox']} with four maintained patches."),
        ('Email and content', f"Jakarta Mail / Angus Mail, java-libpst, Outlook Message Parser, POI {java['poi']}, jsoup {java['jsoup']}, RTF-to-HTML and jutf7."),
        ('Other integrations', f"Bouncy Castle {java['bcpkix-jdk18on']}; Google Gmail / OAuth clients; Gson {java['gson']}; metadata-extractor {java['metadata-extractor']}.")])
    card(xs[1], 122, width, 274, 'VALIDATION LIBRARIES', [
        ('veraPDF engines and project checks', f"Core / XMP {vera['core']}; parser {vera['parser']}; PDF model {vera['pdf-model']}; validation model {vera['validation-model']}. Local storage/resource patches remain."),
        ('PDF and email inspection', f"PDFBox {vera['pdfbox']}; POI {vera['poi']}; Gson {vera['gson']}; Jakarta Mail / Angus Mail; project EA-PDF checks."),
        ('Optional development tool', 'Python CLI for regression tests and diagnostics. The web workbench and its deployment code are removed.')])
    card(xs[2], 122, width, 274, 'READER FOUNDATION', [
        ('PDF engine and shared hooks', f"PDF.js {reader['dependencies']['pdfjs-dist']}. Generic hooks expose document parts / DPM, associated files, metadata and collection membership."),
        ('Application layer', f"TypeScript {reader['devDependencies']['typescript']}; browser extensions. Full and Lite share maintained source; Lite is a reproducible standalone export."),
        ('Build tools only', f"Node {reader['engines']['node']}; npm {reader['engines']['npm']}; Playwright {reader['devDependencies']['playwright']}. No Java or Python needed by the extensions.")])

    text(40, 91, 'High-level overview: transitive libraries and some helpers are omitted. Rule counts are project definitions, not a claim of complete specification coverage.', 9)
    text(40, 73, 'Preserve original mailboxes separately. Validation has defined limits; PDF/UA machine checks do not replace human accessibility review.', 9)
    text(40, 43, 'Source and maintenance notes: docs/ARCHITECTURE.md  |  github.com/thinkbox-digital/eapdf', 9, color=blue)
    pdf.showPage(); pdf.save()


def main():
    version = (ROOT / 'VERSION').read_text().strip()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--creator-jar', type=Path, default=ROOT / 'dist' / f'emailtopdfa-{version}.jar')
    parser.add_argument('--validator-jar', type=Path, default=ROOT / 'dist' / f'eapdf-validator-{version}.jar')
    parser.add_argument('--output', type=Path, default=ROOT / 'docs/EA-PDF_Software_Architecture.pdf')
    args = parser.parse_args(); args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='eapdf-architecture-', dir='/tmp') as temporary:
        work = Path(temporary); layout = work / 'architecture.pdf'; build(layout)
        classpath = os.pathsep.join(str(p.resolve()) for p in (args.creator_jar, args.validator_jar))
        subprocess.run(['javac', '-cp', classpath, '-d', str(work), str(ROOT / 'scripts/manual/ManualPdf.java')], check=True)
        report = work / 'validation.json'
        subprocess.run(['java', '-Xmx512m', '-cp', str(work) + os.pathsep + classpath, 'eapdf.ManualPdf',
                        str(layout), str(args.output.resolve()), str(ROOT / 'src/main/resources/assets/sRGB.icc'), str(report)], check=True)
        assert json.loads(report.read_text())['status'] == 'PASS'


if __name__ == '__main__':
    main()
