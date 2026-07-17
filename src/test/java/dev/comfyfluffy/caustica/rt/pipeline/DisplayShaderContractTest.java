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

    @Test
    void psychoV23ComparisonRemainsSelectableInVideoOptions() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/client/RtVideoOptions.java"));
        assertTrue(source.contains("CausticaConfig.Rt.Composite.DEBUG_VIEW_TONEMAP_COMPARISON, 9, 10, 11, 12"));
        assertTrue(source.contains("new OptionInstance.Enum<>(DEBUG_VIEW_ORDER, Codec.INT)"));
    }

    @Test
    void psychoV23SdrPeakUsesReferenceDefaultAndExpandedRange() throws IOException {
        String config = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/CausticaConfig.java"));
        String options = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/client/RtVideoOptions.java")).replace("\r\n", "\n");

        assertTrue(config.contains("1000.0f / 203.0f, 0.5f, 64.0f"));
        assertTrue(options.contains("CausticaConfig.Rt.Sdr.PSYCHOV23_PEAK,\n                    10, 5, 640, 1"));
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
