[CmdletBinding()]
param(
    [string]$BackendUrl = 'http://localhost:8080',
    [ValidateSet('Docker', 'Local')][string]$Target = 'Docker'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$BackendUrl = $BackendUrl.TrimEnd('/')
# Assign first so PowerShell 5.1 enumerates JSON arrays correctly, including [].
$existing = Invoke-RestMethod -Uri "$BackendUrl/api/services" -TimeoutSec 15

foreach ($demo in @(
    @{ Name = 'payment-service'; Port = 8081 },
    @{ Name = 'order-service'; Port = 8082 },
    @{ Name = 'notification-service'; Port = 8083 }
)) {
    $targetHost = if ($Target -eq 'Docker') { $demo.Name } else { 'localhost' }
    $baseUrl = 'http://{0}:{1}' -f $targetHost, $demo.Port
    $matches = @($existing | Where-Object { $_.name -eq $demo.Name })
    if ($matches.Count -gt 1) { throw "Multiple services named $($demo.Name); resolve this before bootstrap." }
    if ($matches.Count -eq 1) {
        $service = $matches[0]
    } else {
        $body = @{ name = $demo.Name; baseUrl = $baseUrl; healthPath = '/health' } | ConvertTo-Json
        try {
            $service = Invoke-RestMethod -Method Post -Uri "$BackendUrl/api/services" -ContentType 'application/json' -Body $body -TimeoutSec 15
        } catch {
            # Another bootstrap may have registered this name concurrently.
            if ($null -eq $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 409) { throw }
            $fresh = Invoke-RestMethod -Uri "$BackendUrl/api/services" -TimeoutSec 15
            $service = $fresh | Where-Object { $_.name -eq $demo.Name } | Select-Object -First 1
            if ($null -eq $service) { throw }
        }
    }
    if ($service.baseUrl.TrimEnd('/') -ne $baseUrl -or $service.healthPath -ne '/health') {
        throw "$($demo.Name) already targets $($service.baseUrl)$($service.healthPath), expected $baseUrl/health. No data was overwritten. Choose the correct Target or resolve the registration explicitly."
    }
    Write-Host "Registered/reused $($demo.Name): $($service.id) -> $baseUrl"
    [pscustomobject]@{ Name = $demo.Name; Id = $service.id; BaseUrl = $baseUrl }
}
