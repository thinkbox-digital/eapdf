import hashlib
import json
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import unittest
from uuid import uuid4


sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from published_outputs import PUBLICATION_DIRECTORY, latest_batch_pdfs, walk_output_files


class PublishedOutputsTests(unittest.TestCase):
    def setUp(self):
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.output = Path(temporary.name)
        self.store = self.output / PUBLICATION_DIRECTORY

    def publish(self, content):
        generation = str(uuid4())
        directory = self.store / generation / '000000'
        directory.mkdir(parents=True)
        pdf = directory / 'message.pdf'
        pdf.write_bytes(content)
        manifest = {
            'schemaVersion': 1, 'generation': generation,
            'files': [{'path': '000000/message.pdf', 'size': len(content),
                       'sha256': hashlib.sha256(content).hexdigest()}],
        }
        temporary = self.store / 'next.json'
        temporary.write_text(json.dumps(manifest), encoding='utf-8')
        temporary.replace(self.store / 'current.json')
        return pdf, manifest

    def test_snapshot_reader_pins_one_generation(self):
        previous, manifest = self.publish(b'first complete result')
        observed = latest_batch_pdfs(self.output)
        latest, _ = self.publish(b'new complete result')
        self.assertEqual({previous: manifest['files'][0]['sha256']}, observed)
        self.assertEqual(b'first complete result', previous.read_bytes())
        self.assertEqual([latest], list(latest_batch_pdfs(self.output)))

    def test_flat_scan_and_older_creator_fallback_exclude_generations(self):
        self.publish(b'retained PDF')
        flat = self.output / 'public.pdf'
        flat.write_bytes(b'public PDF')
        self.assertEqual([flat], list(walk_output_files(self.output)))
        (self.store / 'current.json').unlink()
        self.assertEqual({flat: None}, latest_batch_pdfs(self.output))

    def test_invalid_manifest_does_not_fall_back_to_flat_outputs(self):
        _, manifest = self.publish(b'committed PDF')
        (self.output / 'stale.pdf').write_bytes(b'older flat result')
        manifest['files'][0]['size'] += 1
        (self.store / 'current.json').write_text(json.dumps(manifest), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'invalid batch member'):
            latest_batch_pdfs(self.output)
