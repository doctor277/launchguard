[CmdletBinding()]
param(
    [string]$BackendUrl = 'http://localhost:8080',
    [string]$WorkerUrl = 'http://localhost:8084',
    [string]$PaymentUrl = 'http://localhost:8081',
    [string]$OrderUrl = 'http://localhost:8082',
    [string]$NotificationUrl = 'http://localhost:8083',
    [string]$PrometheusUrl = 'http://localhost:9090',
    [string]$GrafanaUrl = 'http://localhost:3000',
    [string]$TempoUrl = 'http://localhost:3200',
    [string]$CollectorHealthUrl = 'http://localhost:13133',
    [ValidateRange(30,600)][int]$TimeoutSeconds = 180,
    [string]$WslDistribution,
    [string]$EvidencePath = (Join-Path (Split-Path -Parent $PSScriptRoot) 'target/observability-evidence.json')
)

# Requires a running Compose lab, normal automatic monitoring, default incident thresholds,
# 8s response deadline and full tracing sample rate for the 5000ms demonstration.
# Preserves existing history. Creates/deletes ONLY eight explicitly tracked load fixtures.
# Always recovers payment and restores notification latency, including on assertion failure.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$BackendUrl = $BackendUrl.TrimEnd('/')
$forwarded = @()
foreach ($key in @('POSTGRES_PORT','LAUNCHGUARD_PORT','PAYMENT_PORT','ORDER_PORT','NOTIFICATION_PORT',
    'KAFKA_PORT','PROBE_WORKER_PORT','PROMETHEUS_PORT','GRAFANA_PORT','TEMPO_PORT','OTEL_HTTP_PORT',
    'OTEL_HEALTH_PORT','IMAGE_TAG','IMAGE_PREFIX','COMPOSE_PROJECT_NAME')) {
    $value = [Environment]::GetEnvironmentVariable($key)
    if ($null -ne $value) { $forwarded += "$key=$value" }
}
$linuxRoot = $null
if ($WslDistribution) {
    $linuxRoot = ((& wsl.exe -d $WslDistribution -- wslpath -a $repoRoot.Replace('\','/')) -join '').Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Could not resolve WSL repository path.' }
}
function Invoke-Docker {
    param([string[]]$Arguments, [string]$InputText)
    # Windows PowerShell can otherwise prepend a UTF-8 BOM to native stdin (corrupting the first Kafka key).
    $OutputEncoding = [System.Text.UTF8Encoding]::new($false)
    $previousInputEncoding = [Console]::InputEncoding
    Push-Location $repoRoot
    try {
        # WSL also consults the console input encoding, independently of PowerShell's pipeline encoding.
        [Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
        if ($WslDistribution) {
            if ($InputText) { $output = $InputText | & wsl.exe -d $WslDistribution --cd $linuxRoot -- env @forwarded docker @Arguments }
            else { $output = & wsl.exe -d $WslDistribution --cd $linuxRoot -- env @forwarded docker @Arguments }
        } else {
            if ($InputText) { $output = $InputText | & docker @Arguments }
            else { $output = & docker @Arguments }
        }
        if ($LASTEXITCODE -ne 0) { throw "Docker failed: $($Arguments -join ' ')" }
        $output
    } finally {
        [Console]::InputEncoding = $previousInputEncoding
        Pop-Location
    }
}
function Api {
    param([string]$Path, [string]$Method = 'Get', [object]$Body)
    $arguments = @{Uri="$BackendUrl$Path";Method=$Method;TimeoutSec=15}
    if ($null -ne $Body) { $arguments.ContentType='application/json'; $arguments.Body=$Body | ConvertTo-Json }
    Invoke-RestMethod @arguments
}
function Poll {
    param([string]$Description, [scriptblock]$Condition, [int]$IntervalMs = 500)
    Write-Host "Waiting: $Description"
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $result = & $Condition
        if ($result) { return $result }
        Start-Sleep -Milliseconds $IntervalMs
    }
    throw "Timed out: $Description"
}
function Prom {
    param([string]$Query)
    $response = Invoke-RestMethod "$PrometheusUrl/api/v1/query?query=$([uri]::EscapeDataString($Query))" -TimeoutSec 15
    if ($response.status -ne 'success') { throw 'Prometheus query failed.' }
    $response.data.result
}
function Metric {
    param([string]$Query)
    $results = @(Prom $Query)
    if ($results.Count -ne 1) { throw "Expected one metric series: $Query (found $($results.Count))." }
    [double]$results[0].value[1]
}
function Sql {
    param([string]$Query)
    ((Invoke-Docker @('compose','exec','-T','postgres','psql','-U','launchguard','-d','launchguard','-At','-v','ON_ERROR_STOP=1') $Query) -join "`n").Trim()
}
function Queued {
    param([string]$ServiceId, [string]$TraceId)
    Poll "async HTTP 202 for $ServiceId" {
        try {
            $arguments = @{UseBasicParsing=$true;Method='Post';Uri="$BackendUrl/api/services/$ServiceId/check/async";TimeoutSec=15}
            if ($TraceId) { $arguments.Headers=@{traceparent="00-$TraceId-$(([guid]::NewGuid()).ToString('N').Substring(0,16))-01"} }
            $response = Invoke-WebRequest @arguments
            if ($response.StatusCode -ne 202) { throw 'Expected HTTP 202.' }
            $response.Content | ConvertFrom-Json
        } catch {
            if ($null -eq $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 409) { throw }
        }
    } 50
}
function RequestRow {
    param([string]$ServiceId, [string]$RequestId)
    (Api "/api/services/$ServiceId/checks?size=100").content |
        Where-Object { $_.probeRequestId -eq $RequestId } | Select-Object -First 1
}
function Healthy {
    param([string]$ServiceId)
    Poll "$ServiceId healthy without open incident" {
        $service = Api "/api/services/$ServiceId"
        if ($service.status -eq 'HEALTHY' -and -not $service.hasOpenIncident) { $service }
    } | Out-Null
}
function TraceSpans {
    param([object]$Trace)
    $batches = if ($Trace.PSObject.Properties.Name -contains 'batches') { $Trace.batches } else { $Trace.resourceSpans }
    foreach ($batch in $batches) {
        $scopes = if ($batch.PSObject.Properties.Name -contains 'scopeSpans') { $batch.scopeSpans } else { $batch.instrumentationLibrarySpans }
        foreach ($scope in $scopes) { foreach ($span in $scope.spans) { $span } }
    }
}
function CustomSeries {
    $series = @(Prom '{__name__=~"launchguard_.*"}')
    foreach ($item in $series) {
        foreach ($label in $item.metric.PSObject.Properties.Name) {
            if ($label -match '(?i)(service.?id|request.?id|deployment.?id|commit|sha|trace.?id|span.?id)') {
                throw "Forbidden custom metric label: $label"
            }
        }
    }
    $series
}
$ids = @{}
$fixtures = [Collections.Generic.List[string]]::new()
try {
    foreach ($service in @(& "$PSScriptRoot/register-demo-services.ps1" -BackendUrl $BackendUrl -Target Docker)) { $ids[$service.Name]=$service.Id }
    foreach ($url in @($PaymentUrl,$OrderUrl,$NotificationUrl)) {
        Invoke-RestMethod -Method Post "$url/admin/normal" -TimeoutSec 15 | Out-Null
        Invoke-RestMethod -Method Post "$url/admin/recover" -TimeoutSec 15 | Out-Null
    }
    foreach ($serviceId in $ids.Values) { Healthy $serviceId }
    foreach ($url in @($BackendUrl,$WorkerUrl)) {
        foreach ($path in @('/actuator/health','/actuator/health/readiness','/actuator/health/liveness')) {
            if ((Invoke-RestMethod "$url$path" -TimeoutSec 15).status -ne 'UP') { throw "Management health failed: $url$path" }
        }
        $scrape = (Invoke-WebRequest -UseBasicParsing "$url/actuator/prometheus" -TimeoutSec 15).Content
        if ($scrape -notmatch 'jvm_memory_used_bytes' -or $scrape -notmatch 'launchguard_probe_') { throw "Missing scrape metrics: $url" }
    }
    $targets = Poll 'two Prometheus targets UP' {
        $active = @((Invoke-RestMethod "$PrometheusUrl/api/v1/targets" -TimeoutSec 15).data.activeTargets)
        if ($active.Count -eq 2 -and @($active | Where-Object { $_.health -ne 'up' }).Count -eq 0) { $active }
    }
    if (@(Prom 'jvm_memory_used_bytes').Count -lt 2) { throw 'JVM metrics absent from Prometheus.' }
    $datasourceResponse = Invoke-RestMethod "$GrafanaUrl/api/datasources" -TimeoutSec 15
    $datasources = @($datasourceResponse)
    foreach ($uid in @('launchguard-prometheus','launchguard-tempo')) {
        if (-not ($datasources | Where-Object { $_.uid -eq $uid })) { throw "Missing datasource: $uid" }
    }
    $dashboard = Invoke-RestMethod "$GrafanaUrl/api/dashboards/uid/launchguard-platform" -TimeoutSec 15
    if (@($dashboard.dashboard.panels).Count -lt 10) { throw 'Provisioned dashboard is incomplete.' }
    Invoke-WebRequest -UseBasicParsing "$TempoUrl/ready" -TimeoutSec 15 | Out-Null
    Invoke-WebRequest -UseBasicParsing $CollectorHealthUrl -TimeoutSec 15 | Out-Null

    Write-Host 'Payment failure/recovery must create exactly one open and one resolve transition.'
    $openedBefore = Metric 'launchguard_incidents_opened_total'
    $resolvedBefore = Metric 'launchguard_incidents_resolved_total'
    Invoke-RestMethod -Method Post "$PaymentUrl/admin/fail" -TimeoutSec 15 | Out-Null
    $incident = Poll 'automatic payment incident OPEN' {
        $service = Api "/api/services/$($ids['payment-service'])"
        if ($service.hasOpenIncident) { Api "/api/services/$($ids['payment-service'])/incidents/current" }
    }
    Poll 'incident-open metric scraped' { if ((Metric 'launchguard_incidents_opened_total') -eq $openedBefore + 1) { $true } } | Out-Null
    if ((Metric 'launchguard_current_open_incidents') -lt 1) { throw 'Open-incident gauge failed.' }
    Invoke-RestMethod -Method Post "$PaymentUrl/admin/recover" -TimeoutSec 15 | Out-Null
    Healthy $ids['payment-service']
    Poll 'incident-resolved metric scraped' { if ((Metric 'launchguard_incidents_resolved_total') -eq $resolvedBefore + 1) { $true } } | Out-Null
    $resolved = Api "/api/services/$($ids['payment-service'])/incidents/$($incident.id)"
    if ($resolved.status -ne 'RESOLVED') { throw 'Incident did not resolve.' }

    Write-Host 'Slow notification trace and independent payment/order completion.'
    Invoke-RestMethod -Method Post "$NotificationUrl/admin/slow?delayMs=5000" -TimeoutSec 15 | Out-Null
    $traceId = ([guid]::NewGuid()).ToString('N')
    $slowQueued = Queued $ids['notification-service'] $traceId
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $paymentQueued = Queued $ids['payment-service']
    $orderQueued = Queued $ids['order-service']
    $fast = Poll 'both fast probes persisted before slow probe' {
        $payment = RequestRow $ids['payment-service'] $paymentQueued.requestId
        $order = RequestRow $ids['order-service'] $orderQueued.requestId
        if ($payment -and $order) { @{payment=$payment;order=$order} }
    } 50
    $fastPersistedMs = $timer.ElapsedMilliseconds
    if (RequestRow $ids['notification-service'] $slowQueued.requestId) { throw 'Slow completed before independence check.' }
    $slow = Poll 'slow probe persisted' { RequestRow $ids['notification-service'] $slowQueued.requestId } 100
    $slowPersistedMs = $timer.ElapsedMilliseconds
    if ($slow.status -ne 'HEALTHY' -or $slow.responseTimeMs -lt 5000) { throw 'Use an 8s deadline for the slow demo.' }
    Poll 'worker duration >= 5 seconds' { if ((Metric 'max(launchguard_probe_worker_duration_seconds_max{status="HEALTHY"})') -ge 5) { $true } } | Out-Null
    $durationMax = Metric 'max(launchguard_probe_worker_duration_seconds_max{status="HEALTHY"})'
    Invoke-RestMethod -Method Post "$NotificationUrl/admin/normal" -TimeoutSec 15 | Out-Null
    $trace = Poll 'complete slow trace in Tempo' {
        try { $candidate = Invoke-RestMethod "$TempoUrl/api/traces/$traceId" -Headers @{Accept='application/json'} -TimeoutSec 15 }
        catch { if ($null -ne $_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 404) { return }; throw }
        $names = @(TraceSpans $candidate | ForEach-Object { $_.name })
        $required = @('launchguard.probe.dispatch','launchguard.worker.process','launchguard.probe.http',
            'launchguard.result.consume','launchguard.result.persistence','launchguard.incident.evaluate',
            'launchguard.health-check.requests send','launchguard.health-check.requests process',
            'launchguard.health-check.results send','launchguard.health-check.results process')
        if (@($required | Where-Object { $names -notcontains $_ }).Count -eq 0) { $candidate }
    }
    $spans = @(TraceSpans $trace)
    $httpSpan = $spans | Where-Object { $_.name -eq 'launchguard.probe.http' } | Select-Object -First 1
    $httpSpanSeconds = ([decimal]$httpSpan.endTimeUnixNano - [decimal]$httpSpan.startTimeUnixNano) / 1000000000
    if ($httpSpanSeconds -lt 5) { throw 'Trace does not show the slow HTTP duration.' }
    $logLines = Invoke-Docker @('compose','logs','--no-color','--since','5m','backend','probe-worker')
    $correlatedLogs = @($logLines | Where-Object { $_ -match $slow.probeRequestId -and $_ -match $traceId } |
        ForEach-Object { $jsonStart=$_.IndexOf('{'); if ($jsonStart -ge 0) { $_.Substring($jsonStart) | ConvertFrom-Json } })
    if (@($correlatedLogs | Where-Object { $_.PSObject.Properties.Name -contains 'serviceId' -and $_.serviceId -eq $slow.serviceId -and $_.PSObject.Properties.Name -contains 'spanId' }).Count -lt 2) {
        throw 'Missing backend/worker request-service-trace-span log correlation.'
    }

    Write-Host 'Replay the persisted slow completion twice over actual Kafka.'
    $openedReplayBefore = Metric 'launchguard_incidents_opened_total'
    $resolvedReplayBefore = Metric 'launchguard_incidents_resolved_total'
    $ignoredBefore = Metric 'launchguard_probe_results_ignored_total{reason="duplicate"}'
    $event = @{eventVersion=1;requestId=$slow.probeRequestId;serviceId=$slow.serviceId;deploymentId=$slow.deploymentId
        status=$slow.status;httpStatus=$slow.httpStatus;responseTimeMs=$slow.responseTimeMs;errorMessage=$slow.errorMessage;checkedAt=$slow.checkedAt} | ConvertTo-Json -Compress
    $line = "$($slow.serviceId):$event"
    Invoke-Docker @('compose','exec','-T','kafka','/opt/kafka/bin/kafka-console-producer.sh',
        '--bootstrap-server','kafka:9092','--topic','launchguard.health-check.results',
        '--reader-property','parse.key=true','--reader-property','key.separator=:','--command-property','acks=all') "$line`n$line" | Out-Host
    Poll 'two duplicate outcomes scraped' { if ((Metric 'launchguard_probe_results_ignored_total{reason="duplicate"}') -ge $ignoredBefore + 2) { $true } } | Out-Null
    if ((Sql "SELECT count(*) FROM health_checks WHERE probe_request_id='$([guid]$slow.probeRequestId)'") -ne '1') { throw 'Duplicate database row.' }
    if ((Metric 'launchguard_incidents_opened_total') -ne $openedReplayBefore -or (Metric 'launchguard_incidents_resolved_total') -ne $resolvedReplayBefore) { throw 'Duplicate incident transition metric.' }

    Write-Host 'Eight distinct temporary services must not create service-ID metric series.'
    $beforeSeries = @(CustomSeries)
    $fixtureTag = ([guid]::NewGuid()).ToString('N')
    foreach ($index in 1..8) {
        $fixture = Api '/api/services' Post @{name="observability-$fixtureTag-$index";baseUrl='http://payment-service:8081';healthPath='/health'}
        $fixtures.Add([string]$fixture.id)
    }
    foreach ($fixtureId in $fixtures) {
        $queued = Queued $fixtureId
        Poll 'fixture check persisted' { RequestRow $fixtureId $queued.requestId } 50 | Out-Null
    }
    Poll 'registered-service gauge includes load fixtures' { if ((Metric 'launchguard_monitored_services') -ge $ids.Count + $fixtures.Count) { $true } } | Out-Null
    $afterSeries = @(CustomSeries)
    if ($afterSeries.Count -gt $beforeSeries.Count) { throw "Custom series grew across new identifiers: $($beforeSeries.Count) -> $($afterSeries.Count)" }
    $containers = @()
    foreach ($name in @('postgres','kafka','backend','probe-worker','payment-service','order-service','notification-service','prometheus','grafana','otel-collector','tempo')) {
        $containerId = ((Invoke-Docker @('compose','ps','-q',$name)) -join '').Trim()
        $state = ((Invoke-Docker @('inspect','--format','{{json .State}}',$containerId)) -join '' | ConvertFrom-Json)
        if (-not $state.Running) { throw "Not running: $name" }
        $health = if ($state.PSObject.Properties.Name -contains 'Health') { $state.Health.Status } else { 'running; HTTP readiness checked separately' }
        if ($state.PSObject.Properties.Name -contains 'Health' -and $health -ne 'healthy') { throw "Not healthy: $name ($health)" }
        $uid = $null
        if ($name -in @('backend','probe-worker','payment-service','order-service','notification-service')) {
            $uid = ((Invoke-Docker @('compose','exec','-T',$name,'id','-u')) -join '').Trim()
            if ($uid -eq '0') { throw "Application runs as root: $name" }
        }
        $containers += @{name=$name;health=$health;uid=$uid}
    }
    $evidence = [ordered]@{result='PASS';validatedAt=[DateTime]::UtcNow.ToString('o');serviceIds=$ids
        targets=$targets;datasources=$datasources;dashboardUid=$dashboard.dashboard.uid;containers=$containers
        incident=@{id=$incident.id;resolvedStatus=$resolved.status;openedBefore=$openedBefore;openedAfter=$openedReplayBefore;resolvedBefore=$resolvedBefore;resolvedAfter=$resolvedReplayBefore}
        slow=@{requestId=$slow.probeRequestId;responseTimeMs=$slow.responseTimeMs;workerDurationMaxSeconds=$durationMax;fastPersistedMs=$fastPersistedMs;slowPersistedMs=$slowPersistedMs;traceId=$traceId;httpSpanSeconds=$httpSpanSeconds}
        spans=$spans;correlatedLogs=$correlatedLogs;duplicate=@{requestId=$slow.probeRequestId;rows=1;ignoredBefore=$ignoredBefore;ignoredAfter=(Metric 'launchguard_probe_results_ignored_total{reason="duplicate"}');transitionsUnchanged=$true}
        cardinality=@{temporaryServices=$fixtures.Count;customSeriesBefore=$beforeSeries.Count;customSeriesAfter=$afterSeries.Count;labels=@($afterSeries | ForEach-Object { $_.metric.PSObject.Properties.Name } | Sort-Object -Unique)}
        database=(Sql "SELECT json_build_object('services',(SELECT count(*) FROM monitored_services),'checks',(SELECT count(*) FROM health_checks),'migrations',(SELECT json_agg(version ORDER BY installed_rank) FROM flyway_schema_history WHERE success))" | ConvertFrom-Json)
    }
    $evidenceDirectory = Split-Path -Parent $EvidencePath
    if (-not (Test-Path -LiteralPath $evidenceDirectory)) { New-Item -ItemType Directory -Path $evidenceDirectory | Out-Null }
    $evidence | ConvertTo-Json -Depth 25 | Set-Content -LiteralPath $EvidencePath -Encoding utf8
    Write-Host "OBSERVABILITY_GATE_OK; evidence=$EvidencePath; trace=$traceId; custom series=$($afterSeries.Count)"
} finally {
    foreach ($fixtureId in $fixtures) { Api "/api/services/$fixtureId" Delete | Out-Null }
    Invoke-RestMethod -Method Post "$PaymentUrl/admin/recover" -TimeoutSec 15 | Out-Null
    Invoke-RestMethod -Method Post "$NotificationUrl/admin/normal" -TimeoutSec 15 | Out-Null
}
