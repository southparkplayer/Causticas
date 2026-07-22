package dev.comfyfluffy.caustica.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class OfflineTileScheduleTest {
    @Test
    void invalidEstimatorRunsEveryFrame() {
        assertEquals(1, OfflineTileSchedule.cadence(Float.NaN, 100L, 0.01f));
        assertEquals(1, OfflineTileSchedule.cadence(1.0f,
                OfflineTileSchedule.MIN_ADAPTIVE_SAMPLES - 1L, 0.01f));
    }

    @Test
    void adaptationStartsOnlyAfterTheMinimumSampleFloor() {
        assertEquals(1, OfflineTileSchedule.cadence(0.0f,
                OfflineTileSchedule.MIN_ADAPTIVE_SAMPLES - 1L, 0.01f));
        assertEquals(32, OfflineTileSchedule.cadence(0.0f,
                OfflineTileSchedule.MIN_ADAPTIVE_SAMPLES, 0.01f));
    }

    @Test
    void quietTilesHaveNoShorterCadence() {
        int noisy = OfflineTileSchedule.cadence(1.0f, 4_096L, 0.01f);
        int quiet = OfflineTileSchedule.cadence(0.0001f, 4_096L, 0.01f);
        assertTrue(quiet >= noisy);
    }

    @Test
    void cadenceWindowRunsEachTileOnce() {
        int cadence = 16;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int runs = 0;
                for (long frame = 0; frame < cadence; frame++) {
                    if (OfflineTileSchedule.active(x, y, frame, cadence)) {
                        runs++;
                    }
                }
                assertEquals(1, runs);
            }
        }
    }
}
