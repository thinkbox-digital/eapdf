"""Profile navigation entries must be PDF names, not equivalent text."""
import unittest

import pikepdf

from test_preservation_safety import validator as v, email_metadata


class NavigationNameTests(unittest.TestCase):
    def combinations(self):
        for profile in ('s', 'si'):
            for attachments in (False, True):
                for part in (3, 4):
                    yield profile, attachments, part
        for profile in ('m', 'mi', 'c', 'ci'):
            for part in (3, 4):
                yield profile, False, part

    def check(self, profile, attachments, part, field=None, text=False, wrong=False, absent=False):
        page_mode = 'UseAttachments' if profile in ('c', 'ci') or (profile in ('s', 'si') and attachments) else 'UseOutlines'
        nfsm = 'UseOutlines' if part == 3 else page_mode
        # Metadata supplies the existing attachment condition; its interpretation is unchanged.
        xml = email_metadata([{'NumberAttachments': '1' if attachments else '0'}])
        with pikepdf.Pdf.new() as pdf:
            pdf.add_blank_page()
            pdf.Root.PageMode = pikepdf.Name('/' + page_mode)
            pdf.Root.ViewerPreferences = pikepdf.Dictionary(NonFullScreenPageMode=pikepdf.Name('/' + nfsm))
            if profile in ('c', 'ci'):
                pdf.Root.Collection = pikepdf.Dictionary()
            owner = pdf.Root if field == 'PageMode' else pdf.Root.ViewerPreferences
            if field:
                if absent:
                    del owner['/' + field]
                elif wrong:
                    owner['/' + field] = pikepdf.Name.UseNone
                elif text:
                    owner['/' + field] = pikepdf.String(str(owner['/' + field]).lstrip('/'))
            report = v.Report('navigation.pdf')
            v.check_profile_rules(pdf, [], [], profile, part, report, xml)
            return [i for i in report.items if i['code'].startswith(('PROFILE_PAGEMODE_', 'PROFILE_NFSM_'))]

    def test_canonical_names_pass_every_profile_and_single_message_condition(self):
        for args in self.combinations():
            with self.subTest(args=args):
                self.assertEqual(['PASS', 'PASS'], [i['level'] for i in self.check(*args)])

    def test_text_cannot_satisfy_the_named_mode_in_any_branch(self):
        for args in self.combinations():
            for field, prefix in [('PageMode', 'PROFILE_PAGEMODE_'), ('NonFullScreenPageMode', 'PROFILE_NFSM_')]:
                with self.subTest(args=args, field=field):
                    rows = self.check(*args, field=field, text=True)
                    self.assertEqual(['FAIL'], [i['level'] for i in rows if i['code'].startswith(prefix)])
                    row = next(i for i in rows if i['code'].startswith(prefix))
                    self.assertFalse(row['details']['is_pdf_name'])

    def test_missing_modes_still_fail(self):
        for args in self.combinations():
            for field, prefix in [('PageMode', 'PROFILE_PAGEMODE_'), ('NonFullScreenPageMode', 'PROFILE_NFSM_')]:
                with self.subTest(args=args, field=field):
                    self.assertEqual(['FAIL'], [i['level'] for i in self.check(*args, field=field, absent=True) if i['code'].startswith(prefix)])

    def test_wrong_names_still_fail(self):
        for args in self.combinations():
            for field, prefix in [('PageMode', 'PROFILE_PAGEMODE_'), ('NonFullScreenPageMode', 'PROFILE_NFSM_')]:
                with self.subTest(args=args, field=field):
                    rows = self.check(*args, field=field, wrong=True)
                    row = next(i for i in rows if i['code'].startswith(prefix))
                    self.assertEqual('FAIL', row['level'])
                    self.assertTrue(row['details']['is_pdf_name'])


if __name__ == '__main__':
    unittest.main()
