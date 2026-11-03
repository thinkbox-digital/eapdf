"""Regression tests for the pinned Java model implementation corrections."""
import json, os, subprocess, tempfile, unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

class ArlingtonSemanticsTests(unittest.TestCase):
    def test_actual_model_values_preserve_invalid_controls(self):
        pin = json.loads((ROOT/'config/arlington.json').read_text())
        folder = ROOT/'dist/tools'/('arlington-'+pin['version'])
        jar = folder/'bin'/('cli-arlington-'+pin['version']+'.jar')
        if not jar.exists() or not (folder/'model-corrections/adapter.json').exists():
            self.skipTest('Install the pinned checker with scripts/install_arlington.py')
        with tempfile.TemporaryDirectory(prefix='eapdf-arlington-regression-',dir='/tmp') as temporary:
            subprocess.run(['javac','--release','11','-cp',str(jar),'-d',temporary,str(ROOT/'tests/java/ArlingtonSemanticsRegression.java')],check=True,capture_output=True,text=True,timeout=120)
            classpath = os.pathsep.join([temporary,str(folder/'model-corrections'),str(jar)])
            result = subprocess.run(['java','-cp',classpath,'ArlingtonSemanticsRegression'],check=True,capture_output=True,text=True,timeout=60)
            self.assertIn('PASS: binary checksum lengths 15/16/17',result.stdout)

if __name__ == '__main__':
    unittest.main()
