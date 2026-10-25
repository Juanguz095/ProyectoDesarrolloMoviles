"""HTTP integration checks for the local prototype; all data is temporary."""

import copy
from concurrent.futures import ThreadPoolExecutor
import csv
import http.client
import importlib.util
import io
import json
import os
from pathlib import Path
import secrets
import tempfile
import threading
import unittest
from unittest.mock import patch
import uuid


SOURCE = Path(__file__).resolve().parents[1] / 'server.py'


class ApiFlow(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='auditlab-test-')
        self.addCleanup(self.temp.cleanup)
        self.passwords = {role: secrets.token_urlsafe(24) for role in ('admin', 'auditor')}
        variables = {
            'AUDITLAB_DATA_DIR': self.temp.name,
            'AUDITLAB_ADMIN_PASSWORD': self.passwords['admin'],
            'AUDITLAB_AUDITOR_PASSWORD': self.passwords['auditor'],
            'AUDITLAB_JWT_SECRET': secrets.token_urlsafe(48),
        }
        self.env_patch = patch.dict(os.environ, variables)
        self.env_patch.start()
        self.addCleanup(self.env_patch.stop)
        spec = importlib.util.spec_from_file_location('auditlab_test_server', SOURCE)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.module.initialize()

        class QuietHandler(self.module.Handler):
            def log_message(self, *args):
                pass

        self.server = self.module.ThreadingHTTPServer(('127.0.0.1', 0), QuietHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop_server)
        self.admin = self.login('admin')
        self.auditor = self.login('auditor')

    def stop_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)

    def call(self, method, path, body=None, token=None, code=None):
        headers = {}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        if code:
            headers['X-Enrollment-Code'] = code
        if body is not None:
            headers['Content-Type'] = 'application/json'
        client = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=5)
        try:
            client.request(method, path, json.dumps(body) if body is not None else None, headers)
            response = client.getresponse()
            data = response.read()
            kind = response.getheader('Content-Type', '')
            return response.status, (json.loads(data) if data and 'json' in kind else data.decode()), kind
        finally:
            client.close()

    def login(self, role):
        status, data, _ = self.call('POST', '/v1/auth/login', {'username': role, 'password': self.passwords[role]})
        self.assertEqual(status, 200)
        self.assertEqual(data['role'], role)
        return data['accessToken']

    def code(self):
        status, data, _ = self.call('POST', '/v1/devices/enrollment-code', token=self.admin)
        self.assertEqual(status, 201)
        return data['code']

    def enrollment_body(self):
        return {'consentAcknowledged': True, 'consent': {
            'consentId': str(uuid.uuid4()), 'policyVersion': 'mvp-1', 'acceptedAt': self.module.now_iso(),
        }}

    def enroll(self):
        body = self.enrollment_body()
        status, device, _ = self.call('POST', '/v1/devices/enroll', body, code=self.code())
        self.assertEqual(status, 201)
        return device, body['consent']

    def event(self, device, consent):
        return {'eventId': str(uuid.uuid4()), 'timestamp': self.module.now_iso(),
                'category': 'app_lifecycle', 'direction': 'occurred', 'sourceDevice': device['deviceId'],
                'consentContext': copy.deepcopy(consent), 'metadata': {'appEventCode': 'agent_started'}}

    def events_count(self):
        with self.module.connect() as db:
            return db.execute('SELECT COUNT(*) FROM events').fetchone()[0]

    def test_health_and_web_are_served(self):
        self.assertEqual(self.call('GET', '/health')[1]['status'], 'ok')
        status, page, _ = self.call('GET', '/')
        self.assertEqual(status, 200)
        self.assertIn('Acepto el monitoreo', page)

    def test_errors_follow_problem_json_contract(self):
        status, error, kind = self.call('GET', '/v1/devices')
        self.assertEqual(status, 401)
        self.assertIn('application/problem+json', kind)
        self.assertEqual(error['status'], status)

    def test_protected_routes_reject_missing_or_invalid_tokens(self):
        for route in ('/v1/devices', '/v1/events', '/v1/audit-log', '/v1/events/export.csv'):
            self.assertEqual(self.call('GET', route)[0], 401)
            self.assertEqual(self.call('GET', route, token='invalid')[0], 401)
        device, consent = self.enroll()
        self.assertEqual(self.call('POST', '/v1/events', {'events': [self.event(device, consent)]})[0], 401)
        self.assertEqual(self.events_count(), 0)

    def test_invalid_login_has_uniform_unauthorized_response(self):
        for username in ('admin', 'unknown'):
            self.assertEqual(self.call('POST', '/v1/auth/login', {'username': username, 'password': 'wrong'})[0], 401)

    def test_consent_required_and_code_is_single_use(self):
        code = self.code()
        body = self.enrollment_body()
        body['consentAcknowledged'] = False
        self.assertEqual(self.call('POST', '/v1/devices/enroll', body, code=code)[0], 400)
        body['consentAcknowledged'] = True
        self.assertEqual(self.call('POST', '/v1/devices/enroll', body, code=code)[0], 201)
        self.assertEqual(self.call('POST', '/v1/devices/enroll', body, code=code)[0], 401)

    def test_expired_code_is_rejected(self):
        code = self.code()
        with self.module.connect() as db:
            db.execute("UPDATE enrollment_codes SET expires_at = '2000-01-01T00:00:00Z'")
        self.assertEqual(self.call('POST', '/v1/devices/enroll', self.enrollment_body(), code=code)[0], 401)

    def test_concurrent_enrollment_consumes_code_once(self):
        code, body = self.code(), self.enrollment_body()
        with ThreadPoolExecutor(max_workers=2) as pool:
            statuses = list(pool.map(lambda _: self.call('POST', '/v1/devices/enroll', body, code=code)[0], range(2)))
        self.assertEqual(sorted(statuses), [201, 401])

    def test_role_restrictions(self):
        device, _ = self.enroll()
        for route in ('/v1/devices', '/v1/events', '/v1/audit-log'):
            self.assertEqual(self.call('GET', route, token=self.auditor)[0], 200)
        self.assertEqual(self.call('POST', '/v1/devices/enrollment-code', token=self.auditor)[0], 403)
        self.assertEqual(self.call('POST', f"/v1/devices/{device['deviceId']}/revoke", token=self.auditor)[0], 403)

    def test_event_acceptance_and_idempotent_retry(self):
        device, consent = self.enroll()
        body = {'events': [self.event(device, consent)]}
        self.assertEqual(self.call('POST', '/v1/events', body, token=device['deviceToken'])[1], {'accepted': 1, 'duplicates': 0})
        self.assertEqual(self.call('POST', '/v1/events', body, token=device['deviceToken'])[1], {'accepted': 0, 'duplicates': 1})
        self.assertEqual(self.events_count(), 1)

    def test_reused_id_with_changed_payload_is_conflict(self):
        device, consent = self.enroll()
        event = self.event(device, consent)
        self.assertEqual(self.call('POST', '/v1/events', {'events': [event]}, token=device['deviceToken'])[0], 202)
        event['metadata']['appEventCode'] = 'sync_failed'
        self.assertEqual(self.call('POST', '/v1/events', {'events': [event]}, token=device['deviceToken'])[0], 409)
        self.assertEqual(self.events_count(), 1)

    def test_device_and_consent_must_match(self):
        device, consent = self.enroll()
        other, _ = self.enroll()
        event = self.event(other, consent)
        self.assertEqual(self.call('POST', '/v1/events', {'events': [event]}, token=device['deviceToken'])[0], 400)
        event = self.event(device, consent)
        event['consentContext']['consentId'] = str(uuid.uuid4())
        self.assertEqual(self.call('POST', '/v1/events', {'events': [event]}, token=device['deviceToken'])[0], 409)
        event = self.event(device, consent)
        event['consentContext']['acceptedAt'] = '2000-01-01T00:00:00Z'
        self.assertEqual(self.call('POST', '/v1/events', {'events': [event]}, token=device['deviceToken'])[0], 409)
        self.assertEqual(self.events_count(), 0)

    def test_invalid_batch_is_atomic(self):
        device, consent = self.enroll()
        good = self.event(device, consent)
        bad = self.event(device, consent)
        bad['unapprovedContent'] = 'must not be accepted'
        self.assertEqual(self.call('POST', '/v1/events', {'events': [good, bad]}, token=device['deviceToken'])[0], 400)
        self.assertEqual(self.events_count(), 0)

    def test_json_enum_types_produce_controlled_errors(self):
        device, consent = self.enroll()
        for field in ('category', 'direction'):
            with self.subTest(field=field):
                event = self.event(device, consent)
                event[field] = []
                self.assertEqual(self.call('POST', '/v1/events', {'events': [event]}, token=device['deviceToken'])[0], 400)
        for field in ('appEventCode', 'networkState'):
            with self.subTest(field=field):
                event = self.event(device, consent)
                event['metadata'][field] = {}
                self.assertEqual(self.call('POST', '/v1/events', {'events': [event]}, token=device['deviceToken'])[0], 400)

    def test_remote_and_self_revocation_block_events(self):
        for route, use_admin in (('revoke', True), ('consent-revoke', False)):
            device, consent = self.enroll()
            token = self.admin if use_admin else device['deviceToken']
            status, body, _ = self.call('POST', f"/v1/devices/{device['deviceId']}/{route}", token=token)
            self.assertEqual((status, body), (204, ''))
            self.assertIn(self.call('POST', '/v1/events', {'events': [self.event(device, consent)]}, token=device['deviceToken'])[0], (401, 409))
        self.assertEqual(self.events_count(), 0)

    def test_filters_export_and_audit(self):
        device, consent = self.enroll()
        self.call('POST', '/v1/events', {'events': [self.event(device, consent)]}, token=device['deviceToken'])
        _, result, _ = self.call('GET', '/v1/events?category=app_lifecycle&device=' + device['deviceId'], token=self.auditor)
        self.assertEqual(len(result['items']), 1)
        self.assertEqual(self.call('GET', '/v1/events?category=sync', token=self.auditor)[1]['items'], [])
        status, output, kind = self.call('GET', '/v1/events/export.csv', token=self.auditor)
        self.assertEqual(status, 200)
        self.assertIn('text/csv', kind)
        self.assertEqual(len(list(csv.reader(io.StringIO(output)))), 2)
        actions = {r['action'] for r in self.call('GET', '/v1/audit-log', token=self.admin)[1]['items']}
        self.assertTrue({'events_queried', 'report_exported', 'enrollment_code_created'}.issubset(actions))

    def test_retention_current_prototype_behavior_and_persistence(self):
        device, consent = self.enroll()
        self.call('POST', '/v1/events', {'events': [self.event(device, consent)]}, token=device['deviceToken'])
        self.module.initialize()
        self.assertEqual(self.events_count(), 1)
        with self.module.connect() as db:
            db.execute("UPDATE events SET timestamp = '2000-01-01T00:00:00Z'")
        status, _, _ = self.call('GET', '/v1/events', token=self.admin)
        self.assertEqual(status, 200)
        # The response must arrive only after the purge and its audit row are committed.
        self.assertEqual(self.events_count(), 0)


if __name__ == '__main__':
    unittest.main()
