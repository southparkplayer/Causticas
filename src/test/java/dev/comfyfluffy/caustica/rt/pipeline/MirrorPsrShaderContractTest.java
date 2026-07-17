package dev.comfyfluffy.caustica.rt.pipeline;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class MirrorPsrShaderContractTest {
    @Test
    void exactMetalMirrorsUseDeltaContinuationAndRecursivePrimarySurfaceReplacement() throws Exception {
        String source = Files.readString(Path.of("shaders/world/world.rgen.slang"));
        assertTrue(source.contains("perceptualRough <= PSR_MIRROR_ROUGHNESS_MAX && metal >= 0.999"));
        assertTrue(source.contains("if (exactMirror)"));
        assertTrue(source.contains("rd = reflect(rd, n);"));
        assertTrue(source.contains("PSR_MAX_CONFIGURED_MIRRORS = 32u"));
        assertTrue(source.contains("recursivePrimarySurfaceReplacement("));
        assertTrue(source.contains("maxMirrors = clamp(pc.psrMaxMirrors, 1u, PSR_MAX_CONFIGURED_MIRRORS)"));
        assertTrue(source.contains("translation += mappedNormal"));
        assertTrue(source.contains("maxBounces + maxMirrorDepth + MAX_OPTICAL_INTERFACE_DEPTH + 1"));
    }

    @Test
    void mirrorDepthIsAHotReloadedOneToThirtyTwoSetting() throws Exception {
        String common = Files.readString(Path.of("shaders/world/world_common.slang"));
        String config = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/CausticaConfig.java"));
        String options = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/client/RtVideoOptions.java"));
        // This explicit offset must match the generated WorldPushData serializer. A prior 428-byte
        // annotation made every runtime setting read the four-byte padding lane and clamp to depth 1.
        assertTrue(common.contains("[vk::offset(424)] public uint     psrMaxMirrors"));
        assertTrue(config.contains("\"composite.psr-max-mirrors\", 3, 1, 32"));
        assertTrue(options.contains("new OptionInstance.IntRange(1, 32)"));
    }

    @Test
    void glossySurfacesRetainTheFiniteGgxFloor() throws Exception {
        String source = Files.readString(Path.of("shaders/world/world.rgen.slang"));
        assertTrue(source.contains("float rough = max(perceptualRough, MIN_ROUGH);"));
        assertTrue(source.contains("static const float MIN_ROUGH = 0.045;"));
    }
}
