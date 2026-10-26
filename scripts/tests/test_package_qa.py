"""The handoff contains exactly the tested apps and never bundles private fixtures."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import package_qa
from check_android import EXPECTED_STAGES


class PackageTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='auditlab-package-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.report = self.root / '.tooling/report'
        self.report.mkdir(parents=True)
        self.destination = self.root / '.tooling/delivery/candidate.zip'
        artifacts = []
        for component in ('agent', 'control'):
            for variant, name in [('debug', f'{component}-debug.apk'),
                                  ('androidTest/debug', f'{component}-debug-androidTest.apk')]:
                path = self.root / f'apps/{component}/build/outputs/apk/{variant}/{name}'
                path.parent.mkdir(parents=True)
                path.write_bytes(name.encode())
                artifacts.append({'component': component, 'file': name,
                                  'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
        self.write('verdict.json', {'approved': True, 'expected_stages': 17, 'executed_stages': 17,
                                  'passed': 17, 'failed': 0, 'not_executed': 0})
        self.write('summary.json', [{'component': c, 'stage': s, 'approved': True} for c, s in EXPECTED_STAGES])
        self.write('environment.json', {'artifacts': artifacts, 'devices': {}, 'recorded_at': 'synthetic'})
        for name in ['bin/ktor', 'bin/ktor.bat', 'lib/ktor.jar']:
            path = self.root / 'backend/ktor/build/install/ktor' / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'compiled-fixture')
        (self.root / 'docs').mkdir()
        (self.root / 'docs/MANUAL_PRODUCTO.md').write_text('Public manual')
        (self.root / '.tooling/fixture.json').write_text('DO-NOT-BUNDLE-SYNTHETIC-SECRET')
        self.tracked = ['docs/MANUAL_PRODUCTO.md']

    def write(self, name, data):
        (self.report / name).write_text(json.dumps(data))

    def git(self, command, **kwargs):
        if command[1] == 'ls-files': output = ('\0'.join(self.tracked) + '\0').encode()
        elif command[1] == 'rev-parse': output = 'synthetic-revision\n'
        elif command[1] == 'status': output = b''
        else: raise AssertionError('Unexpected command')
        return subprocess.CompletedProcess(command, 0, stdout=output)

    def package(self):
        with patch.object(package_qa.subprocess, 'run', side_effect=self.git):
            return package_qa.bundle(self.root, self.report, self.destination)

    def test_exact_payload_checksums_permissions_and_no_fixture_or_test_apks(self):
        result = self.package()
        self.assertFalse(result['pilot_approved'])
        self.assertEqual(hashlib.sha256(self.destination.read_bytes()).hexdigest(), result['sha256'])
        with zipfile.ZipFile(self.destination) as archive:
            for line in archive.read('SHA256SUMS').decode().splitlines():
                checksum, name = line.split('  ', 1)
                self.assertEqual(checksum, hashlib.sha256(archive.read(name)).hexdigest())
            self.assertFalse(any('.tooling' in n or ('androidTest' in n and n.endswith('.apk')) for n in archive.namelist()))
            self.assertEqual(b'Public manual', archive.read('source/docs/MANUAL_PRODUCTO.md'))
            self.assertNotIn(b'DO-NOT-BUNDLE-SYNTHETIC-SECRET', b''.join(archive.read(n) for n in archive.namelist()))
            self.assertEqual(0o755, (archive.getinfo('api/bin/ktor').external_attr >> 16) & 0o777)
            self.assertFalse(json.loads(archive.read('manifest.json'))['operator_distribution_signature'])

    def test_partial_native_run_is_not_packaged(self):
        self.write('summary.json', [{'component': c, 'stage': s, 'approved': True} for c, s in EXPECTED_STAGES[:-1]])
        with self.assertRaisesRegex(ValueError, 'complete'): self.package()
        self.assertFalse(self.destination.exists())

    def test_current_app_must_match_tested_apk(self):
        (self.root / 'apps/agent/build/outputs/apk/debug/agent-debug.apk').write_bytes(b'new-build')
        with self.assertRaisesRegex(ValueError, 'hashes differ'): self.package()
        self.assertFalse(self.destination.exists())

    def test_instrumentation_apk_must_also_match(self):
        (self.root / 'apps/control/build/outputs/apk/androidTest/debug/control-debug-androidTest.apk').write_bytes(b'new-test-build')
        with self.assertRaisesRegex(ValueError, 'hashes differ'): self.package()

    def test_existing_archive_is_preserved(self):
        self.destination.parent.mkdir()
        self.destination.write_bytes(b'previous-delivery')
        with self.assertRaisesRegex(ValueError, 'preserved'): self.package()
        self.assertEqual(b'previous-delivery', self.destination.read_bytes())

    def test_missing_api_distribution_is_not_packaged(self):
        (self.root / 'backend/ktor/build/install/ktor/lib/ktor.jar').unlink()
        with self.assertRaisesRegex(ValueError, 'installDist'): self.package()

    def test_symlink_input_is_rejected(self):
        target = self.root / 'backend/ktor/build/install/ktor/bin/ktor'
        target.unlink()
        target.symlink_to(self.root / '.tooling/fixture.json')
        with self.assertRaisesRegex(ValueError, 'regular files'): self.package()

    def test_changed_apk_between_preflight_and_snapshot_is_rejected(self):
        original = package_qa.payloads
        def mutate(root):
            files = original(root)
            files['apks/auditlab-agent-debug.apk'].write_bytes(b'concurrent-rebuild')
            return files
        with patch.object(package_qa, 'payloads', side_effect=mutate):
            with self.assertRaisesRegex(ValueError, 'changed during packaging'): self.package()
        self.assertFalse(self.destination.exists())

    def test_tracked_private_file_is_rejected(self):
        self.tracked.append('.tooling/fixture.json')
        with self.assertRaisesRegex(ValueError, 'Private or generated'): self.package()
        self.assertFalse(self.destination.exists())

    def test_source_wrapper_preserves_its_executable_permission(self):
        path = self.root / 'gradlew'
        path.write_text('#!/bin/sh\n'); path.chmod(0o755)
        self.tracked.append('gradlew')
        self.package()
        with zipfile.ZipFile(self.destination) as archive:
            self.assertEqual(0o755, (archive.getinfo('source/gradlew').external_attr >> 16) & 0o777)


if __name__ == '__main__': unittest.main()
