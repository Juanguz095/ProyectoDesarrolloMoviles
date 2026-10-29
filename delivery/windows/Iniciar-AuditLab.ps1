# Local Windows development server. Uses the prebuilt API from the AuditLab ZIP.
# No public ports, tunnels, source builds, APK changes or removal of existing data.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$appRoot = $PSScriptRoot
$apiBatch = Join-Path $appRoot 'api\bin\ktor.bat'
$privateDir = Join-Path $appRoot '.auditlab-windows'
$configFile = Join-Path $privateDir 'config.json'
$image = 'postgres:17.6-bookworm@sha256:f3bd19c606e442c3d7bdfa8002e03fe260a1023351e0ea4598032022b68dd6e3'
$dockerOptions = @('--host', 'npipe:////./pipe/docker_engine')

function New-PrivateValue {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes)
}

function Invoke-Tool {
    param([string]$Program, [string[]]$Arguments, [string]$InputText = '')
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($InputText.Length -gt 0) { $captured = $InputText | & $Program @Arguments 2>&1 }
        else { $captured = & $Program @Arguments 2>&1 }
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    $text = ($captured | Out-String).Trim()
    Add-Content -LiteralPath (Join-Path $privateDir 'setup.log') -Value $text -Encoding UTF8
    if ($exitCode -ne 0) { throw 'Un comando fallo. Revisa .auditlab-windows\setup.log en esta PC; conserva tus datos.' }
    return $text
}

function Invoke-Docker {
    param([string[]]$Arguments, [string]$InputText = '')
    return Invoke-Tool -Program 'docker.exe' -Arguments ($dockerOptions + $Arguments) -InputText $InputText
}

if (-not (Test-Path -LiteralPath $apiBatch -PathType Leaf)) {
    throw 'Extrae todo el ZIP. Este archivo debe estar junto a las carpetas api, apks y source.'
}
Get-Command docker.exe -ErrorAction Stop | Out-Null
Get-Command java.exe -ErrorAction Stop | Out-Null
$occupied = New-Object Net.Sockets.TcpClient
try {
    try { $occupied.Connect('127.0.0.1', 8081); $portInUse = $true } catch { $portInUse = $false }
} finally { $occupied.Dispose() }
if ($portInUse) { throw 'El puerto 8081 ya esta ocupado. Cierra la ventana anterior del servidor antes de repetir.' }

New-Item -ItemType Directory -Force -Path $privateDir | Out-Null
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls.exe $privateDir '/inheritance:r' '/grant:r' ('{0}:(OI)(CI)F' -f $identity) | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'No se pudo proteger la carpeta privada. Extrae el ZIP en una carpeta NTFS de tu usuario.' }

$savedEnvironment = @{}
$managedNames = @((Get-ChildItem Env: | Where-Object { $_.Name -like 'AUDITLAB_*' } | ForEach-Object { $_.Name })) + @(
    'DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_TLS', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH',
    'JAVA_HOME', 'PGPASSWORD', 'MIGRATOR_PASSWORD', 'RUNTIME_PASSWORD', 'MAINTENANCE_PASSWORD')
foreach ($name in $managedNames) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    [Environment]::SetEnvironmentVariable($name, $null, 'Process')
}

try {
    $javaDetails = Invoke-Tool -Program 'java.exe' -Arguments @('-XshowSettings:properties', '-version')
    if ($javaDetails -notmatch 'java\.specification\.version\s*=\s*21\s') { throw 'Instala Java 21 y abre una PowerShell nueva.' }
    $javaHomeLine = ($javaDetails -split "`r?`n" | Where-Object { $_ -match '^\s*java\.home\s*=' } | Select-Object -First 1)
    if (-not $javaHomeLine) { throw 'No se encontro la carpeta de Java 21.' }
    $env:JAVA_HOME = ($javaHomeLine -split '=', 2)[1].Trim()
    if ((Invoke-Docker -Arguments @('info', '--format', '{{.OSType}}')) -ne 'linux') {
        throw 'Abre Docker Desktop y usa contenedores Linux.'
    }
    if (Test-Path -LiteralPath $configFile) {
        $config = Get-Content -LiteralPath $configFile -Raw -Encoding UTF8 | ConvertFrom-Json
    } else {
        Write-Host 'Crea la contrasena del usuario admin de AuditLab (12 a 128 caracteres).'
        $secret = Read-Host 'Contrasena' -AsSecureString
        $adminPassword = [Net.NetworkCredential]::new('', $secret).Password
        if ($adminPassword.Length -lt 12 -or $adminPassword.Length -gt 128) { throw 'La contrasena necesita entre 12 y 128 caracteres.' }
        $token = [Guid]::NewGuid().ToString('N').Substring(0, 12)
        $config = [ordered]@{
            container = 'auditlab-windows-' + $token
            volume = 'auditlab-windows-data-' + $token
            signingKey = New-PrivateValue
            passwords = [ordered]@{ root = New-PrivateValue; migrator = New-PrivateValue; runtime = New-PrivateValue; maintenance = New-PrivateValue; admin = $adminPassword }
        }
        $config | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $configFile -Encoding UTF8
        $config = Get-Content -LiteralPath $configFile -Raw -Encoding UTF8 | ConvertFrom-Json
    }
    $label = 'com.auditlab.windows-local'
    $names = Invoke-Docker -Arguments @('container', 'ls', '-a', '--format', '{{.Names}}')
    if (($names -split "`r?`n") -notcontains $config.container) {
        Invoke-Docker -Arguments @('volume', 'create', '--label', ($label + '=' + $privateDir), $config.volume) | Out-Null
        $volumeLabels = (Invoke-Docker -Arguments @('volume', 'inspect', '--format', '{{json .Labels}}', $config.volume)) | ConvertFrom-Json
        if ($null -eq $volumeLabels) { throw 'El volumen existente no tiene identificacion; se conserva.' }
        $volumeOwner = $volumeLabels.PSObject.Properties[$label]
        if ($null -eq $volumeOwner -or $volumeOwner.Value -ne $privateDir) { throw 'El volumen existente pertenece a otra configuracion; se conserva.' }
        $databaseEnv = Join-Path $privateDir 'database.env'
        @('POSTGRES_USER=postgres', 'POSTGRES_DB=postgres', 'POSTGRES_INITDB_ARGS=--auth-host=scram-sha-256', ('POSTGRES_PASSWORD=' + $config.passwords.root)) | Set-Content -LiteralPath $databaseEnv -Encoding ASCII
        Invoke-Docker -Arguments @('run', '--detach', '--name', $config.container, '--label', ($label + '=' + $privateDir), '--env-file', $databaseEnv,
            '--mount', ('type=volume,source=' + $config.volume + ',target=/var/lib/postgresql/data'), '--publish', '127.0.0.1::5432', $image) | Out-Null
    }
    $containerLabels = (Invoke-Docker -Arguments @('inspect', '--format', '{{json .Config.Labels}}', $config.container)) | ConvertFrom-Json
    if ($null -eq $containerLabels) { throw 'El contenedor existente no tiene identificacion; se conserva.' }
    $owner = $containerLabels.PSObject.Properties[$label]
    if ($null -eq $owner -or $owner.Value -ne $privateDir) { throw 'El contenedor existente pertenece a otra configuracion; se conserva.' }
    Invoke-Docker -Arguments @('start', $config.container) | Out-Null
    Write-Host 'Esperando a PostgreSQL...'
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    while ((Invoke-Docker -Arguments @('inspect', '--format', '{{.State.Running}}', $config.container)) -ne 'true') {
        if ([DateTime]::UtcNow -gt $deadline) { throw 'PostgreSQL no arranco. Revisa Docker Desktop.' }
        Start-Sleep -Seconds 1
    }
    while ($true) {
        try { Invoke-Docker -Arguments @('exec', $config.container, 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres') | Out-Null; break }
        catch { if ([DateTime]::UtcNow -gt $deadline) { throw 'PostgreSQL no quedo disponible.' }; Start-Sleep -Seconds 1 }
    }
    $env:PGPASSWORD = $config.passwords.root
    $env:MIGRATOR_PASSWORD = $config.passwords.migrator
    $env:RUNTIME_PASSWORD = $config.passwords.runtime
    $env:MAINTENANCE_PASSWORD = $config.passwords.maintenance
    $sql = @'
\getenv migrator_password MIGRATOR_PASSWORD
\getenv runtime_password RUNTIME_PASSWORD
\getenv maintenance_password MAINTENANCE_PASSWORD
SELECT format('CREATE ROLE auditlab_migrator LOGIN PASSWORD %L', :'migrator_password') WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='auditlab_migrator') \gexec
SELECT format('CREATE ROLE auditlab_runtime LOGIN PASSWORD %L', :'runtime_password') WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='auditlab_runtime') \gexec
SELECT format('CREATE ROLE auditlab_maintenance LOGIN PASSWORD %L', :'maintenance_password') WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='auditlab_maintenance') \gexec
SELECT 'CREATE DATABASE auditlab OWNER auditlab_migrator' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname='auditlab') \gexec
'@
    Invoke-Docker -Arguments @('exec', '-i', '--env', 'PGPASSWORD', '--env', 'MIGRATOR_PASSWORD', '--env', 'RUNTIME_PASSWORD', '--env', 'MAINTENANCE_PASSWORD',
        $config.container, 'psql', '-h', '127.0.0.1', '-U', 'postgres', '-d', 'postgres', '-v', 'ON_ERROR_STOP=1', '-At') -InputText $sql | Out-Null
    $mapping = Invoke-Docker -Arguments @('port', $config.container, '5432/tcp')
    $databasePort = [int](($mapping.Trim() -split ':')[-1])
    $jdbc = 'jdbc:postgresql://127.0.0.1:' + $databasePort + '/auditlab'
    $env:AUDITLAB_MIGRATION_JDBC_URL = $jdbc
    $env:AUDITLAB_MIGRATION_USER = 'auditlab_migrator'
    $env:AUDITLAB_MIGRATION_PASSWORD = $config.passwords.migrator
    $env:AUDITLAB_DB_JDBC_URL = $jdbc
    $env:AUDITLAB_DB_USER = 'auditlab_runtime'
    $env:AUDITLAB_DB_PASSWORD = $config.passwords.runtime
    $env:AUDITLAB_MAINTENANCE_JDBC_URL = $jdbc
    $env:AUDITLAB_MAINTENANCE_USER = 'auditlab_maintenance'
    $env:AUDITLAB_MAINTENANCE_PASSWORD = $config.passwords.maintenance
    $env:AUDITLAB_AUTH_SIGNING_KEY_BASE64 = $config.signingKey
    $env:AUDITLAB_PORT = '8081'
    Write-Host 'Preparando las tablas y el usuario admin...'
    Invoke-Tool -Program $apiBatch -Arguments @('migrate') | Out-Null
    $env:AUDITLAB_TARGET_DB_ROLE = 'auditlab_runtime'
    Invoke-Tool -Program $apiBatch -Arguments @('grant-runtime') | Out-Null
    $env:AUDITLAB_TARGET_DB_ROLE = 'auditlab_maintenance'
    Invoke-Tool -Program $apiBatch -Arguments @('grant-maintenance') | Out-Null
    $env:AUDITLAB_BOOTSTRAP_USERNAME = 'admin'
    $env:AUDITLAB_BOOTSTRAP_PASSWORD = $config.passwords.admin
    $env:AUDITLAB_BOOTSTRAP_ROLE = 'admin'
    Invoke-Tool -Program $apiBatch -Arguments @('bootstrap-user') | Out-Null
    foreach ($name in @('AUDITLAB_MIGRATION_JDBC_URL', 'AUDITLAB_MIGRATION_USER', 'AUDITLAB_MIGRATION_PASSWORD',
        'AUDITLAB_TARGET_DB_ROLE', 'AUDITLAB_BOOTSTRAP_USERNAME', 'AUDITLAB_BOOTSTRAP_PASSWORD', 'AUDITLAB_BOOTSTRAP_ROLE',
        'PGPASSWORD', 'MIGRATOR_PASSWORD', 'RUNTIME_PASSWORD', 'MAINTENANCE_PASSWORD')) {
        [Environment]::SetEnvironmentVariable($name, $null, 'Process')
    }
    Write-Host ''
    Write-Host 'Servidor local: http://127.0.0.1:8081'
    Write-Host 'Usuario: admin. Usa la contrasena que elegiste al comenzar.'
    Write-Host 'Mantén esta ventana abierta. Comprueba /ready en el navegador antes de conectar los celulares.'
    & $apiBatch
    if ($LASTEXITCODE -ne 0) { throw 'La API termino con error. Revisa los mensajes de esta ventana.' }
} finally {
    foreach ($item in @(Get-ChildItem Env: | Where-Object { $_.Name -like 'AUDITLAB_*' })) {
        [Environment]::SetEnvironmentVariable($item.Name, $null, 'Process')
    }
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
}
