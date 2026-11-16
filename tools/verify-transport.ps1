<#
.SYNOPSIS
    Checks the Minecraft custom-payload boundary between the OpenDonut mod and the Velocity plugin.

.DESCRIPTION
    Runs tools/transport-check/TransportCheck.java against Velocity's own PluginMessagePacket class and the
    freshly built `common` jar. It proves, without needing a Minecraft client, that a frame survives the
    payload boundary byte for byte in both directions, and that the length-prefixed framing which once caused
    every transfer to fail with BAD_MAGIC is still rejected (and correctly diagnosed).

    Use it after any change to the payload codec, the bridge protocol, or the Velocity dependency.

.PARAMETER VelocityJar
    The Velocity proxy jar to check against. Defaults to the proxy installation in
    %LOCALAPPDATA%\OpenDonut\velocity, then to any velocity*.jar under tests\runtime\downloads.

.PARAMETER KeepSources
    Keep the generated classes in the system temp directory instead of deleting them.

.EXAMPLE
    .\tools\verify-transport.ps1
#>
[CmdletBinding()]
param(
    [string] $VelocityJar,
    [switch] $KeepSources
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

# --- locate the two jars the check needs ---------------------------------
function Find-First {
    param([string[]] $Patterns, [string] $Label)
    foreach ($pattern in $Patterns) {
        $found = Get-ChildItem -Path $pattern -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notlike '*-plain*' -and $_.Name -notlike '*-sources*' } |
            Sort-Object Name -Descending | Select-Object -First 1
        if ($found) { return $found.FullName }
    }
    throw "$Label not found. Looked in:`n  $($Patterns -join "`n  ")"
}

if (-not $VelocityJar) {
    $VelocityJar = Find-First -Label 'the Velocity jar' -Patterns @(
        (Join-Path $env:LOCALAPPDATA 'OpenDonut\velocity\velocity*.jar'),
        (Join-Path $repoRoot 'tests\runtime\downloads\velocity*.jar')
    )
}
$commonJar = Find-First -Label 'the built common jar (run .\gradlew.bat :common:jar first)' -Patterns @(
    (Join-Path $repoRoot 'common\build\libs\common-*.jar')
)

Write-Host "transport check"
Write-Host "  velocity : $VelocityJar"
Write-Host "  common   : $commonJar"

# --- compile and run -----------------------------------------------------
$work = Join-Path ([System.IO.Path]::GetTempPath()) ("opendonut-transport-" + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force -Path $work | Out-Null

try {
    $source = Join-Path $PSScriptRoot 'transport-check\TransportCheck.java'
    Write-Host "  compiling: $source"
    & javac -nowarn -cp "$VelocityJar;$commonJar" -d $work $source
    if ($LASTEXITCODE -ne 0) {
        throw "compilation failed (exit $LASTEXITCODE). Velocity's internal packet classes may have changed " +
              "in this release; re-read them with javap and update TransportCheck.java."
    }

    Write-Host ""
    & java -cp "$work;$VelocityJar;$commonJar" TransportCheck
    $exit = $LASTEXITCODE
}
finally {
    if (-not $KeepSources) {
        Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
    }
}

Write-Host ""
if ($exit -eq 0) {
    Write-Host "The payload boundary is consistent: the mod and the proxy carry frames verbatim."
}
else {
    Write-Warning "The payload boundary check FAILED. Do not ship this build: transfers will be refused."
}
exit $exit
