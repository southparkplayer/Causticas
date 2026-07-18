param(
    [ValidateSet('get','set','wait','reset','shutdown')]
    [string]$Action = 'get',
    [string]$Instance = 'C:\Users\Administrator\AppData\Roaming\PrismLauncher\instances\26.2(2)\minecraft',
    [bool]$Sharc,
    [bool]$DiffusePathGuide,
    [bool]$FrameStats,
    [int]$DebugView,
    [double]$SceneScale,
    [double]$RadianceScale,
    [int]$AccumulationFrames,
    [int]$StaleFrames,
    [bool]$AntiFirefly,
    [int]$CacheExponent,
    [int]$UpdateTileSize,
    [int]$UpdateMaxBounces,
    [double]$MinSegmentRatio,
    [bool]$GlossyQuery,
    [bool]$LiveSecondaryDirect,
    [bool]$PrimaryDiffuseReuse,
    [bool]$SharcDetailedStats,
    [bool]$Fullscreen,
    [bool]$OpenCausticaSettings,
    [ValidateSet('Output','Rendering','SHaRC','Image','View','Diagnostics')]
    [string]$CausticaCategory,
    [bool]$OpenSharcSettings,
    [bool]$CloseScreen,
    [bool]$Screenshot,
    [string]$GameCommand,
    [double]$CameraYaw,
    [double]$CameraPitch,
    [int]$Seconds = 20
)

$directory = Join-Path $Instance 'caustica-debug-bridge'
$command = Join-Path $directory 'command.properties'
$state = Join-Path $directory 'state.properties'

function Read-Properties([string]$Path) {
    $result = [ordered]@{}
    if (Test-Path -LiteralPath $Path) {
        foreach ($line in Get-Content -LiteralPath $Path) {
            if ($line -match '^\s*([^#!][^=]*)=(.*)$') { $result[$matches[1].Trim()] = $matches[2].Trim() }
        }
    }
    [pscustomobject]$result
}

if ($Action -eq 'get') { Read-Properties $state; exit }
if ($Action -eq 'wait') {
    $samples = @()
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 500
        $sample = Read-Properties $state
        if ($sample.fps -and $sample.inWorld -eq 'true') { $samples += [int]$sample.fps }
    }
    $ordered = @($samples | Sort-Object)
    if (-not $ordered.Count) { throw 'No in-world bridge samples were received.' }
    [pscustomobject]@{ Samples=$ordered.Count; Min=$ordered[0]; Median=$ordered[[int][math]::Floor($ordered.Count/2)]; Max=$ordered[-1]; Mean=[math]::Round(($ordered | Measure-Object -Average).Average, 2); State=(Read-Properties $state) }
    exit
}

New-Item -ItemType Directory -Path $directory -Force | Out-Null
$sequence = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$lines = @("sequence=$sequence")
if ($PSBoundParameters.ContainsKey('Sharc')) { $lines += "sharc=$($Sharc.ToString().ToLowerInvariant())" }
if ($PSBoundParameters.ContainsKey('DiffusePathGuide')) { $lines += "diffusePathGuide=$($DiffusePathGuide.ToString().ToLowerInvariant())" }
if ($PSBoundParameters.ContainsKey('FrameStats')) { $lines += "frameStats=$($FrameStats.ToString().ToLowerInvariant())" }
if ($PSBoundParameters.ContainsKey('DebugView')) { $lines += "debugView=$DebugView" }
if ($PSBoundParameters.ContainsKey('SceneScale')) { $lines += "sceneScale=$($SceneScale.ToString([Globalization.CultureInfo]::InvariantCulture))" }
if ($PSBoundParameters.ContainsKey('RadianceScale')) { $lines += "radianceScale=$($RadianceScale.ToString([Globalization.CultureInfo]::InvariantCulture))" }
if ($PSBoundParameters.ContainsKey('AccumulationFrames')) { $lines += "accumulationFrames=$AccumulationFrames" }
if ($PSBoundParameters.ContainsKey('StaleFrames')) { $lines += "staleFrames=$StaleFrames" }
if ($PSBoundParameters.ContainsKey('AntiFirefly')) { $lines += "antiFirefly=$($AntiFirefly.ToString().ToLowerInvariant())" }
if ($PSBoundParameters.ContainsKey('CacheExponent')) { $lines += "cacheExponent=$CacheExponent" }
if ($PSBoundParameters.ContainsKey('UpdateTileSize')) { $lines += "updateTileSize=$UpdateTileSize" }
if ($PSBoundParameters.ContainsKey('UpdateMaxBounces')) { $lines += "updateMaxBounces=$UpdateMaxBounces" }
if ($PSBoundParameters.ContainsKey('MinSegmentRatio')) { $lines += "minSegmentRatio=$($MinSegmentRatio.ToString([Globalization.CultureInfo]::InvariantCulture))" }
if ($PSBoundParameters.ContainsKey('GlossyQuery')) { $lines += "glossyQuery=$($GlossyQuery.ToString().ToLowerInvariant())" }
if ($PSBoundParameters.ContainsKey('LiveSecondaryDirect')) { $lines += "liveSecondaryDirect=$($LiveSecondaryDirect.ToString().ToLowerInvariant())" }
if ($PSBoundParameters.ContainsKey('PrimaryDiffuseReuse')) { $lines += "primaryDiffuseReuse=$($PrimaryDiffuseReuse.ToString().ToLowerInvariant())" }
if ($PSBoundParameters.ContainsKey('SharcDetailedStats')) { $lines += "sharcDetailedStats=$($SharcDetailedStats.ToString().ToLowerInvariant())" }
if ($PSBoundParameters.ContainsKey('Fullscreen')) { $lines += "fullscreen=$($Fullscreen.ToString().ToLowerInvariant())" }
if ($PSBoundParameters.ContainsKey('OpenCausticaSettings')) { $lines += "openCausticaSettings=$($OpenCausticaSettings.ToString().ToLowerInvariant())" }
if ($PSBoundParameters.ContainsKey('CausticaCategory')) { $lines += "causticaCategory=$CausticaCategory" }
if ($PSBoundParameters.ContainsKey('OpenSharcSettings')) { $lines += "openSharcSettings=$($OpenSharcSettings.ToString().ToLowerInvariant())" }
if ($PSBoundParameters.ContainsKey('CloseScreen')) { $lines += "closeScreen=$($CloseScreen.ToString().ToLowerInvariant())" }
if ($PSBoundParameters.ContainsKey('Screenshot')) { $lines += "screenshot=$($Screenshot.ToString().ToLowerInvariant())" }
if ($PSBoundParameters.ContainsKey('GameCommand')) { $lines += "gameCommand=$GameCommand" }
if ($PSBoundParameters.ContainsKey('CameraYaw')) { $lines += "cameraYaw=$($CameraYaw.ToString([Globalization.CultureInfo]::InvariantCulture))" }
if ($PSBoundParameters.ContainsKey('CameraPitch')) { $lines += "cameraPitch=$($CameraPitch.ToString([Globalization.CultureInfo]::InvariantCulture))" }
if ($Action -eq 'reset') { $lines += 'resetCache=true' }
if ($Action -eq 'shutdown') { $lines += 'shutdown=true' }
$temporary = "$command.tmp"
Set-Content -LiteralPath $temporary -Value $lines -Encoding ASCII
Move-Item -LiteralPath $temporary -Destination $command -Force

$deadline = (Get-Date).AddSeconds(10)
do {
    Start-Sleep -Milliseconds 100
    $current = Read-Properties $state
} while (($current.commandSequence -ne "$sequence") -and (Get-Date) -lt $deadline)
if ($current.commandSequence -ne "$sequence") { throw "Bridge did not acknowledge command $sequence" }
$current
