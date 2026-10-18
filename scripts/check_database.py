"""Run JVM database/auth tests, CLI, restart and readiness against fresh PostgreSQL only."""
import base64
import http.client
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
IMAGE = 'postgres:17.6-bookworm@sha256:f3bd19c606e442c3d7bdfa8002e03fe260a1023351e0ea4598032022b68dd6e3'


def main():
    environment = os.environ.copy()
    for variable in list(environment):
        if variable.startswith('AUDITLAB_AUTH_'):
            environment.pop(variable)  # The fixture must not inherit signing keys or limits.
    for selector in ('DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH'):
        environment.pop(selector, None)
    if (ROOT / '.tooling/jdk/bin/java').exists():
        environment['JAVA_HOME'] = str(ROOT / '.tooling/jdk')
    docker = ['docker','--host=unix:///var/run/docker.sock']
    name = 'auditlab-schema-' + uuid.uuid4().hex[:12]
    database_name = 'auditlab_test_' + uuid.uuid4().hex[:12]
    passwords = {role:secrets.token_urlsafe(32) for role in ('root','migrator','runtime','maintenance','bootstrap')}
    passwords['signing_key'] = base64.b64encode(secrets.token_bytes(32)).decode('ascii')
    environment['AUDITLAB_AUTH_SIGNING_KEY_BASE64'] = passwords['signing_key']
    environment['PGPASSWORD'] = passwords['root']
    started, server = False, None
    log_path = ROOT / '.tooling/database-check.log'
    log_path.parent.mkdir(exist_ok=True)

    def sanitized(text):
        for value in passwords.values(): text = text.replace(value, '[redacted]')
        return text

    def run(args, *, input=None, timeout=60, expected_exit=0):
        result = subprocess.run(args, cwd=ROOT, env=environment, input=input, capture_output=True, text=True, timeout=timeout)
        output = sanitized(result.stdout + result.stderr)
        with log_path.open('a') as log: log.write(output)
        if result.returncode != expected_exit:
            print(output[-6000:])
            raise RuntimeError('A database check command failed; see .tooling/database-check.log')
        return output

    def request(path):
        client = http.client.HTTPConnection('127.0.0.1', server_port, timeout=5)
        try:
            client.request('GET', path)
            response = client.getresponse()
            return response.status, json.loads(response.read())
        finally:
            client.close()

    def auth_request(path, body=None, token=None):
        client = http.client.HTTPConnection('127.0.0.1', server_port, timeout=8)
        headers = {'Content-Type': 'application/json'}
        if token: headers['Authorization'] = 'Bearer '+token
        try:
            client.request('POST', '/v1/auth/'+path, json.dumps(body) if body is not None else None, headers)
            response = client.getresponse()
            data = response.read()
            assert response.getheader('Cache-Control') == 'no-store'
            correlation = str(uuid.UUID(response.getheader('X-Correlation-ID')))
            if response.status >= 400:
                assert response.getheader('Content-Type').startswith('application/problem+json')
                assert json.loads(data)['correlationId'] == correlation
            return response.status, json.loads(data) if data else None
        finally:
            client.close()

    log_path.write_text('Isolated PostgreSQL migration/CLI checks\n')
    with tempfile.TemporaryDirectory(prefix='auditlab-schema-') as directory:
        settings = Path(directory) / 'postgres.env'
        settings.write_text('POSTGRES_USER=fixture_root\nPOSTGRES_DB=postgres\nPOSTGRES_INITDB_ARGS=--auth-host=scram-sha-256\nPOSTGRES_PASSWORD='+passwords['root']+'\n')
        settings.chmod(0o600)
        try:
            run(docker + ['info','--format','{{.ServerVersion}}'])
            run(docker + ['run','--rm','--detach','--name',name,'--env-file',str(settings),'--publish','127.0.0.1::5432',IMAGE], timeout=120)
            started = True
            psql = docker + ['exec','--env','PGPASSWORD','-i',name,'psql','-h','127.0.0.1','-U','fixture_root','-d','postgres','-v','ON_ERROR_STOP=1','-At']
            deadline = time.monotonic()+45
            while True:
                probe = subprocess.run(psql, env=environment, input='SELECT 1;', capture_output=True, text=True, timeout=5)
                if probe.returncode == 0: break
                if time.monotonic() > deadline: raise RuntimeError('PostgreSQL TCP startup timed out')
                time.sleep(0.5)
            # Only generated URL-safe secrets are interpolated in this private SQL input.
            sql = '\n'.join(f"CREATE ROLE fixture_{role} LOGIN PASSWORD '{passwords[role]}';" for role in ('migrator','runtime','maintenance'))
            sql += f'\nCREATE DATABASE {database_name} OWNER fixture_migrator;\n'
            run(psql, input=sql)
            database_port = int(run(docker + ['port',name,'5432/tcp']).strip().rsplit(':',1)[1])
            url = f'jdbc:postgresql://127.0.0.1:{database_port}/{database_name}?sslmode=disable&connectTimeout=2&socketTimeout=5'
            for prefix,role in [('AUDITLAB_MIGRATION','migrator'),('AUDITLAB_DB','runtime'),('AUDITLAB_TEST_MAINTENANCE','maintenance')]:
                environment[prefix+'_JDBC_URL'] = url
                environment[prefix+'_USER'] = 'fixture_'+role
                environment[prefix+'_PASSWORD'] = passwords[role]
            environment.update(AUDITLAB_TEST_ISOLATED='true',AUDITLAB_BOOTSTRAP_PASSWORD=passwords['bootstrap'],AUDITLAB_BOOTSTRAP_USERNAME='fixture_cli_admin',AUDITLAB_BOOTSTRAP_ROLE='admin')
            began = time.time_ns()
            run(['python3','scripts/gradle.py',':backend:ktor:test',':backend:ktor:integrationTest',':backend:ktor:installDist','--no-daemon'], timeout=420)
            reports = list((ROOT/'backend/ktor/build/test-results/integrationTest').glob('TEST-*.xml'))
            assert reports and all(p.stat().st_mtime_ns >= began for p in reports), 'Missing or stale integration results'
            suites = [ET.parse(p).getroot() for p in reports]
            count = sum(int(s.attrib['tests']) for s in suites)
            assert count > 0 and all(int(s.attrib.get(key,0)) == 0 for s in suites for key in ('failures','errors','skipped')), 'Integration did not complete successfully'
            print(f'Fresh PostgreSQL integration results: {count} tests passed, none skipped.')
            binary = str(ROOT / 'backend/ktor/build/install/ktor/bin/ktor')
            assert 'Migrations applied: 0' in run([binary,'migrate'])
            assert 'User created: true' in run([binary,'bootstrap-user'])
            assert 'User created: false' in run([binary,'bootstrap-user'])
            environment.pop('AUDITLAB_BOOTSTRAP_PASSWORD')
            assert 'Required variable: AUDITLAB_BOOTSTRAP_PASSWORD' in run([binary,'bootstrap-user'], expected_exit=2)
            environment['AUDITLAB_BOOTSTRAP_PASSWORD'] = passwords['bootstrap']
            for role,command in [('runtime','grant-runtime'),('maintenance','grant-maintenance')]:
                environment['AUDITLAB_TARGET_DB_ROLE']='fixture_'+role
                run([binary,command])
            with socket.socket() as reservation:
                reservation.bind(('127.0.0.1',0));server_port=reservation.getsockname()[1]
            environment['AUDITLAB_PORT'] = str(server_port)
            server_environment = {
                key: value for key, value in environment.items()
                if not key.startswith(('AUDITLAB_MIGRATION_', 'AUDITLAB_BOOTSTRAP_', 'AUDITLAB_TEST_'))
                and key not in ('AUDITLAB_TARGET_DB_ROLE', 'PGPASSWORD')
            }
            with tempfile.TemporaryFile() as server_log:
                server = subprocess.Popen([binary],cwd=ROOT,env=server_environment,stdout=server_log,stderr=subprocess.STDOUT)
                deadline=time.monotonic()+30
                while True:
                    if server.poll() is not None: raise RuntimeError('Ktor exited before readiness')
                    try:
                        status,data=request('/ready')
                        if status==200 and data=={'status':'ready'}: break
                    except (ConnectionError,TimeoutError): pass
                    if time.monotonic()>deadline: raise RuntimeError('Ktor/database readiness timed out')
                    time.sleep(0.2)
                assert request('/health')[0] == 200
                status, session = auth_request('login', {'username':'fixture_cli_admin','password':passwords['bootstrap']})
                assert status == 200 and session['user']['role'] == 'admin'
                status, rotated = auth_request('refresh', {'refreshToken':session['refreshToken']})
                assert status == 200 and rotated['sessionId'] == session['sessionId']
                # Restart only our process and reuse the persisted refresh with the same signing key.
                server.terminate(); server.wait(timeout=10)
                server = subprocess.Popen([binary],cwd=ROOT,env=server_environment,stdout=server_log,stderr=subprocess.STDOUT)
                deadline = time.monotonic()+30
                while True:
                    if server.poll() is not None: raise RuntimeError('Ktor exited after restart')
                    try:
                        if request('/ready') == (200,{'status':'ready'}): break
                    except (ConnectionError,TimeoutError): pass
                    if time.monotonic()>deadline: raise RuntimeError('Ktor restart timed out')
                    time.sleep(0.2)
                status, after_restart = auth_request('refresh', {'refreshToken':rotated['refreshToken']})
                assert status == 200 and after_restart['sessionId'] == session['sessionId']
                assert auth_request('logout', token=after_restart['accessToken']) == (204,None)
                assert auth_request('refresh', {'refreshToken':after_restart['refreshToken']})[0] == 401
                run(docker + ['stop','--timeout','2',name])
                # The pooled connection must detect the lost DB instead of returning a stale success.
                assert request('/ready') == (503,{'status':'unavailable'})
                assert request('/health')[0] == 200
                status, error = auth_request('login', {'username':'fixture_cli_admin','password':passwords['bootstrap']})
                assert status == 503 and error['code'] == 'SERVICE_UNAVAILABLE'
            print('JVM unit/integration tests, repeatable migrations, bootstrap/grants CLI, auth HTTP/restart/logout and readiness/auth DB failure 200→503 passed. Temporary resources removed.')
        finally:
            if server:
                server.terminate()
                try: server.wait(timeout=10)
                except subprocess.TimeoutExpired: server.kill();server.wait()
            if started:
                subprocess.run(docker+['rm','--force',name],env=environment,capture_output=True,check=False,timeout=30)


if __name__ == '__main__':
    try: main()
    except (RuntimeError,subprocess.TimeoutExpired,AssertionError) as error:
        raise SystemExit('Isolated database check failed; inspect its sanitized log. '+str(error)) from None
