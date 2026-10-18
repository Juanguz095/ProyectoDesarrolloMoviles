"""Run the pinned wrapper with writable caches and the managed HTTPS proxy."""
import os
from pathlib import Path
import shlex
import subprocess
import sys
from urllib.parse import urlparse

root = Path(__file__).resolve().parents[1]
environment = os.environ.copy()
if (root / '.tooling/jdk/bin/javac').exists():
    environment['JAVA_HOME'] = str(root / '.tooling/jdk')
environment.setdefault('GRADLE_USER_HOME', str(root / '.tooling/gradle-home'))
environment.setdefault('ANDROID_USER_HOME', str(root / '.tooling/android-user'))
Path(environment['ANDROID_USER_HOME']).mkdir(parents=True, exist_ok=True)
if not environment.get('ANDROID_HOME') and (root / '.tooling/android-sdk').is_dir():
    environment['ANDROID_HOME'] = str(root / '.tooling/android-sdk')
options = shlex.split(environment.get('GRADLE_OPTS', ''))
proxy = urlparse(environment.get('HTTPS_PROXY', environment.get('https_proxy', '')))
if proxy.hostname:
    for protocol in ('http', 'https'):
        options.extend([f'-D{protocol}.proxyHost={proxy.hostname}', f'-D{protocol}.proxyPort={proxy.port or 8080}'])
    options.append('-Dhttp.nonProxyHosts=localhost|127.*|[::1]')
truststore = root / '.tooling/java-truststore'
if truststore.exists():
    options.append('-Djavax.net.ssl.trustStore=' + str(truststore))
environment['GRADLE_OPTS'] = shlex.join(options)
raise SystemExit(subprocess.call([str(root / 'gradlew'), *sys.argv[1:]], cwd=root, env=environment))
