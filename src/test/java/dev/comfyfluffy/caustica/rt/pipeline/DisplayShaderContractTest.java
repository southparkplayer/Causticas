package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Source-level guard for the optional PsychoV23 comparison path. */
final class DisplayShaderContractTest {
    @Test
    void psychoV23ComparisonIsComputedOnlyForTheComparisonView() throws IOException {
        String source = shader();
        int selected = source.indexOf("vec3 selectedLdr = tonemapSdr");
        int comparison = source.indexOf("if (pc.debugView == DISPLAY_DEBUG_TONEMAP_COMPARISON)");
        int psycho = source.indexOf("vec3 psychoV23Ldr =", comparison);

        assertTrue(selected >= 0);
        assertTrue(comparison > selected);
        assertTrue(psycho > comparison);
        assertFalse(source.substring(selected, comparison).contains("psychoV23Ldr"));
    }

    private static String shader() throws IOException {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            Path candidate = cursor.resolve("shaders").resolve("display").resolve("display.comp");
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate).replace("\r\n", "\n");
            }
            cursor = cursor.getParent();
        }
        throw new IOException("Could not locate shaders/display/display.comp");
    }
}
