package dev.comfyfluffy.caustica.rt.pipeline;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PhysicalSceneContractTest {
    @Test
    void spectralSkyUsesThePublishedFourSampleModel() throws Exception {
        String miss = Files.readString(Path.of("shaders/world/world.rmiss.slang"));
        assertTrue(miss.contains("630, 560, 490, 430 nm"));
        assertTrue(miss.contains("float4(1.679, 1.828, 1.986, 1.307)"));
        assertTrue(miss.contains("float4(6.605e-3, 1.067e-2, 1.842e-2, 3.156e-2)"));
        assertTrue(miss.contains("SPECTRAL_OZONE_DU = 334.5"));
        assertTrue(miss.contains("spectralToBt2020"));
        assertTrue(miss.contains("sampleSkyView(skyDir)"));
        assertTrue(miss.contains("filterSky ? sampleMirrorSky(skyDir, pc) : sampleSkyView(skyDir)"));
        assertTrue(miss.contains("spectralAtmosphere(dir, pc.sunDir.xyz, solarSource)"));
        assertFalse(miss.contains("sampleSkyViewFiltered"));
        assertFalse(miss.contains("cubicBSplineWeights"));
        assertTrue(miss.contains("PAYLOAD_FILTER_SKY"));
        String skyLut = Files.readString(Path.of("shaders/display/sky_view.comp.slang"));
        assertTrue(skyLut.contains("pc.sun.w"));
        assertTrue(skyLut.contains("VIEW_STEPS = 32"));
        assertTrue(skyLut.contains("LIGHT_STEPS = 16"));
        assertTrue(skyLut.contains("uv.y * uv.y * (0.5 * PI)"));
        assertFalse(skyLut.contains("signedElevation"));
        assertTrue(miss.contains("SPECTRAL_SKY_PHOTOMETRIC_SCALE = 0.9765625"));
        assertFalse(miss.contains("SPECTRAL_MULTISCATTER_RETURN"));
        assertTrue(miss.contains("pc.environmentSky.xyz"));
        assertTrue(miss.contains("float solarDisc"));
        assertTrue(miss.contains("SUN_LIMB_DARKENING = 0.6"));
        assertTrue(miss.contains("1.0 - SUN_LIMB_DARKENING / 3.0"));
        assertFalse(miss.contains("MOON_DISC_HALF_ANGLE"));
        assertFalse(miss.contains("sunIsNeeLight ? max(pc.lightDir.w"));
        assertTrue(miss.contains("celestialTextureLod"));
        assertTrue(miss.contains("pc.celestialRadii.x"));
        assertTrue(miss.contains("squareBody(dir, pc.moonDir.xyz, pc.celestialRadii.y"));
        assertTrue(miss.contains("celestialTextureLod(pc.moonUv, pc.celestialRadii.y"));
        assertTrue(miss.contains("validUvRect(pc.moonUv)"));
        assertTrue(miss.contains("1.0 - smoothstep(0.90, 1.0, m)"));
        assertFalse(miss.contains("float core = pow(clamp(dot(t.rgb, t.rgb) * 0.45"));
        assertFalse(miss.contains("return m <= 1.0 ? 1.0 : 0.0"));
    }

    @Test
    void moonDirectLightAndDebugStateShareThePhysicalVisibilityContract() throws Exception {
        String composite = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java"));
        String bridge = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/client/CausticaDebugBridge.java"));
        assertTrue(composite.contains("lunarIlluminanceLux(moonMultiplier, litFraction"));
        assertTrue(composite.contains("AstronomicalSky.lunarDiscHorizonVisibility(moonY, moonAngularRadius)"));
        assertTrue(composite.contains("return 0.25f * moonMultiplier"));
        assertTrue(composite.contains("lightRadius = moonAngularRadius"));
        for (String property : new String[] {"moonAltitudeRadians", "moonLitFraction",
                "moonHorizonVisibility", "moonEffectiveIlluminanceLux"}) {
            assertTrue(bridge.contains("setProperty(\"" + property + "\""), property);
        }
    }

    @Test
    void exposureMetersSceneLinearBt2020WithoutInventingBlackSignal() throws Exception {
        String histogram = Files.readString(Path.of("shaders/display/exposure_hist.comp"));
        String resolve = Files.readString(Path.of("shaders/display/exposure_resolve.comp"));
        assertTrue(histogram.contains("vec3(0.2627, 0.6780, 0.0593)"));
        assertTrue(histogram.contains("lum >= signalFloor"));
        assertFalse(histogram.contains("max(lum, 1.0e-5)"));
        assertTrue(histogram.contains("center * clamp(pc.centerWeight, 0.0, 8.0)"));
        assertTrue(histogram.contains("SAMPLE_STRIDE = 2"));
        assertTrue(resolve.contains("highlightPercentile"));
        assertTrue(resolve.contains("uint total = 0u"));
        assertTrue(histogram.contains("TRUSTED_TILE_COUNT"));
        assertTrue(histogram.contains("imageLoad(depthImage"));
        assertTrue(resolve.contains("UNRELIABLE_MAX_EV = 4.0"));
        assertTrue(resolve.contains("COHERENT_MAX_EV = 12.0"));
        assertTrue(resolve.contains("PERSISTENCE_FRAMES = 8u"));
        assertTrue(histogram.contains("TILE_HISTORY_STRIDE = 20u"));
        assertTrue(histogram.contains("comparable >= 8u"));
        assertTrue(histogram.contains("max(pc.logMin, -20.0)"));
        assertTrue(resolve.contains("connectedPersistentSignal"));
        assertTrue(resolve.contains("adapted = min(adapted, exp2(ceilingEv))"));
        assertFalse(histogram.contains("imageLoad(colorImage, pix).rgb * 1024.0"));
        String composite = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java"));
        assertTrue(composite.contains("exposure.record(ctx, cmd, stack, output, gDepth, offlineGroundTruth)"));
        assertFalse(composite.contains("exposure.record(ctx, cmd, stack, displayInput, gDepth, offlineGroundTruth)"));
    }

    @Test
    void torchRemainsAnAuthoredEmissiveTransportSignal() throws Exception {
        String raygen = Files.readString(Path.of("shaders/world/world.rgen.slang"));
        assertTrue(raygen.contains("EMISSIVE_BASE_RADIANCE = 0.1953125"));
        assertTrue(raygen.contains("payloadTorchEmitter()"));
        assertTrue(raygen.contains("albedo * emission * emissiveRadiance"));
        assertFalse(raygen.contains("sampleTerrainEmitter"));
        assertFalse(raygen.contains("torch next-event"));
    }

    @Test
    void hdrKeepsBt2020AndSdrUsesHuePreservingGamutMapping() throws Exception {
        String display = Files.readString(Path.of("shaders/display/display.comp"));
        assertTrue(display.contains("gamutMapBt2020ToBt709(scene2020)"));
        assertTrue(display.contains("tonemapHdr(scene2020, exposure)"));
        assertFalse(display.contains("tonemapHdr(scene709, exposure)"));
        assertTrue(display.contains("float luma = max(dot(scene2020, LUMA_BT2020), 0.0)"));
        assertTrue(display.contains("psychoV24Linear(paperReferred2020, true"));
        assertTrue(display.contains("outputDither"));
        assertTrue(display.contains("ditherSequence.values"));
        assertTrue(display.contains("pc.frameIndex & 7u"));
        assertTrue(display.contains("sampleBloom(pix, size)"));
        String bloom = Files.readString(Path.of("shaders/display/bloom.comp"));
        assertTrue(bloom.contains("smoothstep(1.0, 4.0, exposedLuminance)"));
        assertTrue(bloom.contains("imageSize(bloomEighth)"));
        assertTrue(bloom.contains("tentEighth"));
        assertFalse(display.contains("rt.rgb * 1024.0"));
    }

    @Test
    void celestialAtlasBindingFailsClosedAndBodiesClipPerRayAtTheHorizon() throws Exception {
        String composite = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java"));
        String miss = Files.readString(Path.of("shaders/world/world.rmiss.slang"));
        assertTrue(composite.contains("world and celestial atlases must be ready before RT pipeline creation"));
        assertFalse(composite.contains("celView != 0L ? celView : atlasView"));
        assertFalse(composite.contains("celestialView != 0L ? celestialView : atlasView"));
        assertTrue(miss.contains("if (aboveHorizon && sunCoverage > 0.0)"));
        assertTrue(miss.contains("if (aboveHorizon && moonCoverage > 0.0 && validUvRect(pc.moonUv))"));
        assertFalse(miss.contains("if (sd.y > 0.0"));
        assertFalse(miss.contains("if (pc.moonDir.y > 0.0"));
        assertFalse(composite.contains("starBrightness *= rainBrightness"));
    }

    @Test
    void breakingOverlayMultipliesReflectanceBeforeThePrimaryConversion() throws Exception {
        String hit = Files.readString(Path.of("shaders/world/world.rchit.slang"));
        assertTrue(hit.contains("float3 crack709 = srgbToLinear"));
        assertTrue(hit.contains("return clamp(crack709 * albedo709, 0.0, 1.0)"));
        assertTrue(hit.contains("payload.albedo = bt709ToBt2020(applyBreaking(opticalColor709"));
        assertTrue(hit.contains("payload.albedo = bt709ToBt2020(albedo709)"));
        assertFalse(hit.contains("bt709ToBt2020(srgbToLinear(\n                    entityTex"));
    }

    @Test
    void calibratedRadianceUsesTheNeutralDlssdContractAndGlossyNeeOwnsTheDisc() throws Exception {
        String raygen = Files.readString(Path.of("shaders/world/world.rgen.slang"));
        String bridge = Files.readString(Path.of("native/streamline_bridge/streamline_bridge.cpp"));
        assertFalse(raygen.contains("frameRadiance * (1.0 / 1024.0)"));
        assertFalse(raygen.contains("runningMean * (1.0 / 1024.0)"));
        assertTrue(raygen.contains("GGX direct lighting already owns the finite celestial disk"));
        assertTrue(raygen.contains("showCelestial = false;"));
        assertTrue(bridge.contains("options.preExposure = 1.0f"));
    }

    @Test
    void everyExposureControlIsAvailableInBothSettingsWorkstations() throws Exception {
        String primary = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/client/CausticaSettingsScreen.java"));
        String options = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/client/RtVideoOptions.java"));
        String workstation = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/client/RtTonemapOptionsScreen.java"));
        for (String setting : new String[] {"KEY", "LOW_PERCENTILE", "HIGH_PERCENTILE",
                "HIGHLIGHT_PERCENTILE", "HIGHLIGHT_HEADROOM", "ADAPT_UP", "ADAPT_DOWN",
                "MIN_EV", "MAX_EV", "CENTER_WEIGHT", "LOG_MIN", "LOG_MAX"}) {
            assertTrue(primary.contains("Rt.Exposure." + setting));
            assertTrue(options.contains("Rt.Exposure." + setting));
        }
        assertTrue(workstation.contains("exposureWorkstationControls()"));
        assertTrue(primary.contains("MOON_ANGULAR_RADIUS"));
        assertTrue(options.contains("moonSize()"));
        assertTrue(primary.contains("AMBIENT_LIGHT_EV"));
        assertTrue(options.contains("ambientLight()"));
        assertTrue(primary.contains("SUNLIGHT_INTENSITY_EV"));
        assertTrue(primary.contains("MOONLIGHT_INTENSITY_EV"));
        assertTrue(primary.contains("NIGHT_AIRGLOW_EV"));
        assertTrue(primary.contains("ASTRONOMICAL_LATITUDE_DEG"));
        assertTrue(primary.contains("DAY_OF_YEAR_OFFSET"));
        assertTrue(options.contains("astronomicalLatitude()"));
        assertTrue(options.contains("dayOfYearOffset()"));
        assertTrue(primary.contains("COMPENSATION_EV"));
    }
}
