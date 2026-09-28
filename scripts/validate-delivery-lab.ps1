[CmdletBinding()]
param(
    [string]$BackendUrl = 'http://localhost:8080',
    [string]$PaymentUrl = 'http://localhost:8081',
    [ValidateRange(30,600)][int]$TimeoutSeconds = 180,
    [string]$WslDistribution,
    [string]$EvidencePath = (Join-Path (Split-Path -Parent $PSScriptRoot) 'target/delivery-evidence.json')
)

# Requires the full Compose lab already running. Mutates only demo services and registers one deployment.
# Existing registrations/data/volumes are preserved. Always recovers payment, including on assertion failure.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$BackendUrl = $BackendUrl.TrimEnd('/')
function Api {
    param([string]$Path)
    Invoke-RestMethod -Uri "$BackendUrl$Path" -TimeoutSec 15
}
function Poll {
    param([string]$Description, [scriptblock]$Condition)
    Write-Host "Waiting: $Description"
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $result = & $Condition
        if ($result) { return $result }
        Start-Sleep -Milliseconds 500
    }
    throw "Timed out: $Description"
}
function Sql {
    param([string]$Query)
    Push-Location $repoRoot
    try {
        $dockerArguments = @('compose','exec','-T','postgres','psql','-U','launchguard','-d','launchguard','-At','-v','ON_ERROR_STOP=1')
        if ($WslDistribution) {
            $output = $Query | & wsl.exe -d $WslDistribution -- docker @dockerArguments
        } else { $output = $Query | & docker @dockerArguments }
        if ($LASTEXITCODE -ne 0) { throw 'Database evidence query failed.' }
        ($output -join "`n").Trim()
    } finally { Pop-Location }
}
$registered = @(& "$PSScriptRoot/register-demo-services.ps1" -BackendUrl $BackendUrl -Target Docker)
$payment = $registered | Where-Object { $_.Name -eq 'payment-service' } | Select-Object -First 1
$serviceId = [guid]$payment.Id
$deploymentPath = "/api/services/$serviceId/deployments"
Push-Location $repoRoot
try {
    $sha = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $sha -notmatch '^[0-9a-f]{40}$') { throw 'Cannot identify current Git commit.' }
} finally { Pop-Location }
$externalId = if ($env:GITHUB_RUN_ID) { "github-$env:GITHUB_REPOSITORY_ID-$env:GITHUB_RUN_ID-local" }
    else { "local-$([guid]::NewGuid())" }
$projectVersion = ([xml](Get-Content -Raw -LiteralPath (Join-Path $repoRoot 'pom.xml'))).project.version
$reportArgs = @{ LaunchGuardUrl=$BackendUrl; ServiceId=$serviceId; Version=$projectVersion; CommitSha=$sha
    Environment='local'; ImageTag="sha-$sha"; ExternalId=$externalId; Description='V0.7 delivery demonstration' }
try {
    Invoke-RestMethod -Method Post -Uri "$PaymentUrl/admin/normal" -TimeoutSec 15 | Out-Null
    Invoke-RestMethod -Method Post -Uri "$PaymentUrl/admin/recover" -TimeoutSec 15 | Out-Null
    Poll 'initial payment recovery' {
        $s = Api "/api/services/$serviceId"
        if ($s.status -eq 'HEALTHY' -and -not $s.hasOpenIncident) { $s }
    } | Out-Null
    $deployment = & "$PSScriptRoot/report-deployment.ps1" @reportArgs
    $saved = Api "$deploymentPath/$($deployment.id)"
    foreach ($pair in @{source='CI'; environment='local'; imageTag="sha-$sha"; commitSha=$sha; externalId=$externalId}.GetEnumerator()) {
        if ($saved.($pair.Key) -cne $pair.Value) { throw "Deployment metadata mismatch: $($pair.Key)" }
    }
    if (-not $saved.current) { throw 'CI deployment is not current.' }
    $healthy = Poll 'new Kafka health check correlated with CI deployment' {
        (Api "/api/services/$serviceId/checks?size=100").content |
            Where-Object { $_.deploymentId -eq $deployment.id -and $_.probeRequestId -and $_.status -eq 'HEALTHY' } |
            Select-Object -First 1
    }
    Invoke-RestMethod -Method Post -Uri "$PaymentUrl/admin/fail" -TimeoutSec 15 | Out-Null
    $incident = Poll 'automatic OPEN incident for CI deployment' {
        (Api "/api/services/$serviceId/incidents?status=OPEN").content |
            Where-Object { $_.deployment.id -eq $deployment.id } | Select-Object -First 1
    }
    Invoke-RestMethod -Method Post -Uri "$PaymentUrl/admin/recover" -TimeoutSec 15 | Out-Null
    $resolved = Poll 'automatic incident recovery' {
        $i = Api "/api/services/$serviceId/incidents/$($incident.id)"
        if ($i.status -eq 'RESOLVED') { $i }
    }
    # Freeze the precise historical row IDs; monitoring may continue appending new rows.
    # The digest includes every column, so rewriting any captured row changes the evidence.
    $rowIds = Sql "SELECT COALESCE(string_agg(id::text, ',' ORDER BY id), '') FROM health_checks WHERE service_id='$serviceId';"
    $quotedIds = (@($rowIds.Split(',') | ForEach-Object { "'$([guid]$_)'" }) -join ',')
    $fingerprintQuery = "SELECT count(*)::text || ':' || md5(string_agg(row_to_json(h)::text, '|' ORDER BY id)) FROM health_checks h WHERE id IN ($quotedIds);"
    $beforeHistory = Sql $fingerprintQuery
    $beforeCount = (Api "$deploymentPath`?size=100").totalElements
    $replay = & "$PSScriptRoot/report-deployment.ps1" @reportArgs
    $afterCount = (Api "$deploymentPath`?size=100").totalElements
    $afterHistory = Sql $fingerprintQuery
    if ($replay.id -ne $deployment.id -or $beforeCount -ne $afterCount -or $beforeHistory -ne $afterHistory) {
        throw 'Idempotent replay duplicated deployment or rewrote historical checks.'
    }
    $conflict = $reportArgs.Clone()
    $conflict.Version = 'conflicting-version'
    $conflictError = $null
    try {
        & "$PSScriptRoot/report-deployment.ps1" @conflict | Out-Null
        throw 'Conflicting external ID was accepted.'
    } catch {
        if ($null -eq $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 409) { throw }
        # PowerShell 7 generally supplies ErrorDetails; 5.1 can expose only the WebException response stream.
        if ($null -ne $_.ErrorDetails -and $_.ErrorDetails.Message) {
            $errorBody = $_.ErrorDetails.Message
        } elseif ($null -ne $_.Exception.Response.PSObject.Methods['GetResponseStream']) {
            $responseStream = $_.Exception.Response.GetResponseStream()
            if ($responseStream.CanSeek) { $responseStream.Position = 0 }
            $reader = New-Object System.IO.StreamReader($responseStream)
            try { $errorBody = $reader.ReadToEnd() } finally { $reader.Dispose() }
        } else {
            $errorBody = $_.Exception.Response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        }
        $conflictError = $errorBody | ConvertFrom-Json
        if ($conflictError.status -ne 409 -or -not $conflictError.message -or $null -eq $conflictError.violations) {
            throw 'Conflict did not return the structured error contract.'
        }
    }
    $escapedExternalId = $externalId.Replace("'", "''")
    $databaseCount = Sql "SELECT count(*) FROM deployments WHERE service_id='$serviceId' AND external_id='$escapedExternalId';"
    $migration = Sql "SELECT version || ':' || success::text FROM flyway_schema_history WHERE version='5';"
    $indexValid = Sql "SELECT indisunique AND indisvalid FROM pg_index WHERE indexrelid='uk_deployments_service_external_id'::regclass;"
    if ($databaseCount -ne '1' -or $migration -ne '5:true' -or $indexValid -ne 't') { throw 'V5/database uniqueness evidence failed.' }
    $evidence = [ordered]@{ gitSha=$sha; deployment=$saved; healthyKafkaCheck=$healthy; resolvedIncident=$resolved
        deploymentCountBefore=$beforeCount; deploymentCountAfter=$afterCount; replayId=$replay.id
        historicalChecksBefore=$beforeHistory; historicalChecksAfter=$afterHistory; conflict=$conflictError
        matchingDatabaseDeployments=$databaseCount; migration=$migration; uniqueIndexValid=$indexValid }
    New-Item -ItemType Directory -Path (Split-Path -Parent $EvidencePath) -Force | Out-Null
    $evidence | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $EvidencePath -Encoding utf8
    Write-Host "DELIVERY_LAB_OK deployment=$($deployment.id) incident=$($incident.id) checks=$beforeHistory V5=$migration"
    [pscustomobject]$evidence
} finally {
    Invoke-RestMethod -Method Post -Uri "$PaymentUrl/admin/normal" -TimeoutSec 15 | Out-Null
    Invoke-RestMethod -Method Post -Uri "$PaymentUrl/admin/recover" -TimeoutSec 15 | Out-Null
}
