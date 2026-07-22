package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class OfflineSchedulerContractTest {
    @Test
    void schedulerAndIndirectRaygenShareTheInvocationLayout() throws Exception {
        String shader = Files.readString(Path.of("shaders/world/offline_schedule.comp.slang"));
        String raygen = Files.readString(Path.of("shaders/world/world.rgen.slang"));
        String pipeline = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtOfflineSchedulePipeline.java"));
        String composite = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java"));
        String mode = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/client/OfflineGroundTruth.java"));
        String hud = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/client/OfflineRendererHud.java"));
        assertTrue(shader.contains("uint indirectWidth;"));
        assertTrue(shader.contains("work[slot].tileIndex"));
        assertTrue(shader.contains("InterlockedAdd(state[0].indirectWidth, 64u)"));
        assertTrue(shader.contains("OFFLINE_MIN_ADAPTIVE_SAMPLES = 64u"));
        assertTrue(shader.contains("completedSamples = min(completedSamples"));
        assertTrue(raygen.contains("if (linearIndex == 0u)"));
        assertTrue(raygen.contains("(linearIndex - 1u) >> 6u"));
        assertTrue(pipeline.contains("VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT"));
        assertTrue(pipeline.contains("vkCmdFillBuffer"));
        assertTrue(pipeline.contains("offline_schedule.comp.spv"));
        assertTrue(composite.contains("Nominal path totals are not a convergence signal"));
        assertTrue(mode.contains("scheduledSamplesPerPixel"));
        assertTrue(hud.contains("requested %.1f spp"));
        assertTrue(raygen.contains("uint pathCount = spp;"));
        assertTrue(!raygen.contains("pathCount = min(spp"));
        assertTrue(raygen.contains("traceRadiance(cameraVisibilityMask, ro, RAY_TMIN"));
    }
}
