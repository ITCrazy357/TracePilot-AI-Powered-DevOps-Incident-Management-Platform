[CmdletBinding()]
param(
    [ValidateSet('init', 'build', 'up', 'check', 'status', 'stop', 'down')]
    [string]$Task = 'status',
    [ValidatePattern('^[a-z0-9][a-z0-9_-]*$')]
    [string]$Project = 'tracepilot-shipping'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot '.env.shipping'

function New-LocalSecret {
    $bytes = New-Object byte[] 32
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) }
    finally { $generator.Dispose() }
    return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
}

function Initialize-ShippingEnvironment {
    if (Test-Path -LiteralPath $envPath) {
        Write-Host 'Preserving existing .env.shipping.'
        return
    }
    $template = [IO.File]::ReadAllText((Join-Path $projectRoot '.env.shipping.example'))
    foreach ($placeholder in @('replace-with-random-db-password', 'replace-with-random-broker-password',
        'replace-with-random-64-character-secret')) {
        $template = $template.Replace($placeholder, (New-LocalSecret))
    }
    # CreateNew prevents an overlapping setup from overwriting an existing credential file.
    $stream = [IO.File]::Open($envPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
    try {
        $bytes = [Text.UTF8Encoding]::new($false).GetBytes($template)
        $stream.Write($bytes, 0, $bytes.Length)
    }
    finally { $stream.Dispose() }
    Write-Host 'Created .env.shipping with fresh local secrets; providers remain disabled.'
}

function Invoke-Compose {
    param([string[]]$ComposeArguments)
    Get-Command docker -ErrorAction Stop | Out-Null
    $previousPreference = $ErrorActionPreference
    try {
        # Windows PowerShell 5.1 treats redirected native stderr as ErrorRecords, including normal Docker progress.
        $ErrorActionPreference = 'Continue'
        & docker compose --env-file $envPath -p $Project -f compose.shipping.yaml @ComposeArguments 2>&1 |
            ForEach-Object { $_.ToString() }
        $composeExitCode = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $previousPreference }
    if ($composeExitCode -ne 0) { throw "Docker Compose failed with exit code $composeExitCode." }
}

Push-Location $projectRoot
try {
    if ($Task -in @('init', 'build', 'up')) { Initialize-ShippingEnvironment }
    if ($Task -eq 'init') { return }
    if (-not (Test-Path -LiteralPath $envPath)) { throw 'Run scripts/shipping.ps1 -Task init first.' }
    switch ($Task) {
        'build' { Invoke-Compose -ComposeArguments @('build') }
        'up' { Invoke-Compose -ComposeArguments @('up', '-d', '--build', '--wait', '--wait-timeout', '240') }
        'check' {
            & node (Join-Path $PSScriptRoot 'shipping-smoke.mjs')
            if ($LASTEXITCODE -ne 0) { throw 'Shipping smoke checks failed.' }
        }
        'status' { Invoke-Compose -ComposeArguments @('ps') }
        'stop' { Invoke-Compose -ComposeArguments @('stop') }
        'down' { Invoke-Compose -ComposeArguments @('down') }
    }
}
finally { Pop-Location }
