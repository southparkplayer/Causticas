package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DlssgPresentationContractTest {
    private static String source(String relative) throws Exception {
        return Files.readString(Path.of(relative));
    }

    @Test
    void optionsRemainFrameScopedBeforePresentStart() throws Exception {
        String fg = source("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtDlssFg.java");
        int beforePresent = fg.indexOf("public void beforePresent()");
        int options = fg.indexOf("applyOptionsForPresent();", beforePresent);
        int marker = fg.indexOf("marker(PCL_PRESENT_START);", beforePresent);
        assertTrue(beforePresent >= 0 && options > beforePresent && marker > options);
    }

    @Test
    void mailboxKeepsLogicalVsyncSeparateFromPhysicalFifo() throws Exception {
        String coordinator = source("src/main/java/dev/comfyfluffy/caustica/streamline/StreamlineSwapchainCoordinator.java");
        assertTrue(coordinator.contains("vsyncRequested,\n                physicalFifo, pluginForSwapchain"));
        assertTrue(coordinator.contains("presentMode = GpuSurface.PresentMode.MAILBOX"));
        assertTrue(coordinator.contains("desiredPlugin = CausticaConfig.Rt.Fg.requested() && !physicalFifo"));
    }

    @Test
    void steadyStateRetirementNeverUsesDeviceIdle() throws Exception {
        String fg = source("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtDlssFg.java");
        int wait = fg.indexOf("private int waitForInputSlot");
        int fallback = fg.indexOf("private boolean enterBlockingQueueFallback", wait);
        assertTrue(wait >= 0 && fallback > wait);
        assertFalse(fg.substring(wait, fallback).contains("vkDeviceWaitIdle"));
    }

    @Test
    void automaticQueuePolicyUsesPresentBlockingForMailboxVsync() throws Exception {
        String fg = source("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtDlssFg.java");
        assertTrue(fg.contains("!queueFallback && !logicalVsyncRequested"));
        assertTrue(fg.contains("MAILBOX VSync requires Streamline-owned presenting-queue pacing"));
    }

    @Test
    void reportKeepsApplicationAndProxyCountsDistinct() throws Exception {
        String report = source("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/StreamlineAcceptanceReport.java");
        assertTrue(report.contains("\\\"applicationImageCount\\\""));
        assertTrue(report.contains("\\\"requestedNativeMinImageCount\\\""));
        assertTrue(report.contains("\\\"proxyVisibleImageCount\\\""));
        assertFalse(report.contains("\\\"physicalProxyImageCount\\\""));
    }
}
