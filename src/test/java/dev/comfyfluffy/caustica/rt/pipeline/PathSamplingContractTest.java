package dev.comfyfluffy.caustica.rt.pipeline;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        assertTrue(raygen.contains("beginBounce(sampler, uint(segment));"));
        assertTrue(raygen.contains("sampler.sampleIndex = completedSamples + s"));
        assertTrue(raygen.contains("pc.sampleSequenceAddr"));
        assertTrue(raygen.contains("owenScramble(value, dimensionSeed)"));
        assertTrue(raygen.contains("SAMPLE_SEQUENCE_BITS = 20u"));
        assertTrue(raygen.contains("offlineSampleEpochSeed"));
        assertTrue(raygen.contains("dimension < SAMPLE_SEQUENCE_DIMENSIONS"));
        assertTrue(raygen.contains("without silently reusing"));
        assertTrue(raygen.contains("inline uint liveRandom(inout uint state)"));
        assertTrue(raygen.contains("state = value * 747796405u + 2891336453u"));
        assertTrue(raygen.contains("(sampler.blueIndex << 3u) + (salt << 1u)"));
        assertTrue(raygen.contains("sampler.sampleIndex * 32u + sampler.bounce"));
        assertTrue(raygen.contains("float2 rnd2(inout PathSampler sampler, uint salt)"));
        assertTrue(raygen.contains("float3 sampleCelestialDisk"));
        assertTrue(raygen.contains("float2 sampleUv = rnd2(sampler, 2u)"));
        assertTrue(raygen.contains("float cosTheta = lerp(1.0, cos(halfAngle), sampleUv.x)"));
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
        assertEquals(0, raygen.split("\\[noinline]", -1).length - 1);
        assertTrue(raygen.replace("\r\n", "\n").contains("inline\nvoid setPrimaryMissGuides"));
        assertFalse(raygen.contains("[noRefInline]"));
        assertFalse(raygen.contains("sampleTerrainEmitter"));
    }

    @Test
    void nonSharcRoulettePreservesCurrentVertexLightingAndOnlyGatesContinuation() throws Exception {
        String raygen = Files.readString(Path.of("shaders/world/world.rgen.slang"));

        int emissive = raygen.indexOf(
                "All emissive materials retain the legacy base radiance");
        int celestialNee = raygen.indexOf(
                "NEE: direct light from the dominant celestial body");
        int vertexComplete = raygen.indexOf(
                "This vertex's emission and direct lighting are complete.");
        int roulette = raygen.indexOf(
                "Standard non-SHaRC roulette runs only after this vertex's emission and direct lighting are complete.");
        int lobeSampling = raygen.indexOf(
                "Indirect continuation: importance-sample");
        int sharcRoulette = raygen.indexOf(
                "Cache variants retain roulette after the sampled lobe");

        assertTrue(emissive >= 0);
        assertTrue(celestialNee > emissive);
        assertTrue(vertexComplete > celestialNee);
        assertTrue(roulette > vertexComplete,
                "non-SHaRC roulette must not terminate before current-vertex lighting");
        assertTrue(lobeSampling > roulette,
                "roulette must still gate continuation before the next lobe is sampled");
        assertTrue(sharcRoulette > lobeSampling,
                "SHARC must retain its separate post-lobe roulette path");

        assertTrue(raygen.contains("int rrStart = 1;"));
        assertEquals(1, raygen.split(
                "Standard non-SHaRC roulette runs only after", -1).length - 1);

        String rouletteBlock = raygen.substring(roulette, lobeSampling);
        assertTrue(rouletteBlock.contains(
                "#if !CAUSTICA_SHARC_UPDATE && !CAUSTICA_SHARC_QUERY"));
        assertTrue(rouletteBlock.contains(
                "if (rndf(sampler) > q)"));
        assertTrue(rouletteBlock.contains(
                "throughput /= q;"));

        assertFalse(raygen.contains(
                "Standard path roulette owns the complete opaque vertex"));
    }

    @Test
    void liveOwenSobolPairOccupiesEverySixteenBySixteenCell() {
        for (int pair = 0; pair < RtBlueNoiseSequence.DIMENSIONS / 2; pair++) {
            Set<Integer> cells = new HashSet<>();
            for (int sampleIndex = 0; sampleIndex < 256; sampleIndex++) {
                int x = RtBlueNoiseSequence.sampleBits(sampleIndex, pair * 2);
                int y = RtBlueNoiseSequence.sampleBits(sampleIndex, pair * 2 + 1);
                cells.add(((x >>> 28) << 4) | (y >>> 28));
            }
            assertEquals(256, cells.size(), "pair " + pair);
        }
        assertEquals(4096, RtBlueNoiseSequence.SAMPLE_COUNT);
    }

    @Test
    void liveSpatialAssignmentIsBijectiveAndNotRawScanlineOrder() {
        Set<Integer> assigned = new HashSet<>();
        for (int pixelIndex = 0; pixelIndex < RtBlueNoiseSequence.SAMPLE_COUNT; pixelIndex++) {
            assigned.add(RtBlueNoiseSequence.spatialSampleIndex(pixelIndex));
        }
        assertEquals(RtBlueNoiseSequence.SAMPLE_COUNT, assigned.size());
        assertNotEquals(0, RtBlueNoiseSequence.spatialSampleIndex(0));
        assertNotEquals(1, RtBlueNoiseSequence.spatialSampleIndex(1));
        assertNotEquals(64, RtBlueNoiseSequence.spatialSampleIndex(64));
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
