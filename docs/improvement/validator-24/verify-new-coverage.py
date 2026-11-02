"""Observe the nine formerly unexercised checks on the frozen focused tests."""
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SNAPSHOT = ROOT / '.eapdf-verification-v24'
sys.path[:0] = [str(SNAPSHOT / 'tests'), str(SNAPSHOT)]
codes = {'DOCINFO_PRODUCER_MATCH', 'DOCINFO_AUTHOR_MATCH', 'DOCINFO_SUBJECT_MATCH',
         'DOCINFO_CREATOR_MATCH', 'DOCINFO_CREATIONDATE_MATCH', 'DOCINFO_MODDATE_MATCH',
         'DOCINFO_KEYWORDS', 'CUSTOM_TAG_OPERAND_MATCH', 'SOURCE_MIME_GENERIC'}
observations = defaultdict(lambda: defaultdict(set))
context = [None]
def observe(frame, event, arg):
    if event != 'call' or frame.f_code.co_name != 'add' or frame.f_code.co_filename != str(SNAPSHOT / 'validate_eapdf.py'):
        return
    code = frame.f_locals.get('code')
    if code not in codes or context[0] is None:
        return
    caller = frame.f_back
    while caller is not None and caller.f_code.co_name in {'ok', 'fail', 'warn', 'na', 'emit_if_unseen'}:
        caller = caller.f_back
    if (caller is not None and caller.f_code.co_filename == str(SNAPSHOT / 'validate_eapdf.py')
            and caller.f_code.co_name not in {'finalize_rule_matrix', 'record_verapdf_delegations'}):
        observations[code][frame.f_locals['level']].add(context[0])

class Result(unittest.TextTestResult):
    def startTest(self, test):
        context[0] = test.id()
        super().startTest(test)
    def stopTest(self, test):
        super().stopTest(test)
        context[0] = None

suite = unittest.TestSuite(unittest.defaultTestLoader.loadTestsFromName(name) for name in
    ('test_coverage_exit_metadata.MetadataCoverageTests', 'test_custom_tag_operands.CustomTagOperandTests'))
sys.setprofile(observe)
try:
    result = unittest.TextTestRunner(verbosity=2, resultclass=Result).run(suite)
finally:
    sys.setprofile(None)
assert result.wasSuccessful() and not result.skipped
assert set(observations) == codes
assert all({'PASS', 'FAIL'} <= set(observations[c]) for c in codes - {'SOURCE_MIME_GENERIC'})
assert set(observations['SOURCE_MIME_GENERIC']) == {'WARN'}
assert {'PASS', 'FAIL', 'WARN', 'N/A'} <= set(observations['CUSTOM_TAG_OPERAND_MATCH'])
(HERE / 'new-coverage.json').write_text(json.dumps(dict(
    scope='Focused production emissions for nine checks absent from the V23 census; bounded assertion coverage, not certification',
    tests_run=result.testsRun, tests_passed=result.testsRun,
    runtime_sha256=hashlib.sha256((SNAPSHOT / 'validate_eapdf.py').read_bytes()).hexdigest(),
    rules_sha256=hashlib.sha256((SNAPSHOT / 'validator_rule_text.json').read_bytes()).hexdigest(),
    observations={c: {level: sorted(tests) for level, tests in levels.items()} for c, levels in observations.items()}), indent=2) + '\n')
