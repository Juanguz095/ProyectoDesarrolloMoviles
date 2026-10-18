"""Provision pinned SDK/Gradle in the checkout without modifying system paths."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tarfile

root = Path(__file__).resolve().parents[1]
tools = root / '.tooling'
tools.mkdir(exist_ok=True)

def archive(url, name, expected):
    destination = tools / name
    if not destination.exists():
        subprocess.run(['curl', '--fail', '--location', '--silent', '--show-error', '--max-time', '180', url, '--output', str(destination)], check=True)
    with destination.open('rb') as downloaded:
        actual = hashlib.file_digest(downloaded, 'sha256').hexdigest()
    if actual != expected:
        raise SystemExit('Checksum mismatch: ' + name + '. Remove the failed archive and investigate its source before retrying.')
    return destination

jdk_home = tools / 'jdk'
if not (jdk_home / 'bin/javac').exists():
    jdk = archive('https://download.oracle.com/java/21/archive/jdk-21.0.12.1_linux-x64_bin.tar.gz', 'jdk-21.tar.gz', '12f870b21301b42292558a3f872ce543affa2b86cb6458591c78388c41ddb111')
    jdk_staging = tools / 'jdk-download'
    with tarfile.open(jdk) as bundle:
        bundle.extractall(jdk_staging, filter='data')
    roots = [p for p in jdk_staging.iterdir() if (p / 'bin/javac').is_file()]
    if len(roots) != 1:
        raise SystemExit('Unexpected JDK archive structure')
    roots[0].rename(jdk_home)

gradle = archive('https://downloads.gradle.org/distributions/gradle-8.11.1-bin.zip', 'gradle-8.11.1-bin.zip', 'f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6')
if not (tools / 'gradle-8.11.1/bin/gradle').exists():
    subprocess.run(['unzip', '-q', str(gradle), '-d', str(tools)], check=True)

sdk = tools / 'android-sdk'
cli = sdk / 'cmdline-tools/19.0'
# This archive was also verified against repository2-1.xml's official SHA-1.
android = archive('https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip', 'android-command-tools-19.zip', '7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee')
if not (cli / 'bin/sdkmanager').exists():
    staging = tools / 'android-cli-staging'
    staging.mkdir(exist_ok=True)
    subprocess.run(['unzip', '-q', str(android), '-d', str(staging)], check=True)
    cli.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(staging / 'cmdline-tools'), cli)

environment = os.environ.copy()
environment['JAVA_HOME'] = str(jdk_home)
environment['ANDROID_USER_HOME'] = str(tools / 'android-user')
Path(environment['ANDROID_USER_HOME']).mkdir(exist_ok=True)
proxy_cert = environment.get('CODEX_PROXY_CERT')
truststore = tools / 'java-truststore'
if proxy_cert:
    source = Path('/etc/ssl/certs/java/cacerts')
    if not source.exists():
        raise SystemExit('System Java trust store unavailable; configure trusted proxy CA before continuing.')
    shutil.copyfile(source, truststore)
    subprocess.run(['keytool', '-importcert', '-noprompt', '-keystore', str(truststore), '-storepass', 'changeit', '-alias', 'auditlab-session-proxy', '-file', proxy_cert], check=True, capture_output=True)
    environment['JAVA_TOOL_OPTIONS'] = environment.get('JAVA_TOOL_OPTIONS', '') + ' -Djavax.net.ssl.trustStore=' + str(truststore)

from urllib.parse import urlparse
proxy = urlparse(environment.get('HTTPS_PROXY', environment.get('https_proxy', '')))
arguments = [str(cli / 'bin/sdkmanager'), '--sdk_root=' + str(sdk)]
if proxy.hostname:
    arguments.extend(['--proxy=http', '--proxy_host=' + proxy.hostname, '--proxy_port=' + str(proxy.port or 8080)])
with (tools / 'sdk-setup.log').open('w') as log:
    result = subprocess.run(arguments + ['platforms;android-35', 'build-tools;35.0.0', 'platform-tools'], input='y\n' * 100, text=True, env=environment, stdout=log, stderr=subprocess.STDOUT, timeout=300)
if result.returncode:
    raise SystemExit('SDK install failed. Inspect .tooling/sdk-setup.log; TLS and checksums remain enabled.')
print('JDK 21.0.12.1, Gradle 8.11.1, Android CLI 19.0, platform 35/build-tools 35.0.0 ready.')
