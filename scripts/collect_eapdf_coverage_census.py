"""Observe current validator evidence without treating execution as certification."""
from collections import defaultdict
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import unittest

ROOT=Path(__file__).resolve().parents[1]
SNAPSHOT=ROOT/'.eapdf-verification-v23'
OUT=ROOT/'docs/improvement/coverage-exit-review'


def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def write(name,value):(OUT/name).write_text(json.dumps(value,indent=2,ensure_ascii=False)+'\n')


def main():
    OUT.mkdir(exist_ok=True)
    manifest=json.loads((ROOT/'docs/improvement/validator-23/verification-inputs.json').read_text())
    tested={name:value for name,value in manifest.items() if name in ('validate_eapdf.py','validator_rule_text.json') or name.startswith('tests/') and name.endswith('.py')}
    for name,value in tested.items():
        assert digest(SNAPSHOT/name)==digest(ROOT/name)==value,name
    rules=json.loads((SNAPSHOT/'validator_rule_text.json').read_text())
    observations={phase:defaultdict(lambda:defaultdict(dict)) for phase in ('unit','fixture')}
    context=[None,None]
    source=str(SNAPSHOT/'validate_eapdf.py')
    wrappers={'add','ok','warn','fail','na','emit_if_unseen'}

    def observe(frame,event,arg):
        if event!='call' or frame.f_code.co_name!='add' or frame.f_code.co_filename!=source or context[0] is None:return
        values=frame.f_locals
        code=values.get('code');level=values.get('level')
        if not isinstance(code,str) or code not in rules or level not in ('PASS','FAIL','WARN','N/A'):return
        owner=values.get('self')
        if owner is None or type(owner).__name__!='Report':return
        caller=frame.f_back
        while caller is not None and caller.f_code.co_filename==source and caller.f_code.co_name in wrappers:caller=caller.f_back
        # Direct hand-made test findings cannot establish production execution.
        if caller is None or caller.f_code.co_filename!=source:return
        producer=caller.f_code.co_name
        kind=('delegation' if producer=='record_verapdf_delegations' else
              'fallback' if producer=='finalize_rule_matrix' else 'production')
        key=(kind,producer,caller.f_lineno)
        bucket=observations[context[0]][code][level]
        if key not in bucket:bucket[key]=dict(kind=kind,function=producer,line=caller.f_lineno,count=0,contexts=set())
        row=bucket[key];row['count']+=1;row['contexts'].add(context[1])

    sys.path[:0]=[str(SNAPSHOT/'tests'),str(SNAPSHOT)]
    suite=unittest.defaultTestLoader.discover(str(SNAPSHOT/'tests'))
    class CensusResult(unittest.TextTestResult):
        def startTest(self,test):
            context[:]=['unit',test.id()];super().startTest(test)
        def stopTest(self,test):
            super().stopTest(test);context[:]=[None,None]
    saved=sys.getprofile();sys.setprofile(observe)
    try:result=unittest.TextTestRunner(verbosity=1,resultclass=CensusResult).run(suite)
    finally:sys.setprofile(saved);context[:]=[None,None]
    assert result.wasSuccessful()
    assert (result.testsRun,len(result.skipped))==(487,40)
    print('Unit census complete: 447 pass / 40 skip.',flush=True)
    from test_preservation_safety import validator as engine
    prior=json.loads((ROOT/'docs/improvement/validator-23/custom-regressions.json').read_text())
    expected={r['file']:r for r in prior};assert len(expected)==202
    fixture_results=[]
    sys.setprofile(observe)
    try:
        for manifest_path in sorted((SNAPSHOT/'tests/fixtures/eapdf').rglob('expectations.json')):
            for case in json.loads(manifest_path.read_text())['cases']:
                path=manifest_path.parent/case['file'];relative=str(path.relative_to(SNAPSHOT))
                assert digest(path)==expected[relative]['sha256']
                context[:]=['fixture',relative]
                report=engine.validate(str(path))
                actual=dict(status=report.summary()['status'],failures=sorted({i['code'] for i in report.items if i['level']=='FAIL'}))
                assert actual==expected[relative]['after'],(relative,actual)
                fixture_results.append(dict(file=relative,sha256=digest(path),result=actual))
                if len(fixture_results)%50==0:print('Custom fixture census:',len(fixture_results),flush=True)
    finally:sys.setprofile(saved);context[:]=[None,None]
    assert len(fixture_results)==202
    normalized={phase:{code:{level:sorted((dict(row,contexts=sorted(row['contexts'])) for row in sites.values()),key=lambda x:(x['kind'],x['function'],x['line']))
                             for level,sites in levels.items()} for code,levels in by_code.items()}
                for phase,by_code in observations.items()}
    write('execution-census.json',dict(
        scope='Observed production report emissions; test code can mock dependencies. Reached results do not prove assertion correctness or complete coverage.',
        snapshot=str(SNAPSHOT.relative_to(ROOT)),inputs=tested,collector_sha256=digest(Path(__file__)),
        unit=dict(run=result.testsRun,passed=result.testsRun-len(result.skipped),skipped=[dict(test=t.id(),reason=r) for t,r in result.skipped]),
        fixtures=fixture_results,observations=normalized,
        exclusions=['Direct synthetic findings from test code are excluded.',
                    'Fallback N/A/WARN and delegation are labeled separately from production checks.',
                    'Unit mocks are not real upstream execution.',
                    'No source/branch or normative assertion completeness is inferred.']))
    print('Census saved for',len(rules),'rule codes and',len(fixture_results),'current custom fixtures.',flush=True)


if __name__=='__main__':main()
