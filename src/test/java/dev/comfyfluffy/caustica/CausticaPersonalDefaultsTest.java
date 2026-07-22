package dev.comfyfluffy.caustica;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CausticaPersonalDefaultsTest {
    @Test
    void personalRendererAndDisplayValuesAreTheDefaults() {
        assertEquals(8, CausticaConfig.Rt.Composite.MAX_BOUNCES.defaultValue());
        assertEquals(1.5, Math.toDegrees(CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS.defaultValue()), 1.0e-5);
        assertEquals(5.0, Math.toDegrees(CausticaConfig.Rt.Composite.MOON_ANGULAR_RADIUS.defaultValue()), 1.0e-5);
        assertEquals(0.025641026f, CausticaConfig.Rt.Composite.AMBIENT_LIGHT_EV.defaultValue());
        assertEquals(6.02f, CausticaConfig.Rt.Composite.MOONLIGHT_INTENSITY_EV.defaultValue());
        assertEquals(0.0f, CausticaConfig.Rt.Composite.NIGHT_AIRGLOW_EV.defaultValue());
        assertEquals(0.0f, CausticaConfig.Rt.Composite.SKY_AEROSOL_ABSORPTION.defaultValue());
        assertEquals(0.1f, CausticaConfig.Rt.Composite.SKY_AEROSOL_HEIGHT_KM.defaultValue());
        assertEquals(0.8053686f, CausticaConfig.Rt.Composite.SKY_AEROSOL_ANISOTROPY.defaultValue());
        assertEquals(1.0f, CausticaConfig.Rt.Composite.SKY_SATURATION.defaultValue());
        assertEquals(0.53f, CausticaConfig.Rt.Composite.SUN_LIMB_DARKENING.defaultValue());
        assertEquals(2.025641f, CausticaConfig.Rt.Composite.STAR_BRIGHTNESS_EV.defaultValue());
        assertEquals(2.0f, CausticaConfig.Rt.Composite.STAR_DENSITY.defaultValue());
        assertEquals(1.18f, CausticaConfig.Rt.Composite.AIRGLOW_HORIZON_G.defaultValue());
        assertEquals(0.0f, CausticaConfig.Rt.Composite.AIRGLOW_ZENITH_R.defaultValue());
        assertEquals(32, CausticaConfig.Rt.Composite.PSR_MAX_MIRRORS.defaultValue());
        assertEquals(100, CausticaConfig.Rt.OutputScale.PERCENT.defaultValue());
        assertEquals(32, CausticaConfig.Rt.Terrain.ASYNC_DISPATCH_PER_PASS.defaultValue());
        assertEquals(32, CausticaConfig.Rt.Terrain.COMPLETION_RESULTS_PER_PASS.defaultValue());
        assertEquals(32, CausticaConfig.Rt.Terrain.MAX_INFLIGHT_SECTIONS.defaultValue());
        assertEquals(1.5f, CausticaConfig.Rt.Terrain.STREAM_BUDGET_MS.defaultValue());
        assertEquals(6.0f, CausticaConfig.Rt.Terrain.STREAM_BUDGET_MAX_MS.defaultValue());
        assertEquals(8.0f, CausticaConfig.Rt.Terrain.STREAM_FALLBACK_BUDGET_MS.defaultValue());
        assertEquals(-1.5f, CausticaConfig.Rt.Exposure.MANUAL_EXPOSURE_EV.defaultValue());
        assertEquals(4.0f, CausticaConfig.Rt.Exposure.MAX_EV.defaultValue());
        assertEquals(0.99f, CausticaConfig.Rt.Exposure.HIGHLIGHT_PERCENTILE.defaultValue());
        assertEquals(4.0f, CausticaConfig.Rt.Exposure.HIGHLIGHT_HEADROOM.defaultValue());
        assertEquals("psychov23", CausticaConfig.Rt.Sdr.TONEMAP_MODE.defaultValue());
        assertEquals(1.0f, CausticaConfig.Rt.Sdr.PSYCHO_PEAK.defaultValue());
        assertEquals(1.0f, CausticaConfig.Rt.Sdr.PSYCHOV23_PEAK.defaultValue());
        assertEquals(0.0f, CausticaConfig.Rt.PsychoV23.COMPRESSION.defaultValue());
        assertEquals("eetf", CausticaConfig.Rt.Hdr.TONEMAP_MODE.defaultValue());
        assertFalse(CausticaConfig.Rt.Hdr.ENABLED.defaultValue());
    }

    @Test
    void selectedVendorAndReconstructionDefaultsStayLocked() {
        assertEquals(2, CausticaConfig.Rt.DlssRr.QUALITY.defaultValue());
        assertEquals(4, CausticaConfig.Rt.DlssRr.PRESET.defaultValue());
        assertEquals(20, CausticaConfig.Rt.DlssRr.INPUT_RATIO_TENTHS.defaultValue());
        assertTrue(CausticaConfig.Rt.DlssRr.HIGH_QUALITY_TRANSPARENCY.defaultValue());
        assertTrue(CausticaConfig.Rt.DlssRr.PARTICLE_TEMPORAL_HISTORY.defaultValue());
        assertFalse(CausticaConfig.Rt.Fg.ENABLED.defaultValue());
        assertEquals("off", CausticaConfig.Rt.Fg.MODE.defaultValue());
        assertEquals(1, CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT.defaultValue());
        assertFalse(CausticaConfig.Rt.Reflex.LOW_LATENCY_BOOST.defaultValue());
        assertTrue(CausticaConfig.Rt.Sharc.ENABLED.defaultValue());
        assertEquals(2, CausticaConfig.Rt.Sharc.UPDATE_TILE_SIZE.defaultValue());
    }
}
