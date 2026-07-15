package dev.comfyfluffy.caustica.mixin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Guards physical-swapchain versus visible-content extent ownership across fullscreen transitions. */
final class FullscreenExtentContractTest {
    @Test
    void minecraftSurfaceRequestRemainsOwnedByMinecraftAndVulkan() throws IOException {
        String minecraft = source("src/main/java/dev/comfyfluffy/caustica/mixin/MinecraftMixin.java");
        assertFalse(minecraft.contains("GpuSurface$Configuration;<init>"));
        assertFalse(minecraft.contains("SurfaceExtentNormalizer"));
    }

    @Test
    void createdSwapchainIsAuthoritativeWhileStreamlineReceivesTheVisibleSubrect() throws IOException {
        String surface = source("src/main/java/dev/comfyfluffy/caustica/mixin/VulkanGpuSurfaceMixin.java");
        String coordinator = source(
                "src/main/java/dev/comfyfluffy/caustica/streamline/StreamlineSwapchainCoordinator.java");
        String frameGeneration = source("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtDlssFg.java");

        assertTrue(surface.contains("configured(config, this.swapchainWidth, this.swapchainHeight"));
        assertTrue(coordinator.contains("width = actualWidth;"));
        assertTrue(coordinator.contains("height = actualHeight;"));
        assertTrue(frameGeneration.contains("extent-only backbuffer tag"));
        assertTrue(frameGeneration.contains("contentWidth, contentHeight, 53"));
        assertTrue(frameGeneration.contains("hudless.width > colorWidth"));
        assertTrue(frameGeneration.contains("hudless.height > colorHeight"));
    }

    @Test
    void dlssgUsesMailboxForVsyncAndNoApplicationLimiter() throws IOException {
        String surface = source("src/main/java/dev/comfyfluffy/caustica/mixin/VulkanGpuSurfaceMixin.java");
        String coordinator = source(
                "src/main/java/dev/comfyfluffy/caustica/streamline/StreamlineSwapchainCoordinator.java");
        String frameGeneration = source("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtDlssFg.java");
        String config = source("src/main/java/dev/comfyfluffy/caustica/CausticaConfig.java");

        assertTrue(surface.contains("caustica$normalizeStreamlinePresentMode"));
        assertTrue(surface.contains("normalizeConfiguration(config, this.supportedPresentModes)"));
        assertTrue(coordinator.contains("GpuSurface.PresentMode.MAILBOX"));
        assertTrue(coordinator.contains(
                "new GpuSurface.Configuration(configuration.width(), configuration.height(), presentMode)"));
        assertTrue(coordinator.contains("boolean desiredPlugin = CausticaConfig.Rt.Fg.requested() && !physicalFifo"));
        assertTrue(coordinator.contains("A surface without MAILBOX stays on the requested FIFO mode and DLSS-G fails closed"));
        assertTrue(frameGeneration.contains("int limit = requested() ? 0"));
        assertFalse(frameGeneration.contains("FrameDeadlinePacer"));
        assertFalse(config.contains("AUTO_CAP"));
    }

    private static String source(String relative) throws IOException {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            Path candidate = cursor.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate).replace("\r\n", "\n");
            }
            cursor = cursor.getParent();
        }
        throw new IOException("Could not locate " + relative);
    }
}
