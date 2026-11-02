"""Expected source decoding failures remain local recommendation evidence."""
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import subprocess

import pikepdf
from build_eapdf_header_inventory_fixtures import ROOT, variant, validator, structure_elements, properties


def main():
    output=Path(os.environ.get('EAPDF_ENCODED_HEADER_FIXTURE_DIR',ROOT/'docs/improvement/batch-10-corpus')).resolve()
    output.mkdir(parents=True,exist_ok=True)
    inputs=ROOT/'tests/fixtures/eapdf/batch10'
    config=output/'config.json'
    config.write_text(json.dumps(dict(branding=dict(enabled=False),premis=dict(enabled=False),findingAid=dict(pdfEnabled=False))))
    results=[]
    for case in json.loads((inputs/'cases.json').read_text()):
        seed=output/'seed'/case['id']/'archive.pdf'
        command=['java','-Djava.awt.headless=true','-Xmx256m','-cp',os.environ['EAPDF_GENERATOR_CLASSPATH'],
                 'eapdf.EmailToPDFA','--out',str(seed.parent),'--archive-profile',case['profile'],
                 '--archive-output',str(seed),str(inputs/case['id'])]
        process=subprocess.run(command,cwd=ROOT,env=dict(os.environ,EAPDF_CONFIG=str(config)),capture_output=True,text=True,timeout=90)
        (output/f"generation-{case['id']}.log").write_text(process.stdout+process.stderr)
        assert process.returncode==0,process.stdout+process.stderr
        staged=output/(case['id']+'-staged.pdf')
        guids=variant(seed,staged,dict(mutation='none'))
        pdf=output/(case['id']+'.pdf')
        with pikepdf.Pdf.open(staged) as document:
            if 'value' in case:
                owner=next(element for element in structure_elements(document) if str(element.S)=='/Mail_Message' and
                    any(str(prop.N)=='Mail_GUID' and str(prop.V)==guids['Inventory Alpha'] for prop in properties(element)))
                properties(owner).append(pikepdf.Dictionary(N='Mail_X-Project',V=case['value']))
            document.save(pdf)
        staged.unlink()
        report=validator.validate_one(str(pdf),html_path=str(output/(case['id']+'.html')))
        (output/(case['id']+'.json')).write_text(json.dumps(report,indent=2,default=str)+'\n')
        assert report['pdfa']['status']==report['summary']['status']=='PASS',(case['id'],report['summary'])
        failures={row['code'] for row in report['items'] if row['level']=='FAIL'}
        assert failures==set(),(case['id'],failures)
        finding=next(row for row in report['items'] if row['code']=='HEADER_FIELDS_TAGGED_RECOMMENDED')
        assert finding['level']=='WARN' and finding['details']['assessment']=='partial',finding
        observed=finding['details']['rendered_header_inventory']
        counts=dict(Counter(row['comparison'] for row in observed))
        assert counts==({'unavailable':10} if case['profile']=='mi' else {'equal':10}),(case['id'],counts)
        sources=finding['details']['source_header_inventory']
        assert len(sources)==2,sources
        occurrences=[item for source in sources for item in source['occurrences']]
        errors=[item for item in occurrences if item['decoded'] is None]
        assert len(errors)==case['decode_errors'],(case['id'],errors)
        if case['profile']=='m':
            assert len(occurrences)==18 and all(source['assessment']=='available' for source in sources),sources
            original=next(source for source in sources if source['Mail_GUID']==guids['Inventory Alpha'])
            assert original['source_sha256']==hashlib.sha256((inputs/case['id']/'first.eml').read_bytes()).hexdigest(),original
            project=next(item for item in original['occurrences'] if item['header']=='x-project')
            assert project['raw']==case['payload'],project
            assert project['decoded']==('café' if not case['decode_errors'] else None),project
            assert all(not item['matched_fields'] for item in occurrences if item['header']=='x-project'),occurrences
            assert sum(item['header']=='x-later' and item['decoded']=='preserved' for item in occurrences)==2,occurrences
        else:
            assert all(source['assessment']=='unavailable' and not source['occurrences'] for source in sources),sources
        attrs=[row for row in report['items'] if row['code']=='USERPROPS_VALUES_UNMODIFIED']
        level=max((row['level'] for row in attrs),key={'N/A':0,'PASS':1,'WARN':2,'FAIL':3}.get)
        assert level==case['recommendation'],(case['id'],attrs)
        if 'value' in case and case['profile']=='m':
            properties_found=[row for row in attrs if row['details'].get('name')=='Mail_X-Project']
            assert len(properties_found)==1 and properties_found[0]['level']==case['recommendation'],properties_found
            assert properties_found[0]['details']['comparison']==('unavailable' if case['decode_errors'] else 'equal'),properties_found
        assert not any(isinstance(row.get('details'),dict) and row['details'].get('state')=='unverified' for row in report['items']),case['id']
        results.append(dict(case=case['id'],sha256=hashlib.sha256(pdf.read_bytes()).hexdigest(),pdfa='PASS',eapdf='PASS',
                            failures=[],recommendation=level,decode_errors=len(errors),observed_comparisons=counts,
                            source_occurrences=len(occurrences),expected_result_observed=True))
        print(case['id'],'PDF/A PASS EA-PDF PASS','attribute recommendation',level,'undecodable occurrences',len(errors),flush=True)
    (output/'results.json').write_text(json.dumps(results,indent=2)+'\n')


if __name__=='__main__':main()
