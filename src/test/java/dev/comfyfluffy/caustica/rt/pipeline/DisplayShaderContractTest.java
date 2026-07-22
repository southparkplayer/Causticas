package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Source-level guard for the optional PsychoV24 comparison path. */
final class DisplayShaderContractTest {
    @Test
    void psychoV24ComparisonIsComputedOnlyForTheComparisonView() throws IOException {
        String source = shader();
        int selected = source.indexOf("vec3 selectedLdr = tonemapSdr");
        int comparison = source.indexOf("if (pc.debugView == DISPLAY_DEBUG_TONEMAP_COMPARISON)");
        int psycho = source.indexOf("vec3 psychoV24Ldr =", comparison);

        assertTrue(selected >= 0);
        assertTrue(comparison > selected);
        assertTrue(psycho > comparison);
        assertFalse(source.substring(selected, comparison).contains("psychoV24Ldr"));
    }

    @Test
    void tonemapperComparisonHasNoCenterDivider() throws IOException {
        String source = shader();
        int comparison = source.indexOf("if (pc.debugView == DISPLAY_DEBUG_TONEMAP_COMPARISON)");
        int comparisonEnd = source.indexOf("imageStore(outputImage", comparison);
        String comparisonPath = source.substring(comparison, comparisonEnd);

        assertTrue(comparisonPath.contains("ldr = pix.x < split ? selectedLdr : psychoV24Ldr;"));
        assertFalse(comparisonPath.contains("pix.x == split"));
    }

    @Test
    void psychoV24ComparisonRemainsSelectableInVideoOptions() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/client/RtVideoOptions.java"));
        assertTrue(source.contains("CausticaConfig.Rt.Composite.DEBUG_VIEW_TONEMAP_COMPARISON, 9, 10, 11, 12"));
        assertTrue(source.contains("new OptionInstance.Enum<>(DEBUG_VIEW_ORDER, Codec.INT)"));
    }

    @Test
    void psychoV24SdrPeakUsesPersonalDefaultAndExpandedRange() throws IOException {
        String config = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/CausticaConfig.java"));
        String options = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/client/RtVideoOptions.java")).replace("\r\n", "\n");

        assertTrue(config.contains("1.0f, 0.5f, 64.0f"));
        assertTrue(options.contains("CausticaConfig.Rt.Sdr.PSYCHOV23_PEAK,\n                    10, 5, 640, 1"));
    }

    @Test
    void psychoV24UsesTheAuthoredAdaptiveMbHueInterpolation() throws IOException {
        String source = shader();

        assertTrue(source.contains("const int PSYCHO24_MANUAL_HUE_COUNT = 23;"));
        assertTrue(source.contains("psycho24SampleManualHueLinearity(sourceHuePhase)"));
        assertTrue(source.contains("vec3 hueRestoredLms = psycho24ApplyManualHueDirection("));
        assertFalse(source.contains("psycho23ApplySignedOpponentRetention"));
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
