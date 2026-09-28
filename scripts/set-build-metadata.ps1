Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    $sha = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $sha -notmatch '^[0-9a-f]{40}$') { throw 'Cannot identify the build commit.' }
    [xml]$pom = Get-Content -Raw -LiteralPath (Join-Path $repoRoot 'pom.xml')
    $version = [string]$pom.project.version
    if ($version -notmatch '^[A-Za-z0-9._-]+$') { throw 'Invalid application version.' }
    $values = @{
        APP_VERSION=$version; GIT_SHA=$sha; BUILD_TIMESTAMP=[DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
        IMAGE_TAG="sha-$sha"
    }
    foreach ($pair in $values.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($pair.Key, $pair.Value, 'Process')
        if ($env:GITHUB_ENV) { Add-Content -LiteralPath $env:GITHUB_ENV -Value "$($pair.Key)=$($pair.Value)" -Encoding utf8 }
    }
    Write-Host "Build version=$version revision=$sha tag=$($values.IMAGE_TAG) created=$($values.BUILD_TIMESTAMP)"
    if (& git status --porcelain) { Write-Warning 'Dirty worktree: the Git SHA identifies HEAD, not uncommitted modifications.' }
} finally { Pop-Location }
