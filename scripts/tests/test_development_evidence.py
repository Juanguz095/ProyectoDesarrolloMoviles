import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from package_development import verified_tests


class DevelopmentEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.path = self.root / 'docs/evidencias/result.json'
        self.path.parent.mkdir(parents=True)
        self.record = {'version': '0.8.0-preview', 'test_evidence': 'docs/evidencias/result.json',
                       'artifacts': [{'component': app, 'sha256': app * 10} for app in ['agent', 'control']]}
        self.proof = {'source_revision': 'a' * 40,
                      'artifacts': [{'app': app, 'sha256': app * 10} for app in ['agent', 'control']],
                      'native_update_verification': [{'app': app, 'base_sha256': app * 10, 'passed': True} for app in ['agent', 'control']],
                      'jvm_and_database_suites': [{'tests': 3, 'failures': 0, 'errors': 0, 'skipped': 0}],
                      'native_tests': 9, 'native_confirmed_upgrade': {'installation_confirmed': True},
                      'native_boot_recovery': {'service_started_before_test_runner': True}}

    def verify(self, changed=b''):
        self.path.write_text(json.dumps(self.proof))
        with patch('package_development.subprocess.check_output', return_value=changed):
            return verified_tests(self.root, self.record)

    def test_binds_apks_and_unchanged_runtime_without_granting_delivery_approval(self):
        result = self.verify()
        self.assertEqual(3, result['jvm_and_database_tests'])
        self.assertEqual(9, result['native_tests'])
        self.assertNotIn('publish_allowed', result)

    def test_missing_evidence_is_rejected_for_080(self):
        self.record.pop('test_evidence')
        with self.assertRaises(ValueError): self.verify()

    def test_artifact_drift_and_runtime_changes_are_rejected(self):
        self.proof['native_update_verification'][0]['base_sha256'] = 'different'
        with self.assertRaises(ValueError): self.verify()
        self.proof['native_update_verification'][0]['base_sha256'] = 'agent' * 10
        with self.assertRaises(ValueError): self.verify(changed=b'apps/agent/changed.kt\n')

    def test_failed_skipped_or_unconfirmed_tests_are_rejected(self):
        for key in ['failures', 'errors', 'skipped']:
            self.proof['jvm_and_database_suites'][0][key] = 1
            with self.assertRaises(ValueError): self.verify()
            self.proof['jvm_and_database_suites'][0][key] = 0
        self.proof['native_confirmed_upgrade']['installation_confirmed'] = False
        with self.assertRaises(ValueError): self.verify()

    def test_private_or_external_proof_path_is_rejected(self):
        self.record['test_evidence'] = '.tooling/private.json'
        with self.assertRaises(ValueError): self.verify()


if __name__ == '__main__': unittest.main()
