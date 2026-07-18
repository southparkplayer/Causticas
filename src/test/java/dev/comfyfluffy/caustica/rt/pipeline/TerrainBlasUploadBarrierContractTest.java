package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class TerrainBlasUploadBarrierContractTest {
    @Test
    void transferUploadsBecomeVisibleToAccelerationStructureBuildReads() throws Exception {
        String builder = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtSectionBuilder.java"));

        assertTrue(builder.contains(".srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)"));
        assertTrue(builder.contains(".dstStageMask(VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR)"));
        assertTrue(builder.contains(".dstAccessMask(VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR)"));
    }
}
