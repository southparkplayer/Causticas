param(
    [ValidateSet('Shaders', 'Test', 'Jar', 'Deploy', 'Full')]
    [string]$Mode = 'Test',
    [switch]$WithSharc,
    [switch]$WithoutSharc,
    [ValidateRange(30, 3600)]
    [int]$TimeoutSeconds = 600,
    [ValidateRange(5, 300)]
    [int]$HeartbeatSeconds = 15,
    [string[]]$TestFilter = @(),
    [string]$Instance = 'C:\Users\Administrator\AppData\Roaming\PrismLauncher\instances\26.2(2)\minecraft'
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$javaHome = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.1.8-hotspot'
$slangc = 'C:\Users\Administrator\Documents\Caustica-deps\slang-2026.13\bin\slangc.exe'
$vulkanSdk = 'C:\VulkanSDK\1.4.341.1'
$streamlineSdk = 'C:\Users\Administrator\Documents\Caustica-deps'
$sharcSdk = Join-Path $repo '.deps\sharc-1.6.5.0'
$gradle = Join-Path $repo 'gradlew.bat'
$builtJar = Join-Path $repo 'build\libs\caustica-0.1.0.jar'
$deployedJar = Join-Path $Instance 'mods\caustica-0.1.0.jar'

function Require-File([string]$Path, [string]$Name) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Name is missing at the pinned path: $Path"
    }
}

function Require-Version([string]$Name, [string]$Actual, [string]$Pattern) {
    if ($Actual -notmatch $Pattern) {
        throw "$Name version mismatch. Expected /$Pattern/; received: $Actual"
    }
}

function Get-LiveMinecraftProcess {
    $instanceRoot = (Split-Path -Parent $Instance).Replace('\', '/').TrimEnd('/')
    Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object {
            $_.Name -match '^javaw?\.exe$' -and
            $_.CommandLine -and
            $_.CommandLine.Replace('\', '/').IndexOf($instanceRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0
        } |
        Select-Object -First 1
}

Require-File (Join-Path $javaHome 'bin\java.exe') 'JDK 25'
Require-File $slangc 'Slang 2026.13'
Require-File (Join-Path $vulkanSdk 'Bin\spirv-val.exe') 'Vulkan SDK 1.4.341.1'
Require-File (Join-Path $streamlineSdk 'include\sl.h') 'Streamline 2.12 SDK'
Require-File $gradle 'Gradle wrapper'

$javaVersion = (& (Join-Path $javaHome 'bin\java.exe') --version 2>&1) -join ' '
$slangVersion = (& $env:ComSpec /d /s /c "`"$slangc`" -version 2>&1") -join ' '
Require-Version 'Java' $javaVersion '25\.0\.1'
Require-Version 'Slang' $slangVersion '2026\.13'

$env:JAVA_HOME = $javaHome
$env:CAUSTICA_SLANGC = $slangc
$env:VULKAN_SDK = $vulkanSdk
$env:VK_SDK_PATH = $vulkanSdk
$env:STREAMLINE_SDK = $streamlineSdk
$env:Path = "$(Join-Path $javaHome 'bin');$(Join-Path $vulkanSdk 'Bin');$env:Path"

if ($WithSharc -and $WithoutSharc) {
    throw 'Choose either -WithSharc or -WithoutSharc, not both.'
}

# Production artifacts are expected to contain the complete renderer. Keep quick shader/test
# iterations baseline-only unless requested, but make a SHaRC-free JAR or deploy an explicit opt-out.
$artifactMode = $Mode -in @('Jar', 'Deploy', 'Full')
$includeSharc = $WithSharc -or ($artifactMode -and -not $WithoutSharc)

if ($includeSharc) {
    if (-not (Test-Path -LiteralPath (Join-Path $sharcSdk 'include\HashGridCommon.h') -PathType Leaf)) {
        & (Join-Path $PSScriptRoot 'fetch-sharc.ps1') -Destination $sharcSdk
        if ($LASTEXITCODE -ne 0) { throw 'Pinned SHaRC fetch failed.' }
    }
    $env:SHARC_SDK = $sharcSdk
} else {
    Remove-Item Env:SHARC_SDK -ErrorAction SilentlyContinue
}

$tasks = @(switch ($Mode) {
    'Shaders' { @('compileShaders') }
    'Test'    { @('compileShaders', 'test') }
    'Jar'     { @('test', 'jar', 'verifyProductionArtifact') }
    'Deploy'  { @('test', 'jar', 'verifyProductionArtifact') }
    'Full'    { @('build', 'verifyProductionArtifact') }
})
$gradleArguments = @()
foreach ($task in $tasks) {
    $gradleArguments += $task
    if ($task -eq 'test') {
        foreach ($filter in $TestFilter) {
            $gradleArguments += @('--tests', $filter)
        }
    }
}
if ($artifactMode -and $WithoutSharc) {
    $gradleArguments += '-PwithoutSharc=true'
}

Write-Host "Pinned Java:      $javaHome"
Write-Host "Pinned Slang:     $slangc"
Write-Host "Pinned Vulkan:    $vulkanSdk"
Write-Host "Pinned Streamline:$streamlineSdk"
Write-Host "Pinned SHaRC:     $(if ($includeSharc) { $sharcSdk } else { 'off (explicit for artifact modes with -WithoutSharc)' })"
Write-Host "Gradle tasks:     $($tasks -join ' ')"
Write-Host "Build timeout:    $TimeoutSeconds seconds"

if ($Mode -eq 'Deploy') {
    Remove-Item -LiteralPath "$deployedJar.tmp" -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath "$deployedJar.bak" -Force -ErrorAction SilentlyContinue
    $live = Get-LiveMinecraftProcess
    if ($live) {
        throw "Refusing to replace a JAR under live Minecraft PID $($live.ProcessId). Shut it down through tools\caustica-debug-bridge.ps1 first."
    }
}

Push-Location $repo
try {
    $processArguments = @($gradleArguments + @('--console=plain', '--no-daemon')) |
        ForEach-Object { '"' + $_.Replace('"', '\"') + '"' }
    $commandLine = (Split-Path -Leaf $gradle) + ' ' + ($processArguments -join ' ')
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    $process = Start-Process -NoNewWindow -PassThru -FilePath $env:ComSpec `
        -ArgumentList @('/d', '/c', $commandLine)
    # PowerShell 5.1 can lose ExitCode unless the native process handle is opened before it exits.
    $null = $process.Handle
    $nextHeartbeat = $HeartbeatSeconds
    while (-not $process.WaitForExit(1000)) {
        $elapsed = [int]$timer.Elapsed.TotalSeconds
        if ($elapsed -ge $TimeoutSeconds) {
            & taskkill.exe /PID $process.Id /T /F | Out-Null
            throw "Gradle timed out after $elapsed seconds; process tree $($process.Id) was terminated"
        }
        if ($elapsed -ge $nextHeartbeat) {
            Write-Host "Gradle still running: ${elapsed}s elapsed, $($TimeoutSeconds - $elapsed)s remaining"
            $nextHeartbeat += $HeartbeatSeconds
        }
    }
    $process.WaitForExit()
    $process.Refresh()
    $timer.Stop()
    Write-Host "Gradle finished in $([Math]::Round($timer.Elapsed.TotalSeconds, 1))s with exit code $($process.ExitCode)"
    if ($process.ExitCode -ne 0) { throw "Gradle failed with exit code $($process.ExitCode)" }

    if ($Mode -in @('Jar', 'Deploy', 'Full')) {
        Require-File $builtJar 'Built Caustica JAR'
        $builtHash = (Get-FileHash -LiteralPath $builtJar -Algorithm SHA256).Hash
        Write-Host "Built JAR SHA-256: $builtHash"
    }

    if ($Mode -eq 'Deploy') {
        $mods = Split-Path -Parent $deployedJar
        if (-not (Test-Path -LiteralPath $mods -PathType Container)) {
            throw "Prism mods directory is missing: $mods"
        }
        $temporary = "$deployedJar.tmp"
        $backup = "$deployedJar.bak"
        Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue
        try {
            Copy-Item -LiteralPath $builtJar -Destination $temporary
            if (Test-Path -LiteralPath $deployedJar -PathType Leaf) {
                # File.Replace requires a legal backup path on Windows. Keep the previous deployed JAR
                # recoverable until the new artifact's hash has been verified below.
                [System.IO.File]::Replace($temporary, $deployedJar, $backup, $true)
            } else {
                Move-Item -LiteralPath $temporary -Destination $deployedJar
            }
        } finally {
            Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        }
        $deployedHash = (Get-FileHash -LiteralPath $deployedJar -Algorithm SHA256).Hash
        if ($deployedHash -ne $builtHash) {
            throw "Deployment hash mismatch: built=$builtHash deployed=$deployedHash"
        }
        Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue
        Write-Host "Deployed JAR:       $deployedJar"
        Write-Host "Deployed SHA-256:   $deployedHash"
    }
} finally {
    Pop-Location
}
