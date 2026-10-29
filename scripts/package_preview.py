"""Package the 0.4 preview with its exact paired Android proof, not full pilot acceptance.

Keeps package_qa.py's complete 17-stage acceptance requirement unchanged.
No credentials, local databases or instrumentation APKs are included.
"""
import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile
from package_qa import ROOT, payloads, digest


def bundle(destination, report):
    proof = json.loads((report / 'verdict.json').read_text())
    if (proof.get('approved') is not True
            or proof.get('kind') != 'notification-preview-synthetic-callback'
            or set(proof.get('results', {})) != {'agent', 'control'}
            or any(r != {'started': 1, 'passed': 1, 'failed': 0, 'skipped': 0, 'approved': True}
                   for r in proof['results'].values())):
        raise ValueError('A successful two-AVD preview run is required')
    expected = {}
    for component in ('agent', 'control'):
        for variant, name in [('debug', f'{component}-debug.apk'),
                              ('androidTest/debug', f'{component}-debug-androidTest.apk')]:
            path = ROOT / f'apps/{component}/build/outputs/apk/{variant}/{name}'
            if path.is_symlink() or not path.is_file():
                raise ValueError('Regular APK files required')
            expected[(component, name)] = digest(path)
    if len(proof['artifacts']) != 4 or {
            (a['component'], a['file']): a['sha256'] for a in proof['artifacts']} != expected:
        raise ValueError('APKs do not match the paired Android run')
    if subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT):
        raise ValueError('Commit source and documentation before packaging')
    if destination.exists():
        raise ValueError('Existing package preserved; choose a new destination')
    files = payloads(ROOT)
    files.update({
        'Iniciar-AuditLab.ps1': ROOT / 'delivery/windows/Iniciar-AuditLab.ps1',
        'PASOS-SIN-USB.md': ROOT / 'docs/INICIO_WINDOWS_0.4.md',
    })
    # A stale installed distribution must not silently omit migrations/features.
    with zipfile.ZipFile(ROOT / 'backend/ktor/build/install/ktor/lib/ktor.jar') as jar:
        assert 'com/auditlab/api/WhatsAppPreviewRoutesKt.class' in jar.namelist()
        for migration in (ROOT / 'backend/ktor/src/main/resources/db/migration').glob('*.sql'):
            assert jar.read('db/migration/' + migration.name) == migration.read_bytes()
    contents = {name: path.read_bytes() for name, path in files.items()}
    for component in ('agent', 'control'):
        assert hashlib.sha256(contents[f'apks/auditlab-{component}-debug.apk']).hexdigest() == expected[(component, f'{component}-debug.apk')]
    contents['LEEME.md'] = contents['PASOS-SIN-USB.md']
    manifest = {
        'version': '0.4.0-preview', 'created_at': dt.datetime.now(dt.timezone.utc).isoformat(),
        'source_revision': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        'working_tree_dirty': False, 'kind': 'QA debug preview',
        'pilot_approved': False, 'windows_verified': False, 'real_whatsapp_verified': False,
        'operator_distribution_signature': False, 'native_preview_proof': proof,
        'files': {name: {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
                  for name, data in sorted(contents.items())},
    }
    contents['manifest.json'] = (json.dumps(manifest, indent=2) + '\n').encode()
    contents['SHA256SUMS'] = ''.join(hashlib.sha256(data).hexdigest() + '  ' + name + '\n'
                                   for name, data in sorted(contents.items())).encode()
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination, 'x', compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(contents.items()):
            info = zipfile.ZipInfo(name)
            info.create_system = 3
            info.external_attr = (0o100755 if files.get(name) and files[name].stat().st_mode & 0o111 else 0o100644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
    with zipfile.ZipFile(destination) as archive:
        assert archive.testzip() is None
        for name, data in contents.items():
            assert archive.read(name) == data
    return {'file': str(destination), 'bytes': destination.stat().st_size,
            'sha256': digest(destination), 'source_revision': manifest['source_revision'],
            'files': len(contents), 'pilot_approved': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--report', type=Path, default=ROOT / '.tooling/whatsapp-pair')
    args = parser.parse_args()
    print(json.dumps(bundle(args.output.resolve(), args.report.resolve()), indent=2))
