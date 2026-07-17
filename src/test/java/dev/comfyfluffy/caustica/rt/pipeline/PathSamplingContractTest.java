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
    void onlineAndOfflineUseModeSpecializedSamplersWithoutExtraPaths() throws Exception {
        String raygen = Files.readString(Path.of("shaders/world/world.rgen.slang"));
        String build = Files.readString(Path.of("build.gradle"));
        String composite = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java"));
        assertTrue(raygen.contains("struct PathSampler"));
        assertTrue(raygen.contains("#if CAUSTICA_OFFLINE"));
        assertTrue(raygen.contains("beginBounce(sampler, uint(bounce));"));
        assertTrue(raygen.contains("sampler.sampleIndex = completedSamples + s"));
        assertTrue(raygen.contains("pc.sampleSequenceAddr"));
        assertTrue(raygen.contains("owenScramble(value, dimensionSeed)"));
        assertTrue(raygen.contains("inline uint liveRandom(inout uint state)"));
        assertTrue(raygen.contains("state = value * 747796405u + 2891336453u"));
        assertTrue(raygen.contains("sampler.state = seed"));
        assertTrue(build.contains("-DCAUSTICA_OFFLINE=0"));
        assertTrue(build.contains("-DCAUSTICA_OFFLINE=0\", \"-DCAUSTICA_SER=0"));
        assertTrue(build.contains("-DCAUSTICA_OFFLINE=1\", \"-DCAUSTICA_SER=1"));
        assertTrue(build.contains("world_offline.rgen.spv"));
        assertTrue(composite.contains("offlineGroundTruth && pathSampleSequence != null"));
        assertTrue(raygen.contains("inline float rndf"));
        assertFalse(raygen.contains("sequenceCycle"));
        assertEquals(1 << 20, RtPathSampleSequence.SAMPLE_COUNT);
        assertTrue(raygen.contains("float2 pixelJitter = float2(rndf(sampler) - 0.5, rndf(sampler) - 0.5)"));
        assertTrue(raygen.contains("uint pathCount = primaryOnly ? 1u : spp"));
        assertFalse(raygen.contains("remainingSamples"));
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
