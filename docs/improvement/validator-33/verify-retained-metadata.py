"""Inspect native metadata to bound reuse of twelve known creator diagnoses."""
from datetime import datetime
import hashlib,json
from pathlib import Path
from xml.etree import ElementTree as ET
import pikepdf
ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
NS=dict(rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#',dc='http://purl.org/dc/elements/1.1/',pdf='http://ns.adobe.com/pdf/1.3/',xmp='http://ns.adobe.com/xap/1.0/')
prior=json.loads((ROOT/'docs/improvement/validator-32/retained-title-preconditions.json').read_text())
diagnosis=json.loads((ROOT/'docs/improvement/validator-24/profile-diagnosis.json').read_text())
rows=[]
for entry in prior['rows']:
    mode,profile=entry['profile'].split('-',1)
    record=next(r for r in diagnosis['profiles'] if r['mode']==mode and r['profile']==profile)
    report=json.loads((ROOT/record['report']).read_text())
    path=Path(report['summary']['file']);digest=hashlib.sha256(path.read_bytes()).hexdigest()
    assert digest==entry['sha256']
    with pikepdf.Pdf.open(path) as pdf:
        x=ET.fromstring(pdf.Root.Metadata.read_bytes())
        creators=x.findall('.//dc:creator',NS);producers=x.findall('.//pdf:Producer',NS);dates=x.findall('.//xmp:CreateDate',NS)
        assert len(creators)==len(producers)==len(dates)==1
        seq=creators[0].find('rdf:Seq',NS)
        assert seq is not None and len(seq) and (seq[0].text or '').strip()
        assert all(not len(n) and n.get('{'+NS['rdf']+'}resource') is None for n in seq)
        assert not len(producers[0]) and producers[0].get('{'+NS['rdf']+'}resource') is None and (producers[0].text or '').strip()
        assert not len(dates[0]) and dates[0].get('{'+NS['rdf']+'}resource') is None
        datetime.fromisoformat(dates[0].text.replace('Z','+00:00'))
        observed=dict(creators=[n.text for n in seq],producer=producers[0].text,creation_date=dates[0].text)
    rows.append(dict(profile=entry['profile'],sha256=digest,native_metadata=observed,added_guards=True,
        reused_v29_pipeline_outcome=entry['reused_v29_pipeline_outcome']))
assert len(rows)==12
(HERE/'retained-metadata-preconditions.json').write_text(json.dumps(dict(
    boundary='Actual canonical creator Seq, literal Producer and parseable literal CreateDate inspected. Twelve exact V29 engine diagnoses remain applicable via V32; no new creator engine execution.',rows=rows),indent=2)+'\n')
print('Twelve retained creator inputs satisfy all added required-metadata guards; known failed conformance remains.')
