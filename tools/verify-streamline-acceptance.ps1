[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$MinecraftRoot,

    [ValidateRange(1, 5)]
    [int]$GeneratedFrames,

    [switch]$RequireProduction,
    [switch]$RequireDlssd
)

$ErrorActionPreference = 'Stop'
$reportPath = Join-Path $MinecraftRoot 'caustica-streamline\dlssg-acceptance.json'
if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) {
    throw "Missing Streamline acceptance report: $reportPath"
}
$report = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
$failures = New-Object 'System.Collections.Generic.List[string]'

if ($report.schemaVersion -ne 6) {
    $failures.Add("schemaVersion is $($report.schemaVersion), expected 6")
}
if ($RequireProduction -and -not $report.identity.production) {
    $failures.Add("runtime identity is $($report.identity.streamlineVariant), expected production")
}
if ($report.identity.developmentBehaviorOverrideActive) {
    $failures.Add('a development plugin behavior override is active')
}
if (@($report.activeOverrides).Count -ne 0) {
    $keys = @($report.activeOverrides | ForEach-Object key)
    $failures.Add("launch overrides remain active: $($keys -join ', ')")
}
if ($report.dlssg.verdict -ne 'PASS') {
    $failures.Add("DLSSG verdict is $($report.dlssg.verdict), expected PASS")
}
if ($PSBoundParameters.ContainsKey('GeneratedFrames')) {
    foreach ($field in @('configuredGeneratedFrames', 'effectiveGeneratedFrames', 'nativeSubmittedGeneratedFrames')) {
        if ($report.dlssg.$field -ne $GeneratedFrames) {
            $failures.Add("DLSSG $field is $($report.dlssg.$field), expected $GeneratedFrames")
        }
    }
    if ($report.dlssg.reportedMaximumGeneratedFrames -lt $GeneratedFrames) {
        $failures.Add("reported maximum is $($report.dlssg.reportedMaximumGeneratedFrames), below $GeneratedFrames")
    }
    if ($report.dlssg.maximumNumFramesActuallyPresented -lt ($GeneratedFrames + 1)) {
        $failures.Add("maximum presentation is $($report.dlssg.maximumNumFramesActuallyPresented), expected at least $($GeneratedFrames + 1)")
    }
}
if (-not $report.dlssg.capabilityStateValid) {
    $failures.Add('DLSSG capability state is not valid')
}
$expectedQueueMode = if ($report.dlssg.logicalVsyncRequested) { 'block-presenting-queue' } else { 'no-client-queues' }
if ($report.dlssg.effectiveQueueMode -ne $expectedQueueMode) {
    $failures.Add("effective queue mode is $($report.dlssg.effectiveQueueMode), expected $expectedQueueMode")
}
if ($report.dlssg.queueFallbackActive) {
    $failures.Add("queue fallback is active: $($report.dlssg.queueFallbackReason)")
}
if ($report.dlssg.timelineWaitFailures -ne 0) {
    $failures.Add("timeline wait failures is $($report.dlssg.timelineWaitFailures), expected 0")
}
if ($report.dlssg.steadyStateDeviceIdleCount -ne 0) {
    $failures.Add("steady-state device idle count is $($report.dlssg.steadyStateDeviceIdleCount), expected 0")
}
if ($report.dlssg.inputSlotCount -ne $report.dlssg.applicationImageCount) {
    $failures.Add("input slot count $($report.dlssg.inputSlotCount) does not match application image count $($report.dlssg.applicationImageCount)")
}
if ($report.dlssg.dynamicMfgSupported) {
    $failures.Add('Vulkan unexpectedly reports Dynamic MFG; fixed mode is the accepted target')
}

if ($RequireDlssd) {
    if ($report.dlssd.verdict -ne 'PASS') {
        $failures.Add("DLSSD verdict is $($report.dlssd.verdict), expected PASS")
    }
    if (-not $report.dlssd.configured -or -not $report.dlssd.effective) {
        $failures.Add('DLSSD is not configured and effective')
    }
    if ($report.dlssd.lastOptionsResult -ne 0 -or $report.dlssd.lastEvaluationResult -ne 0) {
        $failures.Add('DLSSD option/evaluation result is not success')
    }
    if ($report.dlssd.submittedResourceCount -ne 8 -or $report.dlssd.nativeResourceCount -ne 8) {
        $failures.Add('DLSSD did not preserve the exact eight-resource contract')
    }
    if ($report.dlssd.nativeOptionsCalls -lt 1 -or $report.dlssd.nativeEvaluationCalls -lt 1) {
        $failures.Add('DLSSD native call counters are zero')
    }
    if ($report.dlssd.fallbackActive) {
        $failures.Add("DLSSD fallback is active: $($report.dlssd.fallbackReason)")
    }
}

$result = [pscustomobject]@{
    Pass = $failures.Count -eq 0
    Report = $reportPath
    Variant = $report.identity.streamlineVariant
    GeneratedFrames = $report.dlssg.nativeSubmittedGeneratedFrames
    MaximumGeneratedFrames = $report.dlssg.reportedMaximumGeneratedFrames
    MaximumPresentedFrames = $report.dlssg.maximumNumFramesActuallyPresented
    DlssdVerdict = $report.dlssd.verdict
    Failures = @($failures)
}
$result | ConvertTo-Json -Depth 5
if ($failures.Count -ne 0) {
    exit 1
}
