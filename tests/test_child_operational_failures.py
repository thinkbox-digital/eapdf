"""Collection conformance failures require child findings, not staging errors."""
from pathlib import Path
from contextlib import contextmanager
import tempfile
import unittest
from unittest.mock import patch

from test_preservation_safety import validator as v, failed_codes


class ChildOperationalFailureTests(unittest.TestCase):
    def child(self, data=b'%PDF-child', name='child.pdf'):
        return dict(bytes=data, collection_item=True, filename=name)

    def result(self, status='PASS'):
        return dict(summary={'status': status}, pdfa={'status': 'PASS'}, items=[])

    def test_child_worker_exception_is_incomplete(self):
        for error in (OSError('worker unavailable'), TimeoutError('worker timed out'),
                      RuntimeError('worker failed')):
            with self.subTest(error=type(error).__name__), tempfile.TemporaryDirectory() as directory:
                context = v.ChildValidationContext(include_pdfa=True)
                context.temporary_root = Path(directory)
                report = v.Report('parent.pdf')
                with patch.object(v, 'validate_one', side_effect=error):
                    v.check_collection_children([self.child()], 'c', report, context, 0)
                self.assertEqual('INCOMPLETE', report.summary()['status'])
                self.assertFalse(failed_codes(report))
                item = next(i for i in report.items if i['code'] == 'COLLECTION_CHILD_VALIDATION')
                self.assertEqual('unavailable', item['details']['assessment'])
                self.assertEqual(type(error).__name__, item['details']['error_type'])
                self.assertFalse(context.active)
                self.assertFalse(context.cache)

    def test_custom_child_worker_exception_is_incomplete(self):
        with tempfile.TemporaryDirectory() as directory:
            context = v.ChildValidationContext()
            context.temporary_root = Path(directory)
            report = v.Report('parent.pdf')
            with patch.object(v, 'validate', side_effect=RuntimeError('custom worker unavailable')):
                v.check_collection_children([self.child()], 'ci', report, context, 0)
            self.assertEqual('INCOMPLETE', report.summary()['status'])
            self.assertFalse(failed_codes(report))

    def test_unusable_temporary_root_is_incomplete_for_real_valid_collection(self):
        folder = Path(__file__).resolve().parent / 'fixtures/eapdf/validator-collection-child-status'
        with tempfile.TemporaryDirectory() as directory:
            blocker = Path(directory) / 'not-a-directory'
            blocker.write_bytes(b'blocked staging root')
            for name in ('normal-1c-valid', 'normal-1ci-valid'):
                with self.subTest(name=name):
                    context = v.ChildValidationContext()
                    context.temporary_root = blocker
                    report = v.validate(str(folder / (name + '.pdf')), child_context=context)
                    self.assertEqual('INCOMPLETE', report.summary()['status'])
                    self.assertFalse(failed_codes(report))
                    self.assertFalse(context.active)

    def test_unwritable_staged_child_is_incomplete(self):
        with tempfile.TemporaryDirectory() as directory:
            context = v.ChildValidationContext()
            context.temporary_root = Path(directory)
            report = v.Report('parent.pdf')
            with patch.object(v.Path, 'write_bytes', side_effect=PermissionError('child write denied')):
                v.check_collection_children([self.child()], 'c', report, context, 0)
            self.assertEqual('INCOMPLETE', report.summary()['status'])
            self.assertFalse(failed_codes(report))

    def test_failed_attempt_is_not_cached_and_does_not_hide_a_later_child_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            context = v.ChildValidationContext(include_pdfa=True)
            context.temporary_root = Path(directory)
            report = v.Report('parent.pdf')
            with patch.object(v, 'validate_one', side_effect=[OSError('unavailable'), self.result('FAIL')]) as run:
                v.check_collection_children([self.child(), self.child()], 'ci', report, context, 0)
            self.assertEqual(2, run.call_count)
            rows = [i for i in report.items if i['code'] == 'COLLECTION_CHILD_VALIDATION']
            self.assertEqual(['WARN', 'FAIL'], [i['level'] for i in rows])
            self.assertEqual('FAIL', report.summary()['status'])

    def test_later_success_cannot_hide_an_unchecked_occurrence(self):
        with tempfile.TemporaryDirectory() as directory:
            context = v.ChildValidationContext(include_pdfa=True)
            context.temporary_root = Path(directory)
            report = v.Report('parent.pdf')
            with patch.object(v, 'validate_one', side_effect=[OSError('unavailable'), self.result()]):
                v.check_collection_children([self.child(), self.child(data=b'%PDF-other')], 'c', report, context, 0)
            self.assertEqual('INCOMPLETE', report.summary()['status'])
            self.assertFalse(failed_codes(report))

    def test_actual_child_failure_remains_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            context = v.ChildValidationContext(include_pdfa=True)
            context.temporary_root = Path(directory)
            report = v.Report('parent.pdf')
            with patch.object(v, 'validate_one', return_value=self.result('FAIL')):
                v.check_collection_children([self.child()], 'c', report, context, 0)
            self.assertEqual({'COLLECTION_CHILD_VALIDATION'}, failed_codes(report))

    def test_cleanup_failure_preserves_available_child_findings(self):
        with tempfile.TemporaryDirectory() as directory:
            @contextmanager
            def staging(*args, **kwargs):
                yield directory
                raise OSError('cleanup unavailable')

            for status, expected in [('FAIL', 'FAIL'), ('PASS', 'INCOMPLETE')]:
                with self.subTest(status=status):
                    context = v.ChildValidationContext(include_pdfa=True)
                    context.temporary_root = Path(directory)
                    report = v.Report('parent.pdf')
                    with patch.object(v.tempfile, 'TemporaryDirectory', side_effect=staging), \
                            patch.object(v, 'validate_one', return_value=self.result(status)):
                        v.check_collection_children([self.child()], 'ci', report, context, 0)
                    self.assertEqual(expected, report.summary()['status'])
                    self.assertEqual(1, len(context.cache))
                    if status == 'FAIL':
                        self.assertEqual({'COLLECTION_CHILD_VALIDATION'}, failed_codes(report))

    def test_corrupt_native_child_still_has_definite_failure_findings(self):
        folder = Path(__file__).resolve().parent / 'fixtures/eapdf/validator-collection-child-status'
        for name in ('normal-1c-failed', 'normal-1ci-failed', 'normal-1c-corrupt', 'normal-1ci-corrupt'):
            with self.subTest(name=name):
                report = v.validate(str(folder / (name + '.pdf')))
                self.assertIn('COLLECTION_CHILD_VALIDATION', failed_codes(report))

    def test_resource_limit_remains_incomplete(self):
        context = v.ChildValidationContext()
        context.max_depth = 0
        report = v.Report('parent.pdf')
        v.check_collection_children([self.child()], 'ci', report, context, 0)
        self.assertEqual('INCOMPLETE', report.summary()['status'])
        self.assertFalse(failed_codes(report))
