"""Exercise update requests/visible notification/local decline/revocation on owned QA only.
Temporarily configures the loopback QA API with a synthetic release catalog, then
restores its empty catalog. Does not download/install a release or erase app data.
"""
import argparse
import json
import os
from pathlib import Path
import shlex
import subprocess
import uuid
import local_fixture as fixture
from check_android import validate_serial


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--agent-serial', required=True, type=validate_serial)
    parser.add_argument('--catalog', type=Path, required=True)
    args = parser.parse_args()
    if not args.agent_serial.startswith('emulator-'):
        parser.error('Dedicated emulator required')
    cfg = fixture.settings()
    if cfg['http_port'] != 8081 or not fixture.ready(cfg):
        raise RuntimeError('Owned QA API must already be ready on 8081')
    out = fixture.ROOT / '.tooling' / ('administration-native-' + uuid.uuid4().hex[:12])
    out.mkdir(mode=0o700)
    adb = fixture.ROOT / '.tooling/android-sdk/platform-tools/adb'
    package = 'com.auditlab.agent'

    def call(arguments, data=None, timeout=180):
        if arguments[0] == 'shell':
            arguments = ['shell', shlex.join(arguments[1:])]
        result = subprocess.run([str(adb), '-s', args.agent_serial, *arguments], input=data, capture_output=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError('QA ADB operation failed; credentials not printed')
        return result.stdout

    subprocess.run(['python3', 'scripts/local_fixture.py', 'stop'], check=True)
    try:
        fixture.start(cfg, args.catalog)
        for apk in ['apps/agent/build/outputs/apk/debug/agent-debug.apk', 'apps/agent/build/outputs/apk/androidTest/debug/agent-debug-androidTest.apk']:
            call(['install', '-r', '-t', str(fixture.ROOT / apk)])
        identity = {'isolated': True, 'url': 'http://10.0.2.2:8081', 'password': cfg['passwords']['admin']}
        call(['shell', 'run-as', package, 'sh', '-c', 'mkdir -p files; cat > files/qa-administration.json; chmod 600 files/qa-administration.json'], json.dumps(identity).encode())
        result = call(['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'com.auditlab.agent.NativeAdministrationTest',
                       package + '.test/androidx.test.runner.AndroidJUnitRunner'], timeout=240)
        with os.fdopen(os.open(out / 'instrumentation.log', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb') as log:
            log.write(result)
        if b'OK (1 test)' not in result:
            raise RuntimeError(f'Native administration test failed; inspect {out}/instrumentation.log')
        report = json.loads(call(['shell', 'run-as', package, 'cat', 'files/qa-administration-result.json']))
        report.update(api=int(call(['shell', 'getprop', 'ro.build.version.sdk']).strip()), tests=1, synthetic_release=True)
        (out / 'result.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2))
    finally:
        try:
            call(['shell', 'run-as', package, 'rm', '-f', 'files/qa-administration.json', 'files/qa-administration-result.json'])
        finally:
            subprocess.run(['python3', 'scripts/local_fixture.py', 'stop'], check=True)
            fixture.start(cfg)


if __name__ == '__main__':
    main()
