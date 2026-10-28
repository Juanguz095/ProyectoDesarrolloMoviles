"""Real two-emulator acceptance against the private loopback QA fixture.
Requires assembled debug + androidTest APKs and two already booted dedicated emulators.
Refuses existing credentials/registrations. Never uninstalls, clears app data or deletes databases.
Private credentials travel over adb stdin into an app-private temporary file, never CLI arguments.
"""
from __future__ import annotations
import argparse
import datetime as dt
import hashlib
import http.server
import http.client
import ssl
import socket
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import subprocess
import threading
import time
import uuid
import xml.etree.ElementTree as ET
import local_fixture as fixture

ROOT = Path(__file__).resolve().parents[1]
ADB = ROOT / '.tooling/android-sdk/platform-tools/adb'
EXPECTED_STAGES = [('agent', 'enroll'), ('control', 'login-read'), ('control', 'create-code'),
    ('control', 'restore-read'), ('agent', 'restore'), ('agent', 'offline'), ('agent', 'recover'),
    ('agent', 'permission-denied'), ('control', 'revoke'), ('agent', 'remote-revoked'),
    ('agent', 'enroll'), ('agent', 'withdraw-offline'), ('agent', 'restore-withdrawal'),
    ('agent', 'confirm-withdrawal'), ('control', 'export-rotation'), ('control', 'auditor'), ('control', 'tls-client')]


def complete(results: list[dict]) -> bool:
    return [(item['component'], item['stage']) for item in results] == EXPECTED_STAGES and all(item['approved'] for item in results)


def protocol(text: str) -> dict:
    lines = text.splitlines()
    passed = sum(line == 'INSTRUMENTATION_STATUS_CODE: 0' for line in lines)
    failed = sum(line in {'INSTRUMENTATION_STATUS_CODE: -1', 'INSTRUMENTATION_STATUS_CODE: -2'} for line in lines)
    skipped = sum(line in {'INSTRUMENTATION_STATUS_CODE: -3', 'INSTRUMENTATION_STATUS_CODE: -4'} for line in lines)
    started = sum(line == 'INSTRUMENTATION_STATUS_CODE: 1' for line in lines)
    final = sum(line == 'INSTRUMENTATION_CODE: -1' for line in lines)
    return {'started': started, 'passed': passed, 'failed': failed, 'skipped': skipped,
            'approved': started == 1 and passed == 1 and failed == 0 and skipped == 0 and final == 1 and 'OK (1 test)' in text and 'FAILURES!!!' not in text}


def validate_serial(value: str) -> str:
    if not re.fullmatch(r'emulator-[0-9]{4,5}', value):
        raise ValueError('Use a dedicated emulator serial; physical devices are not modified by this QA script')
    return value


class Run:
    def __init__(self, agent: str, control: str):
        self.serials = {'agent': validate_serial(agent), 'control': validate_serial(control)}
        if agent == control: raise ValueError('Two distinct emulators are required')
        self.config = fixture.settings()
        self.token = uuid.uuid4().hex[:16]
        self.name = 'qa-native-' + self.token + '.json'
        self.output = ROOT / '.tooling/android-native' / self.token
        self.output.mkdir(parents=True, mode=0o700)
        self.results: list[dict] = []
        self.settings: dict[str, str] = {}
        self.identity = {'isolated': True, 'url': 'http://10.0.2.2:8081', 'password': self.config['passwords']['admin']}
        self.probe = None
        self.devices = {}
        self.artifacts = []
        self.animation_settings = {}
        self.completed = False

    def adb(self, component, args, data=None, timeout=40, required=True):
        result = subprocess.run([str(ADB), '-s', self.serials[component], *args], input=data,
                                capture_output=True, timeout=timeout)
        if required and result.returncode: raise RuntimeError('ADB operation failed; no private arguments printed')
        return result

    def shell(self, component, args, **kwargs):
        return self.adb(component, ['shell', shlex.join([str(a) for a in args])], **kwargs)

    def preflight(self):
        owner = fixture.run(self.config, fixture.DOCKER + ['inspect', '--format',
            '{{index .Config.Labels "com.auditlab.qa-fixture"}}', self.config['container']])[1]
        if owner != str(fixture.DIRECTORY) or self.config['http_port'] != 8081 or not fixture.ready(self.config):
            raise RuntimeError('Only the ready private QA fixture on loopback 8081 is allowed')
        for component in self.serials:
            boot = self.shell(component, ['getprop', 'sys.boot_completed']).stdout.decode().strip()
            sdk = int(self.shell(component, ['getprop', 'ro.build.version.sdk']).stdout)
            self.devices[component] = {'serial': self.serials[component], 'sdk': sdk, 'boot_completed': boot}
            if boot != '1' or sdk < 26: raise RuntimeError('Emulator must be booted on API26 or later')
            package = 'com.auditlab.' + component
            installed = self.shell(component, ['pm', 'path', package], required=False).stdout.strip()
            if not installed: continue
            # Unknown/non-debuggable existing data is preserved rather than treated as empty.
            check = self.shell(component, ['run-as', package, 'ls', '.'], required=False)
            if check.returncode: raise RuntimeError('Existing app data cannot be inspected; use a dedicated QA emulator')
            pref = 'shared_prefs/auditlab_' + component + '.xml'
            content = self.shell(component, ['run-as', package, 'cat', pref], required=False)
            if content.returncode == 0:
                node = ET.fromstring(content.stdout)
                if any((item.text or '').strip() for item in node.findall('string')):
                    raise RuntimeError('Existing encrypted credentials preserved; withdraw/logout in the app or use a fresh AVD')
            if component == 'agent':
                exists = self.shell(component, ['run-as', package, 'ls', 'databases/auditlab-outbox.db'], required=False)
                if exists.returncode == 0:
                    count = self.sql('SELECT (SELECT count(*) FROM agent_registration)+(SELECT count(*) FROM agent_outbox);')
                    if count != '0': raise RuntimeError('Existing registration/events preserved; finish withdrawal or use a fresh AVD')

    def sql(self, text):
        return self.shell('agent', ['run-as', 'com.auditlab.agent', '/system/bin/sqlite3',
            'databases/auditlab-outbox.db', text]).stdout.decode().strip()

    def install(self, component):
        inactive = 'control' if component == 'agent' else 'agent'
        self.adb(inactive, ['emu', 'avd', 'pause'])
        try:
            for path in [ROOT / f'apps/{component}/build/outputs/apk/debug/{component}-debug.apk',
                         ROOT / f'apps/{component}/build/outputs/apk/androidTest/debug/{component}-debug-androidTest.apk']:
                if not path.is_file(): raise RuntimeError('Build debug and androidTest APKs first')
                self.adb(component, ['install', '-r', '-t', str(path)], timeout=300)
                self.artifacts.append({'component': component, 'file': path.name, 'bytes': path.stat().st_size, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
        finally:
            self.adb(inactive, ['emu', 'avd', 'start'], required=False)
        if component == 'agent' and int(self.shell(component, ['getprop', 'ro.build.version.sdk']).stdout) >= 33:
            self.shell(component, ['pm', 'grant', 'com.auditlab.agent', 'android.permission.POST_NOTIFICATIONS'])

    def inject(self, component, metadata=None):
        raw = json.dumps(self.identity | (metadata or {})).encode()
        package = 'com.auditlab.' + component
        self.shell(component, ['run-as', package, 'sh', '-c',
            'mkdir -p files; cat > files/' + self.name + '; chmod 600 files/' + self.name], data=raw)
        size = self.shell(component, ['run-as', package, 'stat', '-c', '%s', 'files/' + self.name]).stdout
        if int(size) != len(raw): raise RuntimeError('Private QA fixture injection not confirmed')

    def stage(self, component, name):
        package = 'com.auditlab.' + component
        inactive = 'control' if component == 'agent' else 'agent'
        # Software-only emulation can starve System UI when both AVDs render at
        # once. Keep both device states, but pause the inactive display/CPU for
        # the duration of each isolated UI stage.
        self.adb(inactive, ['emu', 'avd', 'pause'])
        self.shell(component, ['am', 'force-stop', package])
        start = time.monotonic()
        try:
            try:
                response = self.adb(component, ['shell', 'am', 'instrument', '-w', '-r', '-e', 'fixture', self.name,
                    '-e', 'stage', name, '-e', 'class', package + '.Native' + component.title() + 'FlowTest',
                    package + '.test/androidx.test.runner.AndroidJUnitRunner'],
                    timeout=420 if name == 'auditor' else 210, required=False)
                raw = response.stdout + response.stderr
                result = protocol(raw.decode(errors='replace')) | {'exit_code': response.returncode}
                result['approved'] = result['approved'] and response.returncode == 0
            except subprocess.TimeoutExpired:
                self.shell(component, ['am', 'force-stop', package])
                raw = b'Host deadline exceeded; target process stopped.'
                result = {'passed': 0, 'failed': 0, 'skipped': 0, 'exit_code': 'timeout', 'approved': False}
        finally:
            self.adb(inactive, ['emu', 'avd', 'start'], required=False)
        filename = f'{len(self.results):02d}-{component}-{name}'
        fixture.private_write(self.output / (filename + '.log'), raw.decode(errors='replace'))
        result.update(component=component, stage=name, serial=self.serials[component], seconds=round(time.monotonic() - start, 2))
        self.results.append(result)
        (self.output / 'summary.json').write_text(json.dumps(self.results, indent=2) + '\n')
        print(json.dumps(result), flush=True)
        if not result['approved']: raise RuntimeError('Native stage failed; inspect its private log, no acceptance claimed')

    def bootstrap_auditor(self):
        self.identity.update(auditorUser='qa_native_auditor_' + self.token[:8], auditorPassword=secrets.token_urlsafe(32))
        environment = fixture.environment(self.config)
        environment.update(AUDITLAB_BOOTSTRAP_USERNAME=self.identity['auditorUser'],
            AUDITLAB_BOOTSTRAP_PASSWORD=self.identity['auditorPassword'], AUDITLAB_BOOTSTRAP_ROLE='auditor')
        result = subprocess.run([str(fixture.BINARY), 'bootstrap-user'], env=environment, capture_output=True, timeout=60)
        if result.returncode: raise RuntimeError('QA auditor bootstrap failed')

    def configure_network(self):
        for component in self.serials:
            values = {key: self.shell(component, ['settings', 'get', 'global', key]).stdout.decode().strip()
                for key in ['window_animation_scale', 'transition_animation_scale', 'animator_duration_scale']}
            self.animation_settings[component] = values
            for key in values: self.shell(component, ['settings', 'put', 'global', key, '0'])
            self.shell(component, ['input', 'keyevent', '224'])
        keys = ['captive_portal_http_url', 'captive_portal_https_url', 'captive_portal_fallback_url',
                'captive_portal_use_https', 'wifi_on', 'mobile_data']
        self.settings = {key: self.shell('agent', ['settings', 'get', 'global', key]).stdout.decode().strip() for key in keys}
        (self.output / 'network-before.json').write_text(json.dumps(self.settings, indent=2) + '\n')
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self): self.send_response(204); self.end_headers()
            def log_message(self, *args): pass
        self.probe = http.server.HTTPServer(('127.0.0.1', 0), Handler)
        threading.Thread(target=self.probe.serve_forever, daemon=True).start()
        address = f'http://10.0.2.2:{self.probe.server_port}/generate_204'
        for key in keys[:3]: self.shell('agent', ['settings', 'put', 'global', key, address])
        self.shell('agent', ['settings', 'put', 'global', 'captive_portal_use_https', '0'])
        self.network(False); self.network(True)

    def network(self, connected):
        self.shell('agent', ['svc', 'data', 'disable'])
        self.shell('agent', ['svc', 'wifi', 'enable' if connected else 'disable'])
        if connected:
            deadline = time.monotonic() + 40
            while time.monotonic() < deadline:
                text = self.shell('agent', ['dumpsys', 'connectivity']).stdout.decode()
                if any('NetworkAgentInfo{' in line and 'WIFI' in line and 'VALIDATED' in line for line in text.splitlines()): return
                time.sleep(.5)
            raise RuntimeError('Own local connectivity probe did not validate WiFi; work constraints are not bypassed')
        time.sleep(2)

    def metadata(self):
        values = self.sql('SELECT device_id FROM agent_registration; SELECT event_id FROM agent_outbox ORDER BY event_id;').splitlines()
        if len(values) < 2: raise RuntimeError('No native event available for the control device')
        return {'device': values[0], 'event': values[1]}

    def tls_client(self, metadata):
        cert = fixture.DIRECTORY / 'tls/cert.pem'
        key = fixture.DIRECTORY / 'tls/key.pem'
        with socket.socket() as probe: probe.bind(('127.0.0.1', 18443))
        environment = fixture.environment(self.config)
        environment = {key: value for key, value in environment.items()
            if not key.startswith(('AUDITLAB_MIGRATION_', 'AUDITLAB_BOOTSTRAP_', 'AUDITLAB_TEST_', 'AUDITLAB_MAINTENANCE_')) and key != 'PGPASSWORD'}
        environment.update(AUDITLAB_TLS_CERTIFICATE=str(cert), AUDITLAB_TLS_PRIVATE_KEY=str(key), AUDITLAB_TLS_PORT='18443')
        path = self.output / 'tls-server.log'
        descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(descriptor, 'wb') as log:
            server = subprocess.Popen([str(fixture.BINARY)], cwd=ROOT, env=environment, stdout=log, stderr=log)
        try:
            context = ssl.create_default_context(cafile=str(cert))
            deadline = time.monotonic() + 35
            while True:
                try:
                    client = http.client.HTTPSConnection('127.0.0.1', 18443, context=context, timeout=2)
                    client.request('GET', '/ready'); response = client.getresponse(); response.read(); client.close()
                    if response.status == 200: break
                except OSError: pass
                if server.poll() is not None or time.monotonic() > deadline: raise RuntimeError('Own TLS QA API not ready')
                time.sleep(.25)
            self.inject('control', metadata | {'caPem': cert.read_text()})
            self.stage('control', 'tls-client')
        finally:
            server.terminate(); server.wait(timeout=15)

    def cleanup(self):
        for component in self.serials:
            self.shell(component, ['am', 'force-stop', 'com.auditlab.' + component], required=False)
        for component in self.serials:
            self.shell(component, ['run-as', 'com.auditlab.' + component, 'rm', '-f', 'files/' + self.name], required=False)
        for component, settings in self.animation_settings.items():
            for key, value in settings.items():
                args = ['settings', 'delete', 'global', key] if value == 'null' else ['settings', 'put', 'global', key, value]
                self.shell(component, args, required=False)
        for key, value in self.settings.items():
            if key in {'wifi_on', 'mobile_data'}: continue
            args = ['settings', 'delete', 'global', key] if value == 'null' else ['settings', 'put', 'global', key, value]
            self.shell('agent', args, required=False)
        if self.settings:
            self.shell('agent', ['svc', 'data', 'enable' if self.settings['mobile_data'] != '0' else 'disable'], required=False)
            self.shell('agent', ['svc', 'wifi', 'enable' if self.settings['wifi_on'] != '0' else 'disable'], required=False)
        if self.probe: self.probe.shutdown(); self.probe.server_close()
        (self.output / 'environment.json').write_text(json.dumps({'recorded_at': dt.datetime.now(dt.timezone.utc).isoformat(),
            'devices': self.devices, 'artifacts': self.artifacts, 'transport': 'HTTP loopback QA; separate TLS verification required',
            'network_validation': 'Own loopback HTTP 204 probe; WiFi/mobile toggled on the agent only'}, indent=2) + '\n')
        approved = self.completed and complete(self.results)
        (self.output / 'verdict.json').write_text(json.dumps({'approved': approved,
            'expected_stages': len(EXPECTED_STAGES), 'executed_stages': len(self.results),
            'passed': sum(r['approved'] for r in self.results),
            'failed': sum(not r['approved'] for r in self.results),
            'not_executed': max(0, len(EXPECTED_STAGES) - len(self.results))}, indent=2) + '\n')
        suite = ET.Element('testsuite', name='AuditLab-native', tests=str(len(self.results) + (not approved)),
                           failures=str(sum(not r['approved'] for r in self.results) + (not approved)))
        for index, result in enumerate(self.results):
            test = ET.SubElement(suite, 'testcase', name=f'{index:02d}-{result["component"]}-{result["stage"]}',
                                 classname='AuditLab.Native', time=str(result['seconds']))
            if not result['approved']: ET.SubElement(test, 'failure', message='Native protocol failed; private log retained')
        if not approved:
            check = ET.SubElement(suite, 'testcase', name='full-protocol-completion', classname='AuditLab.Native')
            ET.SubElement(check, 'failure', message='Full ordered acceptance was not completed; passing partial stages do not approve delivery')
        ET.ElementTree(suite).write(self.output / 'TEST-native.xml', encoding='utf-8', xml_declaration=True)

    def execute(self):
        self.preflight()
        try:
            self.bootstrap_auditor()
            self.configure_network()
            for component in self.serials: self.install(component); self.inject(component)
            self.stage('agent', 'enroll')
            first = self.metadata(); self.inject('control', first)
            self.stage('control', 'login-read'); self.stage('control', 'create-code')
            self.stage('control', 'restore-read'); self.stage('agent', 'restore')
            self.network(False); self.stage('agent', 'offline')
            self.network(True); self.stage('agent', 'recover')
            self.stage('agent', 'permission-denied')
            self.stage('control', 'revoke'); self.stage('agent', 'remote-revoked'); self.stage('agent', 'enroll')
            second = self.metadata(); self.inject('control', second)
            self.inject('agent', {'previousDevice': first['device']})
            self.network(False); self.stage('agent', 'withdraw-offline'); self.stage('agent', 'restore-withdrawal')
            self.network(True); self.stage('agent', 'confirm-withdrawal')
            # The completed agent has no credentials/events; sleeping its display reduces TCG rendering load.
            self.shell('agent', ['input', 'keyevent', '223'])
            self.stage('control', 'export-rotation'); self.stage('control', 'auditor')
            self.tls_client(second)
            self.completed = complete(self.results)
            if not self.completed: raise RuntimeError('Incomplete native acceptance protocol')
            print('Native acceptance passed; private evidence:', self.output.relative_to(ROOT), flush=True)
        finally: self.cleanup()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--agent-serial', required=True)
    parser.add_argument('--control-serial', required=True)
    args = parser.parse_args()
    Run(args.agent_serial, args.control_serial).execute()

if __name__ == '__main__': main()
