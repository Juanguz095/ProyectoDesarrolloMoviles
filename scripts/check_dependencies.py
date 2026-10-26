"""Scan Gradle's resolved production runtime modules using a verified Trivy binary.
No source, dependency version, credential or deployment is modified. --prepare installs the
pinned scanner under .tooling; its public advisory database comes from the allowed GHCR registry.
"""
from __future__ import annotations
import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tarfile
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[1]
VERSION = '0.76.1'
ARCHIVE_SHA256 = 'c7cbe3137fb8a48a17574bede3dd6beb16742d1e9772929940fc90082c39d9c0'
DIRECTORY = ROOT / '.tooling/security'
INVENTORY = '''import groovy.json.JsonOutput
gradle.projectsEvaluated {
    allprojects { p ->
        if (p.path in [':apps:agent', ':apps:control', ':backend:ktor']) {
            p.tasks.register('qaRuntimeInventory') {
                doLast {
                    def configuration = p.path.startsWith(':apps:') ? 'releaseRuntimeClasspath' : 'runtimeClasspath'
                    def modules = p.configurations.getByName(configuration).incoming.resolutionResult.allComponents
                        .findAll { it.id instanceof org.gradle.api.artifacts.component.ModuleComponentIdentifier }
                        .collect { [group: it.moduleVersion.group, name: it.moduleVersion.name, version: it.moduleVersion.version] }
                    new File(p.rootDir, '.tooling/security/inventory-' + p.name + '.json').text = JsonOutput.toJson(modules)
                }
            }
        }
    }
}
'''


def execute(command, log):
    with log.open('wb') as stream:
        result = subprocess.run([str(v) for v in command], cwd=ROOT, stdout=stream, stderr=stream)
    if result.returncode: raise RuntimeError(f'{log.name} failed ({result.returncode}); inspect local log')


def prepare():
    archive = DIRECTORY / 'trivy.tar.gz'
    if not archive.exists():
        execute(['curl', '--fail', '--location', '--silent', '--show-error',
            f'https://github.com/aquasecurity/trivy/releases/download/v{VERSION}/trivy_{VERSION}_Linux-64bit.tar.gz',
            '-o', archive], DIRECTORY / 'scanner-download.log')
    if hashlib.sha256(archive.read_bytes()).hexdigest() != ARCHIVE_SHA256:
        raise RuntimeError('Official scanner archive checksum mismatch; preserved, never bypassed')
    target = DIRECTORY / 'trivy'
    with tarfile.open(archive) as bundle:
        binary = bundle.extractfile('trivy').read()
        if (target / 'trivy').exists():
            if hashlib.sha256((target / 'trivy').read_bytes()).digest() != hashlib.sha256(binary).digest():
                raise RuntimeError('Existing scanner differs from the verified archive; preserved for review')
        else:
            target.mkdir(exist_ok=True)
            bundle.extractall(target, filter='data')
    return target / 'trivy'


def sbom():
    components = {}
    for component in ['agent', 'control', 'ktor']:
        path = DIRECTORY / ('inventory-' + component + '.json')
        packages = json.loads(path.read_text())
        if not packages: raise RuntimeError('Empty resolved runtime inventory')
        for package in packages:
            purl = 'pkg:maven/' + quote(package['group'], safe='') + '/' + quote(package['name'], safe='') + '@' + quote(package['version'], safe='')
            components[purl] = {'type': 'library', **package, 'purl': purl, 'bom-ref': purl}
    document = {'bomFormat': 'CycloneDX', 'specVersion': '1.5', 'version': 1,
        'metadata': {'timestamp': dt.datetime.now(dt.timezone.utc).isoformat()},
        'components': [components[key] for key in sorted(components)]}
    path = DIRECTORY / 'runtime.cdx.json'
    path.write_text(json.dumps(document, indent=2) + '\n')
    return path, len(components)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prepare', action='store_true')
    parser.add_argument('--install-only', action='store_true', help='Verify/install the scanner without building, scanning or downloading its database')
    parser.add_argument('--trivy', type=Path)
    parser.add_argument('--reuse-inventory', action='store_true', help='Only for an inventory just generated from the same source/dependencies')
    parser.add_argument('--skip-db-update', action='store_true', help='Use the recorded database for a reproducible before/after comparison')
    args = parser.parse_args()
    DIRECTORY.mkdir(parents=True, exist_ok=True)
    scanner = args.trivy or (prepare() if args.prepare else DIRECTORY / 'trivy/trivy')
    if not scanner.is_file(): raise RuntimeError('Run with --prepare or supply a trusted --trivy binary')
    if args.install_only:
        print('Verified Trivy scanner ready under .tooling; no scan performed')
        return
    if not args.reuse_inventory:
        init = DIRECTORY / 'inventory.init.gradle'; init.write_text(INVENTORY)
        execute([sys.executable, ROOT / 'scripts/gradle.py', ':apps:agent:qaRuntimeInventory', ':apps:control:qaRuntimeInventory',
            ':backend:ktor:qaRuntimeInventory', '-I', init, '--no-daemon', '--max-workers=1'], DIRECTORY / 'inventory.log')
    artifact, modules = sbom()
    if not args.skip_db_update:
        execute([scanner, '--cache-dir', DIRECTORY / 'cache', 'image', '--download-db-only', '--disable-telemetry',
            '--db-repository', 'ghcr.io/aquasecurity/trivy-db:2'], DIRECTORY / 'db-download.log')
    report = DIRECTORY / 'runtime-findings.json'
    execute([scanner, '--cache-dir', DIRECTORY / 'cache', 'sbom', '--skip-db-update', '--offline-scan', '--disable-telemetry',
        '--list-all-pkgs', '--format', 'json', '--output', report, artifact], DIRECTORY / 'scan.log')
    data = json.loads(report.read_text())
    results = data.get('Results', [])
    packages = sum(len(result.get('Packages') or []) for result in results)
    if not results or not packages: raise RuntimeError('Scanner did not identify runtime packages; no clearance claimed')
    expected = {item['purl'] for item in json.loads(artifact.read_text())['components']}
    identified = {item.get('Identifier', {}).get('PURL') for result in results for item in result.get('Packages') or []}
    if expected - identified: raise RuntimeError('Scanner omitted resolved runtime modules; no clearance claimed')
    findings = [item for result in results for item in result.get('Vulnerabilities') or []]
    blockers = [item for item in findings if item['Severity'] in {'HIGH', 'CRITICAL'}]
    metadata = DIRECTORY / 'cache/db/metadata.json'
    summary = {'recorded_at': dt.datetime.now(dt.timezone.utc).isoformat(), 'scanner': 'Trivy ' + VERSION,
        'modules_resolved': modules, 'packages_identified': packages, 'findings': len(findings), 'high_or_critical': len(blockers),
        'severity_counts': {level: sum(item['Severity'] == level for item in findings) for level in ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNKNOWN']},
        'database': json.loads(metadata.read_text()) if metadata.exists() else None,
        'sbom_sha256': hashlib.sha256(artifact.read_bytes()).hexdigest(), 'passed': not blockers,
        'scope': 'Resolved production Maven runtime modules; excludes OS/JDK, test tools and operational configuration'}
    (DIRECTORY / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, indent=2))
    if blockers: raise SystemExit(1)

if __name__ == '__main__': main()
