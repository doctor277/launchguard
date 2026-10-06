Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$contractState = [pscustomobject]@{ Captured = $null; FailHttp = $false }
function Invoke-RestMethod {
    param($Method, $Uri, $Headers, $ContentType, $Body, $TimeoutSec)
    if ($contractState.FailHttp) { throw 'Simulated HTTP 409' }
    $contractState.Captured = @{Method=$Method; Uri=$Uri; Headers=$Headers; ContentType=$ContentType; Body=($Body | ConvertFrom-Json)}
    [pscustomobject]@{ id='493ef769-bb80-4a8f-918a-46b1033fa52b'; version=$contractState.Captured.Body.version
        source='CI'; externalId=$contractState.Captured.Body.externalId }
}
$arguments = @{ LaunchGuardUrl='http://localhost:8080/'; ServiceId='0dd8e2a6-d390-4e93-a777-8798d11868c0'
    Version='0.7.0'; CommitSha='a921fc7'; Environment='local'; ImageTag='sha-a921fc7'; ExternalId='run-1'
    Description="A quoted `"release`"`nwith a newline"; AccessToken='test-token' }
& "$PSScriptRoot/report-deployment.ps1" @arguments | Out-Null
if ($contractState.Captured.Method -ne 'Post' -or $contractState.Captured.ContentType -ne 'application/json' -or
    $contractState.Captured.Uri -ne 'http://localhost:8080/api/services/0dd8e2a6-d390-4e93-a777-8798d11868c0/deployments') {
    throw 'HTTP reporting contract mismatch.'
}
if ($contractState.Captured.Headers.Authorization -ne 'Bearer test-token') { throw 'Bearer token was not propagated.' }
foreach ($pair in @{version=$arguments.Version; commitSha=$arguments.CommitSha; source='CI'; environment='local'
    imageTag=$arguments.ImageTag; externalId=$arguments.ExternalId; description=$arguments.Description}.GetEnumerator()) {
    if ($contractState.Captured.Body.($pair.Key) -cne $pair.Value) { throw "JSON contract mismatch: $($pair.Key)" }
}
$withoutDescription = $arguments.Clone()
$withoutDescription.Remove('Description')
& "$PSScriptRoot/report-deployment.ps1" @withoutDescription | Out-Null
if ($null -ne $contractState.Captured.Body.description) { throw 'Omitted description must be JSON null for stable retries.' }
foreach ($invalid in @(@{CommitSha='not-hex'}, @{ExternalId=' '}, @{Environment=' '}, @{ImageTag=' '},
    @{ServiceId=[guid]::Empty}, @{LaunchGuardUrl='file:///tmp/report'}, @{LaunchGuardUrl='http://user:secret@localhost'},
    @{LaunchGuardUrl='http://launchguard.example.com'})) {
    $bad = $arguments.Clone()
    foreach ($key in $invalid.Keys) { $bad[$key] = $invalid[$key] }
    $rejected = $false
    try { & "$PSScriptRoot/report-deployment.ps1" @bad | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw 'Invalid argument was accepted.' }
}
$rejected = $false
try {
    Get-LaunchGuardAccessToken -AccessToken '' -TokenEndpoint 'http://id.example.com/token' `
        -ClientId 'test-client' -ClientSecret 'test-secret' | Out-Null
} catch { $rejected = $true }
if (-not $rejected) { throw 'External HTTP OIDC token endpoint was accepted.' }
$contractState.FailHttp = $true
$rejected = $false
try { & "$PSScriptRoot/report-deployment.ps1" @arguments | Out-Null } catch { $rejected = $true }
if (-not $rejected) { throw 'HTTP error was swallowed.' }
Write-Host 'REPORT_CONTRACT_OK: JSON escaping, optional description, required arguments, secure transport, and HTTP failure propagation.'
