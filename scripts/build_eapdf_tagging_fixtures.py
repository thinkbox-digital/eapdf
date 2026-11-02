"""Generate independent mutations for conditional tagging recommendations."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

import pikepdf
from build_eapdf_rule_fixtures import fixture_variant, structure_elements

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
import validate_eapdf as validator

CONTENT='CUSTOM_CONTENTSETS_TAGGEDPDF_RECOMMENDED'
HEADERS='HEADER_FIELDS_TAGGED_RECOMMENDED'


def variant(seed,output,case):
    fixture_variant(seed,output,dict(array_form=False,mutation=None,untagged=case['mutation']=='untagged'))
    with pikepdf.Pdf.open(output,allow_overwriting_input=True) as pdf:
        elements=list(structure_elements(pdf)) if '/StructTreeRoot' in pdf.Root else []
        if case['custom']:
            pending=[pdf.Root.DPartRoot.DPartRootNode]
            while pending:
                node=pending.pop()
                if isinstance(node,pikepdf.Array):pending.extend(node);continue
                if '/Start' in node:
                    node.DPM.Mail_ContentSetType=pikepdf.Name.FixtureCustom
                pending.extend(node.get('/DParts',[]))
            for element in elements:
                attrs=element.get('/A',[])
                for attribute in attrs if isinstance(attrs,pikepdf.Array) else [attrs]:
                    if isinstance(attribute,pikepdf.Dictionary) and attribute.get('/O')==pikepdf.Name.UserProperties:
                        for prop in attribute.P:
                            if str(prop.N)=='Mail_ContentSetType':prop.V='FixtureCustom'
        if case['mutation']=='unmarked-paint':
            page=pdf.pages[0]
            streams=page.obj.get('/Contents')
            streams=list(streams) if isinstance(streams,pikepdf.Array) else [streams]
            page.Contents=pikepdf.Array(streams+[pdf.make_stream(b'q 0 g 0 0 1 1 re f Q')])
        elif case['mutation']=='generic-field':
            field=next(element for element in elements if str(element.S)=='/Mail_Field')
            field.S=pikepdf.Name.TR
            children=field.K if isinstance(field.K,pikepdf.Array) else [field.K]
            for child in children:
                if isinstance(child,pikepdf.Dictionary) and str(child.get('/S')) in ('/Mail_FieldName','/Mail_FieldValue'):
                    child.S=pikepdf.Name.TD
        elif case['mutation']=='values-only':
            for element in elements:
                if str(element.S)=='/Mail_FieldName':element.S=pikepdf.Name.TD
        pdf.save(output)


def main():
    output=Path(os.environ.get('EAPDF_TAGGING_FIXTURE_DIR',ROOT/'docs/improvement/batch-07-corpus')).resolve()
    output.mkdir(parents=True,exist_ok=True)
    fixtures=ROOT/'tests/fixtures/eapdf/batch07'
    config=output/'config.json';config.write_text(json.dumps(dict(branding=dict(enabled=False),premis=dict(enabled=False))))
    command=['java','-Djava.awt.headless=true','-Xmx256m','-cp',os.environ['EAPDF_GENERATOR_CLASSPATH'],
             'eapdf.EmailToPDFA','--out',str(output/'seed'),str(fixtures/'source.eml')]
    process=subprocess.run(command,cwd=ROOT,env=dict(os.environ,EAPDF_CONFIG=str(config)),capture_output=True,text=True,timeout=90)
    (output/'generation.log').write_text(process.stdout+process.stderr)
    assert process.returncode==0,process.stdout+process.stderr
    seeds=list((output/'seed').glob('*.pdf'));assert len(seeds)==1,seeds
    results=[]
    for case in json.loads((fixtures/'cases.json').read_text()):
        pdf=output/(case['id']+'.pdf');variant(seeds[0],pdf,case)
        report=validator.validate_one(str(pdf),html_path=str(output/(case['id']+'.html')))
        (output/(case['id']+'.json')).write_text(json.dumps(report,indent=2,default=str)+'\n')
        assert report['pdfa']['status']=='PASS',(case['id'],report['pdfa'])
        failures={item['code'] for item in report['items'] if item['level']=='FAIL'}
        assert failures==set(case['failures']),(case['id'],failures)
        assert report['summary']['status']==case['expected_eapdf'],(case['id'],report['summary'])
        rows={item['code']:item for item in report['items'] if item['code'] in (CONTENT,HEADERS)}
        assert rows[CONTENT]['level']==case['content'],(case['id'],rows[CONTENT])
        assert rows[HEADERS]['level']==case['headers'],(case['id'],rows[HEADERS])
        verified=len((rows[HEADERS].get('details') or {}).get('verified_fields',[]))
        assert verified==case['verified_fields'],(case['id'],verified)
        assert not any(isinstance(item.get('details'),dict) and item['details'].get('state')=='unverified'
                       for item in report['items']),case['id']
        results.append(dict(case=case['id'],sha256=hashlib.sha256(pdf.read_bytes()).hexdigest(),pdfa='PASS',
                            eapdf=report['summary']['status'],failures=sorted(failures),content_recommendation=rows[CONTENT]['level'],
                            header_recommendation=rows[HEADERS]['level'],observed_field_pairs=verified,expected_result_observed=True))
        print(case['id'],'PDF/A PASS','EA-PDF',report['summary']['status'],'content',rows[CONTENT]['level'],'headers',rows[HEADERS]['level'],flush=True)
    (output/'results.json').write_text(json.dumps(results,indent=2)+'\n')


if __name__=='__main__':main()
