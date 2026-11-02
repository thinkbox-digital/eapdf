"""Fill the concrete no-font-object witness gap across the five profiles."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

import pikepdf

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SNAPSHOT = ROOT / '.eapdf-verification-v25'
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT), GOMAXPROCS='2')
sys.path.insert(0, str(SNAPSHOT))
import validate_eapdf as v

output = HERE / 'font-controls'
output.mkdir(exist_ok=True)
baselines = json.loads((ROOT / 'tests/fixtures/eapdf/upstream-profile-controls/cases.json').read_text())['cases']
source = ROOT / 'examples/sample_1s.pdf'
rows = []
for case in baselines:
    if not case['file'].endswith('-valid.pdf'):
        continue
    baseline = ROOT / 'tests/fixtures/eapdf/upstream-profile-controls' / case['file']
    for missing in (False, True):
        path = output / case['file'].replace('-valid', '-font-missing' if missing else '-font-valid')
        with pikepdf.Pdf.open(baseline) as pdf, pikepdf.Pdf.open(source) as font_source:
            original = font_source.pages[0].Resources.Font.F1
            ranges = re.findall(rb'<([0-9a-fA-F]+)>\s*<([0-9a-fA-F]+)>\s*<([0-9a-fA-F]+)>', original.ToUnicode.read_bytes())
            cid = next(int(first, 16) + 0x41 - int(unicode_first, 16) for first, last, unicode_first in ranges
                       if int(unicode_first, 16) <= 0x41 <= int(unicode_first, 16) + int(last, 16) - int(first, 16))
            font = pdf.copy_foreign(original)
            pdf.pages[0].Resources.Font = pikepdf.Dictionary(ControlFont=font)
            pdf.pages[0].Contents.write(f'/P <</MCID 0>> BDC BT /ControlFont 12 Tf 20 20 Td <{cid:04X}> Tj ET EMC'.encode())
            if missing:
                descriptor = font.DescendantFonts[0].FontDescriptor
                assert '/FontFile2' in descriptor
                del descriptor.FontFile2
            pdf.save(path, static_id=True)
        result = v.run_verapdf(str(path))
        (output / (path.stem + '.json')).write_text(json.dumps(result, indent=2) + '\n')
        failures = [r for r in result['rule_summaries'] if r.get('status') == 'failed']
        expected_clause = '6.2.11.4.1' if case['profile'].startswith('PDF/A-3') else '6.2.10.4.1'
        assert result['ran'] and result['profile_name'] == case['profile'] and result['job_end_status'] == 'normal'
        assert result['status'] == ('FAIL' if missing else 'PASS'), (path.name, result['status'], failures)
        if missing:
            assert any(r['clause'] == expected_clause and r['testNumber'] == 1 and r['object'] == 'PDFont' for r in failures), failures
        row = dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                   profile=case['profile'], status=result['status'], missing_font=missing, failures=failures)
        if not missing:
            process = subprocess.run(v._verapdf_command(path, 'json') + ['--success'], capture_output=True, text=True, timeout=180)
            assert process.returncode == 0, process.stderr
            payload = json.loads(process.stdout[process.stdout.index('{'):])
            (output / (path.stem + '-success.json')).write_text(json.dumps(payload, indent=2) + '\n')
            validation = payload['report']['jobs'][0]['validationResult'][0]
            assert validation['compliant'] and validation['profileName'] == case['profile']
            font_witnesses = [r for r in validation['details']['ruleSummaries']
                              if r.get('passedChecks', 0) > 0 and r.get('object') in ('PDFont', 'PDType0Font', 'PDCIDFont', 'TrueTypeFontProgram')]
            assert font_witnesses
            row['font_witnesses'] = font_witnesses
        rows.append(row)
        (output / 'results.json').write_text(json.dumps(rows, indent=2) + '\n')
        print(path.name, case['profile'], result['status'], flush=True)
assert len(rows) == 10
evidence = dict(scope='Five actual font embedding controls and five missing-font violations; no font-format or rendering completeness claim',
                runtime_snapshot='.eapdf-verification-v25', font_source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(), cases=rows)
(output / 'evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
print('All five profiles have actual font witnesses and proved missing-embedding failures.', flush=True)
