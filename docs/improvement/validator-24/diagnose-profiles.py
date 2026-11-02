"""Run original conformance integrations without weakening their assertions.

The accepted creator is expected to fail the repaired EA-PDF gate. Preserve
all generated files and original test failures instead of claiming acceptance.
"""
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SNAPSHOT = ROOT / '.eapdf-verification-v24'
jar = ROOT / '.eapdf-build-creator-structure/emailtopdfa-1.4.0.jar'
assert hashlib.sha256(jar.read_bytes()).hexdigest() == '272ad741590552489dfb6d096f7ee4b01035fb3faf1c76d817394c0c2ad5e16a'
os.environ.update(EAPDF_GENERATOR_CLASSPATH=str(jar), EAPDF_INTEGRATION_REPORT_DIR=str(HERE / 'profiles'),
                  VERAPDF_MOUNT_ROOT=str(ROOT), GOMAXPROCS='2')
sys.path[:0] = [str(SNAPSHOT / 'tests'), str(SNAPSHOT)]


class RetainedDirectory:
    def __init__(self, *args, **kwargs):
        self.name = tempfile.mkdtemp(*args, **kwargs)
    def __enter__(self):
        return self.name
    def __exit__(self, *_):
        return False


# Preserve the test's exact inputs and generated outputs after its assertions.
tempfile.TemporaryDirectory = RetainedDirectory
suite = unittest.TestSuite(unittest.defaultTestLoader.loadTestsFromName(name) for name in
    ('test_generated_profiles.GeneratedProfileIntegrationTests', 'test_accessibility_profiles.AccessibilityProfileTests'))
result = unittest.TextTestRunner(verbosity=2).run(suite)
assert not result.errors and len(result.failures) == 12 and not result.skipped
rows = []
for mode in ('normal', 'accessibility'):
    folder = HERE / 'profiles' / ('accessibility' if mode == 'accessibility' else '')
    for profile in ('1s', '1si', '1m', '1mi', '1c', '1ci'):
        report_path = folder / (profile + '.json')
        report = json.loads(report_path.read_text())
        failures = sorted({i['code'] for i in report['items'] if i['level'] == 'FAIL'})
        expected = ['COLLECTION_CHILD_VALIDATION'] if profile in ('1c', '1ci') else ['CUSTOM_TAG_OPERAND_MATCH']
        assert failures == expected, (mode, profile, failures)
        assert report['summary']['status'] == 'FAIL' and report['pdfa']['status'] == 'PASS'
        iso = next(i for i in report['items'] if i['code'] == 'ISO32005_UPSTREAM')
        assert iso['level'] == 'PASS' and iso['details']['profile_verified']
        rows.append(dict(mode=mode, profile=profile, pdfa='PASS', iso='PASS', status='FAIL', failures=failures,
                         report=str(report_path.relative_to(ROOT))))
(HERE / 'profile-diagnosis.json').write_text(json.dumps(dict(
    scope='Creator conformance gate fails; diagnosis is not creator acceptance',
    original_test_failures=12, original_test_errors=0, creator_jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
    profiles=rows), indent=2) + '\n')
print('All twelve original profile conformance gates expose the independently confirmed operand defect.', flush=True)
