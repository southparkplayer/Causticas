package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class EntityYawFitContractTest {
    @Test
    void computesTheUnchangedYawAngleOnce() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/entity/RtEntities.java"));
        int method = source.indexOf("private static float[] fitYawTransform");
        int nextMethod = source.indexOf("private static boolean positionsBitwiseEqual", method);
        String fit = source.substring(method, nextMethod);

        assertEquals(1, occurrences(fit, "Math.atan2(b, a)"));
        assertTrue(fit.contains("Math.cos(yaw)"));
        assertTrue(fit.contains("Math.sin(yaw)"));
    }

    private static int occurrences(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }
}
