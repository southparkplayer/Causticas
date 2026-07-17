package dev.comfyfluffy.caustica.streamline;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class StreamlineReleaseLoggingTest {
    @Test
    void repeatedLifecycleProofsStayOutOfReleaseInfoLogs() throws Exception {
        String coordinator = source(
                "src/main/java/dev/comfyfluffy/caustica/streamline/StreamlineSwapchainCoordinator.java");
        String frameGeneration = source(
                "src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtDlssFg.java");
        String acceptance = source(
                "src/main/java/dev/comfyfluffy/caustica/rt/pipeline/StreamlineAcceptanceReport.java");

        assertTrue(coordinator.contains("LOGGER.debug(\n                \"Streamline swapchain generation"));
        assertFalse(coordinator.contains("LOGGER.info(\n                \"Streamline swapchain generation"));
        assertTrue(frameGeneration.contains("LOGGER.debug(\n                        \"DLSS-G staged first world frame"));
        assertFalse(frameGeneration.contains("probed = false;"));
        assertFalse(acceptance.contains("PRODUCTION_WRITE_INTERVAL_NANOS"));
        assertTrue(acceptance.contains("if (StreamlineRuntime.productionVariant()"));
    }

    private static String source(String path) throws Exception {
        return Files.readString(Path.of(path)).replace("\r\n", "\n");
    }
}
