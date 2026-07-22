package dev.comfyfluffy.caustica.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class OfflineBatchControllerTest {
    @Test
    void growsAfterThreeLowMeasurements() {
        OfflineBatchController controller = new OfflineBatchController(16_000_000L, 4);
        controller.observe(4_000_000L, 4);
        controller.observe(4_000_000L, 4);
        assertEquals(8, controller.observe(4_000_000L, 4));
    }

    @Test
    void shrinksAfterThreeHighMeasurements() {
        OfflineBatchController controller = new OfflineBatchController(16_000_000L, 16);
        controller.observe(64_000_000L, 16);
        controller.observe(64_000_000L, 16);
        assertEquals(8, controller.observe(64_000_000L, 16));
    }

    @Test
    void ignoresInvalidMeasurements() {
        OfflineBatchController controller = new OfflineBatchController(16_000_000L, 8);
        assertEquals(8, controller.observe(0L, 8));
        assertEquals(8, controller.observe(-1L, 8));
        assertEquals(8, controller.observe(16_000_000L, 0));
    }

    @Test
    void changesByAtMostOnePowerOfTwo() {
        OfflineBatchController controller = new OfflineBatchController(16_000_000L, 1);
        controller.observe(1_000L, 1);
        controller.observe(1_000L, 1);
        assertEquals(2, controller.observe(1_000L, 1));
    }
}
