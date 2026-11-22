#!/usr/bin/env python3
"""Exercise the supplied release binaries and record their verified SHA-256 hashes."""

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile
from email.message import EmailMessage
from email.policy import SMTP

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
PROFILES = ('1s', '1si', '1m', '1mi', '1c', '1ci')
SUBJECTS = ('Release acceptance alpha', 'Release acceptance beta')
ATTACHMENT = 'release-attachment.txt'
PAYLOAD = b'EA-PDF release acceptance attachment\n'
MAIL_ID = '{http://www.pdfa.org/eapdf/ns/id/}'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def run(command, log, expected=0):
    print(log.stem, flush=True)
    with log.open('w') as stream:
        result = subprocess.run([str(arg) for arg in command], cwd=ROOT,
                                stdout=stream, stderr=subprocess.STDOUT, timeout=240)
    require(result.returncode == expected, f'{log.stem} exited {result.returncode}; see {log}')


def create_sources(directory):
    directory.mkdir()
    sources = []
    for index, subject in enumerate(SUBJECTS):
        message = EmailMessage(policy=SMTP)
        message['From'] = 'Sender <sender@example.org>'
        message['To'] = 'Recipient <recipient@example.org>'
        message['Date'] = f'Tue, 10 Nov 2026 10:0{index}:00 +0000'
        message['Message-ID'] = f'<release-acceptance-{index}@example.org>'
        message['Subject'] = subject
        message.set_content(f'{subject} body for the packaged reader.')
        if index == 0:
            message.add_attachment(PAYLOAD, maintype='application', subtype='octet-stream', filename=ATTACHMENT)
            message.set_boundary('eapdf-release-acceptance')
        path = directory / f'message-{index}.eml'
        path.write_bytes(message.as_bytes())
        sources.append(path)
    return sources


def embedded(spec):
    variants = spec['/EF']
    return variants.get('/UF', variants.get('/F')).read_bytes()


def files(document):
    names = document.Root.get('/Names', {}).get('/EmbeddedFiles')
    return dict(pikepdf.NameTree(names).items()) if names is not None else {}


def members(document):
    return {name: spec for name, spec in files(document).items() if '/CI' in spec}


def check_document(document, profile):
    xmp = ET.fromstring(document.Root.Metadata.read_bytes())
    require(xmp.findtext(f'.//{MAIL_ID}conformance') == profile[1:], f'{profile}: EA-PDF declaration')
    require(all(key in document.Root for key in ('/StructTreeRoot', '/DPartRoot', '/Outlines')),
            f'{profile}: tagging, document parts or navigation missing')
    require(len(document.pages) > 0, f'{profile}: no pages')
    require(('/Collection' in document.Root) == profile.startswith('1c'), f'{profile}: unexpected collection catalog')


def check_message_files(document, isolated, expected_sources, has_attachment):
    specs = files(document).values()
    sources = [embedded(spec) for spec in specs if spec.get('/AFRelationship') == '/Source']
    require(sorted(sources) == sorted([] if isolated else expected_sources), 'Embedded source EML differs')
    attachments = [embedded(annotation['/FS']) for page in document.pages
                   for annotation in page.get('/Annots', [])
                   if annotation.get('/Subtype') == '/FileAttachment'
                   and str(annotation['/FS'].get('/UF', annotation['/FS'].get('/F'))) == ATTACHMENT]
    expected = [b'' if isolated else PAYLOAD] if has_attachment else []
    require(attachments == expected, f'Attachment annotation payload differs (isolated={isolated})')
    if isolated:
        forbidden = {PAYLOAD, *expected_sources}
        for obj in document.objects:
            if isinstance(obj, pikepdf.Dictionary) and '/EF' in obj:
                require(all(stream.read_bytes() not in forbidden for stream in obj.EF.values()),
                        'Original EML or native attachment bytes in isolated PDF')


def check_profile(path, profile, sources):
    isolated = profile.endswith('i')
    child_hashes = []
    with pikepdf.open(path) as document:
        check_document(document, profile)
        if profile.startswith('1c'):
            children = members(document)
            require(len(children) == len(sources), f'{profile}: incorrect collection child count')
            check_message_files(document, isolated, sources, False)
            found = set()
            for spec in children.values():
                child_bytes = embedded(spec)
                child_hashes.append(hashlib.sha256(child_bytes).hexdigest())
                with pikepdf.open(io.BytesIO(child_bytes)) as child:
                    check_document(child, '1si' if isolated else '1s')
                    metadata = child.Root.Metadata.read_bytes()
                    subjects = [index for index, subject in enumerate(SUBJECTS) if subject.encode() in metadata]
                    require(len(subjects) == 1, f'{profile}: child subject missing or ambiguous')
                    index = subjects[0]
                    found.add(index)
                    check_message_files(child, isolated, [sources[index]], index == 0)
            require(found == set(range(len(sources))), f'{profile}: collection children differ from input')
        else:
            check_message_files(document, isolated, sources, True)
    return child_hashes


def check_pdfa(report, location):
    pdfa = report['pdfa']
    require(pdfa['status'] == 'PASS' and pdfa['ran'] and pdfa['compliant'] and pdfa['flavour'] == '4f',
            f'{location}: veraPDF did not confirm PDF/A-4f')


def validate(jar, pdf, directory, expected='PASS'):
    before = sha256(pdf)
    report = directory / f'{pdf.stem}.json'
    run(['java', '-Xmx768m', '-cp', jar, 'eapdf.JavaValidation', pdf, report],
        directory / f'{pdf.stem}.validator.log', expected=0 if expected == 'PASS' else 2)
    require(sha256(pdf) == before, f'{pdf.name}: validator changed the PDF')
    result = json.loads(report.read_text())
    require(result['summary']['status'] == expected, f'{pdf.name}: expected {expected}; see {report}')
    if expected == 'PASS':
        check_pdfa(result, report)
    return result


def check_validator_parity(pdf, java_result, directory, collection=False):
    # Python runs its EA-PDF rules here. Collection verdicts also require child PDF/A runs.
    from validate_eapdf import validate as validate_python
    result = validate_python(str(pdf))
    summary = result.summary()
    evidence = directory / f'{pdf.stem}.python-validation.json'
    evidence.write_text(json.dumps({'summary': summary, 'items': result.items}, ensure_ascii=False) + '\n')
    java_status = java_result['summary']['status']
    if collection and summary['status'] == 'INCOMPLETE':
        require(any(item['code'] == 'COLLECTION_CHILD_VALIDATION' and item['level'] == 'WARN'
                    and (item.get('details') or {}).get('pdfa_status') == 'SKIP' for item in result.items),
                f'{pdf.name}: Python’s incomplete collection result lacks a skipped child PDF/A assessment. See {evidence}')
    else:
        require(summary['status'] == java_status,
                f'{pdf.name}: Java and Python EA-PDF status differ. See {evidence}')
    codes = {'EAPDF_CONFORMANCE_VALUE', 'AF_IN_NAME_TREE'}
    if collection:
        codes.add('COLLECTION_CONTENT_TYPES')
    rank = ('N/A', 'PASS', 'WARN', 'FAIL')
    def strongest(items, code):
        return max((item['level'] for item in items if item['code'] == code), key=rank.index, default=None)
    for code in sorted(codes):
        python_level = strongest(result.items, code)
        require(python_level is not None and python_level == strongest(java_result['items'], code),
                f'{pdf.name}: Java and Python disagree on {code}. See {evidence}')


def check_child_reports(report, expected_hashes, pdf):
    results = [details for row in report['items']
               if row['code'] == 'COLLECTION_CHILD_VALIDATION'
               and isinstance(details := row.get('details'), dict) and 'result' in details]
    require(sorted(result['sha256'] for result in results) == sorted(expected_hashes),
            f'{pdf.name}: child reports do not cover the embedded PDFs')
    for result in results:
        child = result['result']
        require(child['summary']['status'] == 'PASS', f"{pdf.name}: invalid child {result['file']}")
        check_pdfa(child, f"{pdf.name}: {result['file']}")


def check_bad_collections(jar, seed, directory):
    for mutation in ('missing', 'invalid'):
        output = directory / f'{seed.stem}-{mutation}-child.pdf'
        with pikepdf.open(seed) as parent:
            children = members(parent)
            require(len(children) == 2, 'Negative controls require two starting children')
            name, spec = next(iter(children.items()))
            if mutation == 'missing':
                names = pikepdf.NameTree(parent.Root.Names.EmbeddedFiles)
                del names[name]
                parent.Root.AF = pikepdf.Array([item for item in parent.Root.get('/AF', []) if item.objgen != spec.objgen])
            else:
                with pikepdf.open(io.BytesIO(embedded(spec))) as child:
                    xmp = child.Root.Metadata.read_bytes()
                    profile = b'si' if seed.stem == '1ci' else b's'
                    changed = xmp.replace(b'<pdfmailid:conformance>' + profile + b'</pdfmailid:conformance>',
                                          b'<pdfmailid:conformance>x</pdfmailid:conformance>')
                    require(changed != xmp, 'Negative control did not change the child profile')
                    child.Root.Metadata.write(changed)
                    buffer = io.BytesIO()
                    child.save(buffer)
                data = buffer.getvalue()
                for stream in {stream.objgen: stream for stream in spec.EF.values()}.values():
                    stream.write(data)
                    stream.Params.Size = len(data)
                    stream.Params.CheckSum = pikepdf.String(hashlib.md5(data).digest())
                if '/Size' in spec.CI:
                    spec.CI.Size = len(data)
            parent.save(output)
        result = validate(jar, output, directory, 'FAIL')
        check_validator_parity(output, result, directory, collection=True)
        require(result['pdfa']['status'] == 'PASS', f'{output.name}: mutation broke outer PDF/A')
        require(any(item['level'] == 'FAIL' and item['code'] == 'COLLECTION_CHILD_VALIDATION'
                    for item in result['items']), f'{output.name}: no child validation failure')


def extract_viewer(archive, destination):
    with zipfile.ZipFile(archive) as package:
        require(package.testzip() is None, f'{archive.name}: ZIP integrity failure')
        names = package.namelist()
        require(len(names) == len(set(names)) and all(
            not Path(name).is_absolute() and '..' not in Path(name).parts and '\\' not in name for name in names
        ), f'{archive.name}: unsafe or duplicate ZIP entry')
        package.extractall(destination)
    info = json.loads((destination / 'build-info.json').read_text())
    for name, digest in info['files'].items():
        path = (destination / name).resolve()
        require(path.is_relative_to(destination) and sha256(path) == digest, f'{archive.name}: {name} differs')


def check_desktop(classpath, java, label, output, manifest, javafx_lib=None):
    classes = output / f'{label}-smoke-classes'
    javafx_options = []
    if javafx_lib:
        javafx_options = [
            '--module-path', javafx_lib,
            '--add-modules', 'javafx.controls,javafx.swing',
            '--add-exports', 'javafx.graphics/com.sun.javafx.application=ALL-UNNAMED',
            '--add-exports', 'javafx.graphics/com.sun.javafx.stage=ALL-UNNAMED',
            '--add-exports', 'javafx.graphics/com.sun.javafx.tk=ALL-UNNAMED',
        ]
    run(['javac', *javafx_options, '-cp', classpath, '-d', classes,
         ROOT / 'desktop-viewer/ReleaseReaderSmoke.java'], output / f'{label}-compile.log')
    run([java, *javafx_options, '-Deapdf.viewer.settings=' + str(output / f'{label}-window.properties'),
         '-cp', f'{classpath}:{classes}', 'eapdf.viewer.ReleaseReaderSmokeLauncher', manifest],
        output / f'{label}.log')


def check_preservation(creator, validator, fixture, sources, output):
    directory = output / 'preservation'
    directory.mkdir()
    disposable = [directory/source.name for source in sources]
    for source, copy in zip(sources, disposable):
        shutil.copyfile(source, copy)
    before = [sha256(source) for source in disposable]
    run(['java', '-jar', creator, '--out', directory/'creator', '--jobs', '1',
         '--archive-profile', 'm', '--archive-output', disposable[0], *disposable],
        directory/'creator-input-collision.log', expected=2)
    require([sha256(source) for source in disposable] == before, 'Creator changed an input EML')

    command = ['java', '-Xmx768m', '-cp', validator, 'eapdf.JavaValidation']
    report = directory/'report.json'
    for kind in ('summary', 'progress'):
        colliding = directory/f'report.json.{kind}.json'
        shutil.copyfile(fixture, colliding)
        before = sha256(colliding)
        run([*command, colliding, report], directory/f'{kind}-collision.log', expected=1)
        require(sha256(colliding) == before and not report.exists(),
                f'Validator wrote over a {kind} collision')
        colliding.unlink()

    checked = directory/'checked.pdf'
    shutil.copyfile(fixture, checked)
    run([*command, checked, report], directory/'valid.log')
    prior = json.loads(report.read_text())['summary']
    require(prior['status'] == 'PASS', 'Stale-report control must start with a genuine PASS')
    checked.rename(directory/'original.pdf')
    run([*command, checked, report], directory/'missing-input.log', expected=1)
    result = json.loads(report.read_text())['summary']
    require(result['status'] == 'INCOMPLETE' and result['run_id'] != prior['run_id'],
            'Missing input retained a prior validation result')


def check_jar_window(jar, title, output, pdf=None):
    """Launch the JAR manifest entry point on the runner's isolated X display."""
    command = ['java', '-Deapdf.viewer.settings='+str(output/'launcher.properties'), '-jar', str(jar)]
    if pdf:
        command.append(str(pdf))
    pattern = re.compile(title)
    with (output/(jar.stem+'.launch.log')).open('w') as log:
        with subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT) as process:
            try:
                deadline = time.monotonic() + 25
                while time.monotonic() < deadline and process.poll() is None:
                    windows = subprocess.check_output(['xwininfo', '-root', '-tree'], text=True, timeout=5)
                    if pattern.search(windows):
                        return
                    time.sleep(0.3)
                raise ValueError(f'{jar.name}: expected window did not open; see {log.name}')
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=8)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()


def check_swing_gui(jar, source, main_class, arguments, output, label):
    classes = output / f'{label}-smoke-classes'
    scratch = output / f'{label}-scratch'
    scratch.mkdir()
    run(['javac', '-cp', jar, '-d', classes, ROOT / source], output / f'{label}-compile.log')
    run(['java', '-Xmx768m', f'-Djava.io.tmpdir={scratch}', f'-Duser.home={scratch}',
         '-cp', os.pathsep.join(map(str, (jar, classes))), main_class, *arguments],
        output / f'{label}.log')


def check_creator_gui(creator_gui, source, validator, output):
    with zipfile.ZipFile(creator_gui) as archive:
        manifest = archive.read('META-INF/MANIFEST.MF').decode('utf-8').splitlines()
    require('Main-Class: eapdf.EapdfGui' in manifest, f'{creator_gui.name}: wrong GUI entry point')
    evidence = output / 'creator-gui-conversion'
    check_swing_gui(creator_gui, 'src/test/java/eapdf/CreatorDesktopSmoke.java', 'eapdf.CreatorDesktopSmoke',
                    [source, output / 'creator-gui-work', evidence], output, 'creator-gui')
    pdf = evidence / 'eml_gui_1s.pdf'
    require(pdf.is_file(), 'Creator GUI did not produce a PDF')
    check_profile(pdf, '1s', [source.read_bytes()])
    validate(validator, pdf, evidence)


def main():
    sys.path.insert(0, str(ROOT))
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--creator', required=True, type=Path)
    parser.add_argument('--validator', required=True, type=Path)
    parser.add_argument('--viewer', required=True, action='append', type=Path, help='Packaged Chromium, Firefox or web ZIP')
    parser.add_argument('--desktop-viewer', type=Path, help='Packaged PDFViewFX standalone JAR')
    parser.add_argument('--creator-gui', type=Path)
    parser.add_argument('--validator-gui', action='store_true')
    parser.add_argument('--security-validator', type=Path)
    parser.add_argument('--auxiliary', type=Path, action='append', default=[])
    parser.add_argument('--output', required=True, type=Path, help='New directory for acceptance evidence')
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True)
    creator, validator = args.creator.resolve(), args.validator.resolve()
    viewers = [path.resolve() for path in args.viewer]
    packages = [creator, validator, *viewers, *args.auxiliary]
    packages += [path.resolve() for path in (args.creator_gui, args.security_validator) if path]
    javafx_lib = None
    if args.desktop_viewer:
        desktop_jar = args.desktop_viewer.resolve()
        packages.append(desktop_jar)
        with zipfile.ZipFile(desktop_jar) as package:
            if 'javafx/application/Application.class' not in package.namelist():
                javafx_lib = os.environ.get('JAVAFX_LIB')
                require(javafx_lib, 'Set JAVAFX_LIB to the JavaFX SDK lib directory for the desktop viewer')
                javafx_lib = Path(javafx_lib).resolve()
                require(javafx_lib.is_dir(), f'JavaFX lib directory does not exist: {javafx_lib}')
    require(len({path.name for path in packages}) == len(packages), 'Package basenames must be unique')
    hashes = {path.name: sha256(path) for path in packages}
    sources = create_sources(output / 'sources')
    (output / ATTACHMENT).write_bytes(PAYLOAD)
    documents = {}
    for profile in PROFILES:
        directory = output / profile
        directory.mkdir()
        command = ['java', '-Xmx768m', '-jar', creator, '--out', directory, '--jobs', '1']
        selected = sources[:1] if profile.startswith('1s') else sources
        if profile == '1si':
            command.append('--omit-source')
        if len(selected) > 1:
            command.extend(['--archive-profile', profile[1:], '--archive-output', directory / f'{profile}.pdf'])
        run([*command, selected[0] if len(selected) == 1 else sources[0].parent], directory / 'creator.log')
        pdfs = list(directory.glob('*.pdf'))
        require(len(pdfs) == 1, f'{profile}: expected one generated PDF')
        pdf = pdfs[0]
        child_hashes = check_profile(pdf, profile, [path.read_bytes() for path in selected])
        report = validate(validator, pdf, directory)
        check_validator_parity(pdf, report, directory, collection=profile in ('1c', '1ci'))
        check_child_reports(report, child_hashes, pdf)
        documents[profile] = {'path': str(pdf), 'sha256': sha256(pdf)}
    for profile in ('1c', '1ci'):
        check_bad_collections(validator, Path(documents[profile]['path']), output)
    manifest = output / 'documents.json'
    manifest.write_text(json.dumps({'documents': documents, 'subjects': SUBJECTS, 'attachment': ATTACHMENT}) + '\n')
    for archive in viewers:
        destination = output / archive.stem
        extract_viewer(archive, destination)
        browser = archive.stem.rsplit('-', 1)[-1]
        require(browser in {'chromium', 'firefox', 'web'}, f'Unknown viewer package type: {archive.name}')
        run(['node', ROOT / 'browser-viewer/scripts/release-reader-smoke.mjs', browser, destination, manifest],
            output / f'{archive.stem}.log')
    if args.desktop_viewer:
        check_desktop(str(args.desktop_viewer.resolve()), 'java', 'desktop-viewer', output, manifest, javafx_lib)
    first_pdf = Path(documents['1s']['path'])
    check_preservation(creator, validator, first_pdf, sources, output)
    if args.desktop_viewer:
        check_jar_window(args.desktop_viewer.resolve(), r'EA-PDF Desktop Viewer', output, first_pdf)
    if args.creator_gui:
        check_creator_gui(args.creator_gui.resolve(), sources[0], validator, output)
    if args.validator_gui:
        check_swing_gui(validator, 'scripts/java/eapdf/ReleaseValidatorGuiSmoke.java', 'eapdf.ReleaseValidatorGuiSmoke',
                        [validator, first_pdf, output / 'validator-gui-report.json'], output, 'validator-gui')
    if args.security_validator:
        security_jar = args.security_validator.resolve()
        check_jar_window(security_jar, r'EA-PDF Security Validator Experimental', output)
        security_report = output / 'unsigned-security.json'
        run(['java', '-cp', security_jar, 'eapdf.SecurityValidation', first_pdf, security_report],
            output / 'unsigned-security.log', expected=2)
        require(json.loads(security_report.read_text())['status'] == 'NOT_APPLICABLE',
                'Unsigned security fixture must not verify')
    require(all(sha256(Path(document['path'])) == document['sha256'] for document in documents.values()),
            'Reader changed the validated PDF')
    require(all(sha256(path) == hashes[path.name] for path in packages), 'A release package changed during verification')
    (output / 'SHA256SUMS').write_text(''.join(f'{digest}  {name}\n' for name, digest in sorted(hashes.items())))
    print(f'Release package acceptance passed. Evidence: {output}')


if __name__ == '__main__':
    main()
