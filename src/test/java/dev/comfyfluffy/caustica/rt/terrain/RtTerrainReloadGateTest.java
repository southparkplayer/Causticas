package dev.comfyfluffy.caustica.rt.terrain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RtTerrainReloadGateTest {
    @Test
    void reloadPausePersistsUntilReplacementBindingsExplicitlyResumeTerrain() {
        RtTerrain.resumeAfterResourceReload();
        assertFalse(RtTerrain.isResourceReloadPaused());

        try {
            RtTerrain.pauseForResourceReload();
            assertTrue(RtTerrain.isResourceReloadPaused());
        } finally {
            RtTerrain.resumeAfterResourceReload();
        }

        assertFalse(RtTerrain.isResourceReloadPaused());
    }
}
