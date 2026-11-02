"""Native collection controls; creator sources and retained PDFs are immutable.

Fix child marked-content operands from an independent page/MCID ownership
oracle, then supply known PASS, unavailable, invalid and corrupt child variants.
"""
import hashlib
import io
import json
from pathlib import Path
import pikepdf
from review_eapdf_operand_corpus import inspect

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'tests/fixtures/eapdf/validator-collection-child-status'


def corrected_child(data):
    changes=[]
    with pikepdf.open(io.BytesIO(data)) as pdf:
        expected={(r['page'],r['mcid']):r['expected'] for r in inspect(pdf)}
        for number,page in enumerate(pdf.pages,1):
            instructions=[]
            for instruction in pikepdf.parse_content_stream(page):
                if str(instruction.operator)=='BDC':
                    tag,props=instruction.operands
                    resolved=page.Resources.Properties[str(props)] if isinstance(props,pikepdf.Name) else props
                    value=expected.get((number,resolved.get('/MCID')))
                    if value:
                        changes.append(dict(page=number,mcid=int(resolved.MCID),before=str(tag),after=value))
                        instruction=pikepdf.ContentStreamInstruction([pikepdf.Name('/'+value),props],instruction.operator)
                instructions.append(instruction)
            page.Contents=pdf.make_stream(pikepdf.unparse_content_stream(instructions))
        assert not inspect(pdf)
        output=io.BytesIO();pdf.save(output,static_id=True,fix_metadata_version=False)
    return output.getvalue(),changes


def main():
    OUT.mkdir(exist_ok=True)
    profiles=[r for r in json.loads((ROOT/'docs/improvement/validator-75/pdf-results.json').read_text())
              if r['entry']=='retained-profile' and r['key'].endswith(('1c','1ci'))]
    replacements={'incomplete':ROOT/'tests/fixtures/eapdf/validator-source-date-availability/extended-year.pdf',
                  'failed':ROOT/'tests/fixtures/eapdf/validator-source-date-availability/negative-zero-wrong.pdf'}
    cases=[];native=[]
    for original in profiles:
        parent=ROOT/original['file']
        assert hashlib.sha256(parent.read_bytes()).hexdigest()==original['sha256']
        mode,profile=original['key'].split('-',1)
        for variant in ('valid','incomplete','failed','corrupt') if mode=='normal' else ('valid',):
            with pikepdf.open(parent) as pdf:
                members=[]
                names=pdf.Root.Names.EmbeddedFiles.Names
                assert not pdf.Root.Names.EmbeddedFiles.get('/Kids')
                for i in range(0,len(names),2):
                    spec=names[i+1]
                    if spec.get('/CI') is None:continue
                    original_data=spec.EF.F.read_bytes()
                    data,changes=corrected_child(original_data)
                    if not members and variant in replacements:data=replacements[variant].read_bytes()
                    elif not members and variant=='corrupt':data=b'%PDF-2.0\nNot a PDF object graph.\n'
                    streams={}
                    for stream in spec.EF.values():streams[stream.objgen]=stream
                    for stream in streams.values():
                        stream.write(data)
                        stream.Params.Size=len(data)
                        stream.Params.CheckSum=pikepdf.String(hashlib.md5(data).digest())
                    spec.CI.Size=len(data)
                    members.append(dict(filename=str(spec.UF),original_sha256=hashlib.sha256(original_data).hexdigest(),
                                        sha256=hashlib.sha256(data).hexdigest(),size=len(data),operand_changes=changes,
                                        replacement=variant if len(members)==0 else 'corrected-original'))
                assert members
                path=OUT/(mode+'-'+profile+'-'+variant+'.pdf')
                pdf.save(path,static_id=True,fix_metadata_version=False)
            failures=['COLLECTION_CHILD_VALIDATION','COLLECTION_CONTENT_TYPES'] if variant=='corrupt' else ['COLLECTION_CHILD_VALIDATION'] if variant=='failed' else []
            custom_status='FAIL' if failures else 'INCOMPLETE'
            full_status='FAIL' if failures else 'INCOMPLETE' if variant=='incomplete' else 'PASS'
            cases.append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                              expected_status=full_status,expected_custom_status=custom_status,expected_failures=failures,
                              parent=str(parent.relative_to(ROOT)),parent_sha256=original['sha256']))
            native.append(dict(file=path.name,members=members))
    (OUT/'expectations.json').write_text(json.dumps(dict(
        authority=['EA-PDF9.7 custom marked-content operands; independent structure ownership oracle',
                   'Contained email validation preserves PASS, INCOMPLETE and definite FAIL outcomes'],
        boundary='Independent native fixture construction, not creator repair/acceptance. Custom-only collection checks lack child PDF/A evidence and remain INCOMPLETE. Full pinned-engine runs provide positive collection acceptance only for these constructed bytes.',
        cases=cases),indent=2)+'\n')
    (OUT/'native-review.json').write_text(json.dumps(native,indent=2)+'\n')


if __name__=='__main__':main()
