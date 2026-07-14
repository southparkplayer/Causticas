package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class DlssgPacingTest {
    @Test
    void automaticCapMaps240HzTo225Fps() {
        assertEquals(225, DlssgPacing.automaticOutputCapFps(240));
    }

    @Test
    void automaticCapAlwaysLeavesRefreshHeadroom() {
        assertEquals(138, DlssgPacing.automaticOutputCapFps(144));
        assertEquals(116, DlssgPacing.automaticOutputCapFps(120));
        assertEquals(59, DlssgPacing.automaticOutputCapFps(60));
        assertEquals(0, DlssgPacing.automaticOutputCapFps(0));
    }

    @Test
    void outputTargetIsDividedAcrossEveryPresentedFrame() {
        assertEquals(8_889, DlssgPacing.reflexIntervalUs(225, 1, 0));
        assertEquals(13_334, DlssgPacing.reflexIntervalUs(225, 2, 0));
        assertEquals(17_778, DlssgPacing.reflexIntervalUs(225, 3, 0));
        assertEquals(22_223, DlssgPacing.reflexIntervalUs(225, 4, 0));
        assertEquals(26_667, DlssgPacing.reflexIntervalUs(225, 5, 0));
    }

    @Test
    void manualIntervalRemainsFallbackWhenAutoCapIsOff() {
        assertEquals(4_505, DlssgPacing.reflexIntervalUs(0, 5, 4_505));
        assertEquals(0, DlssgPacing.reflexIntervalUs(0, 5, 0));
    }

    @Test
    void mailboxVsyncAlwaysEnablesAutomaticPacing() {
        assertTrue(DlssgPacing.automaticPacingEnabled(true, true, false, true));
        assertTrue(DlssgPacing.automaticPacingEnabled(true, true, true, false));
        assertFalse(DlssgPacing.automaticPacingEnabled(false, true, true, true));
        assertFalse(DlssgPacing.automaticPacingEnabled(true, false, true, true));
    }
}
