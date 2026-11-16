<#
.SYNOPSIS
    Installs OpenDonut into a Fabric backend server directory.

.DESCRIPTION
    Copies the OpenDonut mod, the shared configuration and the bridge secret into a server
    directory, and makes the two server.properties changes the mod requires.

    Every change to an existing file is backed up first, and the script refuses to overwrite a
    backup it did not create. It never touches a world folder.

    This script is deliberately conservative: it is meant to be safe to run against a server that
    already has players' builds in it.

.PARAMETER ServerDirectory
    The Fabric server directory, for example "E:\servers\Server1".

.PARAMETER ConfigFile
    The OpenDonut configuration to install. Defaults to config/opendonut.yml in the repository.

.PARAMETER SecretFile
    The bridge secret to install. Defaults to secrets/bridge-secret.txt next to the configuration.

.PARAMETER ModJar
    The OpenDonut mod jar. Defaults to the newest build in fabric-mod/build/libs.

.PARAMETER SkipMods
    Do not copy mod jars. Use when the mods are managed some other way.

.PARAMETER WhatIf
    Print what would happen without changing anything.

.EXAMPLE
    .\tools\install-to-backend.ps1 -ServerDirectory 'E:\servers\Server1' -WhatIf
    .\tools\install-to-backend.ps1 -ServerDirectory 'E:\servers\Server1'
#>
[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory = $true)]
    [string] $ServerDirectory,

    [string] $ConfigFile,
    [string] $SecretFile,
    [string] $ModJar,
    [switch] $SkipMods
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

function Write-Step {
    param([string] $Message)
    Write-Host "  $Message"
}

<#
    Writes a text file as UTF-8 *without* a byte order mark.

    PowerShell 5.1's `Set-Content -Encoding UTF8` prepends a BOM, and the readers on the other end of these
    files are strict: FabricProxy-Lite's TOML parser rejects a BOM outright ("Key is not followed by an equals
    sign on line 1"), and a BOM in server.properties turns the first key into "\uFEFF<key>". Writing through
    .NET with an explicit no-BOM encoder avoids the whole class of problem, on both PowerShell editions.
#>
function Write-TextNoBom {
    param(
        [Parameter(Mandatory = $true)] [string] $Path,
        [Parameter(Mandatory = $true)] [string[]] $Lines
    )
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, (($Lines -join [Environment]::NewLine) + [Environment]::NewLine), $encoding)
}

function Copy-WithBackup {
    param(
        [Parameter(Mandatory = $true)] [string] $Source,
        [Parameter(Mandatory = $true)] [string] $Destination
    )

    if (-not (Test-Path -LiteralPath $Source)) {
        throw "source file not found: $Source"
    }

    $destinationDirectory = Split-Path -Parent $Destination
    if (-not (Test-Path -LiteralPath $destinationDirectory)) {
        if ($PSCmdlet.ShouldProcess($destinationDirectory, 'create directory')) {
            New-Item -ItemType Directory -Force -Path $destinationDirectory | Out-Null
        }
    }

    if (Test-Path -LiteralPath $Destination) {
        $existing = Get-FileHash -LiteralPath $Destination -Algorithm SHA256
        $new = Get-FileHash -LiteralPath $Source -Algorithm SHA256
        if ($existing.Hash -eq $new.Hash) {
            Write-Step "unchanged: $Destination"
            return
        }
        $backup = "$Destination.opendonut-backup"
        if (Test-Path -LiteralPath $backup) {
            throw "refusing to overwrite an existing backup at $backup. Move it aside, decide which " +
                  "version you want, and run the script again."
        }
        if ($PSCmdlet.ShouldProcess($backup, "back up the previous $([System.IO.Path]::GetFileName($Destination))")) {
            Copy-Item -LiteralPath $Destination -Destination $backup
            Write-Step "backed up: $Destination -> $backup"
        }
    }

    if ($PSCmdlet.ShouldProcess($Destination, "install $([System.IO.Path]::GetFileName($Source))")) {
        Copy-Item -LiteralPath $Source -Destination $Destination -Force
        Write-Step "installed: $Destination"
    }
}

function Set-ServerProperty {
    param(
        [Parameter(Mandatory = $true)] [string] $PropertiesFile,
        [Parameter(Mandatory = $true)] [string] $Key,
        [Parameter(Mandatory = $true)] [string] $Value
    )

    if (-not (Test-Path -LiteralPath $PropertiesFile)) {
        throw "server.properties not found at $PropertiesFile"
    }

    $backup = "$PropertiesFile.opendonut-backup"
    if (-not (Test-Path -LiteralPath $backup)) {
        if ($PSCmdlet.ShouldProcess($backup, 'back up server.properties')) {
            Copy-Item -LiteralPath $PropertiesFile -Destination $backup
            Write-Step "backed up: $PropertiesFile -> $backup"
        }
    }

    $lines = Get-Content -LiteralPath $PropertiesFile
    $pattern = "^\s*$([regex]::Escape($Key))\s*="
    $found = $false
    $updated = foreach ($line in $lines) {
        if ($line -match $pattern) {
            $found = $true
            if ($line -eq "$Key=$Value") {
                $line
            }
            else {
                Write-Step "changing $Key in server.properties: '$line' -> '$Key=$Value'"
                "$Key=$Value"
            }
        }
        else {
            $line
        }
    }

    if (-not $found) {
        Write-Step "adding $Key=$Value to server.properties"
        $updated = @($updated) + "$Key=$Value"
    }

    if ($PSCmdlet.ShouldProcess($PropertiesFile, 'write')) {
        Write-TextNoBom -Path $PropertiesFile -Lines @($updated)
    }
}

Write-Host "OpenDonut backend install"
Write-Host "  server directory : $ServerDirectory"

$ServerDirectory = (Resolve-Path -LiteralPath $ServerDirectory).Path
if (-not (Test-Path -LiteralPath (Join-Path $ServerDirectory 'server.properties'))) {
    throw "$ServerDirectory does not look like a Minecraft server directory: no server.properties"
}

$configPath = if ($ConfigFile) { $ConfigFile } else { Join-Path $repoRoot 'config\opendonut.yml' }
if (-not (Test-Path -LiteralPath $configPath)) {
    throw "configuration not found at $configPath. Copy config/opendonut.example.yml to " +
          "config/opendonut.yml and edit it first."
}
$secretPath = if ($SecretFile) { $SecretFile } else { Join-Path (Split-Path -Parent $configPath) 'secrets\bridge-secret.txt' }
if (-not (Test-Path -LiteralPath $secretPath)) {
    throw "bridge secret not found at $secretPath. Generate it with:" +
          "  java -jar common/build/libs/opendonut-tools-0.1.0-dev.jar secret `"$secretPath`""
}

# --- mods ------------------------------------------------------------------
if (-not $SkipMods) {
    $modsDirectory = Join-Path $ServerDirectory 'mods'
    if (-not (Test-Path -LiteralPath $modsDirectory)) {
        if ($PSCmdlet.ShouldProcess($modsDirectory, 'create directory')) {
            New-Item -ItemType Directory -Force -Path $modsDirectory | Out-Null
        }
    }

    if (-not $ModJar) {
        $candidate = Get-ChildItem -Path (Join-Path $repoRoot 'fabric-mod\build\libs') -Filter 'opendonut-fabric-*.jar' -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notlike '*-sources.jar' } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if (-not $candidate) {
            throw "no OpenDonut mod jar found. Run './gradlew :fabric-mod:build' first, or pass -ModJar."
        }
        $ModJar = $candidate.FullName
    }
    Copy-WithBackup -Source $ModJar -Destination (Join-Path $modsDirectory (Split-Path -Leaf $ModJar))

    # The two dependencies the mod cannot work without. Fabric API is required by the mod; FabricProxy-Lite is
    # required for the proxy's forwarding to be secure, and without it the backend cannot be logged into
    # through Velocity at all.
    $dependencies = @(
        @{ Name = 'fabric-api'; Pattern = 'fabric-api-*.jar'; Hint = 'Fabric API for Minecraft 26.3 (0.166.2+26.3)' },
        @{ Name = 'FabricProxy-Lite'; Pattern = 'FabricProxy-Lite-*.jar'; Hint = 'FabricProxy-Lite 2.12.0 (supports Minecraft 26.1-26.3)' }
    )
    foreach ($dependency in $dependencies) {
        $found = Get-ChildItem -Path $modsDirectory -Filter $dependency.Pattern -ErrorAction SilentlyContinue
        if (-not $found) {
            Write-Warning "$($dependency.Name) is not in $modsDirectory. Install $($dependency.Hint) there; " +
                          "OpenDonut cannot work without it."
        }
        else {
            Write-Step "present: $($found[0].Name)"
        }
    }
}

# --- OpenDonut configuration and secret ------------------------------------
Copy-WithBackup -Source $configPath -Destination (Join-Path $ServerDirectory 'config\opendonut.yml')
Copy-WithBackup -Source $secretPath -Destination (Join-Path $ServerDirectory 'config\secrets\bridge-secret.txt')

# The seed state must be identical everywhere, so it is copied rather than regenerated. If it does not exist
# yet, the proxy will create it on first start and it can be copied afterwards.
$seedPath = Join-Path (Split-Path -Parent $configPath) 'seed.properties'
if (Test-Path -LiteralPath $seedPath) {
    Copy-WithBackup -Source $seedPath -Destination (Join-Path $ServerDirectory 'config\seed.properties')
}
else {
    Write-Host "  note: $seedPath does not exist yet. Start the proxy once to generate it, then re-run this script."
}

# --- server.properties -----------------------------------------------------
Set-ServerProperty -PropertiesFile (Join-Path $ServerDirectory 'server.properties') `
                   -Key 'accepts-transfers' -Value 'true'

Write-Host ""
Write-Host "Done. Still to check by hand:"
Write-Host "  * level-name matches world.name in the configuration"
Write-Host "  * level-seed is the resolved seed (run the 'seed' CLI command)"
Write-Host "  * the backend is not reachable from the internet"
Write-Host "  * FabricProxy-Lite's own config in config/ holds the proxy's forwarding secret"
Write-Host "Then start the server and look for the 'OpenDonut' lines in the log."
