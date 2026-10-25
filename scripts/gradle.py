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
# Test workers (including Robolectric) are separate JVMs: GRADLE_OPTS alone does
# not give them writable user.home or the managed proxy/CA configuration.
worker_options = shlex.split(environment.get('JAVA_TOOL_OPTIONS', ''))
writable_java_home = root / '.tooling/user-home'
writable_java_home.mkdir(parents=True, exist_ok=True)
worker_options.append('-Duser.home=' + str(writable_java_home))
network_options = []
proxy = urlparse(environment.get('HTTPS_PROXY', environment.get('https_proxy', '')))
if proxy.hostname:
    for protocol in ('http', 'https'):
        network_options.extend([f'-D{protocol}.proxyHost={proxy.hostname}', f'-D{protocol}.proxyPort={proxy.port or 8080}'])
    network_options.append('-Dhttp.nonProxyHosts=localhost|127.*|[::1]')
truststore = root / '.tooling/java-truststore'
if truststore.exists():
    network_options.append('-Djavax.net.ssl.trustStore=' + str(truststore))
options.extend(network_options)
worker_options.extend(network_options)
environment['GRADLE_OPTS'] = shlex.join(options)
environment['JAVA_TOOL_OPTIONS'] = shlex.join(worker_options)
raise SystemExit(subprocess.call([str(root / 'gradlew'), *sys.argv[1:]], cwd=root, env=environment))
