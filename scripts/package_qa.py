"""Bundle the tested debug APKs, installed Ktor distribution and tracked source.

No signing, credentials, fixture data, androidTest APKs, deployment or publication.
Requires a complete native report matching all four current APK hashes.
"""
import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

from check_android import complete

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def native_proof(root, report):
    verdict = json.loads((report / 'verdict.json').read_text())
    stages = json.loads((report / 'summary.json').read_text())
    environment = json.loads((report / 'environment.json').read_text())
    if (not complete(stages) or verdict != {'approved': True, 'expected_stages': 17,
            'executed_stages': 17, 'passed': 17, 'failed': 0, 'not_executed': 0}):
        raise ValueError('A complete successful 17-stage native run is required')
    expected = {}
    for component in ('agent', 'control'):
        for variant, name in [('debug', f'{component}-debug.apk'),
                              ('androidTest/debug', f'{component}-debug-androidTest.apk')]:
            path = root / f'apps/{component}/build/outputs/apk/{variant}/{name}'
            if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(root.resolve()):
                raise ValueError('Current APKs must exist as regular files')
            expected[(component, name)] = digest(path)
    actual = {(item['component'], item['file']): item['sha256'] for item in environment['artifacts']}
    if actual != expected or len(environment['artifacts']) != len(expected):
        raise ValueError('Current APK hashes differ from the installed native artifacts')
    return {'approved': True, 'stages': 17, 'devices': environment['devices'],
            'recorded_at': environment['recorded_at'], 'artifacts': environment['artifacts']}


def payloads(root):
    files = {}
    for component in ('agent', 'control'):
        files[f'apks/auditlab-{component}-debug.apk'] = root / f'apps/{component}/build/outputs/apk/debug/{component}-debug.apk'
    distribution = root / 'backend/ktor/build/install/ktor'
    files['api/bin/ktor'] = distribution / 'bin/ktor'
    files['api/bin/ktor.bat'] = distribution / 'bin/ktor.bat'
    libraries = sorted((distribution / 'lib').glob('*.jar'))
    if not libraries:
        raise ValueError('Build :backend:ktor:installDist before packaging')
    for path in libraries:
        files['api/lib/' + path.name] = path
    # Use Git's explicit source inventory; never recurse into caches or fixture data.
    names = subprocess.run(['git', 'ls-files', '-z'],
                           cwd=root, capture_output=True, check=True).stdout.decode().split('\0')
    for name in filter(None, names):
        path = Path(name)
        if (any(part in {'.git', '.tooling', '.venv', 'build'} for part in path.parts)
                or (path.name.startswith('.env') and path.name != '.env.example')
                or path.suffix.lower() in {'.pem', '.key', '.jks', '.keystore', '.p12', '.db', '.sqlite', '.sqlite3'}):
            raise ValueError('Private or generated files must not be tracked in the source bundle')
        files['source/' + name] = root / name
        if (name.startswith('docs/') and name.endswith(('.md', '.json', '.yaml'))) or name == 'contracts/openapi.yaml':
            files[name] = root / name
    for path in files.values():
        if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(root.resolve()):
            raise ValueError('Bundle inputs must be regular files inside the checkout')
    return files


README = '''# AuditLab — paquete de desarrollo verificado

Contiene las dos apps Android reales, la API Ktor compilada, manuales y el código
versionado en `source/`, con los wrappers y las pruebas para reconstruir el proyecto.
Es una entrega QA con firma debug; el piloto y la firma de distribución siguen pendientes.
No contiene credenciales activas, claves privadas ni bases de datos.

## Desarrollo con dos emuladores

No necesitas dominio para esta modalidad. Prepara las herramientas y el fixture
local con Docker siguiendo `source/docs/PRUEBAS_INTEGRADAS.md` y la guía de
herramientas de `source/docs/desarrollo.md`. El fixture crea sus propias cuentas
ficticias y conserva sus credenciales en un archivo privado excluido de Git.
Instala las APKs en emuladores separados y usa `http://10.0.2.2:8081` en ambos.
Esa dirección conecta con la API del equipo anfitrión; no es una URL de Internet.

## Instalación para teléfonos y piloto

1. Verifica los archivos al extraer: `sha256sum -c SHA256SUMS`.
2. Prepara Java 21 y PostgreSQL. Sigue `docs/despliegue.md` para configurar
   las cuentas de migración/runtime/mantenimiento, migrar y crear tu usuario.
   El ejecutable es `api/bin/ktor` (o `api/bin/ktor.bat` en Windows).
3. Sirve la API detrás de HTTPS con un certificado reconocido por Android.
   La API escucha en loopback: configura el proxy según el manual.
4. Instala `apks/auditlab-agent-debug.apk` en A y `apks/auditlab-control-debug.apk`
   en B (Android 8/API26 o posterior). Conserva datos y firma al actualizar.
5. Introduce tu URL HTTPS en las apps y sigue `docs/MANUAL_PRODUCTO.md`.
   El centro crea el código; el agente requiere consentimiento e indicador visible.

Los hashes y el alcance de la prueba nativa están en `manifest.json`.
Para reconstruir, entrar en `source/` y seguir su README y guía de desarrollo.
Un emulador no sustituye las pruebas de entrega en dos teléfonos propios.
'''


def bundle(root, report, destination):
    proof = native_proof(root, report)
    files = payloads(root)
    if destination.exists():
        raise ValueError('Existing package preserved; choose a new output filename')
    revision = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=root, capture_output=True, text=True, check=True).stdout.strip()
    dirty = bool(subprocess.run(['git', 'status', '--porcelain'], cwd=root,
                               capture_output=True, check=True).stdout)
    contents = {name: path.read_bytes() for name, path in files.items()}
    executable = {name for name, path in files.items() if path.stat().st_mode & 0o111} | {'api/bin/ktor'}
    for component in ('agent', 'control'):
        expected = next(item['sha256'] for item in proof['artifacts']
                        if item['component'] == component and item['file'] == f'{component}-debug.apk')
        if hashlib.sha256(contents[f'apks/auditlab-{component}-debug.apk']).hexdigest() != expected:
            raise ValueError('APK changed during packaging; repeat acceptance on the current build')
    contents['LEEME.md'] = README.encode()
    manifest = {'created_at': dt.datetime.now(dt.timezone.utc).isoformat(), 'source_revision': revision,
        'working_tree_dirty': dirty, 'kind': 'QA debug', 'pilot_approved': False,
        'operator_distribution_signature': False, 'native_acceptance': proof,
        'files': {name: {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
                  for name, data in sorted(contents.items())}}
    contents['manifest.json'] = (json.dumps(manifest, indent=2, ensure_ascii=False) + '\n').encode()
    contents['SHA256SUMS'] = ''.join(hashlib.sha256(data).hexdigest() + '  ' + name + '\n'
                                   for name, data in sorted(contents.items())).encode()
    destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    with zipfile.ZipFile(destination, 'x', compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(contents.items()):
            entry = zipfile.ZipInfo(name)
            entry.create_system = 3
            entry.external_attr = (0o100755 if name in executable else 0o100644) << 16
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, data)
    with zipfile.ZipFile(destination) as archive:
        if archive.testzip() is not None or any(archive.read(name) != data for name, data in contents.items()):
            raise RuntimeError('Package integrity check failed')
    return {'file': str(destination), 'sha256': digest(destination), 'bytes': destination.stat().st_size,
            'files': len(contents), 'native_stages': 17, 'pilot_approved': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--native-report', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(bundle(ROOT, args.native_report, args.output), indent=2))


if __name__ == '__main__': main()
