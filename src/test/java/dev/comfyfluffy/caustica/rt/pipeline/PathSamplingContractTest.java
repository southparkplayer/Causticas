package dev.comfyfluffy.caustica.rt.pipeline;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PathSamplingContractTest {
    @Test
    void pathSamplerIsLowDiscrepancyAndBounceScopedWithoutExtraPaths() throws Exception {
        String raygen = Files.readString(Path.of("shaders/world/world.rgen.slang"));
        assertTrue(raygen.contains("struct PathSampler"));
        assertTrue(raygen.contains("beginBounce(sampler, uint(bounce));"));
        assertTrue(raygen.contains("offlineGroundTruth ? completedSamples + s : pc.frameIndex * spp + s"));
        assertTrue(raygen.contains("pc.sampleSequenceAddr"));
        assertTrue(raygen.contains("owenScramble(value, dimensionSeed)"));
        assertTrue(raygen.contains("inline float rndf"));
        assertFalse(raygen.contains("sequenceCycle"));
        assertEquals(1 << 20, RtPathSampleSequence.SAMPLE_COUNT);
        assertTrue(raygen.contains("pixelJitter = float2(rndf(sampler) - 0.5, rndf(sampler) - 0.5)"));
        assertTrue(raygen.contains("uint pathCount = primaryOnly ? 1u : min(spp, remainingSamples)"));
        assertFalse(raygen.contains("uint pcg(inout uint s)"));
        assertFalse(raygen.contains("[noinline]"));
        assertFalse(raygen.contains("[noRefInline]"));
        assertFalse(raygen.contains("sampleTerrainEmitter"));
    }

    @Test
    void everySobolDimensionOccupiesEveryEightBitTemporalStratum() {
        for (int dimension = 0; dimension < RtPathSampleSequence.DIMENSIONS; dimension++) {
            Set<Integer> strata = new HashSet<>();
            for (int sampleIndex = 0; sampleIndex < 256; sampleIndex++) {
                strata.add(RtPathSampleSequence.sobolBits(sampleIndex, dimension) >>> 24);
            }
            assertEquals(256, strata.size(), "dimension " + dimension);
        }
    }

    @Test
    void firstTwoDimensionsFormOneSamplePerSixteenBySixteenCell() {
        Set<Integer> cells = new HashSet<>();
        for (int sampleIndex = 0; sampleIndex < 256; sampleIndex++) {
            int x = RtPathSampleSequence.sobolBits(sampleIndex, 0) >>> 28;
            int y = RtPathSampleSequence.sobolBits(sampleIndex, 1) >>> 28;
            cells.add((x << 4) | y);
        }
        assertEquals(256, cells.size());
    }

}
