"""No emulator required: reject partial, failed and skipped native protocols."""
import sys
from pathlib import Path
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_android import protocol, validate_serial, complete, EXPECTED_STAGES

GOOD = 'INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n'

class NativeProtocolTest(unittest.TestCase):
    def test_partial_or_reordered_acceptance_does_not_approve_delivery(self):
        stages = [dict(component=component, stage=stage, approved=True) for component, stage in EXPECTED_STAGES]
        self.assertTrue(complete(stages))
        self.assertFalse(complete([]))
        self.assertFalse(complete(stages[:-1]))
        self.assertFalse(complete(list(reversed(stages))))
        stages[2]['approved'] = False
        self.assertFalse(complete(stages))

    def test_complete_native_case(self):
        self.assertTrue(protocol(GOOD)['approved'])

    def test_missing_start_or_final_is_not_success(self):
        for marker in ['INSTRUMENTATION_STATUS_CODE: 1\n', 'INSTRUMENTATION_CODE: -1\n', 'OK (1 test)\n']:
            self.assertFalse(protocol(GOOD.replace(marker, ''))['approved'])

    def test_failure_or_skip_is_not_success(self):
        for code in [-1, -2, -3, -4]:
            self.assertFalse(protocol(GOOD.replace('STATUS_CODE: 0', 'STATUS_CODE: ' + str(code)))['approved'])

    def test_extra_case_or_duplicate_final_is_rejected(self):
        self.assertFalse(protocol(GOOD + 'INSTRUMENTATION_STATUS_CODE: 0\n')['approved'])
        self.assertFalse(protocol(GOOD + 'INSTRUMENTATION_CODE: -1\n')['approved'])
        self.assertFalse(protocol(GOOD + 'FAILURES!!!\n')['approved'])

    def test_fixture_requires_explicit_emulator_serial(self):
        self.assertEqual('emulator-5580', validate_serial('emulator-5580'))
        for value in ['phone123', '', 'emulator-5580;reboot', '../data', 'emulator-nope']:
            with self.assertRaises(ValueError): validate_serial(value)

if __name__ == '__main__': unittest.main()
