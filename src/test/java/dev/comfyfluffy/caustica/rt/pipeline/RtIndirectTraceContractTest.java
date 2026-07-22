package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class RtIndirectTraceContractTest {
    @Test
    void indirectTraceUsesTheKhrCommandAndRetainsFallback() throws Exception {
        String pipeline = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtPipeline.java"));
        String bringup = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtDeviceBringup.java"));
        assertTrue(pipeline.contains("vkCmdTraceRaysIndirectKHR"));
        assertTrue(pipeline.contains("traceRaysIndirectSupported"));
        assertTrue(pipeline.contains("trace(VkCommandBuffer cmd, int width, int height"));
        assertTrue(bringup.contains("vkCmdTraceRaysIndirectKHR"));
        assertTrue(bringup.contains("maxRayDispatchInvocationCount"));
    }
}
