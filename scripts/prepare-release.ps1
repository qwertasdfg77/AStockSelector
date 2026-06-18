param(
    [Parameter(Mandatory = $true)]
    [string]$VersionName,

    [Parameter(Mandatory = $true)]
    [int]$VersionCode,

    [string]$PreviousVersion,

    [string[]]$Notes = @()
)

$ErrorActionPreference = "Stop"

$script = Join-Path $PSScriptRoot "prepare-release.py"
$arguments = @($script, "--version-name", $VersionName, "--version-code", "$VersionCode")

if (-not [string]::IsNullOrWhiteSpace($PreviousVersion)) {
    $arguments += @("--previous-version", $PreviousVersion)
}

foreach ($note in $Notes) {
    $arguments += @("--note", $note)
}

python @arguments
exit $LASTEXITCODE
