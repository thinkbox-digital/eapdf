"""Run the new navigation tests against the complete frozen V29 engine."""
import importlib.util
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'tests'))
import test_preservation_safety as helpers
spec = importlib.util.spec_from_file_location('before', ROOT / '.eapdf-verification-v29/validate_eapdf.py')
before = importlib.util.module_from_spec(spec)
spec.loader.exec_module(before)
helpers.validator = before
suite = unittest.defaultTestLoader.discover(str(ROOT / 'tests'), pattern='test_navigation_name_types.py')
result = unittest.TextTestRunner(verbosity=2).run(suite)
assert result.testsRun == 4 and len(result.failures) == 24 and len(result.errors) == 24
print('Twenty-four mode-type assertions fail before the repair; twenty-four diagnostic checks lack the new type evidence.')
