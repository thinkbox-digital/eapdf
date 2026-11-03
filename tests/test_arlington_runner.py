import importlib.util,unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('arlington_runner',Path(__file__).resolve().parents[1]/'scripts/validate_arlington.py')
runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)

def report(description=None):
    rules=[] if description is None else [{'ruleStatus':'FAILED','failedChecks':1,'checks':[{'errorMessage':'Reported finding'}],'description':description,'clause':'Example','testNumber':5}]
    result={'jobEndStatus':'normal','profileName':'Arlington PDF 2.0 profile','compliant':not rules,'details':{'failedChecks':len(rules),'failedRules':len(rules),'passedChecks':123,'ruleSummaries':rules}}
    return {'report':{'jobs':[{'arlingtonResult':[result]}],'batchSummary':{'totalJobs':1,'outOfMemory':0,'validationSummary':{'successfulJobCount':1,'failedJobCount':0}}}}

class ArlingtonRunnerTests(unittest.TestCase):
    def test_completed_clean_report(self):
        self.assertEqual('PASS',runner.summarize(report(),0)['status'])
    def test_deprecation_is_separate_without_overriding_upstream_result(self):
        result=runner.summarize(report('Entry CIDSet in FontDescriptorCIDType2 is deprecated since PDF 2.0'),1)
        self.assertEqual('WARNINGS_ONLY',result['status']);self.assertFalse(result['checker_compliant']);self.assertEqual(1,result['deprecation_occurrences'])
    def test_other_deviation_is_not_downgraded(self):
        result=runner.summarize(report('Entry FontWeight shall have type Integer'),1)
        self.assertEqual('DEVIATIONS',result['status']);self.assertEqual(1,result['other_deviation_occurrences'])
    def test_missing_finding_details_cannot_pass_as_complete(self):
        value=report('Entry FontWeight shall have type Integer');value['report']['jobs'][0]['arlingtonResult'][0]['details']['ruleSummaries'][0]['checks']=[]
        with self.assertRaisesRegex(ValueError,'truncated'):runner.summarize(value,1)
    def test_failed_job_or_abnormal_exit_cannot_pass(self):
        for mutate in (lambda x:x['report']['batchSummary'].update(outOfMemory=1),lambda x:x['report']['jobs'][0]['arlingtonResult'][0].update(jobEndStatus='exception')):
            value=report();mutate(value)
            with self.assertRaises(ValueError):runner.summarize(value,0)
        with self.assertRaises(ValueError):runner.summarize(report(),137)
    def test_contradictory_compliance_cannot_pass(self):
        value=report('Missing required entry');value['report']['jobs'][0]['arlingtonResult'][0]['compliant']=True
        with self.assertRaisesRegex(ValueError,'Contradictory'):runner.summarize(value,0)
if __name__=='__main__':unittest.main()
