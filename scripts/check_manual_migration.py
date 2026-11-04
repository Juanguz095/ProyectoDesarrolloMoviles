"""Exact V1–V10 DDL → V11, backup and restore in NEW private QA databases.
Creates its own private fixture. Retains all databases and backups.
Flyway is checked separately by check_database.py.
"""
import json
import os
import subprocess
import uuid
import local_fixture as fixture


def main():
    fixture.DIRECTORY=fixture.ROOT/".tooling/manual-migration-fixture"
    fixture.prepare()
    config=fixture.settings()
    env=fixture.environment(config)
    label=fixture.run(config,fixture.DOCKER+['inspect','--format','{{index .Config.Labels "com.auditlab.qa-fixture"}}',config['container']])[1]
    assert label==str(fixture.DIRECTORY)
    prefix='manual_'+uuid.uuid4().hex[:12]
    directory=fixture.DIRECTORY/'manual-migration'/prefix
    directory.mkdir(parents=True,mode=0o700)
    def command(program,db,args=(),data=None):
        result=subprocess.run(fixture.DOCKER+['exec','--env','PGPASSWORD','-i',config['container'],program,
            '-h','127.0.0.1','-U','qa_root','-d',db,*args],input=data,capture_output=True,env=env,timeout=60)
        if result.returncode:
            error=directory/'command-error.log'
            error.touch(mode=0o600)
            error.write_bytes(result.stderr)
            raise RuntimeError('Isolated migration command failed; see private command-error.log')
        return result.stdout
    def sql(db,text):
        return command('psql',db,['-v','ON_ERROR_STOP=1','-At'],text.encode()).decode().strip()
    source=prefix+'_source'; restored=prefix+'_restored'
    for db in (source,restored): sql('postgres',f'CREATE DATABASE {db};')
    migrations=fixture.ROOT/'backend/ktor/src/main/resources/db/migration'
    sql(source,"CREATE SCHEMA auditlab;")
    for number in range(1,11): sql(source,next(migrations.glob(f'V{number}__*.sql')).read_text())
    user,code,device,consent,event=[str(uuid.uuid4()) for _ in range(5)]
    sql(source,f"""BEGIN; SET CONSTRAINTS ALL DEFERRED;
    INSERT INTO auditlab.users(id,username,password_hash,role) VALUES ('{user}','migration_fixture','$argon2id$fixture-only','admin');
    INSERT INTO auditlab.enrollment_codes(id,code_hash,created_by,expires_at) VALUES ('{code}','{'a'*64}','{user}',now()+interval '10 minutes');
    INSERT INTO auditlab.devices(id,enrollment_code_id,token_hash,consent_id) VALUES ('{device}','{code}','{'b'*64}','{consent}');
    INSERT INTO auditlab.consents(id,device_id,policy_version,accepted_at) VALUES ('{consent}','{device}','mvp-2-real',now());
    UPDATE auditlab.enrollment_codes SET used_at=now(),consumed_device_id='{device}' WHERE id='{code}';
    INSERT INTO auditlab.events(device_id,event_id,consent_id,occurred_at,category,direction,is_simulated,metadata,payload_hash)
    VALUES ('{device}','{event}','{consent}',now(),'app_lifecycle','occurred',true,'{{"appEventCode":"agent_started"}}','{'c'*64}'); COMMIT;""")
    def snapshot(db):
        tables=sql(db,"SELECT tablename FROM pg_tables WHERE schemaname='auditlab' ORDER BY tablename").splitlines()
        return {t:sql(db,f"SELECT count(*),md5(COALESCE(string_agg(row_to_json(t)::text,E'\\n' ORDER BY row_to_json(t)::text),'')) FROM auditlab.{t} t") for t in tables}
    before=snapshot(source)
    backup=command('pg_dump',source,['--no-owner','--no-privileges'])
    fd=os.open(directory/'before-v11.sql',os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,'wb') as stream: stream.write(backup)
    v11=(migrations/'V11__manual_text_shares.sql').read_text()
    sql(source,v11)
    assert snapshot(source)==before, 'V11 changed existing data'
    command('psql',restored,['-v','ON_ERROR_STOP=1'],backup)
    assert snapshot(restored)==before, 'Backup restoration differs'
    sql(restored,v11)
    assert snapshot(restored)==before, 'Restored V10 to V11 changed data'
    result={'passed':True,'ddl_v10_to_v11':True,'backup_before_migration':True,'restore_then_v11':True,
            'identical_tables':len(before),'synthetic_events':1,'flyway_check':'separate check_database.py',
            'fixture_directory':str(directory.relative_to(fixture.ROOT))}
    (directory/'result.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result))

if __name__=='__main__': main()
