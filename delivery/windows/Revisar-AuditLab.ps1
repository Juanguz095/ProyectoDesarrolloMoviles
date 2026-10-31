# Read-only preflight. Does not install software, edit services, change the VPN or delete data.
[CmdletBinding()]
param([string]$ServiceUrl = 'http://127.0.0.1:8081', [switch]$AsJson)
$ErrorActionPreference = 'Stop'
$checks = [System.Collections.Generic.List[object]]::new()
function Add-Check([string]$Name, [bool]$Ok, [string]$Detail) {
    $checks.Add([pscustomobject]@{ name=$Name; ok=$Ok; detail=$Detail })
}
function Invoke-ReadOnlyTool([string]$Program, [string[]]$Arguments) {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $captured = & $Program @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    return [pscustomobject]@{ exitCode=$exitCode; text=($captured | Out-String) }
}
try {
    $os = Get-CimInstance Win32_OperatingSystem
    $total = [math]::Round($os.TotalVisibleMemorySize / 1MB, 1)
    $free = [math]::Round($os.FreePhysicalMemory / 1MB, 1)
    Add-Check 'Windows' ($os.Caption -match 'Windows 11') $os.Caption
    Add-Check 'RAM' ($total -ge 7.5) "$total GB total; $free GB libre. La capacidad real requiere una prueba de carga."
} catch { Add-Check 'Windows/RAM' $false 'No se pudieron consultar los recursos.' }
try {
    $details = Invoke-ReadOnlyTool 'java.exe' @('-XshowSettings:properties','-version')
    Add-Check 'Java 21' ($details.exitCode -eq 0 -and $details.text -match 'java\.specification\.version\s*=\s*21\s') 'Se requiere Java 21.'
} catch { Add-Check 'Java 21' $false 'Java no esta disponible en PATH.' }
try {
    $dockerVersion = Invoke-ReadOnlyTool 'docker.exe' @('--host','npipe:////./pipe/docker_engine','info','--format','{{.ServerVersion}}')
    Add-Check 'Docker' ($dockerVersion.exitCode -eq 0) 'Docker Desktop debe estar iniciado con un backend compatible.'
} catch { Add-Check 'Docker' $false 'Docker Desktop no esta disponible.' }
$tailscale = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'
Add-Check 'Tailscale instalado' (Test-Path -LiteralPath $tailscale) 'La conexion privada debe comprobarse tambien desde cada telefono.'
try {
    $origin = [uri]$ServiceUrl
    if (-not $origin.IsAbsoluteUri -or $origin.UserInfo -or $origin.Query -or $origin.Fragment -or $origin.AbsolutePath -ne '/') { throw 'Invalid origin' }
    if ($origin.Scheme -ne 'https' -and -not ($origin.Scheme -eq 'http' -and $origin.Host -in @('127.0.0.1','localhost','::1'))) { throw 'HTTPS required' }
    $ready = Invoke-WebRequest -UseBasicParsing -Uri ($ServiceUrl.TrimEnd('/') + '/ready') -TimeoutSec 10
    $body = $ready.Content | ConvertFrom-Json
    Add-Check 'API lista' ($ready.StatusCode -eq 200 -and $body.status -eq 'ready') 'Comprobacion /ready con validacion TLS normal.'
} catch { Add-Check 'API lista' $false 'No responde /ready. Revisa URL, certificado, servidor y Tailscale; sin /ready ni /v1 en ServiceUrl.' }
if ($AsJson) { $checks | ConvertTo-Json -Depth 3 } else { $checks | Format-Table -AutoSize -Wrap }
if ($checks | Where-Object { -not $_.ok }) { exit 1 }
exit 0
