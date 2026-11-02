"""Independent source/structure mutations for observed header inventories."""
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from xml.etree import ElementTree as ET

import pikepdf
from build_eapdf_rule_fixtures import structure_elements
from build_eapdf_source_fixtures import properties

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
import validate_eapdf as validator


def variant(seed,output,case):
    with pikepdf.Pdf.open(seed) as pdf:
        ns={'m':'http://www.pdfa.org/eapdf/ns/meta/','r':'http://www.w3.org/1999/02/22-rdf-syntax-ns#'}
        xml=ET.fromstring(pdf.Root.Metadata.read_bytes())
        records=xml.findall('.//m:email/r:Bag/r:li',ns)
        guids={record.findtext('m:Subject',namespaces=ns):record.findtext('m:Mail_GUID',namespaces=ns) for record in records}
        messages=[element for element in structure_elements(pdf) if str(element.S)=='/Mail_Message']
        def message(subject):
            return next(element for element in messages if any(str(prop.N)=='Mail_GUID' and str(prop.V)==guids[subject]
                                                              for prop in properties(element)))
        first=message('Inventory Alpha');second=message('Inventory Bravo')
        mutation=case['mutation']
        if mutation=='missing-guid':
            values=properties(first)
            for index in range(len(values)-1,-1,-1):
                if str(values[index].N)=='Mail_GUID':del values[index]
        elif mutation=='wrong-guid':
            next(prop for prop in properties(first) if str(prop.N)=='Mail_GUID').V=guids['Inventory Bravo']
        elif mutation=='class-map':
            pdf.Root.StructTreeRoot.ClassMap=pikepdf.Dictionary(InventoryOwner=first.A)
            first.C=pikepdf.Name.InventoryOwner;del first.A
        elif mutation=='swap-values':
            def subject_value(owner):
                fields=[element for element in structure_elements_owner(owner) if str(element.S)=='/Mail_Field']
                assert len(fields)==5
                # From, To, Date, Subject, Message-ID: fixture renderer order.
                values=[element for element in structure_elements_owner(fields[3]) if str(element.S)=='/Mail_FieldValue']
                assert len(values)==1
                return values[0]
            a,b=subject_value(first),subject_value(second)
            old_a,old_b=a.K,b.K;page_a,page_b=a.Pg,b.Pg
            a.K=old_b;b.K=old_a;a.Pg=page_b;b.Pg=page_a
            for parent in (a,b):
                children=parent.K if isinstance(parent.K,pikepdf.Array) else [parent.K]
                for child in children:
                    if isinstance(child,pikepdf.Dictionary) and '/S' in child:child.P=parent
            pending=[pdf.Root.StructTreeRoot.ParentTree]
            while pending:
                node=pending.pop();pending.extend(node.get('/Kids',[]))
                nums=node.get('/Nums',[])
                for offset in range(1,len(nums),2):
                    owners=nums[offset]
                    if not isinstance(owners,pikepdf.Array):continue
                    for index,owner in enumerate(list(owners)):
                        if owner is not None and owner.objgen==a.objgen:owners[index]=b
                        elif owner is not None and owner.objgen==b.objgen:owners[index]=a
        else:assert mutation=='none',mutation
        pdf.save(output)
        return guids


def structure_elements_owner(root):
    pending=[root]
    while pending:
        element=pending.pop()
        if isinstance(element,pikepdf.Array):pending.extend(reversed(list(element)))
        elif isinstance(element,pikepdf.Dictionary) and '/S' in element:
            yield element
            if '/K' in element:pending.append(element.K)


def main():
    output=Path(os.environ.get('EAPDF_HEADER_INVENTORY_FIXTURE_DIR',ROOT/'docs/improvement/batch-08-corpus')).resolve()
    output.mkdir(parents=True,exist_ok=True)
    inputs=ROOT/'tests/fixtures/eapdf/batch08'
    config=output/'config.json';config.write_text(json.dumps(dict(branding=dict(enabled=False),premis=dict(enabled=False),findingAid=dict(pdfEnabled=False))))
    seeds={}
    for profile in ('m','mi'):
        path=output/'seed'/profile/'archive.pdf'
        command=['java','-Djava.awt.headless=true','-Xmx256m','-cp',os.environ['EAPDF_GENERATOR_CLASSPATH'],
                 'eapdf.EmailToPDFA','--out',str(path.parent),'--archive-profile',profile,'--archive-output',str(path),str(inputs/'sources')]
        process=subprocess.run(command,cwd=ROOT,env=dict(os.environ,EAPDF_CONFIG=str(config)),capture_output=True,text=True,timeout=90)
        (output/f'generation-{profile}.log').write_text(process.stdout+process.stderr)
        assert process.returncode==0,process.stdout+process.stderr
        seeds[profile]=path
    results=[]
    for case in json.loads((inputs/'cases.json').read_text()):
        pdf=output/(case['id']+'.pdf');guids=variant(seeds[case['profile']],pdf,case)
        report=validator.validate_one(str(pdf),html_path=str(output/(case['id']+'.html')))
        (output/(case['id']+'.json')).write_text(json.dumps(report,indent=2,default=str)+'\n')
        assert report['pdfa']['status']=='PASS',(case['id'],report['pdfa'])
        failures={row['code'] for row in report['items'] if row['level']=='FAIL'}
        assert failures==set(case['failures']),(case['id'],failures)
        assert report['summary']['status']==('FAIL' if failures else 'PASS'),(case['id'],report['summary'])
        row=next(row for row in report['items'] if row['code']=='HEADER_FIELDS_TAGGED_RECOMMENDED')
        assert row['level']=='WARN' and row['details']['assessment']=='partial',row
        inventory=row['details']['rendered_header_inventory']
        counts=dict(Counter(item['comparison'] for item in inventory))
        assert counts==case['counts'],(case['id'],counts,inventory)
        if case['mutation'] in ('swap-values','wrong-guid'):
            assert any(item.get('ownership')=='outside-owner-ranges' for item in inventory),case['id']
        unverified={item['code'] for item in report['items']
                    if isinstance(item.get('details'),dict) and item['details'].get('state')=='unverified'}
        assert unverified==set(case.get('expected_unverified',[])),(case['id'],unverified)
        results.append(dict(case=case['id'],sha256=hashlib.sha256(pdf.read_bytes()).hexdigest(),pdfa='PASS',eapdf=report['summary']['status'],
                            failures=sorted(failures),observed_comparisons=counts,oracle_guids=guids,expected_result_observed=True))
        print(case['id'],'PDF/A PASS','EA-PDF',report['summary']['status'],counts,flush=True)
    (output/'results.json').write_text(json.dumps(results,indent=2)+'\n')


if __name__=='__main__':main()
