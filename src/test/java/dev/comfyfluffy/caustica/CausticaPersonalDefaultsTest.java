package dev.comfyfluffy.caustica;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class CausticaPersonalDefaultsTest {
    @Test
    void personalRendererAndDisplayValuesAreTheDefaults() {
        assertEquals(8, CausticaConfig.Rt.Composite.MAX_BOUNCES.defaultValue());
        assertEquals(1.5, Math.toDegrees(CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS.defaultValue()), 1.0e-5);
        assertEquals(3.0, Math.toDegrees(CausticaConfig.Rt.Composite.MOON_ANGULAR_RADIUS.defaultValue()), 1.0e-5);
        assertEquals(6.0f, CausticaConfig.Rt.Composite.MOONLIGHT_INTENSITY_EV.defaultValue());
        assertEquals(2.9925926f, CausticaConfig.Rt.Composite.NIGHT_AIRGLOW_EV.defaultValue());
        assertEquals(0, CausticaConfig.Rt.Composite.DAY_OF_YEAR_OFFSET.defaultValue());
        assertEquals(32, CausticaConfig.Rt.Composite.PSR_MAX_MIRRORS.defaultValue());
        assertEquals(100, CausticaConfig.Rt.OutputScale.PERCENT.defaultValue());
        assertEquals(192, CausticaConfig.Rt.Terrain.MAX_INFLIGHT_SECTIONS.defaultValue());
        assertEquals(5.027523f, CausticaConfig.Rt.Exposure.MANUAL_EXPOSURE_EV.defaultValue());
        assertEquals(5.7297297f, CausticaConfig.Rt.Exposure.MAX_EV.defaultValue());
        assertEquals(1.995413f, CausticaConfig.Rt.Exposure.HIGHLIGHT_HEADROOM.defaultValue());
        assertEquals(4.0f, CausticaConfig.Rt.Sdr.PSYCHOV23_PEAK.defaultValue());
        assertFalse(CausticaConfig.Rt.Hdr.ENABLED.defaultValue());
    }

    @Test
    void nvidiaSpecificDefaultsRemainVendorNeutral() {
        assertEquals(0, CausticaConfig.Rt.DlssRr.QUALITY.defaultValue());
        assertFalse(CausticaConfig.Rt.DlssRr.HIGH_QUALITY_TRANSPARENCY.defaultValue());
        assertFalse(CausticaConfig.Rt.DlssRr.PARTICLE_TEMPORAL_HISTORY.defaultValue());
        assertFalse(CausticaConfig.Rt.Fg.ENABLED.defaultValue());
        assertEquals("off", CausticaConfig.Rt.Fg.MODE.defaultValue());
        assertEquals(1, CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT.defaultValue());
        assertFalse(CausticaConfig.Rt.Reflex.LOW_LATENCY_BOOST.defaultValue());
        assertEquals(5, CausticaConfig.Rt.Sharc.UPDATE_TILE_SIZE.defaultValue());
    }
}
