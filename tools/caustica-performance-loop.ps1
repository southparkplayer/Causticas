param(
    [string]$Instance = 'C:\Users\Administrator\AppData\Roaming\PrismLauncher\instances\26.2(2)\minecraft',
    [string]$ExpectedArtifactSha256,
    [int]$WarmupSeconds = 10,
    [int]$SampleSeconds = 20,
    [int]$IntervalMilliseconds = 500,
    [int]$ExpectedWidth = 3840,
    [int]$ExpectedHeight = 2160,
    [string]$Label = 'capture',
    [string]$OutputDirectory = 'build/performance-loops'
)

$ErrorActionPreference = 'Stop'
$bridge = Join-Path $PSScriptRoot 'caustica-debug-bridge.ps1'
$statePath = Join-Path $Instance 'caustica-debug-bridge\state.properties'

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
if ($ExpectedArtifactSha256) {
    Require ($initial.artifactSha256 -eq $ExpectedArtifactSha256.ToUpperInvariant()) `
        "Artifact mismatch: expected $ExpectedArtifactSha256, loaded $($initial.artifactSha256)."
}

if ($WarmupSeconds -gt 0) { Start-Sleep -Seconds $WarmupSeconds }
$samples = @()
$deadline = (Get-Date).AddSeconds($SampleSeconds)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds $IntervalMilliseconds
    $state = Read-Properties $statePath
    Require ($state.inWorld -eq 'true') 'World exited during capture.'
    Require ($state.artifactSha256 -eq $initial.artifactSha256) 'Loaded artifact changed during capture.'
    Require ($state.frameStats -eq 'true' -and $state.sharcDetailedStats -eq 'false') `
        'Capture instrumentation changed during capture.'
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
    sharcQueryMs = Metric-Summary $samples 'sharcQueryGpuNanos' 0.000001
    reconstructionMs = Metric-Summary $samples 'reconstructionGpuNanos' 0.000001
    exposureMs = Metric-Summary $samples 'exposureGpuNanos' 0.000001
    displayMs = Metric-Summary $samples 'displayGpuNanos' 0.000001
    copyMs = Metric-Summary $samples 'copyGpuNanos' 0.000001
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$safeLabel = $Label -replace '[^A-Za-z0-9_.-]', '-'
$output = Join-Path (Resolve-Path '.').Path $OutputDirectory
New-Item -ItemType Directory -Path $output -Force | Out-Null
$base = Join-Path $output "$stamp-$safeLabel"
$samples | Export-Csv -LiteralPath "$base.csv" -NoTypeInformation
$report = [pscustomobject][ordered]@{
    capturedAt = (Get-Date).ToUniversalTime().ToString('o')
    label = $Label
    artifactSha256 = $initial.artifactSha256
    resolution = "$ExpectedWidth`x$ExpectedHeight"
    sharcActive = $initial.sharcActive
    sampleCount = $samples.Count
    intervalMilliseconds = $IntervalMilliseconds
    metrics = $metrics
}
$report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath "$base.json" -Encoding UTF8
$report
Write-Host "Raw samples: $base.csv"
Write-Host "Summary:     $base.json"
