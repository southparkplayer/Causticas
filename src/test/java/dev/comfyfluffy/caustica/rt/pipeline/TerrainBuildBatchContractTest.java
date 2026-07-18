package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class TerrainBuildBatchContractTest {
    @Test
    void terrainAccelerationBuildsUseAWaitOnceBatchOfIsolatedCommandBuffers() throws Exception {
        String executor = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtGpuExecutor.java"));
        assertTrue(executor.contains("private static final int MAX_BUILD_BATCH = 4;"));
        assertTrue(executor.contains("VkCommandBufferSubmitInfo.calloc(batch.size(), stack)"));
        assertTrue(executor.contains(".pCommandBufferInfos(commandInfos)"));
        assertTrue(executor.contains(".semaphore(graphicsTimeline).value(priorGraphicsUse)"));
        assertTrue(executor.contains(".pWaitSemaphoreInfos(wait).pSignalSemaphoreInfos(signal)"));
        assertTrue(executor.contains("waitTimeline(buildTimeline, signalValue)"));
        assertTrue(executor.contains("for (VkCommandBuffer cmd : commands)"));
    }

    @Test
    void graphicsAndTerrainReservationsShareOneOrderingLock() throws Exception {
        String executor = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtGpuExecutor.java"));
        assertTrue(executor.contains("private final Object asLaneOrderLock = new Object();"));
        assertTrue(executor.contains("Math.max(pendingPublishWaitValue.get(), latestSubmittedBuildValue.get())"));
        assertTrue(executor.contains("synchronized (asLaneOrderLock)"));
        assertTrue(executor.indexOf("latestSubmittedBuildValue.accumulateAndGet(signalValue, Math::max)")
                > executor.indexOf("vkQueueSubmit2KHR"));
    }
}
