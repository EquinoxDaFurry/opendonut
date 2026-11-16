<#
.SYNOPSIS
    Creates or updates a local Velocity proxy installation for OpenDonut testing.

.DESCRIPTION
    Writes a velocity.toml configured for modern forwarding, installs the OpenDonut plugin, and copies the
    shared configuration and bridge secret into the plugin's data directory.

    This is for development and integration testing. A production proxy should be configured by hand, with the
    forwarding secret generated locally and never copied around in scripts.

.PARAMETER ProxyDirectory
    Where to build the proxy installation. Created when missing.

.PARAMETER ConfigFile
    The OpenDonut configuration. Defaults to config/opendonut.yml in the repository.

.PARAMETER SecretFile
    The bridge secret. Defaults to secrets/bridge-secret.txt next to the configuration.

.PARAMETER Port
    The port the proxy listens on. Defaults to 25565.

.PARAMETER WhatIf
    Print what would happen without writing anything.

.EXAMPLE
    .\tools\install-to-proxy.ps1 -ProxyDirectory 'E:\OpenDonut\tests\runtime\velocity' -WhatIf
#>
[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory = $true)]
    [string] $ProxyDirectory,

    [string] $ConfigFile,
    [string] $SecretFile,
    [string] $VelocityJar,
    [int] $Port = 25565,

    # Proceed even when the proxy directory path is known to break Velocity's own jar handling.
    [switch] $Force

    # The Velocity jar itself is not built here; it is downloaded (see docs/installation.md) and simply placed
    # in the proxy directory, either by the administrator or by passing -VelocityJar.
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

<#
    Writes a text file as UTF-8 *without* a byte order mark.

    Velocity's TOML reader is strict, and PowerShell 5.1's `Set-Content -Encoding UTF8` prepends a BOM. Writing
    through .NET with an explicit no-BOM encoder keeps both PowerShell editions honest.
#>
function Write-TextNoBom {
    param(
        [Parameter(Mandatory = $true)] [string] $Path,
        [Parameter(Mandatory = $true)] [string] $Text
    )
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Text, $encoding)
}

$configPath = if ($ConfigFile) { $ConfigFile } else { Join-Path $repoRoot 'config\opendonut.yml' }
if (-not (Test-Path -LiteralPath $configPath)) {
    throw "configuration not found at $configPath"
}
$secretPath = if ($SecretFile) { $SecretFile } else { Join-Path (Split-Path -Parent $configPath) 'secrets\bridge-secret.txt' }
if (-not (Test-Path -LiteralPath $secretPath)) {
    throw "bridge secret not found at $secretPath. Generate it with the 'secret' CLI command first."
}

if (-not (Test-Path -LiteralPath $ProxyDirectory)) {
    if ($PSCmdlet.ShouldProcess($ProxyDirectory, 'create directory')) {
        New-Item -ItemType Directory -Force -Path $ProxyDirectory | Out-Null
    }
}
$ProxyDirectory = (Resolve-Path -LiteralPath $ProxyDirectory).Path
Write-Host "OpenDonut proxy install"
Write-Host "  proxy directory : $ProxyDirectory"
Write-Host "  listening port  : $Port"

# Velocity 4.3.1 refuses to start when its own directory path contains BOTH a space and an exclamation mark:
# it fails with 'ProviderNotFoundException: Provider "jar" not found' while opening its own jar, which gives no
# hint about the real cause. Verified by experiment on 2026-11-17: a space alone is fine, an '!' alone is fine,
# the two together are not. Refusing early is far kinder than letting an administrator debug a misleading
# stack trace. See docs/troubleshooting.md.
if ($ProxyDirectory -match ' ' -and $ProxyDirectory -match '!') {
    $suggestion = Join-Path $env:LOCALAPPDATA 'OpenDonut\velocity'
    if (-not $Force) {
        throw "the proxy directory '$ProxyDirectory' contains both a space and an exclamation mark. Velocity " +
              "4.3.1 cannot start from such a path (it fails with 'ProviderNotFoundException: Provider ""jar"" " +
              "not found'). Use a plain path such as '$suggestion', or pass -Force if you have verified that " +
              "your Velocity build is not affected."
    }
    Write-Warning "proceeding anyway because -Force was passed, but Velocity is expected to fail to start."
}

# --- read the server list out of the OpenDonut configuration ---------------
# A tiny YAML reader would be a liability here; the CLI already knows how to parse the file, so ask it and use
# the routing table output. That keeps the script honest: it configures velocity.toml from the same data the
# plugin routes with.
$toolsJar = Get-ChildItem -Path (Join-Path $repoRoot 'common\build\libs') -Filter 'opendonut-tools-*.jar' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $toolsJar) {
    throw "the OpenDonut CLI jar is missing. Run './gradlew :common:toolsJar' first."
}

$report = & java -jar $toolsJar.FullName report $configPath 2>&1
$exitCode = $LASTEXITCODE
if ($exitCode -ne 0) {
    Write-Host $report
    throw "the configuration at $configPath is not usable (exit code $exitCode); fix it before configuring a proxy"
}

# Lines look like: '  [0] west  127.0.0.1:25566  X=[-100000,0) ...'
$servers = @()
foreach ($line in $report) {
    if ($line -match '^\s*\[\d+\]\s+(\S+)\s+(\S+:\d+)\s+X=') {
        $servers += [pscustomobject]@{ Id = $Matches[1]; Address = $Matches[2] }
    }
}
if ($servers.Count -eq 0) {
    throw "no servers were found in the report output; is the configuration complete?"
}
Write-Host "  backends        : $($servers.Count) ($(($servers | ForEach-Object { $_.Id }) -join ', '))"

# --- forwarding secret -----------------------------------------------------
# Generated once, then reused. It is a different secret from the OpenDonut bridge secret: this one authenticates
# the proxy to the backends for login, the bridge secret authenticates OpenDonut's own messages.
$forwardingSecret = Join-Path $ProxyDirectory 'forwarding.secret'
if (-not (Test-Path -LiteralPath $forwardingSecret)) {
    if ($PSCmdlet.ShouldProcess($forwardingSecret, 'generate the Velocity forwarding secret')) {
        # RNGCryptoServiceProvider rather than RandomNumberGenerator.Fill: this script has to run under Windows
        # PowerShell 5.1 as well, where the static Fill method does not exist.
        $bytes = New-Object byte[] 12
        $rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
        try {
            $rng.GetBytes($bytes)
        }
        finally {
            $rng.Dispose()
        }
        Write-TextNoBom -Path $forwardingSecret -Text ([Convert]::ToBase64String($bytes))
        Write-Host "  generated       : forwarding.secret (copy its value into each backend's FabricProxy-Lite config)"
    }
}
else {
    Write-Host "  kept existing   : forwarding.secret"
}

# --- velocity.toml ---------------------------------------------------------
$velocityToml = @"
# Generated by OpenDonut's tools/install-to-proxy.ps1 for local testing.
# A production proxy should be configured by hand.

config-version = "2.7"
bind = "0.0.0.0:$Port"
motd = "<yellow>OpenDonut</yellow> <gray>test proxy"
show-max-players = 100
online-mode = true
prevent-client-proxy-connections = false
announce-forge = false
kick-existing-players = false
ping-passthrough = "DISABLED"

# Modern forwarding: the backend trusts identities signed by this proxy.
# 'legacy' or 'none' would let any client claim any identity - do not use them.
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"

enable-player-address-logging = true

[servers]
"@

$try = $servers[0].Id
foreach ($server in $servers) {
    $velocityToml += "`n$($server.Id) = `"$($server.Address)`""
}

$velocityToml += @"

try = ["$try"]

[forced-hosts]
[advanced]
compression-level = -1
compression-threshold = 256
login-ratelimit = 3000
connection-timeout = 5000
read-timeout = 30000
tcp-fast-open = false
proxy-protocol = false
query-enabled = false
query-port = 25577
query-show-plugins = false
show-ping-requests = false
failover-on-unexpected-server-disconnect = true
announce-proxy-commands = true
log-command-executions = true
log-player-connections = true
accepts-transfers = true
scheduler-impl = "adaptive"
bungee-plugin-message-channel = true
"@

if ($PSCmdlet.ShouldProcess((Join-Path $ProxyDirectory 'velocity.toml'), 'write')) {
    Write-TextNoBom -Path (Join-Path $ProxyDirectory 'velocity.toml') -Text $velocityToml
    Write-Host "  wrote           : velocity.toml (modern forwarding, $($servers.Count) servers, try=$try)"
}

# --- plugin, configuration and secret --------------------------------------
$pluginJar = Get-ChildItem -Path (Join-Path $repoRoot 'velocity-plugin\build\libs') -Filter 'opendonut-velocity-*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike '*-plain.jar' -and $_.Name -notlike '*-sources.jar' } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $pluginJar) {
    throw "the OpenDonut plugin jar is missing. Run './gradlew :velocity-plugin:build' first."
}

$pluginsDirectory = Join-Path $ProxyDirectory 'plugins'
$dataDirectory = Join-Path $pluginsDirectory 'opendonut'
foreach ($directory in @($pluginsDirectory, $dataDirectory, (Join-Path $dataDirectory 'secrets'))) {
    if (-not (Test-Path -LiteralPath $directory)) {
        if ($PSCmdlet.ShouldProcess($directory, 'create directory')) {
            New-Item -ItemType Directory -Force -Path $directory | Out-Null
        }
    }
}

$pluginTarget = Join-Path $pluginsDirectory $pluginJar.Name
if ($PSCmdlet.ShouldProcess($pluginTarget, 'install plugin')) {
    # Remove an older build so two versions of the plugin cannot be loaded at once.
    Get-ChildItem -Path $pluginsDirectory -Filter 'opendonut-velocity-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -ne $pluginTarget } |
        ForEach-Object {
            Write-Host "  removing older plugin: $($_.Name)"
            Remove-Item -LiteralPath $_.FullName
        }
    Copy-Item -LiteralPath $pluginJar.FullName -Destination $pluginTarget -Force
    Write-Host "  installed       : plugins\$($pluginJar.Name)"
}
if ($PSCmdlet.ShouldProcess($dataDirectory, 'install configuration and secret')) {
    Copy-Item -LiteralPath $configPath -Destination (Join-Path $dataDirectory 'opendonut.yml') -Force
    Copy-Item -LiteralPath $secretPath -Destination (Join-Path $dataDirectory 'secrets\bridge-secret.txt') -Force
    Write-Host "  installed       : plugins\opendonut\opendonut.yml and its secret"
}

$seedPath = Join-Path (Split-Path -Parent $configPath) 'seed.properties'
if (Test-Path -LiteralPath $seedPath) {
    if ($PSCmdlet.ShouldProcess($dataDirectory, 'install seed.properties')) {
        Copy-Item -LiteralPath $seedPath -Destination (Join-Path $dataDirectory 'seed.properties') -Force
        Write-Host "  installed       : plugins\opendonut\seed.properties"
    }
}

# --- the proxy jar itself --------------------------------------------------
$proxyJar = $null
if ($VelocityJar) {
    if (-not (Test-Path -LiteralPath $VelocityJar)) {
        throw "the Velocity jar was not found at $VelocityJar"
    }
    $proxyJar = $VelocityJar
}
else {
    # Look in the obvious local places: the proxy directory, and the downloads folder the test environment uses.
    $candidates = @(
        (Join-Path $ProxyDirectory 'velocity-*.jar'),
        (Join-Path (Join-Path $repoRoot 'tests\runtime\downloads') 'velocity-*.jar')
    )
    foreach ($pattern in $candidates) {
        $found = Get-ChildItem -Path $pattern -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
        if ($found) { $proxyJar = $found.FullName; break }
    }
}
if ($proxyJar) {
    $destination = Join-Path $ProxyDirectory (Split-Path -Leaf $proxyJar)
    if ((Resolve-Path -LiteralPath $proxyJar).Path -eq (Resolve-Path -LiteralPath $ProxyDirectory).Path + [System.IO.Path]::DirectorySeparatorChar + (Split-Path -Leaf $proxyJar)) {
        Write-Host "  present         : $(Split-Path -Leaf $proxyJar)"
    }
    elseif ($PSCmdlet.ShouldProcess($destination, 'install Velocity')) {
        Copy-Item -LiteralPath $proxyJar -Destination $destination -Force
        Write-Host "  installed       : $(Split-Path -Leaf $proxyJar)"
    }
}
else {
    Write-Warning "no Velocity jar found. Download it (docs/installation.md) into $ProxyDirectory, or pass " +
                  "-VelocityJar."
}

Write-Host ""
Write-Host "Start it with:  java -jar velocity-4.3.1-45.jar       (Velocity needs Java 25 or newer)"
Write-Host "Then copy the same configuration and secret to every backend (tools\install-to-backend.ps1)."
