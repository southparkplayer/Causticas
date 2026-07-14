package dev.comfyfluffy.caustica;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class HdrSessionSettingTest {
    @Test
    void liveToggleIsDeferredAndCannotChangeTheCurrentSwapchainRequest() {
        assertTrue(CausticaConfig.Rt.Hdr.sessionRequest(true, false));
        assertFalse(CausticaConfig.Rt.Hdr.sessionRequest(false, true));
        assertTrue(CausticaConfig.Rt.Hdr.settingRequiresRestart(true, false));
        assertTrue(CausticaConfig.Rt.Hdr.settingRequiresRestart(false, true));
        assertFalse(CausticaConfig.Rt.Hdr.settingRequiresRestart(true, true));
    }
}
