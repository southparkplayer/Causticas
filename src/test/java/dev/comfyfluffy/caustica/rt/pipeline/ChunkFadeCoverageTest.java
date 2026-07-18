package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Statistical contract for the surface-anchored section reveal lookup. */
final class ChunkFadeCoverageTest {
    @Test
    void surfaceHashHasUsefulLocalUniquenessAndUniformRevealCoverage() {
        Set<Integer> firstTile = new HashSet<>();
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                firstTile.add(sampleIndex(x, y, 0, 17, 123));
            }
        }
        assertEquals(64, firstTile.size());

        double previousCoverage = 0.0;
        for (double progress : new double[] {0.15625, 0.5, 0.84375}) {
            int visible = 0;
            int samples = 64 * 64;
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    if (threshold(x, y, 0, 17, 123) < progress) visible++;
                }
            }
            double coverage = visible / (double) samples;
            assertTrue(Math.abs(coverage - progress) < 0.035,
                    () -> "coverage " + coverage + " drifted from " + progress);
            assertTrue(coverage > previousCoverage);
            previousCoverage = coverage;
        }
    }

    private static double threshold(int x, int y, int z, int instanceId, int primitiveId) {
        int sample = sampleIndex(x, y, z, instanceId, primitiveId);
        int bits = RtBlueNoiseSequence.sampleBits(RtBlueNoiseSequence.spatialSampleIndex(sample), 7);
        return (bits >>> 8) * (1.0 / 16_777_216.0);
    }

    private static int sampleIndex(int x, int y, int z, int instanceId, int primitiveId) {
        int value = x * 0x8da6b343
                ^ y * 0xd8163841
                ^ z * 0xcb1ab31f
                ^ instanceId * 0x9e3779b9
                ^ primitiveId * 0x85ebca6b;
        value ^= value >>> 16;
        value *= 0x21f0aaad;
        value ^= value >>> 15;
        value *= 0xf35a2d97;
        value ^= value >>> 15;
        return value & 4095;
    }
}
