function Assert-LaunchGuardSecureTransport {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][Uri]$Uri,
        [Parameter(Mandatory)][string]$Name
    )
    if ($Uri.Scheme -eq 'http' -and -not $Uri.IsLoopback) {
        throw "$Name must use HTTPS unless it targets a loopback address."
    }
}

function Get-LaunchGuardAccessToken {
    [CmdletBinding()]
    param(
        [string]$AccessToken = $env:LAUNCHGUARD_ACCESS_TOKEN,
        [string]$TokenEndpoint = $(if ($env:OIDC_TOKEN_ENDPOINT) { $env:OIDC_TOKEN_ENDPOINT } else { 'http://localhost:8085/realms/launchguard/protocol/openid-connect/token' }),
        [string]$ClientId = $(if ($env:OIDC_AUTOMATION_CLIENT_ID) { $env:OIDC_AUTOMATION_CLIENT_ID } else { 'launchguard-automation' }),
        [string]$ClientSecret = $(if ($env:OIDC_AUTOMATION_CLIENT_SECRET) { $env:OIDC_AUTOMATION_CLIENT_SECRET } else { 'local-automation-secret-demo-only' })
    )
    if ($AccessToken) { return $AccessToken }
    $tokenUri = $null
    if (-not [Uri]::TryCreate($TokenEndpoint, [UriKind]::Absolute, [ref]$tokenUri) -or
        $tokenUri.Scheme -notin @('http','https') -or $tokenUri.UserInfo -or $tokenUri.Fragment) {
        throw 'OIDC token endpoint must be an absolute HTTP(S) URL without credentials or fragment.'
    }
    Assert-LaunchGuardSecureTransport -Uri $tokenUri -Name 'OIDC token endpoint'
    if (-not $ClientId -or -not $ClientSecret) {
        throw 'OIDC automation client ID and secret are required when no access token is supplied.'
    }
    $response = Invoke-RestMethod -Method Post -Uri $TokenEndpoint -ContentType 'application/x-www-form-urlencoded' `
        -Body @{ grant_type = 'client_credentials'; client_id = $ClientId; client_secret = $ClientSecret } -TimeoutSec 15
    if (-not $response.access_token) { throw 'OIDC provider did not return an access token.' }
    return [string]$response.access_token
}

function New-LaunchGuardAuthorizationHeader {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$AccessToken)
    return @{ Authorization = "Bearer $AccessToken" }
}
