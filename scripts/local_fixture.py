"""Persistent, isolated QA database, credentials and certificates under .tooling.

Commands: prepare, migrate, start, stop, status, run -- COMMAND ...
Never prints credentials, clears data, removes containers/volumes, or uses /tmp.
"""
import argparse
import base64
import datetime as dt
import http.client
import json
import os
from pathlib import Path
import secrets
import signal
import socket
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
DIRECTORY = ROOT / '.tooling/integration-fixture'
IMAGE = 'postgres:17.6-bookworm@sha256:f3bd19c606e442c3d7bdfa8002e03fe260a1023351e0ea4598032022b68dd6e3'
DOCKER = ['docker', '--host=unix:///var/run/docker.sock']
BINARY = ROOT / 'backend/ktor/build/install/ktor/bin/ktor'


def private_write(path, text):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, 'w') as stream:
        stream.write(text)


def settings():
    return json.loads((DIRECTORY / 'fixture.json').read_text())


def environment(config):
    env = {key: value for key, value in os.environ.items() if not key.startswith('AUDITLAB_')}
    for key in ['DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_TLS', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH']:
        env.pop(key, None)
    env['JAVA_HOME'] = str(ROOT / '.tooling/jdk')
    env['PATH'] = str(ROOT / '.tooling/jdk/bin') + os.pathsep + env.get('PATH', '')
    persistent_tmp = DIRECTORY / 'tmp'
    persistent_tmp.mkdir(exist_ok=True, mode=0o700)
    env['TMPDIR'] = str(persistent_tmp)
    if config.get('database_port'):
        url = f"jdbc:postgresql://127.0.0.1:{config['database_port']}/{config['database']}?sslmode=disable&connectTimeout=2&socketTimeout=5"
        for prefix, role in [('AUDITLAB_MIGRATION', 'migrator'), ('AUDITLAB_DB', 'runtime'),
                             ('AUDITLAB_MAINTENANCE', 'maintenance'), ('AUDITLAB_TEST_MAINTENANCE', 'maintenance')]:
            env.update({prefix + '_JDBC_URL': url, prefix + '_USER': 'qa_' + role,
                        prefix + '_PASSWORD': config['passwords'][role]})
    env.update(PGPASSWORD=config['passwords']['root'], AUDITLAB_AUTH_SIGNING_KEY_BASE64=config['signing_key'],
               AUDITLAB_BOOTSTRAP_PASSWORD=config['passwords']['admin'], AUDITLAB_BOOTSTRAP_USERNAME='qa_admin',
               AUDITLAB_BOOTSTRAP_ROLE='admin', AUDITLAB_PORT=str(config['http_port']),
               AUDITLAB_TEST_ISOLATED='true', AUDITLAB_CHECK_PYTHON=str(ROOT / '.venv/bin/python'),
               AUDITLAB_TLS_CHECK_DIR=str(DIRECTORY / 'tls-check'),
               AUDITLAB_LIVE_URL=f"http://127.0.0.1:{config['http_port']}", AUDITLAB_LIVE_USER='qa_admin',
               AUDITLAB_LIVE_PASSWORD=config['passwords']['admin'],
               AUDITLAB_SMOKE_URL=f"http://127.0.0.1:{config['http_port']}", AUDITLAB_SMOKE_USER='qa_admin',
               AUDITLAB_SMOKE_PASSWORD=config['passwords']['admin'])
    return env


def run(config, args, *, input=None, timeout=90, check=True):
    result = subprocess.run([str(x) for x in args], cwd=ROOT, env=environment(config), input=input,
                            text=True, capture_output=True, timeout=timeout)
    output = result.stdout + result.stderr
    for value in [*config['passwords'].values(), config['signing_key']]:
        output = output.replace(value, '[redacted]')
    with (DIRECTORY / 'commands.log').open('a') as log:
        log.write(output)
    if check and result.returncode:
        raise RuntimeError(f'Command {Path(str(args[0])).name} failed ({result.returncode}); inspect private commands.log')
    return result.returncode, result.stdout.strip()


def psql(config, sql, check=True):
    return run(config, DOCKER + ['exec', '--env', 'PGPASSWORD', '-i', config['container'], 'psql',
                                '-h', '127.0.0.1', '-U', 'qa_root', '-d', 'postgres',
                                '-v', 'ON_ERROR_STOP=1', '-At'], input=sql, check=check)


def prepare():
    DIRECTORY.mkdir(parents=True, exist_ok=True, mode=0o700)
    DIRECTORY.chmod(0o700)
    if not (DIRECTORY / 'fixture.json').exists():
        token = uuid.uuid4().hex[:12]
        config = {'version': 1, 'container': 'auditlab-qa-' + token, 'volume': 'auditlab-qa-data-' + token,
                  'database': 'auditlab_qa_' + token, 'http_port': 8081,
                  'passwords': {role: secrets.token_urlsafe(32) for role in ['root', 'migrator', 'runtime', 'maintenance', 'admin']},
                  'signing_key': base64.b64encode(secrets.token_bytes(32)).decode(),
                  'created_utc': dt.datetime.now(dt.timezone.utc).isoformat()}
        private_write(DIRECTORY / 'fixture.json', json.dumps(config, indent=2) + '\n')
    config = settings()
    run(config, DOCKER + ['info', '--format', '{{.ServerVersion}}'])
    code, _ = run(config, DOCKER + ['container', 'inspect', '--format', '{{.Id}}', config['container']], check=False)
    if code:
        private_write(DIRECTORY / 'postgres.env', 'POSTGRES_USER=qa_root\nPOSTGRES_DB=postgres\n'
                      'POSTGRES_INITDB_ARGS=--auth-host=scram-sha-256\nPOSTGRES_PASSWORD=' + config['passwords']['root'] + '\n')
        run(config, DOCKER + ['run', '--detach', '--name', config['container'], '--label',
                             'com.auditlab.qa-fixture=' + str(DIRECTORY), '--env-file', DIRECTORY / 'postgres.env',
                             '--mount', 'type=volume,source=' + config['volume'] + ',target=/var/lib/postgresql/data',
                             '--publish', '127.0.0.1::5432', IMAGE], timeout=180)
    _, owner = run(config, DOCKER + ['inspect', '--format', '{{index .Config.Labels "com.auditlab.qa-fixture"}}', config['container']])
    if owner != str(DIRECTORY):
        raise RuntimeError('Refusing to use a container not owned by this fixture')
    run(config, DOCKER + ['start', config['container']])
    deadline = time.monotonic() + 60
    while psql(config, 'SELECT 1;', check=False)[0]:
        if time.monotonic() > deadline:
            raise RuntimeError('PostgreSQL did not become ready')
        time.sleep(.5)
    for role in ['migrator', 'runtime', 'maintenance']:
        if psql(config, f"SELECT 1 FROM pg_roles WHERE rolname='qa_{role}';")[1] != '1':
            psql(config, f"CREATE ROLE qa_{role} LOGIN PASSWORD '{config['passwords'][role]}';")
    if psql(config, f"SELECT 1 FROM pg_database WHERE datname='{config['database']}';")[1] != '1':
        psql(config, f"CREATE DATABASE {config['database']} OWNER qa_migrator;")
    config['database_port'] = int(run(config, DOCKER + ['port', config['container'], '5432/tcp'])[1].rsplit(':', 1)[1])
    private_write(DIRECTORY / 'fixture.json', json.dumps(config, indent=2) + '\n')
    tls = DIRECTORY / 'tls'
    tls.mkdir(exist_ok=True, mode=0o700)
    cert, key = tls / 'cert.pem', tls / 'key.pem'
    if not cert.exists() and not key.exists():
        run(config, ['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', key,
                     '-out', cert, '-days', '7', '-subj', '/CN=localhost', '-addext',
                     'subjectAltName=DNS:localhost,IP:127.0.0.1,IP:10.0.2.2'])
        key.chmod(0o600)
    if not cert.exists() or not key.exists():
        raise RuntimeError('Incomplete existing TLS pair; preserved for inspection')
    print('Persistent PostgreSQL fixture and TLS pair ready under .tooling/integration-fixture; credentials private.')


def migrate(config):
    if not BINARY.exists():
        raise RuntimeError('Build :backend:ktor:installDist first')
    run(config, [BINARY, 'migrate'])
    for role, action in [('runtime', 'grant-runtime'), ('maintenance', 'grant-maintenance')]:
        env = environment(config)
        env['AUDITLAB_TARGET_DB_ROLE'] = 'qa_' + role
        result = subprocess.run([str(BINARY), action], cwd=ROOT, env=env, capture_output=True, timeout=60)
        if result.returncode:
            raise RuntimeError('Fixture role grant failed; no credentials printed')
    run(config, [BINARY, 'bootstrap-user'])
    print('Fixture migrations, least-privilege roles and qa_admin ready.')


def owned_process(record):
    path = Path('/proc') / str(record['pid'])
    try:
        fields = (path / 'stat').read_text().split()
        command = (path / 'cmdline').read_bytes()
        return (fields[2] != 'Z' and fields[21] == record['start_ticks']
                and str(ROOT / 'backend/ktor/build/install/ktor').encode() in command
                and b'com.auditlab.api.ApplicationKt' in command)
    except (OSError, IndexError):
        # Exit can race /proc reads after SIGTERM; an absent/zombie process is
        # stopped, and must never be replaced by a reused PID in this check.
        return False


def ready(config):
    client = http.client.HTTPConnection('127.0.0.1', config['http_port'], timeout=3)
    try:
        client.request('GET', '/ready')
        return client.getresponse().status == 200
    except OSError:
        return False
    finally:
        client.close()


def start(config):
    record_path = DIRECTORY / 'server.json'
    if record_path.exists() and owned_process(json.loads(record_path.read_text())):
        print('Owned fixture server already running; ready=' + str(ready(config)))
        return
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', config['http_port']))
    env = {key: value for key, value in environment(config).items()
           if not key.startswith(('AUDITLAB_MIGRATION_', 'AUDITLAB_BOOTSTRAP_', 'AUDITLAB_TEST_')) and key != 'PGPASSWORD'}
    with (DIRECTORY / 'server.log').open('ab') as log:
        process = subprocess.Popen([str(BINARY)], cwd=ROOT, env=env, stdout=log,
                                   stderr=subprocess.STDOUT, start_new_session=True)
    record = {'pid': process.pid, 'start_ticks': (Path('/proc') / str(process.pid) / 'stat').read_text().split()[21]}
    private_write(record_path, json.dumps(record) + '\n')
    deadline = time.monotonic() + 45
    while not ready(config):
        if process.poll() is not None or time.monotonic() > deadline:
            raise RuntimeError('Fixture server did not become ready; inspect private server.log')
        time.sleep(.3)
    print('Owned fixture API ready on loopback port ' + str(config['http_port']) + '.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'migrate', 'start', 'stop', 'status', 'run'])
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.action == 'prepare':
        prepare()
        return
    config = settings()
    if args.action == 'migrate':
        migrate(config)
    elif args.action == 'start':
        start(config)
    elif args.action == 'stop':
        record = json.loads((DIRECTORY / 'server.json').read_text())
        if owned_process(record):
            os.kill(record['pid'], signal.SIGTERM)
            deadline = time.monotonic() + 15
            while owned_process(record):
                if time.monotonic() > deadline:
                    raise RuntimeError('Owned API did not stop gracefully')
                time.sleep(.2)
        print('Owned API stopped; PostgreSQL, data and certificates retained.')
    elif args.action == 'status':
        print(json.dumps({'directory': str(DIRECTORY), 'database_port': config.get('database_port'),
                          'api_ready': ready(config), 'credentials_printed': False}))
    else:
        command = args.command[1:] if args.command[:1] == ['--'] else args.command
        if not command:
            parser.error('run requires a command after --')
        env = environment(config)
        raise SystemExit(subprocess.call(command, cwd=ROOT, env=env))


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError):
        raise SystemExit('Local fixture operation failed; inspect its private state/logs. No resources were removed.') from None
