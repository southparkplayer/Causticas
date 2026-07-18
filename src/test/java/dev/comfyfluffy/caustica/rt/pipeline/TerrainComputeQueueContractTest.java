package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class TerrainComputeQueueContractTest {
    @Test
    void terrainExecutorUsesInitializedDeviceComputeQueue() throws Exception {
        String context = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtContext.java"));
        String executor = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtGpuExecutor.java"));

        assertTrue(context.contains("this.computeQueue = device.computeQueue();"));
        assertTrue(executor.contains("synchronized (ctx.deviceQueueHostLock())"));
    }
}
