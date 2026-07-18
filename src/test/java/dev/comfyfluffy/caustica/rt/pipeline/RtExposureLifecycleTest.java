package dev.comfyfluffy.caustica.rt.pipeline;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtExposureLifecycleTest {
    @Test
    void autoHistoryResetPreservesOfflineCaptureUntilSessionEnds() throws Exception {
        RtExposure exposure = new RtExposure();
        Field fixedScale = RtExposure.class.getDeclaredField("offlineFixedScale");
        fixedScale.setAccessible(true);
        fixedScale.setFloat(exposure, 0.125f);

        exposure.resetAutoHistory();

        assertEquals(0.125f, exposure.offlineFixedScale());
        exposure.endOfflineSession();
        assertTrue(Float.isNaN(exposure.offlineFixedScale()));
    }
}
