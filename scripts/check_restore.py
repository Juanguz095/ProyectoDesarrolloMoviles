"""Restore the private QA fixture into a NEW database and compare all table fingerprints.
Never overwrites/drops any database. Private plaintext QA backup remains under .tooling;
production backups must be encrypted by the operator, following docs/despliegue.md.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import socket
import time
import http.client
import uuid
import local_fixture as fixture


def main():
    config = fixture.settings()
    owner = fixture.run(config, fixture.DOCKER + ['inspect', '--format',
        '{{index .Config.Labels "com.auditlab.qa-fixture"}}', config['container']])[1]
    if owner != str(fixture.DIRECTORY): raise RuntimeError('Refusing a container outside the private QA fixture')
    # Stop only the fixture-owned process for a quiescent, reproducible comparison.
    subprocess.run(['python3', 'scripts/local_fixture.py', 'stop'], check=True)
    target = 'qa_restore_' + uuid.uuid4().hex[:16]
    directory = fixture.DIRECTORY / 'restore'
    directory.mkdir(exist_ok=True, mode=0o700)
    backup = directory / (target + '.sql')
    env = fixture.environment(config)
    def command(program, db, args=(), data=None):
        result = subprocess.run(fixture.DOCKER + ['exec', '--env', 'PGPASSWORD', '-i', config['container'], program,
            '-h', '127.0.0.1', '-U', 'qa_root', '-d', db, *args], input=data, capture_output=True, env=env, timeout=120)
        if result.returncode: raise RuntimeError('Restore QA command failed; no private SQL printed')
        return result.stdout
    def sql(db, text): return command('psql', db, ['-v', 'ON_ERROR_STOP=1', '-At'], text.encode()).decode().strip()
    def fingerprints(db):
        tables = sql(db, "SELECT tablename FROM pg_tables WHERE schemaname='auditlab' ORDER BY tablename;").splitlines()
        result = {}
        for table in tables:
            assert table.replace('_', '').isalnum()
            value = sql(db, f'SELECT count(*),md5(COALESCE(string_agg(row_to_json(t)::text,E\'\\n\' ORDER BY row_to_json(t)::text),\'\')) FROM auditlab."{table}" t;')
            result[table] = value
        return result
    try:
        snapshot = command('pg_dump', config['database'], ['--no-owner', '--no-privileges'])
        fd = os.open(backup, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'wb') as stream: stream.write(snapshot)
        before = fingerprints(config['database'])
        assert before
        sql('postgres', f'CREATE DATABASE {target} OWNER qa_migrator;')
        assert sql(target, "SELECT count(*) FROM pg_tables WHERE schemaname='auditlab';") == '0'
        command('psql', target, ['-v', 'ON_ERROR_STOP=1'], b'SET ROLE qa_migrator;\n' + snapshot)
        after = fingerprints(target)
        assert before == after, 'Restored contents differ'
        # Validate actual least-privilege runtime reads on the restored schema.
        restored = dict(config, database=target)
        restored_env = fixture.environment(restored)
        restored_env['AUDITLAB_TARGET_DB_ROLE'] = 'qa_runtime'
        grant = subprocess.run([str(fixture.BINARY), 'grant-runtime'], env=restored_env, capture_output=True, timeout=60)
        assert grant.returncode == 0, 'Restore grants failed'
        private_env = dict(env, PGPASSWORD=config['passwords']['runtime'])
        probe = subprocess.run(fixture.DOCKER + ['exec', '--env', 'PGPASSWORD', '-i', config['container'], 'psql',
            '-h', '127.0.0.1', '-U', 'qa_runtime', '-d', target, '-At', '-v', 'ON_ERROR_STOP=1'],
            input=b'SELECT count(*) FROM auditlab.events;', env=private_env, capture_output=True, timeout=30)
        assert probe.returncode == 0, 'Runtime cannot read restored events'
        with socket.socket() as port_probe:
            port_probe.bind(('127.0.0.1', 0)); port = port_probe.getsockname()[1]
        restored_env['AUDITLAB_PORT'] = str(port)
        log_path = directory / (target + '-server.log')
        fd = os.open(log_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'wb') as log:
            process = subprocess.Popen([str(fixture.BINARY)], cwd=fixture.ROOT, env=restored_env, stdout=log, stderr=log)
        try:
            deadline = time.monotonic() + 30
            while True:
                try:
                    client = http.client.HTTPConnection('127.0.0.1', port, timeout=2)
                    client.request('GET', '/ready'); response = client.getresponse(); response.read(); client.close()
                    if response.status == 200: break
                except OSError: pass
                if process.poll() is not None or time.monotonic() > deadline: raise RuntimeError('Restored API not ready')
                time.sleep(.25)
            client = http.client.HTTPConnection('127.0.0.1', port, timeout=5)
            client.request('POST', '/v1/auth/login', json.dumps({'username': 'qa_admin', 'password': config['passwords']['admin']}),
                           {'Content-Type': 'application/json'})
            response = client.getresponse(); body = json.loads(response.read()); assert response.status == 200
            client.request('GET', '/v1/events?limit=100', headers={'Authorization': 'Bearer ' + body['accessToken']})
            response = client.getresponse(); body = json.loads(response.read()); assert response.status == 200 and len(body['items']) == 100
            client.close()
        finally:
            process.terminate(); process.wait(timeout=15)
        report = {'passed': True, 'tables_compared': len(before), 'all_table_fingerprints_equal': True,
            'restored_events': int(probe.stdout), 'backup_sha256': hashlib.sha256(snapshot).hexdigest(),
            'backup_bytes': len(snapshot), 'source_preserved': True, 'target_database': target,
            'runtime_read_confirmed': True, 'api_on_restored_database': 'ready, login and 100-event page passed'}
        Path('.tooling/full-delivery/restore.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2))
    finally:
        fixture.start(config)

if __name__ == '__main__': main()
