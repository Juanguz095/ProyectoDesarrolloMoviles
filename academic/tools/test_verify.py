"""The evidence collector must reject empty, failed, skipped, duplicated or stale reports."""
import os
from pathlib import Path
import tempfile
import time
import unittest

from verify import junit_results, instrumentation_report


class VerificationReportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="auditlab-junit-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def report(self, name="TEST-valid.xml", tests=1, body='<testcase classname="suite" name="one" />', **counts):
        attrs = " ".join(f'{key}="{value}"' for key, value in counts.items())
        path = self.root / name
        path.write_text(f'<testsuite tests="{tests}" {attrs}>{body}</testsuite>')
        return path

    def test_valid_report_has_real_unique_cases(self):
        self.report(tests=2, body='<testcase classname="suite" name="one" /><testcase classname="suite" name="two" />')
        self.assertEqual({"tests": 2, "failures": 0, "errors": 0, "skipped": 0}, junit_results(self.root, 2))
        (self.root / "TEST-valid.xml").write_text('''<testsuites tests="2" failures="0" errors="0" skipped="0">
            <testsuite tests="1"><testcase classname="a" name="one" /></testsuite>
            <testsuite tests="1"><testcase classname="b" name="two" /></testsuite></testsuites>''')
        self.assertEqual(2, junit_results(self.root, 2)["tests"])

    def test_missing_and_zero_case_reports_are_rejected(self):
        with self.assertRaises(ValueError):
            junit_results(self.root, 1)
        self.report(tests=0, body="")
        with self.assertRaises(ValueError):
            junit_results(self.root, 1)

    def test_failure_and_error_nodes_cannot_be_hidden_by_counters(self):
        for tag in ["failure", "error"]:
            self.report(body=f'<testcase classname="suite" name="one"><{tag}/></testcase>')
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                junit_results(self.root, 1)

    def test_skipped_case_is_not_a_pass(self):
        self.report(body='<testcase classname="suite" name="one"><skipped/></testcase>')
        with self.assertRaises(ValueError):
            junit_results(self.root, 1)

    def test_stale_report_is_rejected(self):
        path = self.report()
        os.utime(path, (0, 0))
        with self.assertRaises(ValueError):
            junit_results(self.root, 1, time.time())

    def test_duplicate_identity_cannot_inflate_count(self):
        self.report()
        self.report(name="TEST-duplicate.xml")
        with self.assertRaises(ValueError):
            junit_results(self.root, 2)

    def test_declared_count_must_equal_actual_cases(self):
        self.report(tests=2)
        with self.assertRaises(ValueError):
            junit_results(self.root, 2)
        (self.root / "TEST-valid.xml").write_text('''<testsuites tests="2">
            <testsuite tests="2"><testcase classname="a" name="one" /></testsuite>
            <testsuite tests="1"><testcase classname="b" name="two" /></testsuite></testsuites>''')
        with self.assertRaises(ValueError):
            junit_results(self.root, 2)

    def protocol(self, code=0):
        fields = "INSTRUMENTATION_STATUS: class=suite\nINSTRUMENTATION_STATUS: test=one\nINSTRUMENTATION_STATUS: numtests=1\n"
        return fields + "INSTRUMENTATION_STATUS_CODE: 1\n" + fields + f"INSTRUMENTATION_STATUS_CODE: {code}\nTests run: 1, Failures: {int(code != 0)}\n"

    def test_instrumentation_requires_matching_start_completion_and_count(self):
        path = self.root / "TEST-device.xml"
        instrumentation_report(self.protocol(), path, 1)
        self.assertEqual(1, junit_results(self.root, 1)["tests"])
        for invalid in [self.protocol().replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: 1"),
                        self.protocol().split("INSTRUMENTATION_STATUS_CODE: 0")[0],
                        self.protocol().replace("numtests=1", "numtests=0")]:
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                instrumentation_report(invalid, path, 1)

    def test_instrumentation_failure_cannot_be_reported_as_pass(self):
        path = self.root / "TEST-device.xml"
        with self.assertRaises(ValueError):
            instrumentation_report(self.protocol(-2), path, 1)
        with self.assertRaises(ValueError):
            junit_results(self.root, 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
