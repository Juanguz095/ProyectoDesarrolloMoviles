"""Bundle a development build with honest validation metadata.

Does not replace the native acceptance gates in package_qa/package_preview.
This package records build/static checks and explicitly leaves functional validation pending.
"""
import argparse
import datetime as dt
import hashlib
import json
import re
from pathlib import Path
import subprocess
import zipfile
from package_qa import ROOT, payloads, digest


def verified_tests(root, record):
    """Bind optional automated proof to the exact APKs and unchanged runtime sources.

    This does not grant Windows, pilot, signing or antimalware approval.
    """
    reference = record.get('test_evidence')
    if not reference:
        if record['version'] == '0.8.0-preview':
            raise ValueError('0.8 requires its recorded automated evidence')
        return None
    path = (root / reference).resolve()
    if not path.is_relative_to((root / 'docs/evidencias').resolve()) or not path.is_file():
        raise ValueError('Tracked public test evidence required')
    proof = json.loads(path.read_text())
    source = proof.get('source_revision', '')
    if not re.fullmatch('[0-9a-f]{40}', source):
        raise ValueError('Validated source revision required')
    changed = subprocess.check_output(['git', 'diff', '--name-only', source, 'HEAD', '--',
        'apps', 'backend/ktor', 'contracts', 'build.gradle.kts', 'settings.gradle.kts', 'gradle',
        'delivery/windows/Iniciar-AuditLab.ps1', 'delivery/windows/Revisar-AuditLab.ps1'], cwd=root)
    if changed.strip():
        raise ValueError('Runtime sources differ from tested revision')
    expected = {a['component']: a['sha256'] for a in record['artifacts']}
    actual = {a['app']: a['sha256'] for a in proof.get('artifacts', [])}
    native_runs = proof.get('native_update_verification', [])
    native = {a['app']: a['base_sha256'] for a in native_runs}
    suites = proof.get('jvm_and_database_suites', [])
    if (set(expected) != {'agent', 'control'} or actual != expected or native != expected
            or len(native_runs) != 2 or any(r.get('passed') is not True for r in native_runs) or not suites
            or any(s.get('tests', 0) <= 0 or any(s.get(k, 1) != 0 for k in ('failures', 'errors', 'skipped')) for s in suites)
            or proof.get('native_tests', 0) < 1
            or proof.get('native_confirmed_upgrade', {}).get('installation_confirmed') is not True
            or proof.get('native_boot_recovery', {}).get('service_started_before_test_runner') is not True):
        raise ValueError('Successful tests for these exact artifacts required')
    return {'path': reference, 'sha256': digest(path), 'source_revision': source,
            'jvm_and_database_tests': sum(s['tests'] for s in suites), 'native_tests': proof['native_tests']}


def bundle(destination, record_path):
    record=json.loads(record_path.read_text())
    if record['checks']['build_exit_code']!=0 or record['checks']['apk_audit_exit_code']!=0:
        raise ValueError('A successful build and APK audit record are required')
    if subprocess.check_output(['git','status','--porcelain'],cwd=ROOT):
        raise ValueError('Commit all source and documentation before packaging')
    if destination.exists():
        raise ValueError('Existing archive preserved; choose a new output filename')
    tests = verified_tests(ROOT, record)
    modern = record['version'] == '0.8.0-preview'
    files=payloads(ROOT)
    files.update({'Iniciar-AuditLab.ps1':ROOT/'delivery/windows/Iniciar-AuditLab.ps1',
                  'PASOS-SIN-USB.md':ROOT/'docs/INICIO_WINDOWS_0.4.md',
                  'LEEME.md':ROOT/'docs/ACTUALIZACION_0.7.md'})
    if modern:
        files['LEEME.md'] = ROOT / 'delivery/windows/LEEME_0.8.md'
        files['PASOS-SIN-USB.md'] = ROOT / 'delivery/windows/LEEME_0.8.md'
        files['Revisar-AuditLab.ps1'] = ROOT / 'delivery/windows/Revisar-AuditLab.ps1'
    contents={name:path.read_bytes() for name,path in files.items()}
    for component in ('agent','control'):
        artifact=next(a for a in record['artifacts'] if a['component']==component)
        compiled=json.loads((ROOT/f'apps/{component}/build/outputs/apk/debug/output-metadata.json').read_text())['elements'][0]
        if compiled['versionName']!=record['version'] or compiled['versionCode']!=artifact['versionCode']:
            raise ValueError('Compiled Android version mismatch')
        if hashlib.sha256(contents[f'apks/auditlab-{component}-debug.apk']).hexdigest()!=artifact['sha256']:
            raise ValueError('APK differs from the recorded build')
    with zipfile.ZipFile(ROOT/'backend/ktor/build/install/ktor/lib/ktor.jar') as jar:
        for migration in (ROOT/'backend/ktor/src/main/resources/db/migration').glob('*.sql'):
            if jar.read('db/migration/'+migration.name)!=migration.read_bytes():
                raise ValueError('Installed API migration differs from source')
    manifest={'version':record['version'],'created_at':dt.datetime.now(dt.timezone.utc).isoformat(),
              'source_revision':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
              'kind':('development preview; Windows and pilot validation pending' if tests else 'development preview; functional validation pending'),'working_tree_dirty':False,
              'pilot_approved':False,'windows_verified':False,'real_whatsapp_verified':False,
              'antimalware_validation_required_before_publication':True,'publish_allowed':False,
              'automated_tests_executed_for_this_revision':tests is not None,'automated_validation':tests,
              'complete_native_acceptance':False,'validation_record':record,
              'files':{n:{'bytes':len(b),'sha256':hashlib.sha256(b).hexdigest()} for n,b in sorted(contents.items())}}
    contents['manifest.json']=(json.dumps(manifest,indent=2)+'\n').encode()
    contents['SHA256SUMS']=''.join(hashlib.sha256(b).hexdigest()+'  '+n+'\n' for n,b in sorted(contents.items())).encode()
    destination.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(destination,'x',compression=zipfile.ZIP_DEFLATED) as archive:
        for name,data in sorted(contents.items()):
            info=zipfile.ZipInfo(name);info.create_system=3
            info.external_attr=(0o100755 if name in files and files[name].stat().st_mode & 0o111 else 0o100644)<<16
            info.compress_type=zipfile.ZIP_DEFLATED;archive.writestr(info,data)
    with zipfile.ZipFile(destination) as archive:
        if archive.testzip() is not None: raise ValueError('Archive CRC check failed')
        for name,data in contents.items():
            if archive.read(name)!=data: raise ValueError('Archive content mismatch')
    return {'file':str(destination),'version':record['version'],'source_revision':manifest['source_revision'],
            'bytes':destination.stat().st_size,'sha256':digest(destination),'files':len(contents),'pilot_approved':False,
            'publish_allowed':False,'antimalware_validation_required_before_publication':True}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--build-record',required=True,type=Path)
    args=parser.parse_args()
    print(json.dumps(bundle(args.output.resolve(),args.build_record.resolve()),indent=2))
