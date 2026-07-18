package dev.comfyfluffy.caustica.rt.entity;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RtEntityTransformPoolTest {
    @Test
    void acquisitionsAreDistinctUntilRetirementAndReuseAfterReset() {
        RtEntities.TransformPool pool = new RtEntities.TransformPool(1);
        float[] first = pool.acquire();
        float[] second = pool.acquire();
        float[] third = pool.acquire();

        assertNotSame(first, second);
        assertNotSame(first, third);
        assertNotSame(second, third);
        assertEquals(3, pool.allocations());
        assertEquals(0, pool.reuses());

        first[0] = 17.0f;
        second[0] = 23.0f;
        pool.reset();
        float[] reusedFirst = pool.acquire();
        float[] reusedSecond = pool.acquire();
        assertSame(first, reusedFirst);
        assertSame(second, reusedSecond);
        assertEquals(17.0f, reusedFirst[0]);
        assertEquals(23.0f, reusedSecond[0]);
        assertEquals(0, pool.allocations());
        assertEquals(2, pool.reuses());
    }

    @Test
    void translationTransformMatchesOriginalRawBits() {
        float[] actual = new float[12];
        float x = -0.0f;
        float y = 16_777_216.0f;
        float z = -12345.75f;
        RtEntities.writeTranslationTransform(actual, x, y, z);
        float[] expected = {1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z};
        assertRawBitsEqual(expected, actual);
    }

    @Test
    void placedTransformMatchesOriginalRawBits() {
        float[] local = {
                -0.0f, 0.25f, -0.5f, 1.0f,
                0.75f, 1.0f, 0.125f, -2.0f,
                0.5f, -0.25f, 1.0f, 3.0f};
        float x = -0.0f;
        float y = 8_388_608.0f;
        float z = -4096.5f;
        float[] actual = new float[12];
        RtEntities.writePlacedTransform(actual, local, x, y, z);
        float[] expected = {
                local[0], local[1], local[2], local[3] + x,
                local[4], local[5], local[6], local[7] + y,
                local[8], local[9], local[10], local[11] + z};
        assertRawBitsEqual(expected, actual);
    }

    private static void assertRawBitsEqual(float[] expected, float[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(Float.floatToRawIntBits(expected[i]), Float.floatToRawIntBits(actual[i]),
                    "raw float mismatch at lane " + i);
        }
    }
}
