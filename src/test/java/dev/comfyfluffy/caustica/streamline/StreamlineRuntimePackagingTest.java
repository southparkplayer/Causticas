package dev.comfyfluffy.caustica.streamline;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StreamlineRuntimePackagingTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void productionAndDevelopmentExtractionCannotSharePluginConfiguration() {
        Path production = StreamlineRuntime.packagedNativeDirectory(temporaryDirectory, "production");
        Path development = StreamlineRuntime.packagedNativeDirectory(temporaryDirectory, "development");
        assertNotEquals(production, development);
        assertTrue(production.endsWith(Path.of("windows-x64", "production")));
        assertTrue(development.endsWith(Path.of("windows-x64", "development")));
    }

    @Test
    void normalPackagingRemovesBehaviorChangingDevelopmentJson() throws Exception {
        Files.createDirectories(temporaryDirectory);
        Path stale = temporaryDirectory.resolve("sl.dlss_g.json");
        Files.writeString(stale, "{\"mode\": 1, \"numFramesToGenerate\": 1}");
        StreamlineRuntime.removeStaleBehaviorConfiguration(temporaryDirectory);
        assertFalse(Files.exists(stale));
    }

    @Test
    void vulkanMailboxVsyncWritesOnlyTheInternalVsyncOverride() throws Exception {
        Files.createDirectories(temporaryDirectory);
        StreamlineRuntime.configureBehaviorConfiguration(temporaryDirectory, true);
        assertEquals("{\n  \"vSyncConfig\": 1\n}\n",
                Files.readString(temporaryDirectory.resolve("sl.dlss_g.json")));
        StreamlineRuntime.configureBehaviorConfiguration(temporaryDirectory, false);
        assertFalse(Files.exists(temporaryDirectory.resolve("sl.dlss_g.json")));
    }

    @Test
    void nativeSwapchainTraceMapsVulkanPresentModesWithoutCallingAFrameCapVsync() {
        StreamlineRuntime.SwapchainTrace mailbox = new StreamlineRuntime.SwapchainTrace(
                true, 1, 3, 6, 0, true, 0x1234L);
        assertEquals("MAILBOX", mailbox.presentMode());
        assertTrue(mailbox.createSucceeded());
        assertEquals("0x1234", mailbox.handleHex());

        StreamlineRuntime.SwapchainTrace unknown = StreamlineRuntime.SwapchainTrace.unknown();
        assertEquals("UNKNOWN", unknown.presentMode());
        assertFalse(unknown.presentModeKnown());
    }
}
