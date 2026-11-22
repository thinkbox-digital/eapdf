from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
CREATOR = ROOT / 'src/main/java/eapdf'
VALIDATOR = ROOT / 'validator-desktop/src/main/java/eapdf'


class PdfLoaderParityTest(unittest.TestCase):
    def test_shared_indexes_match(self):
        for name in ('ObjectKeyIndex.java', 'CompactPdfDictionary.java'):
            with self.subTest(name=name):
                self.assertEqual((CREATOR / name).read_text(), (VALIDATOR / name).read_text())

    def test_loader_differs_only_in_validator_cache(self):
        creator = (CREATOR / 'IndexedPdfLoader.java').read_text()
        validator = (VALIDATOR / 'IndexedPdfLoader.java').read_text()
        cache_hook = '''    try {
      PDDocument document = new Parser(source, cache).parse();
      document.setResourceCache(new ValidationResourceCache());
      return document;
    }'''
        shared = '    try { return new Parser(source, cache).parse(); }'
        self.assertEqual(1, validator.count(cache_hook))
        self.assertEqual(creator, validator.replace(cache_hook, shared))
