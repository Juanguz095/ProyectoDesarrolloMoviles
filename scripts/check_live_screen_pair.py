"""Real simultaneous MediaProjection -> API -> production viewer on two empty QA AVDs."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time
import xml.etree.ElementTree as ET
import local_fixture as fixture
from check_android import Run, protocol, validate_serial


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--agent-serial', required=True, type=validate_serial)
    parser.add_argument('--control-serial', required=True, type=validate_serial)
    args = parser.parse_args()
    run = Run(args.agent_serial, args.control_serial)
    run.output = Path('.tooling/live-screen-pair/final-native').resolve()
    run.output.mkdir(parents=True, exist_ok=True, mode=0o700)
    run.preflight()
    processes = {}
    logs = {}
    approved = False

    def inject(component, data):
        run.shell(component, ['run-as', 'com.auditlab.'+component, 'sh', '-c',
                  'mkdir -p files; cat > files/qa-screen.json; chmod 600 files/qa-screen.json'],
                  data=json.dumps(data).encode())

    def marker(component, name, value=b'ready'):
        run.shell(component, ['run-as','com.auditlab.'+component,'sh','-c','cat > files/'+name],data=value)

    def read_marker(component, name):
        end = time.monotonic()+200
        while time.monotonic()<end:
            result = run.shell(component,['run-as','com.auditlab.'+component,'cat','files/'+name],required=False)
            if result.returncode==0: return result.stdout
            if processes[component].poll() is not None: raise RuntimeError(component+' ended before its live marker; private log retained')
            time.sleep(.5)
        raise RuntimeError(component+' did not reach live marker; private log retained')

    def start(component, klass):
        path = run.output / (component+'.log')
        logs[component] = os.fdopen(os.open(path,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600),'wb')
        processes[component] = subprocess.Popen([str(fixture.ROOT/'.tooling/android-sdk/platform-tools/adb'),
            '-s',run.serials[component],'shell','am','instrument','-w','-r','-e','class',klass,
            'com.auditlab.'+component+'.test/androidx.test.runner.AndroidJUnitRunner'],
            stdout=logs[component],stderr=subprocess.STDOUT)

    try:
        for component in run.serials:
            run.install(component)
            run.shell(component,['input','keyevent','224'])
            run.shell(component,['run-as','com.auditlab.'+component,'rm','-f',
                'files/qa-screen-ready.json','files/qa-screen-finish','files/qa-screen-stopped',
                'files/qa-screen-viewer-cleared','files/qa-live-image-seen'])
        run.adb('control',['emu','avd','pause'])
        inject('agent',run.identity | {'livePairHold':True})
        start('agent','com.auditlab.agent.NativeScreenShareTest')
        device = json.loads(read_marker('agent','qa-screen-ready.json'))['device']
        print('Agent: real Settings content and static heartbeat verified.',flush=True)
        run.adb('control',['emu','avd','start'])
        inject('control',run.identity | {'device':device})
        start('control','com.auditlab.control.NativeLiveScreenPairTest')
        read_marker('control','qa-live-image-seen')
        print('Control: real live frame of the other AVD is displayed.',flush=True)
        for component in run.serials:
            (run.output/(component+'-live.png')).write_bytes(run.adb(component,['exec-out','screencap','-p']).stdout)
        marker('agent','qa-screen-finish')
        read_marker('agent','qa-screen-stopped')
        processes['control'].wait(timeout=180)
        marker('agent','qa-screen-viewer-cleared')
        processes['agent'].wait(timeout=180)
        results = {name:protocol((run.output/(name+'.log')).read_text()) for name in run.serials}
        approved = all(item['approved'] for item in results.values())
        report = {'approved':approved,'kind':'simultaneous-live-projection','results':results,
                  'devices':run.devices,'artifacts':run.artifacts,'synthetic_content_only':True,
                  'source':'Android Settings captured with real per-session OS permission',
                  'static_screen_heartbeat_seconds':12,'viewer_cleared_after_local_stop':approved}
        (run.output/'verdict.json').write_text(json.dumps(report,indent=2)+'\n')
        print(json.dumps({'approved':approved,'results':results}),flush=True)
        if not approved: raise RuntimeError('Live pair failed; private logs retained')
    finally:
        run.adb('agent',['emu','avd','start'],required=False)
        run.adb('control',['emu','avd','start'],required=False)
        for name in ['qa-screen-finish','qa-screen-viewer-cleared']:
            try: marker('agent',name)
            except RuntimeError: pass
        for component, process in processes.items():
            if process.poll() is None:
                run.shell(component,['am','force-stop','com.auditlab.'+component],required=False)
                process.terminate(); process.wait(timeout=15)
        for log in logs.values(): log.close()
        for component in run.serials:
            run.shell(component,['run-as','com.auditlab.'+component,'rm','-f','files/qa-screen.json',
                'files/qa-screen-ready.json','files/qa-screen-finish','files/qa-screen-stopped',
                'files/qa-screen-viewer-cleared','files/qa-live-image-seen'],required=False)
        suite=ET.Element('testsuite',name='AuditLab.live-screen-pair',tests='1',failures='0' if approved else '1')
        case=ET.SubElement(suite,'testcase',name='permission-static-live-view-local-stop',classname='AuditLab.ScreenSharing')
        if not approved: ET.SubElement(case,'failure',message='Live pair not completed; private evidence retained')
        ET.ElementTree(suite).write(run.output/'TEST-native.xml',encoding='utf-8',xml_declaration=True)


if __name__=='__main__': main()
