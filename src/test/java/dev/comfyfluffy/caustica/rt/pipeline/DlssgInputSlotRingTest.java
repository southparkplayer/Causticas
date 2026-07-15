package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DlssgInputSlotRingTest {
    @Test
    void associatesRetirementWithUniformInputSlotNotMailboxImageOrder() {
        DlssgInputSlotRing ring = new DlssgInputSlotRing();
        ring.reset(3);
        ring.acquire(2);
        ring.retireActive(0x1234L, 17L);

        assertEquals(2, ring.acquiredApplicationImage());
        assertEquals(0, ring.active());
        assertTrue(ring.pending(0));
        assertTrue(ring.valid(0));
        assertEquals(0x1234L, ring.fence(0));
        assertEquals(17L, ring.value(0));
        assertFalse(ring.pending(2));
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

        assertTrue(ring.pending(0));
        assertFalse(ring.valid(0));
        assertTrue(ring.snapshot().contains("0:cafe@0:pending"));
    }

    @Test
    void swapchainResetDropsOldGenerationAndWrapsIndices() {
        DlssgInputSlotRing ring = new DlssgInputSlotRing();
        ring.reset(2);
        ring.acquire(1);
        ring.retireActive(7L, 9L);
        ring.reset(3);
        ring.acquire(4);

        assertEquals(0, ring.active());
        assertEquals(4, ring.acquiredApplicationImage());
        assertEquals(3, ring.count());
        assertFalse(ring.pending(1));
        assertFalse(ring.prepared());
    }
}
