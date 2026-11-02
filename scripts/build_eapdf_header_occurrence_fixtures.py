"""Ordered source-header evidence with independently declared expected outcomes."""
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import subprocess

from build_eapdf_header_inventory_fixtures import ROOT, variant, validator


def main():
    output=Path(os.environ.get('EAPDF_HEADER_OCCURRENCE_FIXTURE_DIR',ROOT/'docs/improvement/batch-09-corpus')).resolve()
    output.mkdir(parents=True,exist_ok=True)
    inputs=ROOT/'tests/fixtures/eapdf/batch09'
    cases=json.loads((inputs/'cases.json').read_text())
    config=output/'config.json'
    config.write_text(json.dumps(dict(branding=dict(enabled=False),premis=dict(enabled=False),findingAid=dict(pdfEnabled=False))))
    seeds={}
    for kind,profile in sorted({(case['source'],case['profile']) for case in cases}):
        path=output/'seed'/kind/profile/'archive.pdf'
        command=['java','-Djava.awt.headless=true','-Xmx256m','-cp',os.environ['EAPDF_GENERATOR_CLASSPATH'],
                 'eapdf.EmailToPDFA','--out',str(path.parent),'--archive-profile',profile,'--archive-output',str(path),str(inputs/kind)]
        process=subprocess.run(command,cwd=ROOT,env=dict(os.environ,EAPDF_CONFIG=str(config)),capture_output=True,text=True,timeout=90)
        (output/f'generation-{kind}-{profile}.log').write_text(process.stdout+process.stderr)
        assert process.returncode==0,process.stdout+process.stderr
        seeds[kind,profile]=path
    results=[]
    for case in cases:
        pdf=output/(case['id']+'.pdf')
        guids=variant(seeds[case['source'],case['profile']],pdf,case)
        report=validator.validate_one(str(pdf),html_path=str(output/(case['id']+'.html')))
        (output/(case['id']+'.json')).write_text(json.dumps(report,indent=2,default=str)+'\n')
        assert report['pdfa']['status']=='PASS',(case['id'],report['pdfa'])
        failures={row['code'] for row in report['items'] if row['level']=='FAIL'}
        assert failures==set(case['failures']),(case['id'],failures)
        assert report['summary']['status']==('FAIL' if failures else 'PASS'),(case['id'],report['summary'])
        row=next(row for row in report['items'] if row['code']=='HEADER_FIELDS_TAGGED_RECOMMENDED')
        assert row['level']=='WARN' and row['details']['assessment']=='partial',row
        observed=row['details']['rendered_header_inventory']
        counts=dict(Counter(item['comparison'] for item in observed))
        assert counts==case['counts'],(case['id'],counts,observed)
        sources=row['details']['source_header_inventory']
        assert len(sources)==2,(case['id'],sources)
        occurrences=[item for source in sources for item in source['occurrences']]
        assert len(occurrences)==case['occurrences'],(case['id'],len(occurrences))
        assert sum(len(item['matched_fields']) for item in occurrences)==case['matched_fields'],case['id']
        for source in sources:
            if case.get('expected_source_assessment')=='unavailable' or case['profile']=='mi':
                assert source['assessment']=='unavailable' and source['occurrences']==[],source
                assert source.get('reason'),source
                continue
            assert source['assessment']=='available' and source['parse_defects']==[],source
            # Independently split these simple CRLF fixtures; preserve names, order and raw values.
            raw=next(path.read_bytes() for path in sorted((inputs/case['source']).glob('*.eml'))
                     if hashlib.sha256(path.read_bytes()).hexdigest()==source['source_sha256'])
            lines=raw.split(b'\r\n\r\n',1)[0].split(b'\r\n')
            expected=[(name.decode(),value.lstrip(b' ').decode()) for name,value in (line.split(b':',1) for line in lines)]
            assert expected==[(item['name'],item['raw']) for item in source['occurrences']],source
            assert list(range(1,len(lines)+1))==[item['index'] for item in source['occurrences']],source
            # Extra X-Project headers are source evidence; no claim they were rendered or omitted.
            projects=[item for item in source['occurrences'] if item['header']=='x-project']
            assert [1,2]==[item['occurrence'] for item in projects],source
            assert all(not item['matched_fields'] and not item['candidate_fields'] for item in projects),source
        subjects=[item for item in observed if item.get('source_header')=='subject']
        source_available=case.get('expected_source_assessment')!='unavailable' and case['profile']!='mi'
        if source_available and case['source']=='distinct' and case['mutation']!='swap-values':
            assert len(subjects)==2 and all(item['source_occurrence']==1 for item in subjects),subjects
        if source_available and case['source']=='identical' and case['profile']=='m':
            assert len(subjects)==2 and all(item['candidate_occurrences']==[1,2] and 'source_occurrence' not in item for item in subjects),subjects
        if source_available and case['mutation']=='swap-values':
            assert len(subjects)==2 and all(item['ownership']=='outside-owner-ranges' and not item['candidate_occurrences'] for item in subjects),subjects
        if case['profile']=='mi':
            assert len(observed)==10 and all(item.get('name') and item.get('value') and item.get('pages') for item in observed),observed
        unverified={item['code'] for item in report['items']
                    if isinstance(item.get('details'),dict) and item['details'].get('state')=='unverified'}
        assert unverified==set(case.get('expected_unverified',[])),(case['id'],unverified)
        results.append(dict(case=case['id'],sha256=hashlib.sha256(pdf.read_bytes()).hexdigest(),pdfa='PASS',
                            eapdf=report['summary']['status'],failures=sorted(failures),observed_comparisons=counts,
                            source_occurrences=len(occurrences),matched_fields=case['matched_fields'],oracle_guids=guids,
                            expected_result_observed=True))
        print(case['id'],'PDF/A PASS','EA-PDF',report['summary']['status'],counts,'source occurrences',len(occurrences),flush=True)
    (output/'results.json').write_text(json.dumps(results,indent=2)+'\n')


if __name__=='__main__':main()
