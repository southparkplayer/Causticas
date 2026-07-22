package dev.comfyfluffy.caustica.rt;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class RtTraceGpuProfilerOfflineContractTest {
    @Test
    void delayedSlotsOwnWorkloadMetadata() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtTraceGpuProfiler.java"));
        assertTrue(source.contains("class Slot"));
        assertTrue(source.contains("OfflineDispatchMetadata offlineMetadata"));
        assertTrue(source.contains("completedOfflineMetadata"));
        assertTrue(source.contains("completed.frameSerial"));
        assertTrue(source.contains("current.reset()"));
        assertTrue(source.contains("never wait on the render thread"));
    }
}
