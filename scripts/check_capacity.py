"""P7.3: 10k own simulated events, stable pagination and five concurrent readers.
Run ONLY against the private QA fixture using local_fixture.py run -- .venv/bin/python scripts/check_capacity.py.
Creates a dedicated device; never deletes existing data or prints credentials.
"""
import concurrent.futures
import csv
import io
import json
import math
import os
from pathlib import Path
import platform
import time
import uuid
from urllib.parse import urlsplit
from datetime import datetime, timezone
import requests


def running_emulators():
    if not Path('/proc').is_dir(): return None
    count = 0
    for process in Path('/proc').glob('[0-9]*'):
        try:
            if (process / 'comm').read_text().startswith('qemu-system') and (process / 'stat').read_text().split(') ', 1)[1].split()[0] != 'Z': count += 1
        except (OSError, IndexError): pass  # Process may exit while enumerating.
    return count


def main():
    base = os.environ['AUDITLAB_LIVE_URL'].rstrip('/')
    origin = urlsplit(base)
    if (os.environ.get('AUDITLAB_TEST_ISOLATED') != 'true' or origin.scheme != 'http'
            or origin.hostname != '127.0.0.1' or not origin.port or origin.username or origin.password
            or origin.path or origin.query or origin.fragment):
        raise RuntimeError('Only the isolated loopback QA fixture is allowed')
    def call(method, path, token=None, **kwargs):
        headers = kwargs.pop('headers', {})
        if token: headers['Authorization'] = 'Bearer ' + token
        response = requests.request(method, base + '/v1' + path, headers=headers, timeout=30, allow_redirects=False, **kwargs)
        if response.status_code not in range(200, 300):
            raise RuntimeError(f'{method} {path.split("?")[0]} returned {response.status_code}')
        return response
    session = call('POST', '/auth/login', json={'username': os.environ['AUDITLAB_LIVE_USER'], 'password': os.environ['AUDITLAB_LIVE_PASSWORD']}).json()
    access = session['accessToken']
    try:
        code = call('POST', '/devices/enrollment-code', access).json()['code']
        now = datetime.now(timezone.utc).isoformat(timespec='microseconds').replace('+00:00', 'Z')
        consent = {'consentId': str(uuid.uuid4()), 'policyVersion': 'mvp-1', 'acceptedAt': now}
        device = call('POST', '/devices/enroll', headers={'X-Enrollment-Code': code}, json={'consentAcknowledged': True, 'consent': consent, 'appVersion': 'capacity-qa'}).json()
        ids = set()
        for _ in range(100):
            batch = [{'eventId': str(uuid.uuid4()), 'timestamp': now, 'sourceDevice': device['deviceId'],
                      'category': 'sync', 'direction': 'occurred', 'consentContext': consent, 'isSimulated': True,
                      'metadata': {'appEventCode': 'sync_succeeded'}} for _ in range(100)]
            ids.update(e['eventId'] for e in batch)
            assert call('POST', '/events', device['deviceToken'], json={'events': batch}).json() == {'accepted': 100, 'duplicates': 0}
        seen = set(); cursor = None; pages = 0
        while True:
            params = {'device': device['deviceId'], 'limit': 100}
            if cursor: params['cursor'] = cursor
            page = call('GET', '/events', access, params=params).json(); pages += 1
            page_ids = {e['eventId'] for e in page['items']}
            assert not seen & page_ids
            seen.update(page_ids); cursor = page['nextCursor']
            if not cursor: break
        assert seen == ids and pages == 100
        def reader(_):
            samples = []
            for __ in range(20):
                start = time.perf_counter()
                page = call('GET', '/events', access, params={'device': device['deviceId'], 'limit': 100}).json()
                assert len(page['items']) == 100
                samples.append((time.perf_counter() - start) * 1000)
            return samples
        with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
            timings = sorted(x for group in pool.map(reader, range(5)) for x in group)
        p95 = timings[math.ceil(len(timings) * .95) - 1]
        report = call('GET', '/events/export.csv', access, params={'device': device['deviceId']})
        rows = list(csv.DictReader(io.StringIO(report.text)))
        assert len(rows) == 10_000 and {r['eventId'] for r in rows} == ids
        call('POST', '/devices/' + device['deviceId'] + '/revoke', access)
        result = {'events': len(ids), 'pages': pages, 'concurrency': 5, 'queries': len(timings),
                  'p50_ms': round(timings[49], 2), 'p95_ms': round(p95, 2), 'max_ms': round(max(timings), 2),
                  'target_p95_ms': 2000, 'csv_rows': len(rows), 'passed': p95 < 2000,
                  'host': platform.platform(), 'cpu_max': Path('/sys/fs/cgroup/cpu.max').read_text().strip(),
                  'memory_max': Path('/sys/fs/cgroup/memory.max').read_text().strip(), 'emulators_running': running_emulators(),
                  'recorded_at': datetime.now(timezone.utc).isoformat()}
        destination = Path('.tooling/full-delivery'); destination.mkdir(exist_ok=True, parents=True)
        (destination / 'capacity.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result, indent=2))
        assert result['passed'], 'Query p95 exceeds 2 seconds'
    finally:
        call('POST', '/auth/logout', access)

if __name__ == '__main__':
    main()
