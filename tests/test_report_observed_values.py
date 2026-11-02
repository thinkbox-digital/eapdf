import unittest

from test_preservation_safety import validator, web


class IdentificationDetailTests(unittest.TestCase):
    def identification_report(self, version, revision, attributes=False):
        properties = {"version": version, "rev": revision, "conformance": "s"}
        if attributes:
            fields = " ".join(f'pdfmailid:{name}="{value}"' for name, value in properties.items())
            description = f"<rdf:Description {fields}/>"
        else:
            fields = "".join(f"<pdfmailid:{name}>{value}</pdfmailid:{name}>" for name, value in properties.items())
            description = f"<rdf:Description>{fields}</rdf:Description>"
        metadata = (
            f'<rdf:RDF xmlns:rdf="{validator.NS_RDF}" '
            f'xmlns:pdfmailid="{validator.NS_PDFMAILID}" '
            f'xmlns:pdfmail="{validator.NS_PDFMAIL}" '
            f'xmlns:pdfmailmeta="{validator.NS_PDFMAILMETA}">'
            f"{description}</rdf:RDF>"
        ).encode()
        report = validator.Report("identification.pdf")
        root, namespaces, _ = validator.parse_xmp(metadata, report)
        validator.check_eapdf_xmp(root, namespaces, report)
        return report

    def test_passing_identification_values_reach_report_detail(self):
        for attributes in (False, True):
            with self.subTest(attributes=attributes):
                report = self.identification_report("1", "2024", attributes)
                rows = {row["code"]: row for row in web.collapse_items(report.items)}
                for code, value in (("EAPDF_VERSION", "1"), ("EAPDF_REV", "2024")):
                    with self.subTest(code=code):
                        row = rows[code]
                        self.assertEqual("PASS", row["level"])
                        self.assertEqual({"actual": value}, row["details"])
                        detail = web.render_details(row["details"], row["level"], code)
                        self.assertIn("Observed", detail)
                        self.assertIn(f"<span class='detail-value-inline'>{value}</span>", detail)
                        self.assertNotIn("Expected", detail)
                        self.assertIn(detail, web.render_file_cell(row))
                standalone = validator.render_html(report)
                self.assertIn('&quot;actual&quot;: &quot;2024&quot;', standalone)

    def test_failed_identification_retains_actual_values(self):
        report = self.identification_report("2", "2030")
        rows = {row["code"]: row for row in web.collapse_items(report.items)}
        for code, actual, expected in (("EAPDF_VERSION", "2", "1"), ("EAPDF_REV", "2030", "2024")):
            with self.subTest(code=code):
                row = rows[code]
                self.assertEqual("FAIL", row["level"])
                self.assertEqual({"actual": actual}, row["details"])
                detail = web.render_details(row["details"], row["level"], code)
                self.assertIn(f"<span class='detail-value-inline'>{actual}</span>", detail)
                self.assertIn("Expected", detail)
                self.assertIn(f"<span class='detail-value-inline'>{expected}</span>", detail)


if __name__ == "__main__":
    unittest.main()
