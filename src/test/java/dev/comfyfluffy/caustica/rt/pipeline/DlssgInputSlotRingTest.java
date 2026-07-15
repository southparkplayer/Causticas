package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DlssgInputSlotRingTest {
    @Test
    void associatesRetirementWithAcquiredApplicationImage() {
        DlssgInputSlotRing ring = new DlssgInputSlotRing();
        ring.reset(3);
        ring.acquire(2);
        ring.retireActive(0x1234L, 17L);

        assertEquals(2, ring.active());
        assertTrue(ring.pending(2));
        assertTrue(ring.valid(2));
        assertEquals(0x1234L, ring.fence(2));
        assertEquals(17L, ring.value(2));
        assertFalse(ring.pending(0));
    }

    @Test
    void onlyReusedSlotRequiresRetirement() {
        DlssgInputSlotRing ring = new DlssgInputSlotRing();
        ring.reset(3);
        for (int image = 0; image < 3; image++) {
            ring.acquire(image);
            assertFalse(ring.pending(image));
            ring.retireActive(0x1000L, image + 1L);
        }

        ring.acquire(0);
        assertTrue(ring.pending(0));
        ring.clear(0);
        assertFalse(ring.pending(0));
        assertTrue(ring.pending(1));
        assertTrue(ring.pending(2));
    }

    @Test
    void exposesInvalidFenceForControlledFallback() {
        DlssgInputSlotRing ring = new DlssgInputSlotRing();
        ring.reset(2);
        ring.acquire(1);
        ring.retireActive(0xCAFE, 0L);

        assertTrue(ring.pending(1));
        assertFalse(ring.valid(1));
        assertTrue(ring.snapshot().contains("1:cafe@0:pending"));
    }

    @Test
    void swapchainResetDropsOldGenerationAndWrapsIndices() {
        DlssgInputSlotRing ring = new DlssgInputSlotRing();
        ring.reset(2);
        ring.acquire(1);
        ring.retireActive(7L, 9L);
        ring.reset(3);
        ring.acquire(4);

        assertEquals(1, ring.active());
        assertEquals(3, ring.count());
        assertFalse(ring.pending(1));
        assertFalse(ring.prepared());
    }
}
