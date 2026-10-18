"""Start the installed Ktor distribution, request health, and stop only that process."""
import http.client
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time

root = Path(__file__).resolve().parents[1]
binary = root / 'backend/ktor/build/install/ktor/bin/ktor'
if not binary.exists():
    raise SystemExit('Build :backend:ktor:installDist before checking startup.')
environment = os.environ.copy()
for name in list(environment):
    if name not in ('AUDITLAB_DB_JDBC_URL', 'AUDITLAB_DB_USER', 'AUDITLAB_DB_PASSWORD') and not name.startswith('AUDITLAB_AUTH_'):
        continue
    environment.pop(name, None)  # Standalone liveness check must never connect to a user's existing DB.
if (root / '.tooling/jdk').exists():
    environment['JAVA_HOME'] = str(root / '.tooling/jdk')
with socket.socket() as reservation:
    reservation.bind(('127.0.0.1', 0))
    port = reservation.getsockname()[1]
environment['AUDITLAB_PORT'] = str(port)
with tempfile.TemporaryFile() as log:
    process = subprocess.Popen([str(binary)], cwd=root, env=environment, stdout=log, stderr=subprocess.STDOUT)
    try:
        deadline = time.monotonic() + 30
        while True:
            if process.poll() is not None:
                raise RuntimeError('Ktor exited before readiness')
            client = http.client.HTTPConnection('127.0.0.1', port, timeout=1)
            try:
                client.request('GET', '/health')
                response = client.getresponse()
                health = json.loads(response.read())
                assert response.status == 200 and health == {'status': 'ok', 'service': 'auditlab-api', 'stage': 'foundation'}
                print('Ktor distribution started and returned expected health JSON over HTTP; process stopped.')
                break
            except (ConnectionError, TimeoutError):
                if time.monotonic() >= deadline:
                    raise RuntimeError('Ktor startup exceeded 30 seconds') from None
                time.sleep(0.1)
            finally:
                client.close()
    finally:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
