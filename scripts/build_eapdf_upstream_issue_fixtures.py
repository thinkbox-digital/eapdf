"""Freeze probes for upstream issue 22 and published AF/MCAF erratum 374."""
import hashlib
import json
from pathlib import Path
import shutil
import sys
from datetime import timedelta, timezone
from xml.etree import ElementTree as ET

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import validate_eapdf as v


def main():
    source = ROOT / 'tests/fixtures/eapdf/validator-table11/valid.pdf'
    output = ROOT / 'tests/fixtures/eapdf/upstream-issues'
    output.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, output / 'valid.pdf')
    cases = [dict(file='valid.pdf', expected_failures=[])]
    for mode in ('createdate-equal', 'createdate-earlier', 'createdate-later', 'mcaf-listed', 'mcaf-unlisted'):
        with pikepdf.Pdf.open(source) as pdf:
            if mode.startswith('createdate'):
                root, nsmap, _ = v.parse_xmp(v.read_metadata_stream(pdf), v.Report('parse'))
                for prefix, uri in nsmap.items():
                    ET.register_namespace(prefix, uri)
                _, afiles = v.check_embedded_files(pdf, root, 's', 4, v.Report('parse'))
                summary = v.summarize_source_email(afiles)
                assert summary and summary.get('sent_norm')
                dates = {'createdate-equal': summary['sent_norm'],
                         'createdate-earlier': '1900-01-01T00:00:00Z',
                         'createdate-later': (v.parse_xmp_datetime(summary['sent_norm']) + timedelta(days=1)).astimezone(timezone.utc).isoformat()}
                root.find('.//' + v.qn(v.NS_XMP, 'CreateDate')).text = dates[mode]
                text = ET.tostring(root, encoding='utf-8')
                for prefix, uri in (('pdfmail', v.NS_PDFMAIL), ('pdfmailid', v.NS_PDFMAILID),
                                    ('pdfmailmeta', v.NS_PDFMAILMETA)):
                    if ('xmlns:' + prefix + '=').encode() not in text:
                        root.set('xmlns:' + prefix, uri)
                packet = b'<?xpacket begin="\xef\xbb\xbf" id="W5M0MpCehiHzreSzNTczkc9d"?>\n'
                packet += ET.tostring(root, encoding='utf-8') + b'\n<?xpacket end="w"?>'
                pdf.Root.Metadata.write(packet)
            else:
                page = pdf.pages[0]
                if mode == 'mcaf-listed':
                    spec = next(spec for spec in pdf.Root.AF if spec.AFRelationship == pikepdf.Name.Source)
                else:
                    data = b'AF evidence absent from EmbeddedFiles name tree'
                    stream = pdf.make_stream(data)
                    stream.Type = pikepdf.Name.EmbeddedFile
                    stream.Subtype = pikepdf.Name('/text/plain')
                    stream.Params = pikepdf.Dictionary(Size=len(data), CheckSum=pikepdf.String(hashlib.md5(data).digest()),
                                                      ModDate="D:20261019000000Z")
                    spec = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Filespec,
                        F='mcaf-evidence.txt', UF='mcaf-evidence.txt', AFRelationship=pikepdf.Name.Data,
                        EF=pikepdf.Dictionary(F=stream, UF=stream)))
                properties = page.Resources.get('/Properties')
                if properties is None:
                    page.Resources.Properties = pikepdf.Dictionary()
                page.Resources.Properties.UpstreamAF = pikepdf.Dictionary(MCAF=pikepdf.Array([spec]))
                contents = page.obj.get('/Contents')
                streams = list(contents) if isinstance(contents, pikepdf.Array) else [contents]
                page.Contents = pdf.make_stream(b'/AF /UpstreamAF BDC\n' + b'\n'.join(s.read_bytes() for s in streams) + b'\nEMC\n')
            filename = mode + '.pdf'
            pdf.save(output / filename, fix_metadata_version=False)
            cases.append(dict(file=filename, expected_failures=['AF_IN_NAME_TREE'] if mode == 'mcaf-unlisted' else []))
    for case in cases:
        case['sha256'] = hashlib.sha256((output / case['file']).read_bytes()).hexdigest()
        case['expected_custom_status'] = 'FAIL' if case['expected_failures'] else 'PASS'
    (output / 'expectations.json').write_text(json.dumps(dict(
        authority=['EA-PDF 9.8; report-an-issue 22; no provenance inference from Sent alone',
                   'ISO 32000-2 erratum 374; EA-PDF 9.4 AF name-tree correspondence'],
        source=str(source.relative_to(ROOT)), source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
        cases=cases), indent=2) + '\n')
    print('Wrote', len(cases), 'probes to', output)


if __name__ == '__main__':
    main()
