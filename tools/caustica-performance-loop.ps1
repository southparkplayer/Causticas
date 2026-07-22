param(
    [string]$Instance = 'C:\Users\Administrator\AppData\Roaming\PrismLauncher\instances\26.2(2)\minecraft',
    [string]$ExpectedArtifactSha256,
    [int]$WarmupSeconds = 10,
    [int]$SampleSeconds = 20,
    [int]$IntervalMilliseconds = 500,
    [int]$ExpectedWidth = 3840,
    [int]$ExpectedHeight = 2160,
    [int]$ExpectedRenderWidth = 1920,
    [int]$ExpectedRenderHeight = 1080,
    [string]$Label = 'capture',
    [string]$BenchmarkKind = 'steady-state',
    [string]$OutputDirectory = 'build/performance-loops'
)

$ErrorActionPreference = 'Stop'
$bridge = Join-Path $PSScriptRoot 'caustica-debug-bridge.ps1'
$statePath = Join-Path $Instance 'caustica-debug-bridge\state.properties'

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class CausticaForegroundWindow {
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);
    [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
    [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool attach);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr window);
    [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr window);
    [DllImport("user32.dll")] public static extern bool ShowWindowAsync(IntPtr window, int command);
}
'@

function Read-Properties([string]$Path) {
    $result = [ordered]@{}
    if (Test-Path -LiteralPath $Path) {
        foreach ($line in Get-Content -LiteralPath $Path) {
            if ($line -match '^\s*([^#!][^=]*)=(.*)$') { $result[$matches[1].Trim()] = $matches[2].Trim() }
        }
    }
    [pscustomobject]$result
}

function Require([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Require-MinecraftForeground {
    [uint32]$foregroundProcessId = 0
    $window = [CausticaForegroundWindow]::GetForegroundWindow()
    [void][CausticaForegroundWindow]::GetWindowThreadProcessId($window, [ref]$foregroundProcessId)
    $process = Get-Process -Id $foregroundProcessId -ErrorAction SilentlyContinue
    Require ($process -and $process.ProcessName -eq 'javaw') `
        "Minecraft must own the foreground window; foreground process is '$($process.ProcessName)'."
}

function Set-MinecraftForeground {
    $minecraft = Get-Process javaw -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowHandle -ne [IntPtr]::Zero } |
        Sort-Object @{ Expression = { $_.MainWindowTitle -like 'Minecraft*' }; Descending = $true } |
        Select-Object -First 1
    Require ($null -ne $minecraft) 'No Minecraft window was available for foreground capture.'

    $targetWindow = $minecraft.MainWindowHandle
    [uint32]$targetProcessId = 0
    $targetThread = [CausticaForegroundWindow]::GetWindowThreadProcessId($targetWindow, [ref]$targetProcessId)
    $currentThread = [CausticaForegroundWindow]::GetCurrentThreadId()

    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        [uint32]$foregroundProcessId = 0
        $foregroundWindow = [CausticaForegroundWindow]::GetForegroundWindow()
        $foregroundThread = [CausticaForegroundWindow]::GetWindowThreadProcessId(
            $foregroundWindow, [ref]$foregroundProcessId)

        $attachedForeground = $foregroundThread -ne 0 -and $foregroundThread -ne $currentThread -and
            [CausticaForegroundWindow]::AttachThreadInput($currentThread, $foregroundThread, $true)
        $attachedTarget = $targetThread -ne 0 -and $targetThread -ne $currentThread -and
            [CausticaForegroundWindow]::AttachThreadInput($currentThread, $targetThread, $true)
        try {
            [void][CausticaForegroundWindow]::ShowWindowAsync($targetWindow, 9)
            [void][CausticaForegroundWindow]::BringWindowToTop($targetWindow)
            [void][CausticaForegroundWindow]::SetForegroundWindow($targetWindow)
        } finally {
            if ($attachedTarget) {
                [void][CausticaForegroundWindow]::AttachThreadInput($currentThread, $targetThread, $false)
            }
            if ($attachedForeground) {
                [void][CausticaForegroundWindow]::AttachThreadInput($currentThread, $foregroundThread, $false)
            }
        }

        Start-Sleep -Milliseconds 100
        $activeWindow = [CausticaForegroundWindow]::GetForegroundWindow()
        [uint32]$activeProcessId = 0
        [void][CausticaForegroundWindow]::GetWindowThreadProcessId($activeWindow, [ref]$activeProcessId)
        if ($activeProcessId -eq [uint32]$minecraft.Id) { return }
    }

    Require-MinecraftForeground
}

function Metric-Summary($Samples, [string]$Name, [double]$Scale) {
    $values = @($Samples | ForEach-Object { [double]($_.$Name) * $Scale } | Sort-Object)
    if (-not $values.Count) { return $null }
    [pscustomobject][ordered]@{
        mean = [math]::Round(($values | Measure-Object -Average).Average, 4)
        median = [math]::Round($values[[int][math]::Floor($values.Count / 2)], 4)
        min = [math]::Round($values[0], 4)
        max = [math]::Round($values[-1], 4)
    }
}

Require (Test-Path -LiteralPath $bridge) "Debug bridge script not found: $bridge"
& $bridge -Action set -Instance $Instance -FrameStats $true -SharcDetailedStats $false -DebugView 0 | Out-Null

$initial = Read-Properties $statePath
Require ($initial.inWorld -eq 'true') 'Performance capture requires an entered world.'
Require ($initial.rtContextReady -eq 'true' -and $initial.rtStatus -eq 'ready') 'RT context is not ready.'
Require ($initial.backend -eq 'vulkan') "Expected Vulkan backend, got '$($initial.backend)'."
Require ($initial.fullscreen -eq 'true') 'Performance capture requires fullscreen.'
Require ([int]$initial.windowWidth -eq $ExpectedWidth -and [int]$initial.windowHeight -eq $ExpectedHeight) `
    "Expected ${ExpectedWidth}x${ExpectedHeight}, got $($initial.windowWidth)x$($initial.windowHeight)."
Require ([int]$initial.renderWidth -eq $ExpectedRenderWidth -and [int]$initial.renderHeight -eq $ExpectedRenderHeight) `
    "Expected internal ${ExpectedRenderWidth}x${ExpectedRenderHeight}, got $($initial.renderWidth)x$($initial.renderHeight)."
Require ($initial.screenWidth -gt 0 -and $initial.screenHeight -gt 0) 'Display is not reporting a valid screen size.'
Set-MinecraftForeground
if ($ExpectedArtifactSha256) {
    Require ($initial.artifactSha256 -eq $ExpectedArtifactSha256.ToUpperInvariant()) `
        "Artifact mismatch: expected $ExpectedArtifactSha256, loaded $($initial.artifactSha256)."
}

if ($WarmupSeconds -gt 0) { Start-Sleep -Seconds $WarmupSeconds }
Set-MinecraftForeground
$samples = @()
$deadline = (Get-Date).AddSeconds($SampleSeconds)
while ((Get-Date) -lt $deadline) {
    Set-MinecraftForeground
    Start-Sleep -Milliseconds $IntervalMilliseconds
    $state = Read-Properties $statePath
    Require ($state.inWorld -eq 'true') 'World exited during capture.'
    Require ($state.artifactSha256 -eq $initial.artifactSha256) 'Loaded artifact changed during capture.'
    Require ($state.frameStats -eq 'true' -and $state.sharcDetailedStats -eq 'false') `
        'Capture instrumentation changed during capture.'
    Require ([int]$state.renderWidth -eq $ExpectedRenderWidth -and [int]$state.renderHeight -eq $ExpectedRenderHeight) `
        'Internal render resolution changed during capture.'
    Require ($state.screenWidth -eq $initial.screenWidth -and $state.screenHeight -eq $initial.screenHeight) `
        'Display resolution changed during capture.'
    Require-MinecraftForeground
    $samples += $state
}
Require ($samples.Count -gt 0) 'No bridge samples were captured.'

$metrics = [ordered]@{
    fps = Metric-Summary $samples 'fps' 1.0
    blasMs = Metric-Summary $samples 'blasGpuNanos' 0.000001
    tlasMs = Metric-Summary $samples 'tlasGpuNanos' 0.000001
    baselineTraceMs = Metric-Summary $samples 'baselineTraceGpuNanos' 0.000001
    sharcUpdateMs = Metric-Summary $samples 'sharcUpdateGpuNanos' 0.000001
    sharcResolveMs = Metric-Summary $samples 'sharcResolveGpuNanos' 0.000001
    sharcQueryPassMs = Metric-Summary $samples 'sharcQueryPassGpuNanos' 0.000001
    reconstructionMs = Metric-Summary $samples 'reconstructionGpuNanos' 0.000001
    disocclusionMs = Metric-Summary $samples 'disocclusionGpuNanos' 0.000001
    dlssRrMs = Metric-Summary $samples 'dlssRrGpuNanos' 0.000001
    exposureMs = Metric-Summary $samples 'exposureGpuNanos' 0.000001
    displayMs = Metric-Summary $samples 'displayGpuNanos' 0.000001
    copyMs = Metric-Summary $samples 'copyGpuNanos' 0.000001
    pilotMs = Metric-Summary $samples 'pilotGpuNanos' 0.000001
    mainTraceMs = Metric-Summary $samples 'mainTraceGpuNanos' 0.000001
    scheduleMs = Metric-Summary $samples 'scheduleGpuNanos' 0.000001
    offlineMainPaths = Metric-Summary $samples 'offlineMainPaths' 1.0
    offlinePilotPaths = Metric-Summary $samples 'offlinePilotPaths' 1.0
    offlineActiveTiles = Metric-Summary $samples 'offlineActiveTiles' 1.0
    offlineTotalTiles = Metric-Summary $samples 'offlineTotalTiles' 1.0
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$safeLabel = $Label -replace '[^A-Za-z0-9_.-]', '-'
$output = Join-Path (Resolve-Path '.').Path $OutputDirectory
New-Item -ItemType Directory -Path $output -Force | Out-Null
$base = Join-Path $output "$stamp-$safeLabel"
$samples | Export-Csv -LiteralPath "$base.csv" -NoTypeInformation
$report = [pscustomobject][ordered]@{
    schemaVersion = 2
    capturedAt = (Get-Date).ToUniversalTime().ToString('o')
    label = $Label
    benchmarkKind = $BenchmarkKind
    artifactSha256 = $initial.artifactSha256
    backend = $initial.backend
    rtStatus = $initial.rtStatus
    resolution = "$ExpectedWidth`x$ExpectedHeight"
    internalResolution = "$ExpectedRenderWidth`x$ExpectedRenderHeight"
    sharcActive = $initial.sharcActive
    sampleCount = $samples.Count
    intervalMilliseconds = $IntervalMilliseconds
    warmupSeconds = $WarmupSeconds
    sampleSeconds = $SampleSeconds
    rawSamples = "$base.csv"
    metrics = $metrics
}
$report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath "$base.json" -Encoding UTF8
$report
Write-Host "Raw samples: $base.csv"
Write-Host "Summary:     $base.json"
