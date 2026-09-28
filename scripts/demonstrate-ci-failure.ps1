[CmdletBinding()]
param([string]$WslDistribution)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
# Feed invalid configuration through stdin: no source files, containers, or volumes are changed.
$broken = "services:`n  broken:`n    thisIsNotAComposeProperty: true"
try {
    # Windows PowerShell 5.1 wraps native stderr in ErrorRecord; inspect it as expected failure data.
    $ErrorActionPreference = 'Continue'
    if ($WslDistribution) {
        $output = $broken | & wsl.exe -d $WslDistribution -- docker compose -f - config --quiet 2>&1
    } else { $output = $broken | & docker compose -f - config --quiet 2>&1 }
    $exitCode = $LASTEXITCODE
} finally { $ErrorActionPreference = 'Stop' }
if ($exitCode -eq 0) { throw 'The CI configuration gate accepted a broken Compose file.' }
if (($output -join "`n") -notmatch 'thisIsNotAComposeProperty') {
    throw "Expected schema validation failure, not an unrelated Docker failure: $output"
}
Write-Host "CI_FAILURE_DEMO_OK: invalid Compose configuration exited $exitCode, so subsequent build/delivery steps cannot run."
