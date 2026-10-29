# Scans an existing candidate without executing or restoring its contents.
param(
    [Parameter(Mandatory=$true)][string]$ArchivePath,
    [Parameter(Mandatory=$true)][string]$ExpectedSha256,
    [Parameter(Mandatory=$true)][string]$ReportPath
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$report = [ordered]@{
    schema_version = 1
    scanner = 'Microsoft Defender Antivirus'
    status = 'blocked'
    scanned_at_utc = [DateTime]::UtcNow.ToString('o')
    artifact = $null
    scan_exit_code = $null
    engine_version = $null
    signature_version = $null
    signature_updated_at_utc = $null
    detected_threats = @()
    error = $null
}
try {
    $archive = Get-Item -LiteralPath $ArchivePath
    if ($archive.PSIsContainer) { throw 'The candidate must be a regular file.' }
    $hash = (Get-FileHash -LiteralPath $archive.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $report.artifact = [ordered]@{ name = $archive.Name; bytes = $archive.Length; sha256 = $hash }
    if ($hash -ne $ExpectedSha256.ToLowerInvariant()) { throw 'Candidate SHA256 differs from the recorded delivery.' }
    $status = Get-MpComputerStatus
    if (-not $status.AntivirusEnabled -or -not $status.AMServiceEnabled -or -not $status.AntispywareEnabled) {
        throw 'Defender is unavailable or inactive. No antivirus clearance can be issued.'
    }
    if ((Get-MpPreference).DisableArchiveScanning) {
        throw 'Archive scanning is disabled. No clearance can be issued for a ZIP.'
    }
    Update-MpSignature
    $status = Get-MpComputerStatus
    $report.engine_version = $status.AMEngineVersion
    $report.signature_version = $status.AntivirusSignatureVersion
    $report.signature_updated_at_utc = $status.AntivirusSignatureLastUpdated.ToUniversalTime().ToString('o')
    if ($status.AntivirusSignatureAge -gt 2) { throw 'Defender signatures are stale.' }
    $platform = Join-Path $env:ProgramData 'Microsoft\Windows Defender\Platform'
    $commands = @(Get-ChildItem -LiteralPath $platform -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName 'MpCmdRun.exe' } |
        Where-Object { Test-Path -LiteralPath $_ -PathType Leaf })
    $command = if ($commands.Count -gt 0) { $commands[0] } else { Join-Path $env:ProgramFiles 'Windows Defender\MpCmdRun.exe' }
    if (-not (Test-Path -LiteralPath $command -PathType Leaf)) { throw 'Microsoft Defender scan command not found.' }
    $signature = Get-AuthenticodeSignature -LiteralPath $command
    if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'Microsoft') {
        throw 'Microsoft Defender scanner signature could not be verified.'
    }
    & $command -Scan -ScanType 3 -File $archive.FullName
    $report.scan_exit_code = $LASTEXITCODE
    $detections = @(Get-MpThreatDetection | Where-Object { ($_.Resources -join ' ') -like ('*' + $archive.Name + '*') })
    $report.detected_threats = @($detections | ForEach-Object { [ordered]@{
        threat_id = $_.ThreatID
        action_success = $_.ActionSuccess
        resources = @($_.Resources)
    } })
    if ($report.scan_exit_code -ne 0 -or $detections.Count -gt 0) { throw 'Defender reported a threat or failed to finish the scan.' }
    if (-not (Test-Path -LiteralPath $archive.FullName -PathType Leaf)) { throw 'Candidate no longer exists after scan; no clearance issued.' }
    if ((Get-FileHash -LiteralPath $archive.FullName -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hash) {
        throw 'Candidate changed during scanning.'
    }
    $report.status = 'no_threats_detected'
} catch {
    $report.error = $_.Exception.Message
} finally {
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
}
if ($report.status -ne 'no_threats_detected') { exit 1 }
