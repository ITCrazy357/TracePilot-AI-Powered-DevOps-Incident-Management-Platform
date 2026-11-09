[CmdletBinding()]
param(
    [ValidateSet('setup', 'doctor', 'infra', 'verify', 'run', 'preview', 'stop')]
    [string]$Task = 'doctor'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = Join-Path $projectRoot 'backend'

function Invoke-Checked {
    param([string]$Command, [string[]]$Arguments)
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Command failed with exit code $LASTEXITCODE" }
}

function Initialize-LocalEnvironment {
    $envPath = Join-Path $projectRoot '.env'
    if (Test-Path -LiteralPath $envPath) {
        # Phase 01 adds a signing secret without changing existing local credentials.
        $existing = [IO.File]::ReadAllText($envPath)
        if ($existing -notmatch '(?m)^AUTH_SIGNING_SECRET=') {
            $secret = New-AuthSigningSecret
            [IO.File]::AppendAllText($envPath, "`nAUTH_SIGNING_SECRET=$secret`n", [Text.UTF8Encoding]::new($false))
            Write-Host 'Added the missing local AUTH_SIGNING_SECRET to .env.'
        }
        return
    }
    $content = Get-Content -LiteralPath (Join-Path $projectRoot '.env.example') -Raw
    # Hex values work with both Docker Compose dotenv and Spring .properties parsing.
    $content = $content.Replace('POSTGRES_PASSWORD=replace-with-local-password',
        ('POSTGRES_PASSWORD=' + [guid]::NewGuid().ToString('N')))
    $content = $content.Replace('RABBITMQ_PASSWORD=replace-with-local-password',
        ('RABBITMQ_PASSWORD=' + [guid]::NewGuid().ToString('N')))
    $content = $content.Replace('AUTH_SIGNING_SECRET=replace-with-random-64-character-secret',
        ('AUTH_SIGNING_SECRET=' + (New-AuthSigningSecret)))
    [IO.File]::WriteAllText($envPath, $content, [Text.UTF8Encoding]::new($false))
    Write-Host 'Created .env with local credentials. Existing .env files are preserved.'
}

function New-AuthSigningSecret {
    $bytes = New-Object byte[] 32
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) }
    finally { $generator.Dispose() }
    return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
}

function Set-Java21 {
    $candidates = @($env:JAVA_HOME)
    foreach ($base in @('C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Java',
        'C:\Program Files\Microsoft', (Join-Path $env:USERPROFILE '.jdks'))) {
        if (Test-Path -LiteralPath $base) {
            $candidates += @(Get-ChildItem -LiteralPath $base -Directory | Sort-Object Name -Descending |
                Select-Object -ExpandProperty FullName)
        }
    }
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCommand) { $candidates += Split-Path -Parent (Split-Path -Parent $javaCommand.Source) }
    foreach ($candidate in $candidates) {
        if (-not $candidate) { continue }
        if (-not (Test-Path -LiteralPath (Join-Path $candidate 'bin\javac.exe'))) { continue }
        $releaseFile = Join-Path $candidate 'release'
        if ((Test-Path -LiteralPath $releaseFile) -and
            (Select-String -LiteralPath $releaseFile -Pattern '^JAVA_VERSION="21[.\"]' -Quiet)) {
            $env:JAVA_HOME = $candidate
            $env:PATH = (Join-Path $candidate 'bin') + ';' + $env:PATH
            Write-Host "Using JDK 21: $candidate"
            return
        }
    }
    throw 'JDK 21 was not found. Install a JDK 21 and set JAVA_HOME to its directory.'
}

function Assert-Docker {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Install Docker Desktop first.' }
    & docker info --format '{{.ServerVersion}}'
    if ($LASTEXITCODE -ne 0) { throw 'Start Docker Desktop with Linux containers, then rerun this command.' }
}

function Start-Infrastructure {
    Assert-Docker
    Invoke-Checked -Command docker -Arguments @('compose', 'up', '-d', '--wait', '--wait-timeout', '180')
}

Push-Location $projectRoot
try {
    switch ($Task) {
        'stop' {
            Assert-Docker
            Invoke-Checked -Command docker -Arguments @('compose', 'stop')
        }
        'infra' {
            Initialize-LocalEnvironment
            Start-Infrastructure
        }
        'doctor' {
            Set-Java21
            & (Join-Path $env:JAVA_HOME 'bin\java.exe') -version
            Assert-Docker
            Invoke-Checked -Command docker -Arguments @('compose', 'version')
        }
        default {
            Set-Java21
            Initialize-LocalEnvironment
            # Keep wrapper downloads and Maven artifacts inside the project.
            $env:MAVEN_USER_HOME = Join-Path $projectRoot '.tools\maven'
            $mavenRepo = Join-Path $projectRoot '.tools\m2'
            if ($Task -eq 'setup') { Start-Infrastructure }
            if ($Task -in @('verify', 'preview')) { Assert-Docker }
            Push-Location $backendRoot
            try {
                $mavenArgs = @('-B', '-ntp', "-Dmaven.repo.local=$mavenRepo")
                switch ($Task) {
                    'setup' { $mavenArgs += 'verify' }
                    'verify' { $mavenArgs += 'verify' }
                    'run' { $mavenArgs += 'spring-boot:run' }
                    'preview' {
                        # ServiceConnection uses disposable DB/broker containers instead of the local Compose volumes.
                        $mavenArgs += @(
                            '-Dspring-boot.run.main-class=com.tracepilot.TestTracePilotApplication',
                            '-Dspring-boot.run.arguments=--server.port=18080 --tracepilot.github.enabled=false --tracepilot.pipeline.enabled=false --tracepilot.ai.enabled=false --tracepilot.rag.enabled=false --tracepilot.rate-limit.enabled=true',
                            'spring-boot:test-run'
                        )
                    }
                }
                Invoke-Checked -Command '.\mvnw.cmd' -Arguments $mavenArgs
            }
            finally { Pop-Location }
        }
    }
}
finally { Pop-Location }
