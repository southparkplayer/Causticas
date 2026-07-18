package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtCompositeLifecycleTest {
    @Test
    void reloadWaitsForBothReplacementAtlasGenerations() {
        long oldBlock = 11L;
        long oldCelestial = 22L;
        assertFalse(RtComposite.replacementAtlasesReady(0L, 0L, oldBlock, oldCelestial));
        assertFalse(RtComposite.replacementAtlasesReady(33L, oldCelestial, oldBlock, oldCelestial));
        assertFalse(RtComposite.replacementAtlasesReady(oldBlock, 44L, oldBlock, oldCelestial));
        assertTrue(RtComposite.replacementAtlasesReady(33L, 44L, oldBlock, oldCelestial));
    }

    @Test
    void celestialUvRectsFailClosed() {
        assertFalse(RtComposite.validCelestialUvRect(0.0f, 0.0f, 0.0f, 0.0f));
        assertFalse(RtComposite.validCelestialUvRect(0.0f, 0.0f, 1.1f, 1.0f));
        assertFalse(RtComposite.validCelestialUvRect(Float.NaN, 0.0f, 0.5f, 0.5f));
        assertTrue(RtComposite.validCelestialUvRect(0.125f, 0.25f, 0.25f, 0.5f));
    }

    @Test
    void spectralTransmittanceIsBoundedAndFallsTowardTheHorizon() {
        float[] zenith = new float[3];
        float[] lowSun = new float[3];
        RtComposite.atmosphereTransmittance(0.0f, 1.0f, 0.0f, zenith);
        float elevation = (float) Math.toRadians(5.0);
        RtComposite.atmosphereTransmittance((float) Math.cos(elevation),
                (float) Math.sin(elevation), 0.0f, lowSun);
        for (int channel = 0; channel < 3; channel++) {
            assertTrue(zenith[channel] >= 0.0f && zenith[channel] <= 1.0f);
            assertTrue(lowSun[channel] >= 0.0f && lowSun[channel] <= 1.0f);
            assertTrue(lowSun[channel] < zenith[channel]);
        }
    }

    @Test
    void exposureDepthFallbackIsSelectedOnlyWithoutAnExistingGuideProducer() {
        assertTrue(RtComposite.exposureDepthRequired(true, false, false));
        assertFalse(RtComposite.exposureDepthRequired(false, false, false));
        assertFalse(RtComposite.exposureDepthRequired(true, true, false));
        assertFalse(RtComposite.exposureDepthRequired(true, false, true));
        assertFalse(RtComposite.exposureDepthRequired(true, true, true));
    }

    @Test
    void skyLutIgnoresSubpixelClockMotionButRespondsToVisibleChanges() {
        float tiny = (float)Math.toRadians(0.01);
        float stillSubpixel = (float)Math.toRadians(0.10);
        float visible = (float)Math.toRadians(0.20);
        assertFalse(RtComposite.materiallyDifferentDirection(
                (float)Math.sin(tiny), (float)Math.cos(tiny), 0.0f, 0.0f, 1.0f, 0.0f));
        assertFalse(RtComposite.materiallyDifferentDirection(
                (float)Math.sin(stillSubpixel), (float)Math.cos(stillSubpixel), 0.0f, 0.0f, 1.0f, 0.0f));
        assertTrue(RtComposite.materiallyDifferentDirection(
                (float)Math.sin(visible), (float)Math.cos(visible), 0.0f, 0.0f, 1.0f, 0.0f));
        assertFalse(RtComposite.materiallyDifferentSource(1.001f, 1.0f));
        assertTrue(RtComposite.materiallyDifferentSource(1.01f, 1.0f));
    }
}
