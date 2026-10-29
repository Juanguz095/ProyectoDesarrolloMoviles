"""Verify antimalware evidence for an exact Windows archive before publication.

A successful build, ZIP CRC, dependency scan or checksum alone is not clearance.
This command never modifies Defender, restores quarantined files or executes ZIPs.
"""
import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def verify(archive, report_path, now=None):
    hold = json.loads((ROOT / 'delivery/windows/security-status.json').read_text())
    if hold['distribution_suspended'] is not False:
        raise ValueError('Windows distribution remains suspended: ' + hold['reason'])
    if archive.is_symlink() or not archive.is_file():
        raise ValueError('A regular existing candidate archive is required')
    report = json.loads(report_path.read_text(encoding='utf-8-sig'))
    if (report.get('schema_version') != 1
            or report.get('scanner') != 'Microsoft Defender Antivirus'
            or report.get('status') != 'no_threats_detected'
            or type(report.get('scan_exit_code')) is not int
            or report['scan_exit_code'] != 0
            or report.get('detected_threats') != []
            or report.get('error') is not None
            or not report.get('engine_version')
            or not report.get('signature_version')):
        raise ValueError('Complete successful Microsoft Defender evidence is required')
    current = now or dt.datetime.now(dt.timezone.utc)
    for field in ('scanned_at_utc', 'signature_updated_at_utc'):
        stamp = dt.datetime.fromisoformat(report[field].replace('Z', '+00:00'))
        if stamp.tzinfo is None or not dt.timedelta(0) <= current - stamp <= dt.timedelta(days=2):
            raise ValueError('Scanner evidence or signatures are stale or have an invalid date')
    digest = hashlib.sha256()
    with archive.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    actual = {'name': archive.name, 'bytes': archive.stat().st_size, 'sha256': digest.hexdigest()}
    if report.get('artifact') != actual:
        raise ValueError('Scan evidence does not match the exact candidate archive')
    if not report.get('source_commit') or not report.get('workflow_run_id'):
        raise ValueError('The isolated workflow run and scanner source must be identified')
    return {'antimalware_gate_passed': True, 'artifact': actual,
            'workflow_run_id': report['workflow_run_id'], 'scope': 'Antimalware gate only; not functional or pilot acceptance'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', required=True, type=Path)
    parser.add_argument('--report', required=True, type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(verify(args.archive, args.report), indent=2))
    except (ValueError, KeyError, OSError, TypeError) as error:
        parser.exit(1, 'Windows publication blocked: ' + str(error) + '\n')
