"""Check authenticated PostgreSQL TCP and transactions in an isolated container."""
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import uuid

IMAGE = 'postgres:17.6-bookworm@sha256:f3bd19c606e442c3d7bdfa8002e03fe260a1023351e0ea4598032022b68dd6e3'
environment = os.environ.copy()
for selector in ('DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_TLS', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH'):
    environment.pop(selector, None)
docker = ['docker', '--host=unix:///var/run/docker.sock']
name = 'auditlab-check-' + uuid.uuid4().hex[:12]

def run(*args, **kwargs):
    return subprocess.run(docker + list(args), env=environment, check=True, capture_output=True, text=True, timeout=60, **kwargs)

with tempfile.TemporaryDirectory(prefix='auditlab-pg-') as directory:
    password = secrets.token_urlsafe(32)
    environment['PGPASSWORD'] = password
    settings = Path(directory) / 'postgres.env'
    settings.write_text('POSTGRES_USER=auditlab\nPOSTGRES_DB=auditlab\nPOSTGRES_INITDB_ARGS=--auth-host=scram-sha-256\nPOSTGRES_PASSWORD=' + password + '\n')
    settings.chmod(0o600)
    started = False
    try:
        run('info', '--format', '{{.ServerVersion}}')
        run('run', '--rm', '--detach', '--name', name, '--env-file', str(settings), IMAGE)
        started = True
        deadline = time.monotonic() + 45
        while True:
            try:
                run('exec', '--env', 'PGPASSWORD', name, 'psql', '-h', '127.0.0.1', '-U', 'auditlab', '-d', 'auditlab', '-Atc', 'SELECT 1;')
                break
            except subprocess.CalledProcessError:
                if time.monotonic() > deadline:
                    raise RuntimeError('PostgreSQL did not become ready within 45 seconds') from None
                time.sleep(0.5)
        sql = ['exec', '--env', 'PGPASSWORD', '-i', name, 'psql', '-h', '127.0.0.1', '-U', 'auditlab', '-d', 'auditlab', '-v', 'ON_ERROR_STOP=1', '-At']
        environment['PGPASSWORD'] = secrets.token_urlsafe(32)
        try:
            run(*sql, input='SELECT 1;')
        except subprocess.CalledProcessError:
            pass
        else:
            raise RuntimeError('PostgreSQL unexpectedly accepted the wrong password')
        environment['PGPASSWORD'] = password
        result = run(*sql, input="SELECT current_setting('server_version'); BEGIN; CREATE TABLE readiness(value integer); INSERT INTO readiness VALUES (42); SELECT sum(value) FROM readiness; ROLLBACK;")
        assert any(line.startswith('17.6') for line in result.stdout.splitlines()), 'Unexpected PostgreSQL server version'
        assert '42' in result.stdout.splitlines(), 'Expected transaction result not returned'
        print('PostgreSQL 17.6: wrong password rejected, authenticated TCP and transaction passed; temporary container removed.')
    except subprocess.CalledProcessError as exc:
        # Never print command arguments or environment secrets.
        raise SystemExit('Docker/PostgreSQL prerequisite failed: ' + exc.stderr.replace(password, '[redacted]')) from None
    finally:
        if started:
            run('rm', '--force', name)
