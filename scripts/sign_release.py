"""Sign prebuilt release APKs using the operator's external key; no key generation or upload.
Use --keystore /secure/key.jks --alias NAME --store-pass-file /secure/store-password
--key-pass-file /secure/key-password. Password files must already exist outside this checkout.
"""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]

def password_file(path):
    try:
        with path.open(encoding='utf-8') as stream: value = stream.readline().rstrip('\r\n')
        if not value or '\0' in value: raise ValueError()
        return value
    except (OSError, UnicodeError, ValueError):
        raise SystemExit('Password file must contain a nonempty UTF-8 password line') from None

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--keystore', type=Path, required=True)
    parser.add_argument('--alias', required=True)
    parser.add_argument('--store-pass-file', type=Path, required=True)
    parser.add_argument('--key-pass-file', type=Path, required=True)
    args = parser.parse_args()
    for path in [args.keystore, args.store_pass_file, args.key_pass_file]:
        path = path.resolve(strict=True)
        if path.is_relative_to(ROOT): raise SystemExit('Key and password files must be outside the checkout')
        if path.stat().st_mode & 0o077: raise SystemExit('Private key/password files require mode 600 or stricter')
    sdk = Path(os.environ.get('ANDROID_HOME', ROOT / '.tooling/android-sdk'))
    tool = sdk / 'build-tools/35.0.0/apksigner'
    env = dict(os.environ)
    if (ROOT / '.tooling/jdk/bin/java').is_file(): env['JAVA_HOME'] = str(ROOT / '.tooling/jdk')
    output = ROOT / '.tooling/release'
    artifacts = []
    for component in ['agent', 'control']:
        source = ROOT / f'apps/{component}/build/outputs/apk/release/{component}-release-unsigned.apk'
        destination = output / f'auditlab-{component}-0.2.0-rc1.apk'
        if destination.exists(): raise SystemExit('Existing signed APK preserved; move it before signing another candidate')
        if not source.is_file(): raise SystemExit('Build both release APKs before signing')
        artifacts.append((source, destination))
    if not tool.is_file(): raise SystemExit('Android build-tools 35.0.0/apksigner is required')
    # apksigner consumes password files sequentially when the same path is reused.
    # Read each explicit input independently; only child environment selectors enter argv.
    env['AUDITLAB_SIGN_STORE_PASSWORD'] = password_file(args.store_pass_file.resolve())
    env['AUDITLAB_SIGN_KEY_PASSWORD'] = password_file(args.key_pass_file.resolve())
    output.mkdir(exist_ok=True, parents=True)
    for source, destination in artifacts:
        subprocess.run([str(tool), 'sign', '--ks', str(args.keystore.resolve()), '--ks-key-alias', args.alias,
            '--ks-pass', 'env:AUDITLAB_SIGN_STORE_PASSWORD', '--key-pass', 'env:AUDITLAB_SIGN_KEY_PASSWORD',
            '--out', str(destination), str(source)], check=True, env=env)
        subprocess.run([str(tool), 'verify', '--verbose', str(destination)], check=True, env=env)
        print(destination.name, hashlib.sha256(destination.read_bytes()).hexdigest())

if __name__ == '__main__': main()
