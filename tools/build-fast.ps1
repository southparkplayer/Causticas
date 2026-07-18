param(
    [ValidateSet('Shaders', 'Test', 'Jar', 'Deploy', 'Full')]
    [string]$Mode = 'Test',
    [switch]$WithSharc,
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
    Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object {
            $_.Name -match '^javaw?\.exe$' -and
            $_.CommandLine -and
            $_.CommandLine.IndexOf($Instance, [StringComparison]::OrdinalIgnoreCase) -ge 0
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

if ($WithSharc) {
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

Write-Host "Pinned Java:      $javaHome"
Write-Host "Pinned Slang:     $slangc"
Write-Host "Pinned Vulkan:    $vulkanSdk"
Write-Host "Pinned Streamline:$streamlineSdk"
Write-Host "Pinned SHaRC:     $(if ($WithSharc) { $sharcSdk } else { 'off (pass -WithSharc to include)' })"
Write-Host "Gradle tasks:     $($tasks -join ' ')"

Push-Location $repo
try {
    & $gradle @tasks '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }

    if ($Mode -in @('Jar', 'Deploy', 'Full')) {
        Require-File $builtJar 'Built Caustica JAR'
        $builtHash = (Get-FileHash -LiteralPath $builtJar -Algorithm SHA256).Hash
        Write-Host "Built JAR SHA-256: $builtHash"
    }

    if ($Mode -eq 'Deploy') {
        $live = Get-LiveMinecraftProcess
        if ($live) {
            throw "Refusing to replace a JAR under live Minecraft PID $($live.ProcessId). Shut it down through tools\caustica-debug-bridge.ps1 first."
        }
        $mods = Split-Path -Parent $deployedJar
        if (-not (Test-Path -LiteralPath $mods -PathType Container)) {
            throw "Prism mods directory is missing: $mods"
        }
        $temporary = "$deployedJar.tmp"
        Copy-Item -LiteralPath $builtJar -Destination $temporary -Force
        Move-Item -LiteralPath $temporary -Destination $deployedJar -Force
        $deployedHash = (Get-FileHash -LiteralPath $deployedJar -Algorithm SHA256).Hash
        if ($deployedHash -ne $builtHash) {
            throw "Deployment hash mismatch: built=$builtHash deployed=$deployedHash"
        }
        Write-Host "Deployed JAR:       $deployedJar"
        Write-Host "Deployed SHA-256:   $deployedHash"
    }
} finally {
    Pop-Location
}
