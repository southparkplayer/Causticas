package dev.comfyfluffy.caustica.rt.pipeline;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NightRenderingContractTest {
    @Test
    void everyRayFamilyCarriesAndUsesTheGeometricNormal() throws Exception {
        String common = Files.readString(Path.of("shaders/world/world_common.slang"));
        String hit = Files.readString(Path.of("shaders/world/world.rchit.slang"));
        String raygen = Files.readString(Path.of("shaders/world/world.rgen.slang"));
        assertTrue(common.contains("public float3 geometricNormal;"));
        assertFalse(common.contains("#if CAUSTICA_SHARC\n    // Compiled only"));
        assertTrue(hit.contains("payload.geometricNormal = geometricNormal"));
        assertTrue(raygen.contains("float3 geometricNormal = payload.geometricNormal"));
        assertTrue(raygen.contains("offsetRayOrigin(hitPos, geometricNormal, lightDir)"));
        assertTrue(raygen.contains("offsetRayOrigin(surfacePos, surfaceGeometricNormal, specDir)"));
        assertTrue(raygen.contains("float3 firstSideNormal = dot(surfaceGeometricNormal, direction) >= 0.0"));
        assertTrue(raygen.contains("surfacePos + rayBias * firstSideNormal"));
        assertTrue(raygen.contains("RAY_ORIGIN_BIAS = 2.0e-4"));
        assertTrue(raygen.contains("return position + RAY_ORIGIN_BIAS * sideNormal"));
        assertFalse(raygen.contains("asfloat(asint(position)"));
        assertFalse(raygen.contains("SURF_BIAS = 0.005"));
    }

    @Test
    void commandTimeJumpsResetSharcDlssAndExposure() throws Exception {
        String composite = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java"));
        int handler = composite.indexOf("private void handleSkyDiscontinuity");
        String body = composite.substring(handler, Math.min(composite.length(), handler + 2200));
        assertTrue(body.contains("timeJump"));
        assertTrue(body.contains("requestSharcReset(reason)"));
        assertTrue(body.contains("RtDlssRr.INSTANCE.requestHistoryReset()"));
        assertTrue(body.contains("exposure.resetAutoHistory()"));
        String packetMixin = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/mixin/ClientPacketListenerMixin.java"));
        assertTrue(packetMixin.contains("observeServerTime(packet.gameTime(), state.totalTicks())"));
    }

    @Test
    void nightControlsScaleSourcesAndNotTheDenoiser() throws Exception {
        String composite = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java"));
        String miss = Files.readString(Path.of("shaders/world/world.rmiss.slang"));
        String skyLut = Files.readString(Path.of("shaders/display/sky_view.comp.slang"));
        assertTrue(composite.contains("lunarIlluminanceLux(moonMultiplier, litFraction"));
        assertTrue(composite.contains("return 0.25f * moonMultiplier"));
        assertTrue(composite.contains("moonTopOfAtmosphereLux * sceneScale"));
        assertTrue(skyLut.contains("pc.moon.w"));
        assertTrue(miss.contains("pc.skyLighting.z"));
        assertTrue(miss.contains("bool aboveHorizon = dir.y >= 0.0"));
        assertFalse(miss.contains("downFade = exp"));
        assertTrue(miss.contains("groundVoid"));
        assertTrue(miss.contains("boundaryWeight = smoothstep(-1.0e-3, 0.0, dir.y)"));
        assertFalse(miss.contains("if (!aboveHorizon) col = min"));
        assertTrue(composite.contains("if (sunLuma >= moonLuma)"));
        assertFalse(Files.readString(Path.of("shaders/world/world.rgen.slang"))
                .contains("DirectCelestialSample"));
    }
}
