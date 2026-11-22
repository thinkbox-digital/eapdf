from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from zipfile import ZipFile

from scripts.security_inventory import maven_components


class SecurityInventoryTests(unittest.TestCase):
    def test_maven_annotations_preserve_artifact_path_and_classifier(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            jar = root / 'artifacts -- module cache' / 'sample-1.0-data.jar'
            jar.parent.mkdir()
            with ZipFile(jar, 'w'):
                pass
            dependencies = root / 'dependencies.txt'
            line = f'example:sample:jar:data:1.0:runtime:{jar}'
            dependencies.write_text(line + '\n')
            expected = maven_components(dependencies)
            self.assertEqual('pkg:maven/example/sample@1.0?classifier=data', expected[0]['purl'])

            for annotation in (' (optional)', ' -- module example.sample [auto]',
                               ' (optional) -- module example.sample (auto)'):
                with self.subTest(annotation=annotation):
                    dependencies.write_text(line + annotation + '\n')
                    self.assertEqual(expected, maven_components(dependencies))

            dependencies.write_text(line + '\x1b[36m -- module example.sample [auto]\x1b[m\n')
            self.assertEqual(expected, maven_components(dependencies))
