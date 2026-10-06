[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$LaunchGuardUrl,
    [Parameter(Mandatory)][guid]$ServiceId,
    [Parameter(Mandatory)][ValidatePattern('(?s).*\S.*')][ValidateLength(1,100)][string]$Version,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-fA-F]{7,64}$')][string]$CommitSha,
    [Parameter(Mandatory)][ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$')][string]$Environment,
    [Parameter(Mandatory)][ValidatePattern('(?s).*\S.*')][ValidateLength(1,512)][string]$ImageTag,
    [Parameter(Mandatory)][ValidatePattern('(?s).*\S.*')][ValidateLength(1,200)][string]$ExternalId,
    [ValidateLength(0,1000)][string]$Description,
    [string]$AccessToken
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/launchguard-auth.ps1"
$baseUri = $null
if (-not [Uri]::TryCreate($LaunchGuardUrl, [UriKind]::Absolute, [ref]$baseUri) -or
    $baseUri.Scheme -notin @('http','https') -or $baseUri.UserInfo -or $baseUri.Query -or $baseUri.Fragment) {
    throw 'LaunchGuardUrl must be an absolute HTTP(S) URL without credentials, query, or fragment.'
}
Assert-LaunchGuardSecureTransport -Uri $baseUri -Name 'LaunchGuardUrl'
if ($ServiceId -eq [guid]::Empty) { throw 'ServiceId must not be the empty UUID.' }
$AccessToken = Get-LaunchGuardAccessToken -AccessToken $AccessToken
$authorization = New-LaunchGuardAuthorizationHeader -AccessToken $AccessToken
$body = @{
    version = $Version.Trim(); commitSha = $CommitSha; source = 'CI'
    environment = $Environment; imageTag = $ImageTag; externalId = $ExternalId
    description = if ($PSBoundParameters.ContainsKey('Description')) { $Description } else { $null }
} | ConvertTo-Json -Depth 3 -Compress
$uri = '{0}/api/services/{1}/deployments' -f $LaunchGuardUrl.TrimEnd('/'), $ServiceId
# No retries here: callers may retry with the same external ID and identical payload.
# HTTP/network errors are terminating, giving pwsh -File a non-zero exit code.
$deployment = Invoke-RestMethod -Method Post -Uri $uri -Headers $authorization -ContentType 'application/json' -Body $body -TimeoutSec 30
if (-not $deployment.id -or $deployment.version -ne $Version.Trim() -or $deployment.externalId -ne $ExternalId) {
    throw 'LaunchGuard returned an invalid deployment response.'
}
Write-Host "Deployment $($deployment.id) version=$($deployment.version) source=$($deployment.source)"
$deployment
