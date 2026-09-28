[CmdletBinding()]
param([string]$Root = (Split-Path -Parent $PSScriptRoot))
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$modules = @('monitoring-events','probe-worker','backend','demo-services/payment-service',
    'demo-services/order-service','demo-services/notification-service')
$total = 0
$backendNames = @()
foreach ($module in $modules) {
    $reports = @(Get-ChildItem -LiteralPath (Join-Path $Root "$module/target/surefire-reports") -Filter 'TEST-*.xml')
    if ($reports.Count -eq 0) { throw "No Surefire reports for $module. Tests did not run." }
    foreach ($report in $reports) {
        [xml]$xml = Get-Content -Raw -LiteralPath $report.FullName
        $suite = $xml.testsuite
        if ([int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0) {
            throw "Test gate failed: $($suite.name) failures=$($suite.failures) errors=$($suite.errors) skipped=$($suite.skipped)"
        }
        $total += [int]$suite.tests
        if ($module -eq 'backend') { $backendNames += [string]$suite.name }
    }
}
foreach ($required in @('PersistenceIntegrationTest','KafkaMonitoringIntegrationTest')) {
    if (-not ($backendNames | Where-Object { $_.EndsWith(".$required") })) { throw "Required real integration suite missing: $required" }
}
if ($total -lt 125) { throw "Expected at least the V0.7 baseline of 125 tests, found $total." }
Write-Host "TEST_GATE_OK tests=$total failures=0 errors=0 skipped=0; PostgreSQL and Kafka suites present."
