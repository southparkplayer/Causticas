package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.RtComposite;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SkyControlsContractTest {
    @Test
    void exactPsrMissesSharePrimaryHorizonWithoutOpeningTransportVoid() throws Exception {
        String miss = Files.readString(Path.of("shaders/world/world.rmiss.slang"));
        assertTrue(miss.contains("bool presentationSky = primaryRay || filterSky"));
        assertTrue(miss.contains("PAYLOAD_PRESENTATION_SKY"));
        assertTrue(miss.contains("if (!presentationSky && earthAtmosphere && !aboveHorizon)"));
        assertTrue(miss.contains("if (earthAtmosphere && aboveHorizon && (!primaryRay || !directSkyStarLayer))"));
        assertTrue(miss.contains("col += sharedStars"));
        assertTrue(miss.contains("if (aboveHorizon && sunCoverage > 0.0)"));
        assertTrue(miss.contains("if (aboveHorizon && moonCoverage > 0.0"));
        assertFalse(miss.contains("if (!primaryRay && earthAtmosphere && !aboveHorizon)"));
    }

    @Test
    void skyPassKeepsFixedWorkAndFitsGuaranteedPushConstantBudget() throws Exception {
        String shader = Files.readString(Path.of("shaders/display/sky_view.comp.slang"));
        String pipeline = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtSkyViewPipeline.java"));
        assertTrue(shader.contains("VIEW_STEPS = 32"));
        assertTrue(shader.contains("LIGHT_STEPS = 16"));
        assertTrue(shader.contains("float4 optical"));
        assertTrue(shader.contains("float4 shape"));
        assertTrue(shader.contains("float4 tint"));
        assertTrue(pipeline.contains(".offset(0).size(88)"));
        assertTrue(88 <= 128);
    }

    @Test
    void artistDefaultsUseTheDeepBlueCalibrationAndNeutralRgb() {
        assertEquals(0.02f, CausticaConfig.Rt.Composite.SKY_RAYLEIGH.defaultValue());
        assertEquals(1.0022436f, CausticaConfig.Rt.Composite.SKY_DAY_RAYLEIGH.defaultValue());
        assertEquals(4.0f, CausticaConfig.Rt.Composite.SKY_AEROSOL_SCATTER.defaultValue());
        assertEquals(1.0f, CausticaConfig.Rt.Composite.SKY_SATURATION.defaultValue());
        assertEquals(-1.0f, CausticaConfig.Rt.Composite.SUN_DISC_BRIGHTNESS_EV.defaultValue());
        assertEquals(1.0f, CausticaConfig.Rt.Composite.SKY_TINT_R.defaultValue());
        assertEquals(1.0f, CausticaConfig.Rt.Composite.SKY_TINT_G.defaultValue());
        assertEquals(1.0f, CausticaConfig.Rt.Composite.SKY_TINT_B.defaultValue());
    }

    @Test
    void rayleighStrengthUsesTheNightSettingAndReachesFourInDaylight() {
        assertEquals(0.02f, RtComposite.interpolatedRayleighStrength(0.0f, 0.02f, 4.0f), 1.0e-6f);
        assertEquals(2.01f, RtComposite.interpolatedRayleighStrength(0.5f, 0.02f, 4.0f), 1.0e-6f);
        assertEquals(4.0f, RtComposite.interpolatedRayleighStrength(1.0f, 0.02f, 4.0f), 1.0e-6f);
        assertEquals(3.0f, RtComposite.interpolatedRayleighStrength(1.0f, 0.02f, 3.0f), 1.0e-6f);
        assertEquals(0.02f, RtComposite.interpolatedRayleighStrength(-1.0f, 0.0f, 0.0f), 1.0e-6f);
    }

    @Test
    void workstationOwnsAResettableSearchableSkyCategory() throws Exception {
        String screen = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/client/CausticaSettingsScreen.java"));
        String language = Files.readString(Path.of(
                "src/main/resources/assets/caustica/lang/en_us.json"));
        assertTrue(screen.contains("case SKY_ATMOSPHERE -> addSky()"));
        assertTrue(screen.contains("private void addSky()"));
        assertTrue(screen.contains("resetOnShift(() -> setting.set(setting.defaultValue()))"));
        assertTrue(language.contains("\"caustica.options.category.skyAtmosphere\": \"Sky & Atmosphere\""));
        assertTrue(language.contains("caustica.options.rt.sky.aerosolScatter"));
        assertTrue(language.contains("Night Rayleigh Strength"));
        assertTrue(screen.contains("SKY_DAY_RAYLEIGH"));
        assertTrue(language.contains("caustica.options.rt.sky.dayRayleigh"));
        assertTrue(language.contains("caustica.options.rt.sky.airglowZenithB"));
    }

    @Test
    void discPresentationControlsStayOutOfDirectLightCalibration() throws Exception {
        String miss = Files.readString(Path.of("shaders/world/world.rmiss.slang"));
        String composite = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java"));
        assertTrue(miss.contains("pc.skyCelestialAppearance.x * pc.skySunTint.xyz"));
        assertTrue(miss.contains("pc.skyCelestialAppearance.y"));
        int sunPeak = composite.indexOf("float sunPeak = 120_000.0f");
        int discScale = composite.indexOf("float sunDiscScale =");
        assertTrue(sunPeak >= 0 && discScale > sunPeak);
        assertFalse(composite.substring(sunPeak, discScale).contains("SUN_DISC_BRIGHTNESS_EV"));
    }
}
