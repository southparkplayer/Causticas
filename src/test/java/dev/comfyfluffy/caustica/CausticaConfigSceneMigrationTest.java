package dev.comfyfluffy.caustica;

import com.electronwill.nightconfig.core.CommentedConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CausticaConfigSceneMigrationTest {
    @Test
    void obsoleteExposureDefaultsMigrateExactlyOnce() {
        CommentedConfig config = CommentedConfig.inMemory();
        config.set("exposure.min-ev", -1.5);
        config.set("exposure.max-ev", 2.0);
        config.set("exposure.adapt-up", 0.12);
        config.set("exposure.adapt-down", 0.35);

        assertTrue(CausticaConfig.migrateLegacySceneConfig(config));
        assertEquals(CausticaConfig.CONFIG_SCHEMA_VERSION, ((Number) config.get("config-version")).intValue());
        assertEquals(-20.0, ((Number) config.get("exposure.min-ev")).doubleValue());
        assertEquals(12.0, ((Number) config.get("exposure.max-ev")).doubleValue());
        assertEquals(3.0, ((Number) config.get("exposure.adapt-up")).doubleValue());
        assertEquals(2.0, ((Number) config.get("exposure.adapt-down")).doubleValue());
        assertFalse(CausticaConfig.migrateLegacySceneConfig(config));
    }

    @Test
    void schemaTwoSuperhumanDefaultMigratesButCustomCeilingDoesNot() {
        CommentedConfig oldDefault = CommentedConfig.inMemory();
        oldDefault.set("config-version", 2);
        oldDefault.set("exposure.max-ev", 20.0);
        assertTrue(CausticaConfig.migrateLegacySceneConfig(oldDefault));
        assertEquals(12.0, ((Number) oldDefault.get("exposure.max-ev")).doubleValue());

        CommentedConfig custom = CommentedConfig.inMemory();
        custom.set("config-version", 2);
        custom.set("exposure.max-ev", 12.0);
        assertTrue(CausticaConfig.migrateLegacySceneConfig(custom));
        assertEquals(12.0, ((Number) custom.get("exposure.max-ev")).doubleValue());
    }

    @Test
    void userExposureAndTorchChoicesArePreserved() {
        CommentedConfig config = CommentedConfig.inMemory();
        config.set("exposure.min-ev", -6.0);
        config.set("exposure.adapt-up", 1.25);
        config.set("composite.torch-emission-multiplier", 0.1529052);

        assertTrue(CausticaConfig.migrateLegacySceneConfig(config));
        assertEquals(-6.0, ((Number) config.get("exposure.min-ev")).doubleValue());
        assertEquals(1.25, ((Number) config.get("exposure.adapt-up")).doubleValue());
        assertEquals(0.1529052,
                ((Number) config.get("composite.torch-emission-multiplier")).doubleValue());
    }

    @Test
    void legacyCelestialTiltIsDiscardedAtSchemaFour() {
        CommentedConfig config = CommentedConfig.inMemory();
        config.set("config-version", 3);
        config.set("composite.sun-noon-south-tilt-deg", 47.0);
        assertTrue(CausticaConfig.migrateLegacySceneConfig(config));
        assertFalse(config.contains("composite.sun-noon-south-tilt-deg"));
    }

    @Test
    void legacyExposureDialSplitsByModeAtSchemaFive() {
        CommentedConfig auto = CommentedConfig.inMemory();
        auto.set("config-version", 4);
        auto.set("exposure.mode", "auto");
        auto.set("exposure.manual-ev", -1.25);
        assertTrue(CausticaConfig.migrateLegacySceneConfig(auto));
        assertEquals(-1.25, ((Number) auto.get("exposure.compensation-ev")).doubleValue());
        assertFalse(auto.contains("exposure.manual-ev"));

        CommentedConfig manual = CommentedConfig.inMemory();
        manual.set("config-version", 4);
        manual.set("exposure.mode", "manual");
        manual.set("exposure.manual-ev", 2.5);
        assertTrue(CausticaConfig.migrateLegacySceneConfig(manual));
        assertEquals(2.5, ((Number) manual.get("exposure.manual-exposure-ev")).doubleValue());
    }

    @Test
    void legacyExposureDialNeverOverwritesTheNewDestination() {
        CommentedConfig auto = CommentedConfig.inMemory();
        auto.set("config-version", 4);
        auto.set("exposure.mode", "auto");
        auto.set("exposure.manual-ev", -1.25);
        auto.set("exposure.compensation-ev", 0.75);
        assertTrue(CausticaConfig.migrateLegacySceneConfig(auto));
        assertEquals(0.75, ((Number) auto.get("exposure.compensation-ev")).doubleValue());
        assertFalse(auto.contains("exposure.manual-ev"));

        CommentedConfig manual = CommentedConfig.inMemory();
        manual.set("config-version", 4);
        manual.set("exposure.mode", "manual");
        manual.set("exposure.manual-ev", 2.5);
        manual.set("exposure.manual-exposure-ev", -0.5);
        assertTrue(CausticaConfig.migrateLegacySceneConfig(manual));
        assertEquals(-0.5, ((Number) manual.get("exposure.manual-exposure-ev")).doubleValue());
        assertFalse(manual.contains("exposure.manual-ev"));
    }

    @Test
    void schemaFiveMoonBoostMigratesOnlyWhenItIsStillTheOldDefault() {
        CommentedConfig oldDefault = CommentedConfig.inMemory();
        oldDefault.set("config-version", 5);
        oldDefault.set("composite.moonlight-intensity-ev", 3.0);
        assertTrue(CausticaConfig.migrateLegacySceneConfig(oldDefault));
        assertEquals(0.0, ((Number) oldDefault.get("composite.moonlight-intensity-ev")).doubleValue());

        CommentedConfig custom = CommentedConfig.inMemory();
        custom.set("config-version", 5);
        custom.set("composite.moonlight-intensity-ev", 1.5);
        assertTrue(CausticaConfig.migrateLegacySceneConfig(custom));
        assertEquals(1.5, ((Number) custom.get("composite.moonlight-intensity-ev")).doubleValue());
    }

    @Test
    void schemaSevenNormalizesOnlyTheObsoleteTerrainBurstDefaults() {
        CommentedConfig oldDefaults = CommentedConfig.inMemory();
        oldDefaults.set("config-version", 6);
        oldDefaults.set("terrain.async-dispatch-per-tick", 64);
        oldDefaults.set("terrain.section-results-per-tick", 64);
        oldDefaults.set("terrain.max-inflight-sections", 192);
        assertTrue(CausticaConfig.migrateLegacySceneConfig(oldDefaults));
        assertEquals(32, ((Number) oldDefaults.get("terrain.async-dispatch-per-tick")).intValue());
        assertEquals(32, ((Number) oldDefaults.get("terrain.section-results-per-tick")).intValue());
        assertEquals(32, ((Number) oldDefaults.get("terrain.max-inflight-sections")).intValue());

        CommentedConfig custom = CommentedConfig.inMemory();
        custom.set("config-version", 6);
        custom.set("terrain.async-dispatch-per-tick", 12);
        custom.set("terrain.section-results-per-tick", 18);
        custom.set("terrain.max-inflight-sections", 48);
        assertTrue(CausticaConfig.migrateLegacySceneConfig(custom));
        assertEquals(12, ((Number) custom.get("terrain.async-dispatch-per-tick")).intValue());
        assertEquals(18, ((Number) custom.get("terrain.section-results-per-tick")).intValue());
        assertEquals(48, ((Number) custom.get("terrain.max-inflight-sections")).intValue());
    }

    @Test
    void schemaEightDerivesInputResolutionFromTheSavedDlssPreset() {
        CommentedConfig quality = CommentedConfig.inMemory();
        quality.set("config-version", 7);
        quality.set("reconstruction.backend", "nrd");
        quality.set("dlss-rr.quality", 2);
        assertTrue(CausticaConfig.migrateLegacySceneConfig(quality));
        assertEquals(15, ((Number) quality.get("dlss-rr.input-scale-percent")).intValue());

        CommentedConfig custom = CommentedConfig.inMemory();
        custom.set("config-version", 7);
        custom.set("reconstruction.backend", "nrd");
        custom.set("dlss-rr.quality", 2);
        custom.set("dlss-rr.input-scale-percent", 20);
        assertTrue(CausticaConfig.migrateLegacySceneConfig(custom));
        assertEquals(40, ((Number) custom.get("dlss-rr.input-scale-percent")).intValue());
    }

    @Test
    void schemaNineCollapsesLegacyCompoundedOutputAndRemovesFast() {
        CommentedConfig config = CommentedConfig.inMemory();
        config.set("config-version", 8);
        config.set("output-scale.percent", 80);
        config.set("output-scale.fast-percent", 125);
        assertTrue(CausticaConfig.migrateLegacySceneConfig(config));
        assertEquals(100, ((Number) config.get("output-scale.percent")).intValue());
        assertFalse(config.contains("output-scale.fast-percent"));
    }

    @Test
    void schemaTenMigratesLegacyFrameGenerationEnabledToMode() {
        CommentedConfig disabled = CommentedConfig.inMemory();
        disabled.set("config-version", 9);
        disabled.set("frame-generation.enabled", false);
        assertTrue(CausticaConfig.migrateLegacySceneConfig(disabled));
        assertEquals("off", disabled.get("frame-generation.mode"));

        CommentedConfig enabled = CommentedConfig.inMemory();
        enabled.set("config-version", 9);
        enabled.set("frame-generation.enabled", true);
        assertTrue(CausticaConfig.migrateLegacySceneConfig(enabled));
        assertEquals("fixed", enabled.get("frame-generation.mode"));
    }

    @Test
    void schemaTenDoesNotOverwriteExplicitModeWhenEnabledExists() {
        CommentedConfig config = CommentedConfig.inMemory();
        config.set("config-version", 9);
        config.set("frame-generation.enabled", false);
        config.set("frame-generation.mode", "fixed");
        assertTrue(CausticaConfig.migrateLegacySceneConfig(config));
        assertEquals("fixed", config.get("frame-generation.mode"));
    }

    @Test
    void schemaElevenMigratesSkyAndDisplayDefaultsFromOldDefaults() {
        CommentedConfig oldDefaults = CommentedConfig.inMemory();
        oldDefaults.set("config-version", 10);
        oldDefaults.set("composite.ambient-light-ev", -8.0);
        oldDefaults.set("composite.night-airglow-ev", 2.99);
        oldDefaults.set("composite.sky.day-rayleigh", 4.0);
        oldDefaults.set("composite.sky.aerosol-scatter", 0.55);
        oldDefaults.set("composite.sky.aerosol-absorption", 4.0);
        oldDefaults.set("composite.sky.ozone", 1.0);
        oldDefaults.set("composite.sky.aerosol-height-km", 4.0);
        oldDefaults.set("composite.sky.aerosol-anisotropy", 0.0);
        oldDefaults.set("composite.sky.star-brightness-ev", 8.0);
        oldDefaults.set("composite.sky.star-size", 1.0);
        oldDefaults.set("dlss-rr.quality", 0);
        oldDefaults.set("nrd.upscale-sharpness", 0.0);
        oldDefaults.set("nrd.min-blur-radius", 8.02);
        oldDefaults.set("frame-generation.multi-frame-count", 1);
        oldDefaults.set("exposure.high-percentile", 0.80);
        oldDefaults.set("sharc.cache-exponent", 24);
        oldDefaults.set("hdr.tonemap-mode", "psychov23");
        oldDefaults.set("hdr.paper-white-nits", 200.0);
        oldDefaults.set("hdr.ui-brightness-nits", 200.0);
        oldDefaults.set("hdr.peak-nits", 1000.0);
        assertTrue(CausticaConfig.migrateLegacySceneConfig(oldDefaults));
        assertEquals(CausticaConfig.CONFIG_SCHEMA_VERSION,
                ((Number) oldDefaults.get("config-version")).intValue());
        assertEquals(0.025641026,
                ((Number) oldDefaults.get("composite.ambient-light-ev")).doubleValue(), 1.0e-6);
        assertEquals(-8.0,
                ((Number) oldDefaults.get("composite.night-airglow-ev")).doubleValue(), 1.0e-6);
        assertEquals(1.0022436,
                ((Number) oldDefaults.get("composite.sky.day-rayleigh")).doubleValue(), 1.0e-6);
        assertEquals(4.0,
                ((Number) oldDefaults.get("composite.sky.aerosol-scatter")).doubleValue(), 1.0e-6);
        assertEquals(0.0,
                ((Number) oldDefaults.get("composite.sky.aerosol-absorption")).doubleValue(), 1.0e-6);
        assertEquals(2.0,
                ((Number) oldDefaults.get("composite.sky.ozone")).doubleValue(), 1.0e-6);
        assertEquals(0.1,
                ((Number) oldDefaults.get("composite.sky.aerosol-height-km")).doubleValue(), 1.0e-6);
        assertEquals(0.8053686,
                ((Number) oldDefaults.get("composite.sky.aerosol-anisotropy")).doubleValue(), 1.0e-6);
        assertEquals(2.025641,
                ((Number) oldDefaults.get("composite.sky.star-brightness-ev")).doubleValue(), 1.0e-6);
        assertEquals(0.50240386,
                ((Number) oldDefaults.get("composite.sky.star-size")).doubleValue(), 1.0e-6);
        assertEquals(2, ((Number) oldDefaults.get("dlss-rr.quality")).intValue());
        assertEquals(0.23453094,
                ((Number) oldDefaults.get("nrd.upscale-sharpness")).doubleValue(), 1.0e-6);
        assertEquals(8.01626,
                ((Number) oldDefaults.get("nrd.min-blur-radius")).doubleValue(), 1.0e-3);
        assertEquals(2, ((Number) oldDefaults.get("frame-generation.multi-frame-count")).intValue());
        assertEquals(0.99425286,
                ((Number) oldDefaults.get("exposure.high-percentile")).doubleValue(), 1.0e-6);
        assertEquals(23, ((Number) oldDefaults.get("sharc.cache-exponent")).intValue());
        assertEquals("eetf", oldDefaults.get("hdr.tonemap-mode"));
        assertEquals(203.0,
                ((Number) oldDefaults.get("hdr.paper-white-nits")).doubleValue(), 1.0e-6);
        assertEquals(100.0,
                ((Number) oldDefaults.get("hdr.ui-brightness-nits")).doubleValue(), 1.0e-6);
        assertEquals(800.0,
                ((Number) oldDefaults.get("hdr.peak-nits")).doubleValue(), 1.0e-6);
    }

    @Test
    void schemaTwelveConvertsLegacyAbsoluteInputScaleToRatioTenths() {
        CommentedConfig nativeOutput = CommentedConfig.inMemory();
        nativeOutput.set("config-version", 11);
        nativeOutput.set("reconstruction.backend", "nrd");
        nativeOutput.set("output-scale.percent", 100);
        nativeOutput.set("dlss-rr.input-scale-percent", 50);
        assertTrue(CausticaConfig.migrateLegacySceneConfig(nativeOutput));
        assertEquals(20, ((Number) nativeOutput.get("dlss-rr.input-scale-percent")).intValue());

        CommentedConfig source33 = CommentedConfig.inMemory();
        source33.set("config-version", 11);
        source33.set("reconstruction.backend", "nrd");
        source33.set("output-scale.percent", 100);
        source33.set("dlss-rr.input-scale-percent", 33);
        assertTrue(CausticaConfig.migrateLegacySceneConfig(source33));
        assertEquals(30, ((Number) source33.get("dlss-rr.input-scale-percent")).intValue());

        CommentedConfig double200To67 = CommentedConfig.inMemory();
        double200To67.set("config-version", 11);
        double200To67.set("reconstruction.backend", "nrd");
        double200To67.set("output-scale.percent", 200);
        double200To67.set("dlss-rr.input-scale-percent", 67);
        assertTrue(CausticaConfig.migrateLegacySceneConfig(double200To67));
        assertEquals(30, ((Number) double200To67.get("dlss-rr.input-scale-percent")).intValue());

        CommentedConfig double200To133 = CommentedConfig.inMemory();
        double200To133.set("config-version", 11);
        double200To133.set("reconstruction.backend", "nrd");
        double200To133.set("output-scale.percent", 200);
        double200To133.set("dlss-rr.input-scale-percent", 133);
        assertTrue(CausticaConfig.migrateLegacySceneConfig(double200To133));
        assertEquals(15, ((Number) double200To133.get("dlss-rr.input-scale-percent")).intValue());

        CommentedConfig single200To200 = CommentedConfig.inMemory();
        single200To200.set("config-version", 11);
        single200To200.set("reconstruction.backend", "nrd");
        single200To200.set("output-scale.percent", 10);
        single200To200.set("dlss-rr.input-scale-percent", 200);
        assertTrue(CausticaConfig.migrateLegacySceneConfig(single200To200));
        assertEquals(10, ((Number) single200To200.get("dlss-rr.input-scale-percent")).intValue());

        CommentedConfig double200To10 = CommentedConfig.inMemory();
        double200To10.set("config-version", 11);
        double200To10.set("reconstruction.backend", "nrd");
        double200To10.set("output-scale.percent", 200);
        double200To10.set("dlss-rr.input-scale-percent", 10);
        assertTrue(CausticaConfig.migrateLegacySceneConfig(double200To10));
        assertEquals(40, ((Number) double200To10.get("dlss-rr.input-scale-percent")).intValue());

        CommentedConfig missingValues = CommentedConfig.inMemory();
        missingValues.set("config-version", 11);
        missingValues.set("reconstruction.backend", "nrd");
        missingValues.set("output-scale.percent", 100);
        missingValues.set("dlss-rr.input-scale-percent", "invalid");
        assertTrue(CausticaConfig.migrateLegacySceneConfig(missingValues));
        assertEquals(20, ((Number) missingValues.get("dlss-rr.input-scale-percent")).intValue());

        CommentedConfig missingBoth = CommentedConfig.inMemory();
        missingBoth.set("config-version", 11);
        missingBoth.set("reconstruction.backend", "nrd");
        assertTrue(CausticaConfig.migrateLegacySceneConfig(missingBoth));
        assertEquals(20, ((Number) missingBoth.get("dlss-rr.input-scale-percent")).intValue());

        CommentedConfig badInputZero = CommentedConfig.inMemory();
        badInputZero.set("config-version", 11);
        badInputZero.set("reconstruction.backend", "nrd");
        badInputZero.set("output-scale.percent", 200);
        badInputZero.set("dlss-rr.input-scale-percent", 0);
        assertTrue(CausticaConfig.migrateLegacySceneConfig(badInputZero));
        assertEquals(40, ((Number) badInputZero.get("dlss-rr.input-scale-percent")).intValue());
    }

    @Test
    void schema13ForcesNewDlssdDefaults() {
        CommentedConfig nonNrd = CommentedConfig.inMemory();
        nonNrd.set("config-version", 12);
        nonNrd.set("reconstruction.backend", "dlss-rr");
        nonNrd.set("dlss-rr.preset", "A");
        nonNrd.set("dlss-rr.quality", 3);
        nonNrd.set("dlss-rr.input-scale-percent", 30);
        assertTrue(CausticaConfig.migrateLegacySceneConfig(nonNrd));
        assertEquals(CausticaConfig.CONFIG_SCHEMA_VERSION, ((Number) nonNrd.get("config-version")).intValue());
        assertEquals("dlss-rr", nonNrd.get("reconstruction.backend"));
        assertEquals(true, nonNrd.get("dlss-rr.enabled"));
        assertEquals("D", nonNrd.get("dlss-rr.preset"));
        assertEquals(20, ((Number) nonNrd.get("dlss-rr.input-scale-percent")).intValue());

        CommentedConfig defaultsAdded = CommentedConfig.inMemory();
        defaultsAdded.set("config-version", 12);
        defaultsAdded.set("reconstruction.backend", "off");
        assertTrue(CausticaConfig.migrateLegacySceneConfig(defaultsAdded));
        assertEquals(CausticaConfig.CONFIG_SCHEMA_VERSION, ((Number) defaultsAdded.get("config-version")).intValue());
        assertEquals("dlss-rr", defaultsAdded.get("reconstruction.backend"));
        assertEquals(true, defaultsAdded.get("dlss-rr.enabled"));
        assertEquals("D", defaultsAdded.get("dlss-rr.preset"));
        assertEquals(20, ((Number) defaultsAdded.get("dlss-rr.input-scale-percent")).intValue());
    }

    @Test
    void schema13DoesNotReplaceNrdBackend() {
        CommentedConfig nrd = CommentedConfig.inMemory();
        nrd.set("config-version", 12);
        nrd.set("reconstruction.backend", "nrd");
        assertTrue(CausticaConfig.migrateLegacySceneConfig(nrd));
        assertEquals(CausticaConfig.CONFIG_SCHEMA_VERSION, ((Number) nrd.get("config-version")).intValue());
        assertFalse(nrd.contains("dlss-rr.enabled"));
        assertFalse(nrd.contains("dlss-rr.quality"));
        assertFalse(nrd.contains("dlss-rr.preset"));
        assertFalse(nrd.contains("dlss-rr.input-scale-percent"));
        assertEquals("nrd", nrd.get("reconstruction.backend"));
    }

    @Test
    void schema14UnifiesCelestialLightBouncesIntoMaxBounces() {
        CommentedConfig defaults = CommentedConfig.inMemory();
        defaults.set("config-version", 13);
        defaults.set("composite.max-bounces", 8.0);
        defaults.set("composite.celestial-light-bounces", 4.0);
        assertTrue(CausticaConfig.migrateLegacySceneConfig(defaults));
        assertEquals(CausticaConfig.CONFIG_SCHEMA_VERSION, ((Number) defaults.get("config-version")).intValue());
        assertEquals(64.0, ((Number) defaults.get("composite.max-bounces")).doubleValue());
        assertFalse(defaults.contains("composite.celestial-light-bounces"));

        CommentedConfig custom = CommentedConfig.inMemory();
        custom.set("config-version", 13);
        custom.set("composite.max-bounces", 16.0);
        custom.set("composite.celestial-light-bounces", 4.0);
        assertTrue(CausticaConfig.migrateLegacySceneConfig(custom));
        assertEquals(CausticaConfig.CONFIG_SCHEMA_VERSION, ((Number) custom.get("config-version")).intValue());
        assertEquals(16.0, ((Number) custom.get("composite.max-bounces")).doubleValue());
        assertFalse(custom.contains("composite.celestial-light-bounces"));
    }

    @Test
    void schemaElevenPreservesUserCustomizations() {
        CommentedConfig custom = CommentedConfig.inMemory();
        custom.set("config-version", 10);
        custom.set("composite.ambient-light-ev", -2.0);
        custom.set("composite.sky.aerosol-scatter", 1.5);
        custom.set("hdr.tonemap-mode", "caustica");
        custom.set("dlss-rr.quality", 3);
        custom.set("sharc.cache-exponent", 26);
        assertTrue(CausticaConfig.migrateLegacySceneConfig(custom));
        assertEquals(-2.0,
                ((Number) custom.get("composite.ambient-light-ev")).doubleValue(), 1.0e-6);
        assertEquals(1.5,
                ((Number) custom.get("composite.sky.aerosol-scatter")).doubleValue(), 1.0e-6);
        assertEquals("caustica", custom.get("hdr.tonemap-mode"));
        assertEquals(3, ((Number) custom.get("dlss-rr.quality")).intValue());
        assertEquals(26, ((Number) custom.get("sharc.cache-exponent")).intValue());
    }
}
