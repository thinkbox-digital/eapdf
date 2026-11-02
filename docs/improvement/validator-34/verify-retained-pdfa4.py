"""The V34 crosswalk changes apply to PDF/A-3; inspect actual retained creator declarations."""
import hashlib,json
from pathlib import Path
from xml.etree import ElementTree as ET
import pikepdf
ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
prior=json.loads((ROOT/'docs/improvement/validator-33/retained-metadata-preconditions.json').read_text())
diagnosis=json.loads((ROOT/'docs/improvement/validator-24/profile-diagnosis.json').read_text())
rows=[]
for entry in prior['rows']:
    mode,profile=entry['profile'].split('-',1)
    rec=next(r for r in diagnosis['profiles'] if r['mode']==mode and r['profile']==profile)
    report=json.loads((ROOT/rec['report']).read_text());path=Path(report['summary']['file'])
    digest=hashlib.sha256(path.read_bytes()).hexdigest();assert digest==entry['sha256']
    with pikepdf.Pdf.open(path) as pdf:
        x=ET.fromstring(pdf.Root.Metadata.read_bytes())
        part=x.find('.//{http://www.aiim.org/pdfa/ns/id/}part').text
        assert part=='4'
        info=pdf.trailer.get('/Info')
        assert info is None or isinstance(info,pikepdf.Dictionary)
    rows.append(dict(profile=entry['profile'],sha256=digest,pdfa_part=part,changed_branch_inapplicable=True,
        reused_v29_pipeline_outcome=entry['reused_v29_pipeline_outcome']))
assert len(rows)==12
(HERE/'retained-pdfa4-preconditions.json').write_text(json.dumps(dict(
    boundary='Actual PDF/A-4 declarations inspected; changed crosswalk/applicability branches are inapplicable. Twelve exact V29 engine diagnoses remain applicable through V33; no new creator engine execution.',rows=rows),indent=2)+'\n')
print('All twelve actual creator outputs are PDF/A-4; V34 crosswalk changes are inapplicable; known failed diagnoses remain.')
