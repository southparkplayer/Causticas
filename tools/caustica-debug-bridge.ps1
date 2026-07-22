param(
    [ValidateSet('get','set','wait','benchmark','reset','shutdown')]
    [string]$Action = 'get',
    [string]$Instance = 'C:\Users\Administrator\AppData\Roaming\PrismLauncher\instances\26.2(2)\minecraft',
    [bool]$Sharc,
    [bool]$DiffusePathGuide,
    [bool]$FrameStats,
    [bool]$TerrainBenchmark,
    [int]$TerrainDispatch,
    [int]$TerrainResults,
    [int]$TerrainInflight,
    [int]$TerrainBuildBatch,
    [bool]$Omm,
    [int]$OmmSubdivision,
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
    [ValidateSet('Essentials','DisplayHdr','FrameGeneration','Reconstruction','ExposureTonemap',
        'Lighting','SkyAtmosphere','GeometryScene','FirstPerson','Denoising','Materials','SHaRC','Diagnostics',
        'Overview','Output','View')]
    [string]$CausticaCategory,
    [bool]$OpenSharcSettings,
    [bool]$CloseScreen,
    [bool]$Screenshot,
    [string]$GameCommand,
    [double]$CameraYaw,
    [double]$CameraPitch,
    [int]$Seconds = 20,
    [int]$PollMilliseconds = 100,
    [string]$Route,
    [int]$RouteIntervalSeconds = 5,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\build\reports\terrain-benchmark')
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
if ($Action -eq 'benchmark') {
    $frameCsvSource = Join-Path $Instance 'rt-frame-stats\frame.csv'
    $frameStartRows = if (Test-Path -LiteralPath $frameCsvSource) {
        [math]::Max(0, (Get-Content -LiteralPath $frameCsvSource).Count - 1)
    } else { 0 }
    $set = @{ Action='set'; Instance=$Instance; TerrainBenchmark=$true; FrameStats=$true }
    foreach ($name in @('TerrainDispatch','TerrainResults','TerrainInflight','TerrainBuildBatch','Omm','OmmSubdivision')) {
        if ($PSBoundParameters.ContainsKey($name)) { $set[$name] = $PSBoundParameters[$name] }
    }
    & $PSCommandPath @set | Out-Null
    $commands = @($Route -split ';' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    $nextRoute = if ($commands.Count) { (Get-Date).AddSeconds($RouteIntervalSeconds) } else { [datetime]::MaxValue }
    $routeIndex = 0
    $samples = [System.Collections.Generic.List[object]]::new()
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds ([math]::Max(50, $PollMilliseconds))
        $sample = Read-Properties $state
        if ($sample.inWorld -eq 'true' -and $sample.terrainBenchmark -eq 'true') {
            $samples.Add([pscustomobject]@{
                timestampMillis=[long]$sample.timestampMillis; fps=[int]$sample.fps
                desired=[int]$sample.terrainDesired; missing=[int]$sample.terrainMissing
                reextract=[int]$sample.terrainReextract; inFlight=[int]$sample.terrainInFlight
                workerActive=[int]$sample.terrainWorkerActive; gpuQueued=[int]$sample.terrainGpuQueued
                completedQueued=[int]$sample.terrainCompletedQueued; published=[int]$sample.terrainPublished
                empty=[int]$sample.terrainEmpty; resident=[int]$sample.terrainResident
                actionableBacklog=[int]$sample.terrainActionableBacklog
                dispatchedTotal=[long]$sample.terrainDispatchedTotal
                cpuCompletedTotal=[long]$sample.terrainCpuCompletedTotal
                gpuCompletedTotal=[long]$sample.terrainGpuCompletedTotal
                publishedTotal=[long]$sample.terrainPublishedTotal
                emptyCompletedTotal=[long]$sample.terrainEmptyCompletedTotal
                neighborBlockedTotal=[long]$sample.terrainNeighborBlockedTotal
                snapshotNanosTotal=[long]$sample.terrainSnapshotNanosTotal
                cpuNanosTotal=[long]$sample.terrainCpuNanosTotal
                gpuNanosTotal=[long]$sample.terrainGpuNanosTotal
            })
        }
        if ((Get-Date) -ge $nextRoute -and $routeIndex -lt $commands.Count) {
            & $PSCommandPath -Action set -Instance $Instance -GameCommand $commands[$routeIndex] | Out-Null
            $routeIndex++
            $nextRoute = (Get-Date).AddSeconds($RouteIntervalSeconds)
        }
    }
    if (-not $samples.Count) { throw 'No in-world terrain benchmark samples were received.' }
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $csv = Join-Path $OutputDirectory "terrain-$stamp.csv"
    $json = Join-Path $OutputDirectory "terrain-$stamp.json"
    $frameCsv = Join-Path $OutputDirectory "terrain-frames-$stamp.csv"
    $samples | Export-Csv -LiteralPath $csv -NoTypeInformation
    $fps = @($samples.fps | Sort-Object)
    $last = $samples[$samples.Count - 1]
    $elapsedSeconds = [math]::Max(0.001, ($last.timestampMillis - $samples[0].timestampMillis) / 1000.0)
    $frameRows = if (Test-Path -LiteralPath $frameCsvSource) {
        @(Import-Csv -LiteralPath $frameCsvSource | Select-Object -Skip $frameStartRows)
    } else { @() }
    if ($frameRows.Count) { $frameRows | Export-Csv -LiteralPath $frameCsv -NoTypeInformation }
    $frameTimes = @($frameRows | ForEach-Object { [double]$_.totalMs } | Sort-Object)
    $resolvedTarget = [math]::Max(1, [int]$last.desired)
    $startMillis = $samples[0].timestampMillis
    function Time-To-Residency([double]$Ratio) {
        $target = [math]::Ceiling($resolvedTarget * $Ratio)
        $hit = $samples | Where-Object { ($_.published + $_.empty) -ge $target } | Select-Object -First 1
        if ($hit) { return [math]::Round(($hit.timestampMillis - $startMillis) / 1000.0, 3) }
        return $null
    }
    $summary = [ordered]@{
        artifactSha256=(Read-Properties $state).artifactSha256
        samples=$samples.Count; elapsedSeconds=$elapsedSeconds
        fpsMin=$fps[0]; fpsMedian=$fps[[int][math]::Floor($fps.Count/2)]; fpsMax=$fps[-1]
        frameCount=$frameTimes.Count
        frameP95Ms=if($frameTimes.Count){$frameTimes[[math]::Min($frameTimes.Count-1,[int][math]::Floor($frameTimes.Count*0.95))]}else{$null}
        frameP99Ms=if($frameTimes.Count){$frameTimes[[math]::Min($frameTimes.Count-1,[int][math]::Floor($frameTimes.Count*0.99))]}else{$null}
        frameMaxMs=if($frameTimes.Count){$frameTimes[-1]}else{$null}
        hitchFrames=@($frameRows | Where-Object { $_.hitch -eq '1' }).Count
        desired=$last.desired; resolved=($last.published + $last.empty); missing=$last.missing
        timeTo50Seconds=(Time-To-Residency 0.50)
        timeTo90Seconds=(Time-To-Residency 0.90)
        timeTo99Seconds=(Time-To-Residency 0.99)
        dispatched=$last.dispatchedTotal; published=$last.publishedTotal; emptyCompleted=$last.emptyCompletedTotal
        sectionsPerSecond=[math]::Round(($last.publishedTotal + $last.emptyCompletedTotal) / $elapsedSeconds, 3)
        neighborBlockedChecks=$last.neighborBlockedTotal
        averageSnapshotMs=if($last.dispatchedTotal){[math]::Round($last.snapshotNanosTotal/$last.dispatchedTotal/1e6,4)}else{0}
        averageCpuMs=if($last.cpuCompletedTotal){[math]::Round($last.cpuNanosTotal/$last.cpuCompletedTotal/1e6,4)}else{0}
        averageGpuMs=if($last.gpuCompletedTotal){[math]::Round($last.gpuNanosTotal/$last.gpuCompletedTotal/1e6,4)}else{0}
        finalBacklog=$last.actionableBacklog; csv=$csv; frameCsv=if($frameRows.Count){$frameCsv}else{$null}
    }
    $summary | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $json -Encoding UTF8
    & $PSCommandPath -Action set -Instance $Instance -TerrainBenchmark $false -FrameStats $false | Out-Null
    [pscustomobject]$summary
    exit
}

New-Item -ItemType Directory -Path $directory -Force | Out-Null
$sequence = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$lines = @("sequence=$sequence")
if ($PSBoundParameters.ContainsKey('Sharc')) { $lines += "sharc=$($Sharc.ToString().ToLowerInvariant())" }
if ($PSBoundParameters.ContainsKey('DiffusePathGuide')) { $lines += "diffusePathGuide=$($DiffusePathGuide.ToString().ToLowerInvariant())" }
if ($PSBoundParameters.ContainsKey('FrameStats')) { $lines += "frameStats=$($FrameStats.ToString().ToLowerInvariant())" }
if ($PSBoundParameters.ContainsKey('TerrainBenchmark')) { $lines += "terrainBenchmark=$($TerrainBenchmark.ToString().ToLowerInvariant())" }
if ($PSBoundParameters.ContainsKey('TerrainDispatch')) { $lines += "terrainDispatch=$TerrainDispatch" }
if ($PSBoundParameters.ContainsKey('TerrainResults')) { $lines += "terrainResults=$TerrainResults" }
if ($PSBoundParameters.ContainsKey('TerrainInflight')) { $lines += "terrainInflight=$TerrainInflight" }
if ($PSBoundParameters.ContainsKey('TerrainBuildBatch')) { $lines += "terrainBuildBatch=$TerrainBuildBatch" }
if ($PSBoundParameters.ContainsKey('Omm')) { $lines += "omm=$($Omm.ToString().ToLowerInvariant())" }
if ($PSBoundParameters.ContainsKey('OmmSubdivision')) { $lines += "ommSubdivision=$OmmSubdivision" }
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
