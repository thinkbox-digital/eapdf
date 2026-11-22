"""Compatibility across the public validator facade and extracted rule families."""
import unittest
from unittest.mock import patch
import validate_eapdf as validator


class RuleFamilyCompatibilityTests(unittest.TestCase):
    def test_xmp_family_reads_live_policy_and_helper(self):
        report = validator.Report('mail.pdf')
        with patch.object(validator, 'PDFA_COVERED_RULES', set()), \
                patch.object(validator, 'ALLOWED_PDFA', {(4, None)}), \
                patch.object(validator, 'first_xmp_value', side_effect=['4', '']) as value:
            self.assertEqual((4, ''), validator.check_pdfa_xmp(object(), report))
        self.assertEqual(2, value.call_count)
        self.assertTrue(all(item['level'] == 'PASS' for item in report.items))

    def test_family_contexts_borrow_current_helpers_without_owning_documents(self):
        # Every adapter must capture the current root binding, including patches
        # installed after import. Closing the document remains the caller's job.
        families = [validator._xmp_rules_dependencies,
                    validator._embedding_rules_dependencies,
                    validator._structure_rules_dependencies,
                    validator._message_rules_dependencies,
                    validator._media_rules_dependencies]
        for context in families:
            with self.subTest(family=context.__name__), \
                    patch.object(validator, 'norm_text') as normalize:
                self.assertIs(normalize, context().norm_text)

    def test_nested_rule_calls_still_use_public_facade(self):
        report = validator.Report('mail.pdf')
        with patch.object(validator, 'check_single_source_xmp_semantics') as single:
            validator.check_standard_xmp_semantics(None, [], report)
        single.assert_called_once_with(None, [], report)

    def test_scoped_attachments_keep_their_source_nodes_after_malformed_members(self):
        root = validator.ET.fromstring(f'''
            <rdf:RDF xmlns:rdf="{validator.NS_RDF}" xmlns:mail="{validator.NS_PDFMAILMETA}">
              <rdf:Description>
                <mail:email><rdf:Bag>
                  <rdf:li rdf:parseType="Resource"><mail:Mail_GUID>first</mail:Mail_GUID></rdf:li>
                  <rdf:li rdf:parseType="Resource"><mail:Mail_GUID>second</mail:Mail_GUID></rdf:li>
                </rdf:Bag></mail:email>
                <mail:attachments><rdf:Bag>
                  <rdf:li>malformed scalar</rdf:li>
                  <rdf:li rdf:parseType="Resource">
                    <mail:Mail_GUID>second</mail:Mail_GUID><mail:FileName>second.txt</mail:FileName>
                  </rdf:li>
                  <rdf:li rdf:parseType="Resource">
                    <mail:Mail_GUID>first</mail:Mail_GUID><mail:FileName>first.txt</mail:FileName>
                  </rdf:li>
                </rdf:Bag></mail:attachments>
                <mail:attachments rdf:parseType="Resource">
                  <mail:Mail_GUID>first</mail:Mail_GUID><mail:FileName>outside-bag.txt</mail:FileName>
                </mail:attachments>
              </rdf:Description>
            </rdf:RDF>''')
        report = validator.Report('mail.pdf')
        with patch.object(validator, 'check_single_message_metadata') as check, \
                patch.object(validator, 'validate_attachment_records') as check_unscoped:
            validator.check_message_metadata(root, [], 'm', report)

        scoped = [validator.flatten_attachments(call.args[0]) for call in check.call_args_list]
        self.assertEqual([[{'Mail_GUID': 'first', 'FileName': 'first.txt'}],
                          [{'Mail_GUID': 'second', 'FileName': 'second.txt'}]], scoped)
        check_unscoped.assert_called_once_with([{'Mail_GUID': 'first', 'FileName': 'outside-bag.txt'}], [], report)
        self.assertTrue(any(item['level'] == 'FAIL' and item['code'] == 'ATTACHMENT_XMP_CONTAINER'
                            for item in report.items))


if __name__ == '__main__':
    unittest.main()
