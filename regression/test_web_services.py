import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import validator_web as web


class WebServiceCompatibilityTests(unittest.TestCase):
    def test_existing_registry_uses_live_paths_and_expiry_removes_only_managed_files(self):
        registry = web.CapabilityRegistry("report")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "app"
            outside = Path(directory) / "original.pdf"
            outside.write_bytes(b"original")
            with patch.object(web, "ROOT", root), patch.object(web, "OUT_PDF_ROOT", root / "out"):
                managed = root / ".validator_web_tmp" / "report.html"
                managed.parent.mkdir(parents=True)
                managed.write_text("report")
                for token, path in [("a" * 32, managed), ("b" * 32, outside)]:
                    registry[token] = path
                    self.assertEqual(path, web.CapabilityRegistry("report").get(token))
                    receipt = root / ".validator_web_tmp" / "registry" / (token + ".json")
                    record = json.loads(receipt.read_text())
                    record["expires"] = 0
                    receipt.write_text(json.dumps(record))
                web.cleanup_expired_jobs()
                self.assertFalse(managed.exists())
                self.assertEqual(b"original", outside.read_bytes())
                self.assertIsNone(registry.get("a" * 32))
                self.assertIsNone(registry.get("b" * 32))
                self.assertFalse(list((root / ".validator_web_tmp" / "registry").glob("*.json")))

    def test_web_pages_keep_live_root_callbacks_and_escape_errors(self):
        with patch.object(web, "APP_VERSION", "test version"), \
                patch.object(web, "render_summary_strip", return_value="summary marker") as summary, \
                patch.object(web, "render_pdfa_table", return_value="pdfa marker") as pdfa, \
                patch.object(web, "render_comparison_table", return_value="comparison marker") as comparison:
            results = [{"sentinel": True}]
            page = web.render_page(results, "<unsafe>")
        for callback in (summary, pdfa, comparison):
            callback.assert_called_once_with(results)
        for text in ("summary marker", "pdfa marker", "comparison marker", "test version", "&lt;unsafe&gt;"):
            self.assertIn(text, page)
        self.assertNotIn("<unsafe>", page)

        with patch.object(web, "render_generation_result", return_value="generated marker") as generated, \
                patch.object(web, "list_project_output_directories", return_value=[]):
            result = {"sentinel": True}
            page = web.render_generate_page(result, "<unsafe>")
        generated.assert_called_once_with(result)
        self.assertIn("generated marker", page)
        self.assertIn("&lt;unsafe&gt;", page)


if __name__ == "__main__":
    unittest.main()
