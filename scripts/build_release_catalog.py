"""Create an operator-reviewed update catalog from signed AuditLab APKs.

Does not upload, publish, change credentials or modify the APKs. The URLs must be
published separately with the same bytes before configuring this file on a server.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]


def tool(name):
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or ROOT / '.tooling/android-sdk')
    suffix = '.bat' if os.name == 'nt' and name == 'apksigner' else '.exe' if os.name == 'nt' else ''
    for directory in sorted((sdk / 'build-tools').iterdir(), reverse=True):
        candidate = directory / (name + suffix)
        if candidate.is_file():
            return candidate
    raise RuntimeError(f'Android SDK tool unavailable: {name}')


def entry(apk):
    data = apk.read_bytes()
    if not 0 < len(data) <= 64 * 1024 * 1024:
        raise ValueError('APK size outside the supported range')
    badging = subprocess.run([str(tool('aapt2')), 'dump', 'badging', str(apk)], check=True, capture_output=True, text=True).stdout
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not package or package[1] not in ('com.auditlab.agent', 'com.auditlab.control'):
        raise ValueError('Only AuditLab packages are accepted')
    app = package[1].rsplit('.', 1)[1]
    version = package[3]
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,39}', version):
        raise ValueError('Unsupported version name')
    signature = subprocess.run([str(tool('apksigner')), 'verify', '--verbose', '--print-certs', str(apk)], check=True, capture_output=True, text=True).stdout
    signers = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})', signature)
    if len(signers) != 1:
        raise ValueError('A single verified signing certificate is required')
    digest = hashlib.sha256(data).hexdigest()
    return {'releaseId': str(uuid.uuid5(uuid.NAMESPACE_URL, f'{package[1]}:{package[2]}:{digest}')),
            'app': app, 'packageName': package[1], 'versionName': version, 'versionCode': int(package[2]),
            'minSdk': int(re.search(r"(?:minSdkVersion|sdkVersion):'(\d+)'", badging)[1]), 'bytes': len(data),
            'sha256': digest, 'signerSha256': signers[0].lower(),
            'downloadUrl': f'https://raw.githubusercontent.com/Juanguz095/ProyectoDesarrolloMoviles/refs/heads/descargas-auditlab/auditlab-{app}-{version}.apk'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('apks', type=Path, nargs='+')
    args = parser.parse_args()
    items = [entry(apk) for apk in args.apks]
    if len(items) > 100 or len({(item['app'], item['versionCode']) for item in items}) != len(items):
        parser.error('Duplicate app/version or catalog exceeds 100 entries')
    if args.output.exists():
        parser.error('Output already exists; review it and select a new file instead of replacing it')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('x', encoding='utf-8') as stream:
        json.dump({'items': items}, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    print(f'Catalog prepared: {len(items)} APK(s). Not uploaded or enabled on any server.')


if __name__ == '__main__':
    main()
