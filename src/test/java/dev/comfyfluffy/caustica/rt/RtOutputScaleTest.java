package dev.comfyfluffy.caustica.rt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class RtOutputScaleTest {
    @Test
    void calculatesRequestedDimensionsWithNearestPixelRounding() {
        int width = 1919;
        int height = 1079;
        assertEquals(192, RtOutputScale.dimension(width, 10));
        assertEquals(108, RtOutputScale.dimension(height, 10));
        assertEquals(960, RtOutputScale.dimension(width, 50));
        assertEquals(540, RtOutputScale.dimension(height, 50));
        assertEquals(1900, RtOutputScale.dimension(width, 99));
        assertEquals(1068, RtOutputScale.dimension(height, 99));
        assertEquals(1919, RtOutputScale.dimension(width, 100));
        assertEquals(1090, RtOutputScale.dimension(height, 101));
        assertEquals(2879, RtOutputScale.dimension(width, 150));
        assertEquals(2158, RtOutputScale.dimension(height, 200));
    }

    @Test
    void clampsRangeAndKeepsAtLeastOnePixel() {
        assertEquals(1, RtOutputScale.dimension(1, 10));
        assertEquals(1, RtOutputScale.dimension(0, 100));
        assertEquals(10, RtOutputScale.clampPercent(-1));
        assertEquals(200, RtOutputScale.clampPercent(999));
    }

    @Test
    void reportsTruthfulPaths() {
        assertEquals("fsr1", RtOutputScale.path(99, false));
        assertEquals("native", RtOutputScale.path(100, false));
        assertEquals("downsample", RtOutputScale.path(101, false));
        assertEquals("linear-fallback", RtOutputScale.path(50, true));
    }

    @Test
    void inputScaleIsRelativeToOutputResolution() {
        assertEquals(697, RtResolutionScale.inputDimension(2160, 31));
        assertEquals(540, RtResolutionScale.inputDimension(2160, 40));
        assertEquals(1394, RtResolutionScale.inputDimension(4320, 31));
        assertEquals(1080, RtResolutionScale.inputDimension(4320, 40));
    }

    @Test
    void exposesCanonicalDlssPresetTenths() {
        assertEquals(30, RtResolutionScale.presetRatioTenths(3));
        assertEquals(20, RtResolutionScale.presetRatioTenths(0));
        assertEquals(17, RtResolutionScale.presetRatioTenths(1));
        assertEquals(15, RtResolutionScale.presetRatioTenths(2));
        assertEquals(13, RtResolutionScale.presetRatioTenths(4));
        assertEquals(10, RtResolutionScale.presetRatioTenths(5));
    }

    @Test
    void presetScaleUsesDisplayOverInputRatherThanItsReciprocal() {
        assertEquals(3.0, RtResolutionScale.presetUpscaleRatio(3));
        assertEquals(2.0, RtResolutionScale.presetUpscaleRatio(0));
        assertEquals(1.7, RtResolutionScale.presetUpscaleRatio(1));
        assertEquals(1.5, RtResolutionScale.presetUpscaleRatio(2));
        assertEquals(1.3, RtResolutionScale.presetUpscaleRatio(4));
        assertEquals(1.0, RtResolutionScale.presetUpscaleRatio(5));
    }
}
