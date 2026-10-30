"""Actual reboot of a dedicated QA emulator: explicit opt-in, basic status recovery, stop.
Requires the ready isolated local fixture and freshly built agent/test APKs.
Only QA enrollments bound to emulator loopback can be reset. No phone is accepted.
"""
import argparse
import json
import os
from pathlib import Path
import shlex
import subprocess
import time
import local_fixture as fixture
from check_android import validate_serial


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--agent-serial',required=True,type=validate_serial)
    args=parser.parse_args()
    if not args.agent_serial.startswith('emulator-'):raise RuntimeError('Dedicated QA emulator required')
    config=fixture.settings()
    if config['http_port']!=8081 or not fixture.ready(config):raise RuntimeError('Isolated QA fixture on 8081 must be ready')
    root=fixture.ROOT;adb=root/'.tooling/android-sdk/platform-tools/adb'
    out=root/'.tooling/basic-recovery-native';out.mkdir(exist_ok=True,mode=0o700)
    def call(args,data=None,timeout=180,check=True):
        if args[0]=='shell':args=['shell',shlex.join(args[1:])]
        r=subprocess.run([str(adb),'-s',args_serial,*args],input=data,capture_output=True,timeout=timeout)
        if check and r.returncode:raise RuntimeError('ADB operation failed; no private arguments printed')
        return r
    args_serial=args.agent_serial
    for path in ['apps/agent/build/outputs/apk/debug/agent-debug.apk','apps/agent/build/outputs/apk/androidTest/debug/agent-debug-androidTest.apk']:
        call(['install','-r','-t',path])
    identity={'isolated':True,'url':'http://10.0.2.2:8081','password':config['passwords']['admin']}
    call(['shell','run-as','com.auditlab.agent','sh','-c','mkdir -p files; cat > files/qa-basic-recovery.json; chmod 600 files/qa-basic-recovery.json'],json.dumps(identity).encode())
    def instrument(method):
        path=out/(method+'.log')
        with os.fdopen(os.open(path,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600),'wb') as log:
            r=subprocess.run([str(adb),'-s',args_serial,'shell','am','instrument','-w','-r','-e','class',
                'com.auditlab.agent.NativeBasicRecoveryTest#'+method,'com.auditlab.agent.test/androidx.test.runner.AndroidJUnitRunner'],stdout=log,stderr=subprocess.STDOUT,timeout=240)
        if r.returncode or 'OK (1 test)' not in path.read_text():raise RuntimeError('Native test failed; inspect private '+str(path))
    try:
        instrument('prepareExplicitBasicConsent');print('Explicit basic consent and visible capture verified. Rebooting QA emulator.',flush=True)
        call(['reboot']);time.sleep(2);call(['wait-for-device'],timeout=360)
        end=time.monotonic()+360
        while time.monotonic()<end:
            r=call(['shell','getprop','sys.boot_completed'],check=False)
            if r.returncode==0 and r.stdout.strip()==b'1':break
            time.sleep(2)
        else:raise RuntimeError('QA emulator did not complete boot')
        print('Android boot completed; checking service before opening any Activity.',flush=True)
        end=time.monotonic()+90;started=False
        while time.monotonic()<end:
            r=call(['shell','dumpsys','activity','services','com.auditlab.agent'],check=False)
            if b'BasicMonitoringService' in r.stdout and b'foregroundId=5110' in r.stdout:started=True;break
            time.sleep(2)
        if not started:raise RuntimeError('Basic service was not started automatically after boot')
        print('Basic foreground service verified before instrumentation.',flush=True)
        call(['shell','run-as','com.auditlab.agent','sh','-c','cat > files/qa-basic-observed.json'],b'{"serviceStartedBeforeTestRunner":true}')
        instrument('verifyAfterActualRebootAndStop')
        record=json.loads(call(['shell','run-as','com.auditlab.agent','cat','files/qa-basic-result.json']).stdout)
        record.update(service_started_before_test_runner=True,agent_serial=args_serial,api=int(call(['shell','getprop','ro.build.version.sdk']).stdout))
        (out/'result.json').write_text(json.dumps(record,indent=2)+'\n')
        print(json.dumps(record),flush=True)
    finally:
        call(['shell','run-as','com.auditlab.agent','rm','-f','files/qa-basic-recovery.json','files/qa-basic-before.json','files/qa-basic-observed.json','files/qa-basic-result.json'],check=False)


if __name__=='__main__':main()
