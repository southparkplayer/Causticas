$ErrorActionPreference = "Stop"

$candelaRoot = $PSScriptRoot

Push-Location $candelaRoot
try {
	$env:JAVA_TOOL_OPTIONS='-Xmx8G -XX:+UseCompactObjectHeaders -XX:+AlwaysPreTouch -XX:+UseStringDeduplication -XX:+UseZGC -Dcandela.rt.fg=false -Dcandela.rt.reflex=false -Dcandela.rt.frameStats=false -Dcandela.rt.lodWorld=true -Dcandela.rt.lodDebug=true'
	.\gradlew.bat --stop
	.\gradlew.bat runClient --args="--renderDebugLabels --graphicsBackend VULKAN"
} finally {
	Pop-Location
}
