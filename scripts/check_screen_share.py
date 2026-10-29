"""Exercise real, locally authorized MediaProjection on an empty dedicated QA emulator."""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import local_fixture as fixture
from check_android import protocol, validate_serial

ROOT = Path(__file__).resolve().parents[1]
ADB = ROOT / '.tooling/android-sdk/platform-tools/adb'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, type=validate_serial)
    parser.add_argument('--pause-other', type=validate_serial)
    args = parser.parse_args()
    if args.serial == args.pause_other: raise ValueError('Emulators must be distinct')
    config = fixture.settings()
    if not fixture.ready(config): raise RuntimeError('Ready private QA fixture required')
    output = ROOT / '.tooling/screen-share-validation'
    output.mkdir(exist_ok=True, mode=0o700)

    def adb(parts, data=None, required=True, timeout=60, serial=None):
        result = subprocess.run([str(ADB), '-s', serial or args.serial, *parts], input=data,
                                capture_output=True, timeout=timeout)
        if required and result.returncode: raise RuntimeError('ADB failed; no private input printed')
        return result

    def shell(parts, **kwargs): return adb(['shell', shlex.join(parts)], **kwargs)
    if shell(['getprop', 'sys.boot_completed']).stdout.strip() != b'1': raise RuntimeError('Booted QA emulator required')
    avd = adb(['emu', 'avd', 'name']).stdout.decode().splitlines()[0]
    if not avd.startswith('auditlab-'): raise RuntimeError('Dedicated AuditLab QA AVD required')
    installed = shell(['run-as', 'com.auditlab.agent', 'ls', 'databases/auditlab-outbox.db'], required=False)
    if installed.returncode == 0:
        count = shell(['run-as', 'com.auditlab.agent', '/system/bin/sqlite3', 'databases/auditlab-outbox.db',
                       'SELECT (SELECT count(*) FROM agent_registration)+(SELECT count(*) FROM agent_outbox);']).stdout.strip()
        if count != b'0': raise RuntimeError('Existing agent registration/events preserved; finish withdrawal first')
    hashes = {}
    for path in ['apps/agent/build/outputs/apk/debug/agent-debug.apk',
                 'apps/agent/build/outputs/apk/androidTest/debug/agent-debug-androidTest.apk']:
        apk = ROOT / path
        adb(['install', '-r', str(apk)], timeout=120)
        hashes[path] = hashlib.sha256(apk.read_bytes()).hexdigest()
    shell(['run-as', 'com.auditlab.agent', 'mkdir', '-p', 'files'])
    payload = json.dumps({'isolated': True, 'url': 'http://10.0.2.2:8081', 'password': config['passwords']['admin']}).encode()
    shell(['run-as', 'com.auditlab.agent', 'sh', '-c', 'cat > files/qa-screen.json; chmod 600 files/qa-screen.json'], data=payload)
    payload = None
    try:
        if args.pause_other: adb(['emu', 'avd', 'pause'], serial=args.pause_other)
        shell(['input', 'keyevent', '224'])
        shell(['am', 'force-stop', 'com.auditlab.agent'])
        result = adb(['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                      'com.auditlab.agent.NativeScreenShareTest',
                      'com.auditlab.agent.test/androidx.test.runner.AndroidJUnitRunner'], required=False, timeout=360)
        log = output / 'native-screen.log'
        fixture.private_write(log, result.stdout.decode() + result.stderr.decode())
        verdict = protocol(result.stdout.decode()) | {'hashes': hashes, 'serial': args.serial, 'avd': avd}
        (output / 'verdict.json').write_text(json.dumps(verdict, indent=2)+'\n')
        print(json.dumps({key: verdict[key] for key in ['approved', 'started', 'passed', 'failed', 'skipped']}))
        if not verdict['approved']: raise RuntimeError('Native screen test failed; private log retained')
        if args.pause_other:
            adb(['emu', 'avd', 'start'], serial=args.pause_other)
            adb(['emu', 'avd', 'pause'])
            for name in ['apps/control/build/outputs/apk/debug/control-debug.apk',
                         'apps/control/build/outputs/apk/androidTest/debug/control-debug-androidTest.apk']:
                path = ROOT / name
                adb(['install', '-r', str(path)], serial=args.pause_other, timeout=120)
                hashes[name] = hashlib.sha256(path.read_bytes()).hexdigest()
            payload = json.dumps({'isolated': True, 'url': 'http://10.0.2.2:8081', 'password': config['passwords']['admin']}).encode()
            shell(['run-as', 'com.auditlab.control', 'sh', '-c', 'mkdir -p files; cat > files/qa-screen.json; chmod 600 files/qa-screen.json'], serial=args.pause_other, data=payload)
            payload = None
            shell(['input', 'keyevent', '224'], serial=args.pause_other)
            response = adb(['shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                            'com.auditlab.control.NativeScreenViewerTest',
                            'com.auditlab.control.test/androidx.test.runner.AndroidJUnitRunner'],
                           serial=args.pause_other, required=False, timeout=240)
            fixture.private_write(output / 'native-viewer.log', response.stdout.decode()+response.stderr.decode())
            viewer = protocol(response.stdout.decode())
            verdict.update(viewer=viewer, hashes=hashes)
            verdict['approved'] = verdict['approved'] and viewer['approved']
            (output / 'verdict.json').write_text(json.dumps(verdict, indent=2)+'\n')
            print('Control viewer: '+json.dumps(viewer))
            if not viewer['approved']: raise RuntimeError('Native viewer failed; private log retained')
    finally:
        if args.pause_other:
            adb(['emu', 'avd', 'start'], required=False)
            shell(['run-as', 'com.auditlab.control', 'rm', '-f', 'files/qa-screen.json'], serial=args.pause_other, required=False)
        shell(['run-as', 'com.auditlab.agent', 'rm', '-f', 'files/qa-screen.json'], required=False)
        if args.pause_other: adb(['emu', 'avd', 'start'], serial=args.pause_other, required=False)


if __name__ == '__main__': main()
