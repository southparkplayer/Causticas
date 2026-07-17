package dev.comfyfluffy.caustica.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class TorchIntensityScaleTest {
    @Test
    void perceptualScalePreservesRequestedAnchors() {
        assertEquals(0, RtVideoOptions.torchSliderFromMultiplier(0.0f));
        assertEquals(75, RtVideoOptions.torchSliderFromMultiplier(0.1f));
        assertEquals(100, RtVideoOptions.torchSliderFromMultiplier(1.0f));
        assertEquals(0.0f, RtVideoOptions.torchMultiplierFromSlider(0));
        assertEquals(0.1f, RtVideoOptions.torchMultiplierFromSlider(75), 0.000001f);
        assertEquals(1.0f, RtVideoOptions.torchMultiplierFromSlider(100), 0.000001f);
    }
}
