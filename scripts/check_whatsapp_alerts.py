"""Native Android alerts against the isolated local API; synthetic input, no WhatsApp account.
Run on a dedicated AVD with no existing Control login. Never clears app data.
"""
from pathlib import Path
import sys,subprocess,json,os,shlex,argparse
import local_fixture
from check_android import validate_serial
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--control-serial',required=True,type=validate_serial)
args=parser.parse_args()
root=local_fixture.ROOT;adb=root/'.tooling/android-sdk/platform-tools/adb';serial=args.control_serial
if not serial.startswith('emulator-'):
 raise RuntimeError('Use an isolated QA emulator, not a personal phone')
if not local_fixture.ready(local_fixture.settings()):
 raise RuntimeError('Private QA API must be ready before running')
def call(args,data=None):
 if args[0]=='shell':args=['shell',shlex.join(args[1:])]
 r=subprocess.run([str(adb),'-s',serial,*args],input=data,capture_output=True,timeout=180)
 if r.returncode:raise RuntimeError('ADB operation failed; private output retained')
 return r.stdout
for path in ['apps/control/build/outputs/apk/debug/control-debug.apk','apps/control/build/outputs/apk/androidTest/debug/control-debug-androidTest.apk']:
 call(['install','-r','-t',path])
cfg=local_fixture.settings()
identity={'isolated':True,'url':'http://10.0.2.2:8081','password':cfg['passwords']['admin']}
call(['shell','run-as','com.auditlab.control','sh','-c','mkdir -p files; cat > files/qa-alerts.json; chmod 600 files/qa-alerts.json'],json.dumps(identity).encode())
try:
 log=root/'.tooling/alerts-native-run.log'
 with os.fdopen(os.open(log,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600),'wb') as output:
  r=subprocess.run([str(adb),'-s',serial,'shell','am','instrument','-w','-r','-e','class','com.auditlab.control.NativeWhatsAppAlertsTest','com.auditlab.control.test/androidx.test.runner.AndroidJUnitRunner'],stdout=output,stderr=subprocess.STDOUT,timeout=300)
 text=log.read_text()
 passed=r.returncode==0 and 'OK (1 test)' in text and 'FAILURES' not in text
 if passed:
  result=json.loads(call(['shell','run-as','com.auditlab.control','cat','files/qa-alerts-native-result.json']))
  assert result['passed']
  (root/'.tooling/native-alerts-result.json').write_text(json.dumps(result,indent=2)+'\n')
 print(json.dumps({'instrumentation_passed':passed,'serial':serial,'private_log':str(log)}))
 if not passed:sys.exit(1)
finally:
 call(['shell','run-as','com.auditlab.control','rm','-f','files/qa-alerts.json','files/qa-alerts-native-result.json'])
