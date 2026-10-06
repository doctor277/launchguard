[CmdletBinding()]
param(
    [string]$BackendUrl = 'http://localhost:8080',
    [string]$PaymentUrl = 'http://localhost:8081',
    [string]$OrderUrl = 'http://localhost:8082',
    [string]$NotificationUrl = 'http://localhost:8083',
    [ValidateRange(30, 1800)][int]$TimeoutSeconds = 240,
    [string]$WslDistribution,
    [string]$AccessToken
)

# Intentionally changes demo health and restarts this Compose project.
# Never removes volumes. Run against the local lab, not another environment.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/launchguard-auth.ps1"
$repoRoot = Split-Path -Parent $PSScriptRoot
$BackendUrl = $BackendUrl.TrimEnd('/')
$AccessToken = Get-LaunchGuardAccessToken -AccessToken $AccessToken
$authorization = New-LaunchGuardAuthorizationHeader -AccessToken $AccessToken
$demoUrls = @{ 'payment-service' = $PaymentUrl; 'order-service' = $OrderUrl; 'notification-service' = $NotificationUrl }
$linuxRoot = $null
if ($WslDistribution) {
    # Forward slashes prevent WSL's command launcher interpreting Windows backslashes as escapes.
    $resolvedRoot = & wsl.exe -d $WslDistribution -- wslpath -a $repoRoot.Replace('\', '/')
    if ($LASTEXITCODE -ne 0 -or -not $resolvedRoot) { throw 'Could not resolve WSL repository path.' }
    $linuxRoot = ($resolvedRoot -join '').Trim()
}
$composeEnvironment = @()
foreach ($key in @('POSTGRES_PORT', 'LAUNCHGUARD_PORT', 'PAYMENT_PORT', 'ORDER_PORT', 'NOTIFICATION_PORT',
    'MONITORING_INTERVAL', 'MONITORING_INITIAL_DELAY', 'MONITORING_CONNECT_TIMEOUT', 'MONITORING_RESPONSE_TIMEOUT',
    'INCIDENT_FAILURE_THRESHOLD', 'INCIDENT_RECOVERY_THRESHOLD', 'PAYMENT_SLOW_DELAY_MS', 'ORDER_SLOW_DELAY_MS',
    'NOTIFICATION_SLOW_DELAY_MS', 'COMPOSE_PROJECT_NAME', 'KAFKA_PORT', 'PROBE_WORKER_PORT',
    'KAFKA_TOPIC_PARTITIONS', 'KAFKA_CONSUMER_CONCURRENCY', 'PROBE_WORKER_THREADS',
    'PROBE_WORKER_QUEUE_CAPACITY', 'PROBE_IN_FLIGHT_TTL', 'KEYCLOAK_PORT',
    'OIDC_AUTOMATION_CLIENT_SECRET', 'DASHBOARD_PUBLIC_URL')) {
    $value = [Environment]::GetEnvironmentVariable($key)
    if ($null -ne $value) { $composeEnvironment += "$key=$value" }
}

function Invoke-LabDocker {
    param([string[]]$DockerArguments)
    if ($WslDistribution) {
        $output = & wsl.exe -d $WslDistribution --cd $linuxRoot -- env @composeEnvironment docker @DockerArguments
    } else {
        Push-Location $repoRoot
        try { $output = & docker @DockerArguments } finally { Pop-Location }
    }
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed: docker $($DockerArguments -join ' ')" }
    return $output
}
function Get-LabApi {
    param([string]$Path)
    $response = Invoke-RestMethod -Uri "$BackendUrl$Path" -Headers $authorization -TimeoutSec 15
    return $response
}
function Wait-LabCondition {
    param([string]$Description, [scriptblock]$Condition)
    Write-Host "Waiting: $Description"
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $result = & $Condition
        if ($result) { return $result }
        Start-Sleep -Seconds 1
    }
    throw "Timed out: $Description"
}
function Set-DemoControl {
    param([string]$Name, [string]$Control)
    Invoke-RestMethod -Method Post -Uri "$($demoUrls[$Name])/admin/$Control" -TimeoutSec 15 | Out-Null
}
function Get-Demo {
    param([string]$Name)
    Get-LabApi "/api/services/$($ids[$Name])"
}
function Wait-DemoHealthy {
    param([string]$Name)
    Wait-LabCondition "$Name HEALTHY with no OPEN incident" {
        $service = Get-Demo $Name
        if ($service.status -eq 'HEALTHY' -and -not $service.hasOpenIncident) { $service }
    }
}
function Wait-DemoIncident {
    param([string]$Name)
    Wait-LabCondition "$Name DOWN with an automatically OPEN incident" {
        $service = Get-Demo $Name
        if ($service.status -eq 'DOWN' -and $service.hasOpenIncident) {
            $incident = Get-LabApi "/api/services/$($ids[$Name])/incidents/current"
            if ($null -ne $incident -and $incident.status -eq 'OPEN' -and $incident.serviceId -eq $ids[$Name]) { $incident }
        }
    }
}
function Assert-DemoHealthy {
    param([string]$Name)
    $service = Get-Demo $Name
    if ($service.status -ne 'HEALTHY' -or $service.hasOpenIncident) { throw "Incident isolation failed for $Name." }
}
function Get-DatabaseSnapshot {
    # Sorted UUID arrays prove old rows survived, not merely that counts increased.
    $sql = @'
SELECT json_build_object(
  'services', (SELECT coalesce(json_agg(id ORDER BY id), '[]'::json) FROM monitored_services),
  'checks', (SELECT coalesce(json_agg(id ORDER BY id), '[]'::json) FROM health_checks),
  'incidents', (SELECT coalesce(json_agg(id ORDER BY id), '[]'::json) FROM incidents),
  'deployments', (SELECT coalesce(json_agg(id ORDER BY id), '[]'::json) FROM deployments));
'@
    $json = Invoke-LabDocker -DockerArguments @('compose', 'exec', '-T', 'postgres', 'psql', '-U', 'launchguard', '-d', 'launchguard', '-At', '-c', $sql)
    ($json -join '') | ConvertFrom-Json
}

Write-Host 'V0.5 validation: this will simulate outages and recreate the Compose containers, preserving the named volume.'
$first = @(& "$PSScriptRoot/register-demo-services.ps1" -BackendUrl $BackendUrl -Target Docker -AccessToken $AccessToken)
$second = @(& "$PSScriptRoot/register-demo-services.ps1" -BackendUrl $BackendUrl -Target Docker -AccessToken $AccessToken)
$ids = @{}
foreach ($service in $first) {
    $ids[$service.Name] = $service.Id
    if (@($second | Where-Object { $_.Name -eq $service.Name -and $_.Id -eq $service.Id }).Count -ne 1) {
        throw 'Bootstrap was not idempotent.'
    }
    Set-DemoControl $service.Name 'normal'
    Set-DemoControl $service.Name 'recover'
}
foreach ($name in $ids.Keys) { Wait-DemoHealthy $name | Out-Null }
$initialStatuses = @(Get-LabApi '/api/services')

Write-Host 'Scenario A: isolated order outage, automatic opening and resolution.'
Set-DemoControl 'order-service' 'fail'
$orderIncident = Wait-DemoIncident 'order-service'
Assert-DemoHealthy 'payment-service'
Assert-DemoHealthy 'notification-service'
Set-DemoControl 'order-service' 'recover'
Wait-DemoHealthy 'order-service' | Out-Null
$resolvedOrder = Get-LabApi "/api/services/$($ids['order-service'])/incidents/$($orderIncident.id)"
if ($resolvedOrder.status -ne 'RESOLVED' -or $null -eq $resolvedOrder.resolvedAt) { throw 'Order incident did not resolve.' }

Write-Host 'Scenario B: independent payment and notification outages.'
Set-DemoControl 'payment-service' 'fail'
Set-DemoControl 'notification-service' 'fail'
$paymentIncident = Wait-DemoIncident 'payment-service'
$notificationIncident = Wait-DemoIncident 'notification-service'
if ($paymentIncident.id -eq $notificationIncident.id) { throw 'Different services shared an incident.' }
Assert-DemoHealthy 'order-service'
foreach ($name in @('payment-service', 'notification-service')) { Set-DemoControl $name 'recover' }
foreach ($name in @('payment-service', 'notification-service')) { Wait-DemoHealthy $name | Out-Null }
foreach ($incident in @($paymentIncident, $notificationIncident)) {
    $resolved = Get-LabApi "/api/services/$($incident.serviceId)/incidents/$($incident.id)"
    if ($resolved.status -ne 'RESOLVED') { throw 'Simultaneous outage incident did not resolve.' }
}

Write-Host 'Scenario C: 250ms healthy latency, then 7000ms delay exceeding the default 5s probe timeout.'
$slowStarted = [DateTimeOffset]::UtcNow
Set-DemoControl 'notification-service' 'slow?delayMs=250'
$slowCheck = Wait-LabCondition 'new notification HEALTHY check with artificial latency' {
    $history = Get-LabApi "/api/services/$($ids['notification-service'])/checks?size=20"
    $history.content | Where-Object {
        [DateTimeOffset]$_.checkedAt -ge $slowStarted -and $_.status -eq 'HEALTHY' -and $_.responseTimeMs -ge 250
    } | Select-Object -First 1
}
$timeoutStarted = [DateTimeOffset]::UtcNow
Set-DemoControl 'notification-service' 'slow?delayMs=7000'
$timeoutCheck = Wait-LabCondition 'new notification DOWN check recording a network timeout' {
    $history = Get-LabApi "/api/services/$($ids['notification-service'])/checks?size=20"
    $history.content | Where-Object {
        [DateTimeOffset]$_.checkedAt -ge $timeoutStarted -and $_.status -eq 'DOWN' -and
        $null -eq $_.httpStatus -and $_.responseTimeMs -ge 4500 -and $_.responseTimeMs -lt 7000 -and
        $_.errorMessage -match 'timed out|timeout|Request cancelled'
    } | Select-Object -First 1
}
Assert-DemoHealthy 'payment-service'
Assert-DemoHealthy 'order-service'
Set-DemoControl 'notification-service' 'normal'
Wait-DemoHealthy 'notification-service' | Out-Null

Write-Host 'Container outage: stop order, detect connection failure, restart, recover.'
Invoke-LabDocker -DockerArguments @('compose', 'stop', 'order-service') | Out-Host
$containerIncident = Wait-DemoIncident 'order-service'
$unreachableCheck = (Get-LabApi "/api/services/$($ids['order-service'])/checks?size=1").content[0]
if ($null -ne $unreachableCheck.httpStatus -or -not $unreachableCheck.errorMessage) { throw 'Unreachable target was not recorded as a network error.' }
Invoke-LabDocker -DockerArguments @('compose', 'start', 'order-service') | Out-Host
Wait-DemoHealthy 'order-service' | Out-Null

Write-Host 'Full environment restart: down (without -v), up with health-based waiting, then compare persisted UUIDs.'
$before = Get-DatabaseSnapshot
Invoke-LabDocker -DockerArguments @('compose', 'down') | Out-Host
Invoke-LabDocker -DockerArguments @('compose', 'up', '-d', '--wait', '--wait-timeout', '180') | Out-Host
foreach ($name in $ids.Keys) {
    Wait-DemoHealthy $name | Out-Null
    if ((Get-Demo $name).id -ne $ids[$name]) { throw 'Service ID changed after full restart.' }
}
$after = Get-DatabaseSnapshot
foreach ($table in @('services', 'checks', 'incidents', 'deployments')) {
    foreach ($rowId in @($before.$table)) {
        if (@($after.$table) -notcontains $rowId) { throw "Lost $table row $rowId after full restart." }
    }
}
if (@($before.services).Count -ne @($after.services).Count) { throw 'Restart inserted unexpected services.' }
$persistedOrder = Get-LabApi "/api/services/$($ids['order-service'])/incidents/$($resolvedOrder.id)"
if ($persistedOrder.resolvedAt -ne $resolvedOrder.resolvedAt) { throw 'Resolved incident changed after restart.' }

Write-Host 'Verify all seven containers healthy and application runtimes non-root.'
foreach ($name in @('postgres', 'kafka', 'backend', 'probe-worker', 'payment-service', 'order-service', 'notification-service')) {
    $containerId = (Invoke-LabDocker -DockerArguments @('compose', 'ps', '-q', $name) -join '').Trim()
    if (-not $containerId) { throw "$name is not running." }
    $health = (Invoke-LabDocker -DockerArguments @('inspect', '--format', '{{.State.Health.Status}}', $containerId) -join '').Trim()
    if ($health -ne 'healthy') { throw "$name container is $health." }
    if ($name -ne 'postgres') {
        $uid = (Invoke-LabDocker -DockerArguments @('compose', 'exec', '-T', $name, 'id', '-u') -join '').Trim()
        if ($uid -eq '0' -or -not $uid) { throw "$name runs as root." }
    }
}
Invoke-LabDocker -DockerArguments @('compose', 'ps') | Out-Host
[pscustomobject]@{
    result = 'PASS'
    initialServices = $initialStatuses
    finalServices = @(Get-LabApi '/api/services')
    orderResolvedIncident = $resolvedOrder
    paymentOpenIncidentSample = $paymentIncident
    notificationOpenIncidentSample = $notificationIncident
    stoppedContainerIncidentSample = $containerIncident
    slowCheck = $slowCheck
    timeoutCheck = $timeoutCheck
    unreachableCheck = $unreachableCheck
    databaseBeforeRestart = @{ services = @($before.services).Count; checks = @($before.checks).Count; incidents = @($before.incidents).Count; deployments = @($before.deployments).Count }
    databaseAfterRestart = @{ services = @($after.services).Count; checks = @($after.checks).Count; incidents = @($after.incidents).Count; deployments = @($after.deployments).Count }
} | ConvertTo-Json -Depth 8
