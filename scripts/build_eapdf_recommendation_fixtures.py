"""Exercise recommendation evidence and extraction faults on exact creator PDFs."""
from contextlib import nullcontext
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from unittest.mock import patch

from build_eapdf_rule_fixtures import fixture_variant

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
import validate_eapdf as validator

SOURCE_CODES={
    'UNSPECIFIED_COLOR_SRGB_ASSUMED','REFERENCED_ASSETS_AS_ASSOCIATED_FILES',
    'REFERENCED_ASSETS_ASSOCIATED_TO_APPROPRIATE_OBJECT','LOGICAL_STRUCTURE_EQUIVALENCE_RECOMMENDED',
}
PAGE_CODES={
    'PAGE_CONTEXT_PRESENT_RECOMMENDED','PAGE_CONTEXT_TEXT_OBJECTS_RECOMMENDED',
    'ACTIONABLE_URLS_LINK_ANNOTATIONS',
}


def main():
    output=Path(os.environ.get('EAPDF_RECOMMENDATION_FIXTURE_DIR',ROOT/'docs/improvement/batch-06-corpus')).resolve()
    output.mkdir(parents=True,exist_ok=True)
    fixtures=ROOT/'tests/fixtures/eapdf/batch06'
    config=output/'config.json'
    config.write_text(json.dumps(dict(branding=dict(enabled=False),premis=dict(enabled=False))))
    command=['java','-Djava.awt.headless=true','-Xmx256m','-cp',os.environ['EAPDF_GENERATOR_CLASSPATH'],
             'eapdf.EmailToPDFA','--out',str(output/'seed'),str(fixtures/'source.eml')]
    process=subprocess.run(command,cwd=ROOT,env=dict(os.environ,EAPDF_CONFIG=str(config)),
                           capture_output=True,text=True,timeout=90)
    (output/'generation.log').write_text(process.stdout+process.stderr)
    assert process.returncode==0,process.stdout+process.stderr
    seeds=list((output/'seed').glob('*.pdf'))
    assert len(seeds)==1,seeds
    for form in ('tagged','untagged'):
        fixture_variant(seeds[0],output/(form+'.pdf'),
                        dict(array_form=False,mutation=None,untagged=form=='untagged'))
    results=[]
    for case in json.loads((fixtures/'cases.json').read_text()):
        pdf=output/case['pdf']
        before=hashlib.sha256(pdf.read_bytes()).hexdigest()
        fault=case['fault']
        context=(patch.object(validator,'collect_page_text_analysis',side_effect=RuntimeError('Batch 06 extractor fault'))
                 if fault=='exception' else patch.object(validator,'collect_page_text_analysis',return_value=[])
                 if fault=='empty-analysis' else nullcontext())
        with context:
            report=validator.validate_one(str(pdf),html_path=str(output/(case['id']+'.html')))
        (output/(case['id']+'.json')).write_text(json.dumps(report,default=str,indent=2)+'\n')
        assert report['pdfa']['status']=='PASS',(case['id'],report['pdfa'])
        assert report['summary']['status']==case['expected_eapdf'],(case['id'],report['summary'])
        assert not any(item['level']=='FAIL' for item in report['items']),case['id']
        source_rows=[item for item in report['items'] if item['code'] in SOURCE_CODES]
        assert {item['code'] for item in source_rows}==SOURCE_CODES
        assert all(item['level']=='WARN' and item['details']['assessment']=='unavailable' for item in source_rows),source_rows
        page_rows=[item for item in report['items'] if item['code'] in PAGE_CODES]
        assert {item['code'] for item in page_rows}==PAGE_CODES
        if fault!='none':
            assert all(item['level']=='WARN' and item['details']['state']=='unverified' for item in page_rows),page_rows
            assert any(item['code']=='VALIDATION_INCOMPLETE' and item['level']=='WARN' for item in report['items'])
        else:
            assert not any(isinstance(item.get('details'),dict) and item['details'].get('state')=='unverified'
                           for item in report['items']),case['id']
        assert hashlib.sha256(pdf.read_bytes()).hexdigest()==before,case['id']
        results.append(dict(case=case['id'],pdf=case['pdf'],sha256=before,fault=fault,
                            pdfa=report['pdfa']['status'],eapdf=report['summary']['status'],
                            recommendations={item['code']:item['level'] for item in source_rows+page_rows},
                            expected_result_observed=True))
        print(case['id'],'PDF/A',report['pdfa']['status'],'EA-PDF',report['summary']['status'],flush=True)
    (output/'results.json').write_text(json.dumps(results,indent=2)+'\n')


if __name__=='__main__':
    main()
