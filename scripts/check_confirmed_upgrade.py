"""Two-stage, locally confirmed upgrade test on a dedicated QA emulator only.

Prepare opens Android's installer and waits for its confirmation page. The local
operator confirms installation in Android; verify checks enrollment, Keystore and
the pending Room event after replacement. Never clicks Install or grants a source.
Cleanup removes only this test's private files; it never downgrades or erases app data.
"""
import argparse
import datetime
import json
import os
from pathlib import Path
import shlex
import subprocess
import uuid
import local_fixture as fixture
from build_release_catalog import entry
from check_android import validate_serial


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--agent-serial', required=True, type=validate_serial)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--phase', choices=['prepare', 'verify', 'cleanup'], required=True)
    args = parser.parse_args()
    if not args.agent_serial.startswith('emulator-'):
        parser.error('A dedicated QA emulator is required')
    cfg = fixture.settings()
    if cfg['http_port'] != 8081 or not fixture.ready(cfg):
        raise RuntimeError('Owned QA API must be ready on loopback 8081')
    metadata = entry(args.candidate)
    if metadata['app'] != 'agent':
        parser.error('An Agent QA candidate is required')
    out = fixture.ROOT / '.tooling' / 'confirmed-upgrade-qa' / args.agent_serial
    out.mkdir(parents=True, exist_ok=True, mode=0o700)
    adb = fixture.ROOT / '.tooling/android-sdk/platform-tools/adb'

    def call(arguments, data=None, timeout=180):
        if arguments[0] == 'shell':
            arguments = ['shell', shlex.join(arguments[1:])]
        result = subprocess.run([str(adb), '-s', args.agent_serial, *arguments], input=data, capture_output=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError('QA operation failed; credentials not printed')
        return result.stdout

    package = 'com.auditlab.agent'
    if args.phase == 'cleanup':
        call(['shell', 'run-as', package, 'rm', '-f', 'files/qa-upgrade-config.json', 'files/qa-upgrade-release.json',
              'files/qa-upgrade-before.json', 'files/qa-upgrade-result.json', 'cache/app-updates/qa-upgrade.apk'])
        print('Only temporary QA files removed. Installed version and app data preserved.')
        return
    if args.phase == 'prepare':
        call(['install', '-r', '-t', str(fixture.ROOT / 'apps/agent/build/outputs/apk/debug/agent-debug.apk')])
        identity = {'isolated': True, 'url': 'http://10.0.2.2:8081', 'password': cfg['passwords']['admin']}
        for name, data in [('qa-upgrade-config.json', json.dumps(identity).encode()), ('qa-upgrade-release.json', json.dumps(metadata).encode())]:
            call(['shell', 'run-as', package, 'sh', '-c', f'mkdir -p files cache/app-updates; cat > files/{name}; chmod 600 files/{name}'], data)
        call(['shell', 'run-as', package, 'sh', '-c', 'cat > cache/app-updates/qa-upgrade.apk'], args.candidate.read_bytes())
    call(['install', '-r', '-t', str(fixture.ROOT / 'apps/agent/build/outputs/apk/androidTest/debug/agent-debug-androidTest.apk')])
    method = 'prepareQueueAndOpenInstallerForLocalConfirmation' if args.phase == 'prepare' else 'verifyEnrollmentKeystoreAndQueueAfterConfirmedReplacement'
    result = call(['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                   'com.auditlab.agent.NativeUpgradePreservationTest#' + method, package + '.test/androidx.test.runner.AndroidJUnitRunner'], timeout=240)
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:6]
    with os.fdopen(os.open(out / (args.phase + '-' + stamp + '.log'), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb') as log:
        log.write(result)
    if b'OK (1 test)' not in result:
        raise RuntimeError(f'Native phase failed; inspect {out}. No app data was erased.')
    if args.phase == 'prepare':
        print('Android confirmation ready. Confirm installation locally, then run --phase verify.')
    else:
        report = json.loads(call(['shell', 'run-as', package, 'cat', 'files/qa-upgrade-result.json']))
        report.update(candidate_sha256=metadata['sha256'], candidate_version=metadata['versionName'], tests=2, synthetic_candidate=True)
        (out / ('result-' + stamp + '.json')).write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
