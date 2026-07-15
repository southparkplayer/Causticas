package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

final class RtDlssFgFixedMultiplierTest {
    @Test
    void fixedGeneratedFrameRangeIsOneThroughFive() {
        assertEquals(1, RtDlssFg.selectMultiFrameCount(-10, 0));
        assertEquals(1, RtDlssFg.selectMultiFrameCount(1, 5));
        assertEquals(2, RtDlssFg.selectMultiFrameCount(2, 5));
        assertEquals(3, RtDlssFg.selectMultiFrameCount(3, 5));
        assertEquals(4, RtDlssFg.selectMultiFrameCount(4, 5));
        assertEquals(5, RtDlssFg.selectMultiFrameCount(5, 5));
        assertEquals(5, RtDlssFg.selectMultiFrameCount(99, 5));
        assertEquals(2, RtDlssFg.selectMultiFrameCount(5, 2));
    }

    @Test
    void zeroFromARecreatedSwapchainCannotEraseAdapterCapability() {
        assertEquals(5, RtDlssFg.retainReportedMaximum(5, 0));
        assertEquals(5, RtDlssFg.retainReportedMaximum(0, 5));
    }

    @Test
    void staleDynamicAndAutoSelectionsFallBackToFixed() {
        assertEquals(0, RtDlssFg.streamlineModeForVulkan("off"));
        assertEquals(1, RtDlssFg.streamlineModeForVulkan("fixed"));
        assertEquals(1, RtDlssFg.streamlineModeForVulkan("dynamic"));
        assertEquals(1, RtDlssFg.streamlineModeForVulkan("auto"));
    }

    @Test
    void swapchainRecreationRetainsMaximumAndDeviceDestroyClearsIt() throws Exception {
        RtDlssFg fg = RtDlssFg.INSTANCE;
        Field maximum = RtDlssFg.class.getDeclaredField("multiFrameCountMax");
        maximum.setAccessible(true);
        maximum.setInt(fg, 5);

        fg.onSwapchainConfigured(3840, 2160, 64, 6, false, false, false, 2L);
        assertEquals(5, fg.multiFrameCountMax());

        fg.destroy();
        assertEquals(0, fg.multiFrameCountMax());
    }
}
