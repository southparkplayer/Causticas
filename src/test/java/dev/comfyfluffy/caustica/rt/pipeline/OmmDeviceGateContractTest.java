package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class OmmDeviceGateContractTest {
    @Test
    void persistedOmmSettingControlsDeviceExtensionEnablement() throws Exception {
        String bringup = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtDeviceBringup.java"));

        assertTrue(bringup.contains("return CausticaConfig.Rt.Omm.ENABLED.value();"));
        assertTrue(!bringup.matches("(?s).*private static boolean ommRequested\\(\\) \\{\\s*return true;.*"));
    }
}
