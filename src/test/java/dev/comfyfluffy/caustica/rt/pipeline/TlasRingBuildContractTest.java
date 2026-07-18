package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class TlasRingBuildContractTest {
    @Test
    void frameTlasRingDoesNotRefitWithoutCompletionOwnedSlots() throws Exception {
        String accel = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/accel/RtAccel.java"));
        String executor = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtGpuExecutor.java"));
        String composite = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java"));

        assertTrue(accel.contains("tlas.label + \" build\""));
        assertTrue(!accel.contains("tlas.update"));
        assertTrue(!accel.contains("updatesSinceBuild"));
        assertTrue(accel.contains("waitForGraphicsValue(slot.lastGraphicsUse)"));
        assertTrue(accel.contains("slot.lastGraphicsUse = graphicsUse"));
        assertTrue(executor.indexOf("latestGraphicsUseValue.accumulateAndGet(graphicsValue, Math::max)")
                < executor.indexOf("public void endGraphicsTerrainUse"));
        assertTrue(composite.indexOf("beginGraphicsTerrainUse(encoder)")
                < composite.indexOf("RtAccel.prepareTlas"));
        assertTrue(composite.indexOf("RtAccel.markTlasUsed(frameTlas, graphicsUse)")
                < composite.indexOf("encoder.execute(cmd)"));
    }
}
