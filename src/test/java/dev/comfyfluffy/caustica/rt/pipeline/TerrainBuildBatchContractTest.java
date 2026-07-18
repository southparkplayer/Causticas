package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class TerrainBuildBatchContractTest {
    @Test
    void terrainAccelerationBuildsUseIsolatedCommandBuffers() throws Exception {
        String executor = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtGpuExecutor.java"));
        assertTrue(executor.contains("private static final int MAX_BUILD_BATCH = 1;"));
    }
}
