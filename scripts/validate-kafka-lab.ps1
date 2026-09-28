[CmdletBinding()]
param(
    [string]$BackendUrl = 'http://localhost:8080',
    [string]$PaymentUrl = 'http://localhost:8081',
    [string]$OrderUrl = 'http://localhost:8082',
    [string]$NotificationUrl = 'http://localhost:8083',
    [ValidateRange(30, 1800)][int]$TimeoutSeconds = 240,
    [string]$WslDistribution
)

# Changes only the local lab: controls demos, stops/starts order, restarts Kafka,
# registers two deployments, and replays a real result. Never deletes data/volumes.
# Use MONITORING_RESPONSE_TIMEOUT=8s so the 5000ms demonstration completes healthy.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$BackendUrl = $BackendUrl.TrimEnd('/')
$demoUrls = @{ 'payment-service' = $PaymentUrl; 'order-service' = $OrderUrl; 'notification-service' = $NotificationUrl }
$forwarded = @()
foreach ($key in @('POSTGRES_PORT','LAUNCHGUARD_PORT','PAYMENT_PORT','ORDER_PORT','NOTIFICATION_PORT',
    'KAFKA_PORT','PROBE_WORKER_PORT','MONITORING_INTERVAL','MONITORING_INITIAL_DELAY',
    'MONITORING_CONNECT_TIMEOUT','MONITORING_RESPONSE_TIMEOUT','KAFKA_TOPIC_PARTITIONS',
    'KAFKA_CONSUMER_CONCURRENCY','PROBE_WORKER_THREADS','PROBE_WORKER_QUEUE_CAPACITY',
    'PROBE_IN_FLIGHT_TTL','INCIDENT_FAILURE_THRESHOLD','INCIDENT_RECOVERY_THRESHOLD','COMPOSE_PROJECT_NAME')) {
    $value = [Environment]::GetEnvironmentVariable($key)
    if ($null -ne $value) { $forwarded += "$key=$value" }
}
$linuxRoot = $null
if ($WslDistribution) {
    $linuxRoot = ((& wsl.exe -d $WslDistribution -- wslpath -a $repoRoot.Replace('\','/')) -join '').Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Could not resolve WSL path.' }
}
function Invoke-Docker {
    param([string[]]$Arguments, [string]$InputText)
    Push-Location $repoRoot
    try {
        if ($WslDistribution) {
            if ($InputText) { $output = $InputText | & wsl.exe -d $WslDistribution --cd $linuxRoot -- env @forwarded docker @Arguments }
            else { $output = & wsl.exe -d $WslDistribution --cd $linuxRoot -- env @forwarded docker @Arguments }
        } else {
            if ($InputText) { $output = $InputText | & docker @Arguments }
            else { $output = & docker @Arguments }
        }
        if ($LASTEXITCODE -ne 0) { throw "Docker command failed: $($Arguments -join ' ')" }
        $output
    } finally { Pop-Location }
}
function Api {
    param([string]$Path, [string]$Method = 'Get', [object]$Body)
    $arguments = @{ Uri = "$BackendUrl$Path"; Method = $Method; TimeoutSec = 15 }
    if ($null -ne $Body) { $arguments.ContentType = 'application/json'; $arguments.Body = $Body | ConvertTo-Json }
    Invoke-RestMethod @arguments
}
function Poll {
    param([string]$Description, [scriptblock]$Condition, [int]$IntervalMs = 500)
    Write-Host "Waiting: $Description"
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $value = & $Condition
        if ($value) { return $value }
        Start-Sleep -Milliseconds $IntervalMs
    }
    throw "Timed out: $Description"
}
function Control {
    param([string]$Name, [string]$Action)
    Invoke-RestMethod -Method Post -Uri "$($demoUrls[$Name])/admin/$Action" -TimeoutSec 15 | Out-Null
}
function Get-KafkaHistory {
    param([string]$Name)
    (Api "/api/services/$($ids[$Name])/checks?size=100").content
}
function Queued {
    param([string]$Name)
    Poll "$Name async HTTP 202" {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$BackendUrl/api/services/$($ids[$Name])/check/async" -TimeoutSec 15
            if ($response.StatusCode -ne 202) { throw 'Async endpoint did not return 202.' }
            $queued = $response.Content | ConvertFrom-Json
            if ($queued.status -ne 'QUEUED') { throw 'Invalid queued status.' }
            $queued
        } catch {
            if ($null -eq $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 409) { throw }
        }
    } 50
}
function RequestRow {
    param([string]$Name, [string]$RequestId)
    Get-KafkaHistory $Name | Where-Object { $_.probeRequestId -eq $RequestId } | Select-Object -First 1
}
function Healthy {
    param([string]$Name)
    Poll "$Name HEALTHY without OPEN incident" {
        $service = Api "/api/services/$($ids[$Name])"
        if ($service.status -eq 'HEALTHY' -and -not $service.hasOpenIncident) { $service }
    }
}
function Sql {
    param([string]$Query)
    # stdin avoids PowerShell 5.1/native quoting problems with JSON SQL and literals.
    ((Invoke-Docker @('compose','exec','-T','postgres','psql','-U','launchguard','-d','launchguard','-At','-v','ON_ERROR_STOP=1') "$Query;") -join '').Trim()
}
function Assert-Isolation {
    foreach ($name in @('payment-service','notification-service')) {
        $service = Api "/api/services/$($ids[$name])"
        if ($service.status -ne 'HEALTHY' -or $service.hasOpenIncident) { throw "Isolation failed for $name." }
    }
}

Write-Host 'V0.6 actual Kafka lab validation.'
$ids = @{}
foreach ($service in @(& "$PSScriptRoot/register-demo-services.ps1" -BackendUrl $BackendUrl -Target Docker)) {
    $ids[$service.Name] = $service.Id
    Control $service.Name 'normal'
    Control $service.Name 'recover'
}
foreach ($name in $ids.Keys) { Healthy $name | Out-Null }
$automatic = @()
foreach ($name in $ids.Keys) {
    $automatic += Poll "$name automatically persisted Kafka request ID" {
        Get-KafkaHistory $name | Where-Object { $null -ne $_.probeRequestId -and $_.status -eq 'HEALTHY' } | Select-Object -First 1
    }
}

Write-Host '5000ms notification probe, two fast services, and in-flight deployment replacement.'
$tag = [DateTime]::UtcNow.ToString('yyyyMMddHHmmssfff')
$v1 = Api "/api/services/$($ids['notification-service'])/deployments" Post @{version="kafka-$tag-v1"}
Control 'notification-service' 'slow?delayMs=5000'
$slowQueued = Queued 'notification-service'
$started = [DateTime]::UtcNow
$timer = [Diagnostics.Stopwatch]::StartNew()
$v2 = Api "/api/services/$($ids['notification-service'])/deployments" Post @{version="kafka-$tag-v2"}
$paymentQueued = Queued 'payment-service'
$orderQueued = Queued 'order-service'
$dispatchSpanMs = $timer.ElapsedMilliseconds
$fast = Poll 'both fast request IDs persisted before slow completion' {
    $payment = RequestRow 'payment-service' $paymentQueued.requestId
    $order = RequestRow 'order-service' $orderQueued.requestId
    if ($payment -and $order) { [pscustomobject]@{payment=$payment; order=$order} }
} 50
$fastPersistedMs = $timer.ElapsedMilliseconds
if (RequestRow 'notification-service' $slowQueued.requestId) { throw 'Slow result completed before fast-results verification.' }
$slow = Poll 'slow correlated result' { RequestRow 'notification-service' $slowQueued.requestId } 100
$slowPersistedMs = $timer.ElapsedMilliseconds
if ($slow.deploymentId -ne $v1.id -or $slow.responseTimeMs -lt 5000 -or $slow.status -ne 'HEALTHY') { throw 'Slow/deployment snapshot assertion failed; use an 8s response timeout.' }
if ((Api "/api/services/$($ids['notification-service'])").currentDeployment.id -ne $v2.id) { throw 'Current deployment was overwritten.' }
Control 'notification-service' 'normal'
Healthy 'notification-service' | Out-Null

Write-Host 'Stop order, automatically open an isolated incident, restart and resolve.'
Invoke-Docker @('compose','stop','order-service') | Out-Host
$open = Poll 'order incident automatically OPEN' {
    $service = Api "/api/services/$($ids['order-service'])"
    if ($service.status -eq 'DOWN' -and $service.hasOpenIncident) { Api "/api/services/$($ids['order-service'])/incidents/current" }
}
$down = (Get-KafkaHistory 'order-service')[0]
if ($down.httpStatus -or -not $down.errorMessage -or -not $down.probeRequestId) { throw 'Expected a Kafka network failure result.' }
Assert-Isolation
Invoke-Docker @('compose','start','order-service') | Out-Host
Healthy 'order-service' | Out-Null
$resolved = Api "/api/services/$($ids['order-service'])/incidents/$($open.id)"
if ($resolved.status -ne 'RESOLVED') { throw 'Incident did not resolve.' }

Write-Host 'Restart Kafka; backend and worker must reconnect and persist NEW requests.'
$beforeRestart = [DateTimeOffset]::UtcNow
$backendBeforeRestart = ((Invoke-Docker @('compose','ps','-q','backend')) -join '').Trim()
$workerBeforeRestart = ((Invoke-Docker @('compose','ps','-q','probe-worker')) -join '').Trim()
Invoke-Docker @('compose','restart','kafka') | Out-Host
Invoke-Docker @('compose','up','-d','--no-recreate','--wait','--wait-timeout','180') | Out-Host
if (((Invoke-Docker @('compose','ps','-q','backend')) -join '').Trim() -ne $backendBeforeRestart -or
    ((Invoke-Docker @('compose','ps','-q','probe-worker')) -join '').Trim() -ne $workerBeforeRestart) {
    throw 'Backend/worker were recreated; this does not prove reconnection of existing processes.'
}
$afterRestart = @()
foreach ($name in $ids.Keys) {
    $afterRestart += Poll "$name new healthy Kafka result after restart" {
        Get-KafkaHistory $name | Where-Object { $_.probeRequestId -and $_.status -eq 'HEALTHY' -and [DateTimeOffset]$_.checkedAt -gt $beforeRestart } | Select-Object -First 1
    }
}

Write-Host 'Replay an actually persisted completion twice; inspect the unique row directly.'
$replayed = $fast.payment
$event = @{
    eventVersion=1; requestId=$replayed.probeRequestId; serviceId=$replayed.serviceId; deploymentId=$replayed.deploymentId
    status=$replayed.status; httpStatus=$replayed.httpStatus; responseTimeMs=$replayed.responseTimeMs
    errorMessage=$replayed.errorMessage; checkedAt=$replayed.checkedAt
} | ConvertTo-Json -Compress
$incidentCountBefore = Sql "SELECT count(*) FROM incidents WHERE service_id='$($replayed.serviceId)'"
$replayLine = "$($replayed.serviceId):$event"
Invoke-Docker @('compose','exec','-T','kafka','/opt/kafka/bin/kafka-console-producer.sh',
    '--bootstrap-server','kafka:9092','--topic','launchguard.health-check.results',
    '--property','parse.key=true','--property','key.separator=:','--producer-property','acks=all') "$replayLine`n$replayLine" | Out-Host
Poll 'duplicate log appears after Kafka replay' {
    $logs = Invoke-Docker @('compose','logs','--since','30s','backend')
    if (($logs -join "`n") -match "health_result_duplicate requestId=$($replayed.probeRequestId)") { $true }
} | Out-Null
$duplicateRows = Sql "SELECT count(*) FROM health_checks WHERE probe_request_id='$($replayed.probeRequestId)'"
$incidentCountAfter = Sql "SELECT count(*) FROM incidents WHERE service_id='$($replayed.serviceId)'"
if ($duplicateRows -ne '1' -or $incidentCountBefore -ne $incidentCountAfter) { throw 'Replay was not idempotent.' }

$database = Sql @'
SELECT json_build_object(
 'services',(SELECT count(*) FROM monitored_services),
 'checks',(SELECT count(*) FROM health_checks),
 'kafkaChecks',(SELECT count(*) FROM health_checks WHERE probe_request_id IS NOT NULL),
 'legacyChecks',(SELECT count(*) FROM health_checks WHERE probe_request_id IS NULL),
 'incidents',(SELECT count(*) FROM incidents),
 'deployments',(SELECT count(*) FROM deployments),
 'migrationVersions',(SELECT json_agg(version ORDER BY installed_rank) FROM flyway_schema_history WHERE success),
 'validUniqueIndex',(SELECT indisvalid AND indisunique FROM pg_index WHERE indexrelid='uk_health_checks_probe_request_id'::regclass));
'@
$containers = @()
foreach ($name in @('postgres','kafka','backend','probe-worker','payment-service','order-service','notification-service')) {
    $containerId = ((Invoke-Docker @('compose','ps','-q',$name)) -join '').Trim()
    $health = Poll "$name container healthy" {
        $state = ((Invoke-Docker @('inspect','--format','{{.State.Health.Status}}',$containerId)) -join '').Trim()
        if ($state -eq 'healthy') { $state }
    }
    $uid = $null
    if ($name -ne 'postgres') {
        $uid = ((Invoke-Docker @('compose','exec','-T',$name,'id','-u')) -join '').Trim()
        if ($uid -eq '0') { throw "$name runs as root." }
    }
    $containers += @{name=$name;health=$health;uid=$uid}
}
$metrics = Api "/api/services/$($ids['order-service'])/metrics?window=all"
$availability = [Math]::Round(100.0 * $metrics.healthyChecks / $metrics.totalChecks, 2)
if ($metrics.availabilityPercentage -ne $availability) { throw 'Manual availability calculation differs.' }
if ((Api "/api/services/$($ids['order-service'])/checks?page=0&size=2").content.Count -ne 2) { throw 'Pagination failed.' }
$timeline = Api "/api/services/$($ids['order-service'])/metrics/timeline?window=1h"
if (@($timeline).Count -eq 0) { throw 'Timeline was empty.' }
[pscustomobject]@{
    result='PASS';serviceIds=$ids;automaticKafkaChecks=$automatic
    concurrency=@{dispatchSpanMs=$dispatchSpanMs;fastPersistedMs=$fastPersistedMs;slowPersistedMs=$slowPersistedMs;slowResponseTimeMs=$slow.responseTimeMs;startedAt=$started}
    deploymentSnapshot=@{requestId=$slow.probeRequestId;captured=$slow.deploymentId;current=$v2.id}
    stoppedOrderCheck=$down;resolvedOrderIncident=$resolved;newChecksAfterKafkaRestart=$afterRestart
    duplicate=@{requestId=$replayed.probeRequestId;rows=$duplicateRows;incidentsBefore=$incidentCountBefore;incidentsAfter=$incidentCountAfter}
    database=($database | ConvertFrom-Json);containers=$containers;exampleMetrics=$metrics;manuallyCalculatedAvailability=$availability
} | ConvertTo-Json -Depth 10
