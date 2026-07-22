package dev.comfyfluffy.caustica.rt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class OfflineDispatchMetadataTest {
    @Test
    void computesActiveTileRatio() {
        OfflineDispatchMetadata metadata = new OfflineDispatchMetadata(8, 100L, 4L, 25, 100, 0, false);
        assertEquals(0.25, metadata.activeTileRatio());
    }

    @Test
    void rejectsInconsistentTileCounts() {
        assertThrows(IllegalArgumentException.class,
                () -> new OfflineDispatchMetadata(8, 0L, 0L, 2, 1, 0, false));
    }

    @Test
    void noneIsAnEmptyMetadataValue() {
        assertEquals(0, OfflineDispatchMetadata.NONE.configuredBatchLimit());
        assertEquals(0.0, OfflineDispatchMetadata.NONE.activeTileRatio());
    }
}
