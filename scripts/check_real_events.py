"""Native metadata check on a dedicated empty auditlab-real-* emulator, never a physical phone.

The emulator modem generates incoming SMS/calls; the stock SMS/dialer apps send an SMS
and place an outgoing call. No production write-SMS or call permissions are added.
Private fixture input travels over stdin and is removed after the run. No app data is cleared.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET
import local_fixture as fixture
from check_android import protocol, validate_serial

ROOT = Path(__file__).resolve().parents[1]
ADB = ROOT / '.tooling/android-sdk/platform-tools/adb'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, type=validate_serial)
    parser.add_argument('--resume-owned', action='store_true', help='Resume only an interrupted run marked by this checker')
    parser.add_argument('--reuse-empty-qa', action='store_true', help='Reuse an AuditLab QA emulator only when its Room registration and queue are empty')
    parser.add_argument('--output-directory', type=Path, default=ROOT / '.tooling/real-events-validation')
    args = parser.parse_args()
    serial = args.serial
    output = args.output_directory.resolve()
    if not output.is_relative_to((ROOT / '.tooling').resolve()): raise ValueError('Evidence must remain under the private .tooling directory')
    output.mkdir(parents=True, exist_ok=True)
    (output / 'native-verdict.json').write_text(json.dumps({'approved': False, 'state': 'running'})+'\n')

    def adb(*parts, data=None, check=True, timeout=60):
        if parts and parts[0] == 'shell': parts = ('shell', shlex.join(map(str, parts[1:])))
        result = subprocess.run([str(ADB), '-s', serial, *map(str, parts)], input=data,
                                capture_output=True, timeout=timeout)
        if check and result.returncode: raise RuntimeError('ADB operation failed; no private input printed')
        return result

    avd = adb('emu', 'avd', 'name').stdout.decode().splitlines()[0]
    if not avd.startswith('auditlab-real-') and not (args.reuse_empty_qa and avd.startswith('auditlab-')):
        raise RuntimeError('A dedicated AuditLab QA AVD is required')
    if adb('shell', 'getprop', 'sys.boot_completed').stdout.strip() != b'1': raise RuntimeError('AVD not booted')
    config = fixture.settings()
    label = fixture.run(config, fixture.DOCKER + ['inspect', '--format',
        '{{index .Config.Labels "com.auditlab.qa-fixture"}}', config['container']])[1]
    if label != str(fixture.DIRECTORY) or not fixture.ready(config): raise RuntimeError('Owned ready QA fixture required')
    # Preserve existing registrations. This check requires a fresh, dedicated app installation.
    old = adb('shell', 'run-as', 'com.auditlab.agent', 'ls', 'databases/auditlab-outbox.db', check=False)
    previous_marker = adb('shell', 'run-as', 'com.auditlab.agent', 'sh', '-c', 'test -f files/qa-real-ready', check=False)
    resume = old.returncode == 0 and args.resume_owned and previous_marker.returncode == 0
    empty = False
    if old.returncode == 0 and args.reuse_empty_qa:
        rows = adb('shell', 'run-as', 'com.auditlab.agent', '/system/bin/sqlite3', 'databases/auditlab-outbox.db',
                   'SELECT (SELECT count(*) FROM agent_registration)+(SELECT count(*) FROM agent_outbox);')
        empty = rows.stdout.strip() == b'0'
    if old.returncode == 0 and not resume and not empty:
        raise RuntimeError('Existing agent database preserved; use another empty dedicated AVD')
    for apk in ['apps/agent/build/outputs/apk/debug/agent-debug.apk',
                'apps/agent/build/outputs/apk/androidTest/debug/agent-debug-androidTest.apk']:
        adb('install', '-r', ROOT / apk, timeout=120)
    for permission in ['READ_SMS', 'READ_CALL_LOG']:
        adb('shell', 'pm', 'grant', 'com.auditlab.agent', 'android.permission.' + permission)
    adb('shell', 'run-as', 'com.auditlab.agent', 'mkdir', '-p', 'files')
    payload = json.dumps({'isolated': True, 'resumeOwned': resume, 'url': 'http://10.0.2.2:8081', 'password': config['passwords']['admin']}).encode()
    adb('shell', 'run-as', 'com.auditlab.agent', 'sh', '-c', 'cat > files/qa-real-events.json', data=payload)
    payload = None
    adb('shell', 'run-as', 'com.auditlab.agent', 'rm', '-f', 'files/qa-real-ready')
    # A pre-consent SMS must not be imported by the monitoring session.
    adb('emu', 'sms', 'send', '15555210001', 'AuditLab_before_consent')
    time.sleep(3)
    process = None
    try:
        with (output / 'native-real.log').open('wb') as log:
            process = subprocess.Popen([str(ADB), '-s', serial, 'shell', 'am', 'instrument', '-w', '-r',
                '-e', 'class', 'com.auditlab.agent.NativeRealEventsTest',
                'com.auditlab.agent.test/androidx.test.runner.AndroidJUnitRunner'], stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 360
            while adb('shell', 'run-as', 'com.auditlab.agent', 'sh', '-c', 'test -f files/qa-real-ready', check=False).returncode:
                if process.poll() is not None or time.monotonic() > deadline: raise RuntimeError('Native test did not reach capture-ready state')
                time.sleep(1)
            print('Native consent/enrollment/foreground service ready.', flush=True)
            adb('emu', 'sms', 'send', '15555210002', 'AuditLab_authorized_incoming')
            adb('emu', 'gsm', 'call', '15555210003')
            time.sleep(4)
            adb('emu', 'gsm', 'cancel', '15555210003')
            print('Incoming SMS and missed call generated by emulator modem.', flush=True)
            adb('shell', 'am', 'start', '-a', 'android.intent.action.CALL', '-d', 'tel:15555210004')
            # The stock dialer may take longer than five seconds on software emulation.
            # Wait for the real modem call, then hang up through Android's end-call key.
            deadline = time.monotonic() + 40
            while b'15555210004' not in adb('emu', 'gsm', 'list').stdout:
                if time.monotonic() > deadline: raise RuntimeError('Stock dialer did not place the outgoing call')
                time.sleep(1)
            adb('shell', 'input', 'keyevent', '6')
            deadline = time.monotonic() + 25
            while b'15555210004' in adb('emu', 'gsm', 'list').stdout:
                if time.monotonic() > deadline: raise RuntimeError('Outgoing call did not end; no completed-call record claimed')
                adb('shell', 'input', 'keyevent', '6')
                time.sleep(1)
            adb('shell', 'am', 'start', '-a', 'android.intent.action.SENDTO', '-d', 'smsto:15555210005',
                '--es', 'sms_body', 'AuditLab_outgoing_QA')
            deadline = time.monotonic() + 35
            while True:
                adb('shell', 'uiautomator', 'dump', '/sdcard/qa-real-sms.xml')
                xml = adb('shell', 'cat', '/sdcard/qa-real-sms.xml').stdout
                (output / 'sms-system-ui.xml').write_bytes(xml)
                nodes = ET.fromstring(xml).iter('node')
                button = next((n for n in nodes if 'send_message_button' in n.get('resource-id', '')
                               and n.get('enabled') == 'true'), None)
                if button is not None:
                    coordinates = list(map(int, re.findall(r'\d+', button.attrib['bounds'])))
                    if len(coordinates) != 4: raise RuntimeError('Invalid native SMS send button')
                    x1, y1, x2, y2 = coordinates
                    adb('shell', 'input', 'tap', (x1+x2)//2, (y1+y2)//2)
                    break
                if time.monotonic() > deadline: raise RuntimeError('Stock SMS send UI unavailable; outgoing SMS not validated')
                time.sleep(1)
            print('Outgoing call and SMS requested through stock Android apps.', flush=True)
            adb('shell', 'am', 'start', '-n', 'com.auditlab.agent/.MainActivity')
            process.wait(timeout=170)
        verdict = protocol((output / 'native-real.log').read_text(errors='replace'))
        verdict['process_exit'] = process.returncode
        (output / 'native-verdict.json').write_text(json.dumps(verdict, indent=2)+'\n')
        if not verdict['approved'] or process.returncode: raise RuntimeError('Native real-event test failed; private log retained')
        result = json.loads(adb('shell', 'run-as', 'com.auditlab.agent', 'cat', 'files/qa-real-result.json').stdout)
        result.update(avd=avd, serial=serial, system_modem_and_apps=True, physical_phone=False)
        (output / 'native-result.json').write_text(json.dumps(result, indent=2)+'\n')
        print('Native real SMS/call capture, API roundtrip, deduplication, CSV and withdrawal passed.')
    finally:
        if process is not None and process.poll() is None:
            # Stop only this host command. The native test and database remain for diagnosis.
            process.terminate()
            process.wait(timeout=10)
        adb('shell', 'run-as', 'com.auditlab.agent', 'rm', '-f', 'files/qa-real-events.json', check=False)


if __name__ == '__main__': main()
