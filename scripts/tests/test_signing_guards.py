"""Signing preflight preserves existing artifacts and rejects private inputs in source."""
import contextlib
import io
import sys
import tempfile
import subprocess
from pathlib import Path
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import sign_release

class SigningGuardsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='auditlab-signing-guards-')
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.root = self.base / 'checkout'
        self.root.mkdir()
        self.private = self.base / 'private'
        self.private.mkdir(mode=0o700)
        self.paths = [self.private / name for name in ['key.jks', 'store-password', 'key-password']]
        for path in self.paths:
            path.write_text('synthetic-private-input')
            path.chmod(0o600)

    def reject(self, message):
        arguments = ['sign_release.py', '--keystore', str(self.paths[0]), '--alias', 'qa',
                     '--store-pass-file', str(self.paths[1]), '--key-pass-file', str(self.paths[2])]
        with patch.object(sign_release, 'ROOT', self.root), patch.object(sys, 'argv', arguments), \
                patch.object(sign_release.subprocess, 'run') as run, contextlib.redirect_stdout(io.StringIO()) as output:
            with self.assertRaisesRegex(SystemExit, message): sign_release.main()
            run.assert_not_called()
            self.assertNotIn('synthetic-private-input', output.getvalue())

    def test_key_inside_checkout_is_rejected(self):
        self.paths[0] = self.root / 'private-key.jks'
        self.paths[0].write_bytes(b'private-qa'); self.paths[0].chmod(0o600)
        self.reject('outside the checkout')

    def test_publicly_readable_key_is_rejected(self):
        self.paths[0].chmod(0o644)
        self.reject('mode 600')

    def test_missing_second_apk_does_not_start_signing_the_first(self):
        first = self.root / 'apps/agent/build/outputs/apk/release/agent-release-unsigned.apk'
        first.parent.mkdir(parents=True); first.write_bytes(b'unsigned-qa')
        self.reject('both release APKs')
        self.assertFalse((self.root / '.tooling/release').exists())
        self.assertEqual(b'unsigned-qa', first.read_bytes())

    def test_existing_second_artifact_is_preserved_before_any_signing(self):
        for component in ['agent', 'control']:
            source = self.root / f'apps/{component}/build/outputs/apk/release/{component}-release-unsigned.apk'
            source.parent.mkdir(parents=True); source.write_bytes(b'unsigned-qa')
        target = self.root / '.tooling/release/auditlab-control-0.2.0-rc1.apk'
        target.parent.mkdir(parents=True); target.write_bytes(b'existing-qa-release')
        self.reject('Existing signed APK preserved')
        self.assertEqual(b'existing-qa-release', target.read_bytes())
        self.assertFalse((target.parent / 'auditlab-agent-0.2.0-rc1.apk').exists())

    def test_reused_password_file_is_read_independently_without_secret_in_argv(self):
        for component in ['agent', 'control']:
            source = self.root / f'apps/{component}/build/outputs/apk/release/{component}-release-unsigned.apk'
            source.parent.mkdir(parents=True); source.write_bytes(b'unsigned-qa')
        sdk = self.root / 'sdk'
        signer = sdk / 'build-tools/35.0.0/apksigner'
        signer.parent.mkdir(parents=True); signer.touch()
        arguments = ['sign_release.py', '--keystore', str(self.paths[0]), '--alias', 'qa',
                     '--store-pass-file', str(self.paths[1]), '--key-pass-file', str(self.paths[1])]
        def run(command, **kwargs):
            self.assertNotIn('synthetic-private-input', ' '.join(command))
            if command[1] == 'sign':
                self.assertEqual('synthetic-private-input', kwargs['env']['AUDITLAB_SIGN_STORE_PASSWORD'])
                self.assertEqual('synthetic-private-input', kwargs['env']['AUDITLAB_SIGN_KEY_PASSWORD'])
                Path(command[command.index('--out') + 1]).write_bytes(b'argument-formation-fixture')
            return subprocess.CompletedProcess(command, 0)
        with patch.object(sign_release, 'ROOT', self.root), patch.object(sys, 'argv', arguments), \
                patch.dict(sign_release.os.environ, {'ANDROID_HOME': str(sdk)}), \
                patch.object(sign_release.subprocess, 'run', side_effect=run) as tool, \
                contextlib.redirect_stdout(io.StringIO()) as output:
            sign_release.main()
            self.assertEqual(4, tool.call_count)
            self.assertNotIn('synthetic-private-input', output.getvalue())

    def test_empty_password_is_rejected_without_printing_its_contents(self):
        self.paths[1].write_text('')
        with self.assertRaisesRegex(SystemExit, 'nonempty UTF-8'):
            sign_release.password_file(self.paths[1])

if __name__ == '__main__': unittest.main()
