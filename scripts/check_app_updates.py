"""Verify a newer signed QA APK and Android installer UI on a dedicated emulator.

Installs the normal debug app/test with -r (preserves app data), then supplies a QA
candidate to the app's private cache. Instrumentation verifies it, opens/cancels
Android's installer, and rejects corruption/signature/downgrade. It never confirms
installation or changes unknown-source settings. No physical phones accepted.
"""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import uuid
from build_release_catalog import entry
from check_android import validate_serial

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, type=validate_serial)
    parser.add_argument('--app', required=True, choices=['agent', 'control'])
    parser.add_argument('--candidate', required=True, type=Path)
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        parser.error('A dedicated QA emulator is required')
    release = entry(args.candidate)
    if release['app'] != args.app:
        parser.error('Candidate does not match the selected app')
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    out = ROOT / '.tooling' / f'update-native-{args.app}-{stamp}-{uuid.uuid4().hex[:6]}'
    out.mkdir(mode=0o700)
    adb = ROOT / '.tooling/android-sdk/platform-tools/adb'
    package = f'com.auditlab.{args.app}'

    def call(arguments, data=None, timeout=180):
        if arguments[0] == 'shell':
            arguments = ['shell', shlex.join(arguments[1:])]
        result = subprocess.run([str(adb), '-s', args.serial, *arguments], input=data, capture_output=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError('QA ADB operation failed; no application data was erased')
        return result.stdout

    base = ROOT / f'apps/{args.app}/build/outputs/apk/debug/{args.app}-debug.apk'
    test = ROOT / f'apps/{args.app}/build/outputs/apk/androidTest/debug/{args.app}-debug-androidTest.apk'
    call(['install', '-r', '-t', str(base)])
    call(['install', '-r', '-t', str(test)])
    remote = call(['shell', 'pm', 'path', package]).decode().strip().removeprefix('package:')
    installed = call(['exec-out', 'cat', remote])
    assert hashlib.sha256(installed).hexdigest() == hashlib.sha256(base.read_bytes()).hexdigest()
    try:
        call(['shell', 'run-as', package, 'sh', '-c', 'mkdir -p files cache/app-updates; cat > files/qa-update-release.json'],
             json.dumps(release).encode())
        call(['shell', 'run-as', package, 'sh', '-c', 'cat > cache/app-updates/qa-candidate.apk'], args.candidate.read_bytes())
        result = call(['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                       'com.auditlab.design.NativeAppUpdateTest', f'{package}.test/androidx.test.runner.AndroidJUnitRunner'], timeout=240)
        (out / 'instrumentation.log').write_bytes(result)
        if b'OK (2 tests)' not in result:
            raise RuntimeError(f'Native update verification failed; inspect {out}/instrumentation.log')
        report = {'passed': True, 'app': args.app, 'serial': args.serial,
                  'api': int(call(['shell', 'getprop', 'ro.build.version.sdk']).strip()),
                  'base_sha256': hashlib.sha256(installed).hexdigest(), 'candidate_sha256': release['sha256'],
                  'candidate_version': release['versionName'], 'tests': 2, 'synthetic_candidate': True,
                  'signed_newer_apk_verified': True, 'corruption_signer_and_downgrade_rejected': True,
                  'system_installer_or_source_confirmation_shown': True, 'installation_confirmed': False,
                  'unknown_source_settings_changed': False, 'app_data_erased': False}
        (out / 'result.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2))
    finally:
        call(['shell', 'run-as', package, 'rm', '-f', 'files/qa-update-release.json',
              'cache/app-updates/qa-candidate.apk', 'cache/app-updates/qa-corrupt.apk'])


if __name__ == '__main__':
    main()
