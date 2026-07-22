package dev.comfyfluffy.caustica.rt.pipeline;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FirstPersonVisibilityContractTest {
    @Test
    void bodyIsPrimaryAndSecondaryWhileHeadRemainsSecondaryOnly() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/entity/RtEntities.java"));

        assertTrue(source.contains(
                "captureKey(entityToken, CAPTURE_BODY), MASK_PRIMARY | MASK_SECONDARY,"));
        assertTrue(source.contains(
                "captureKey(entityToken, CAPTURE_HEAD), MASK_SECONDARY,"));
    }

    @Test
    void filteredPassesKeepDistinctLongKeysAndBypassFullModelCuboidTemplates() throws Exception {
        String entities = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/entity/RtEntities.java"));
        String collector = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/entity/RtEntityCollector.java")).replace("\r\n", "\n");

        assertTrue(entities.contains("Long2ObjectOpenHashMap<EntityPrev> prevVerts"));
        assertTrue(entities.contains("private static long captureKey(long entityToken, int part)"));
        assertTrue(collector.contains("directTemplate = captureMode == CaptureMode.FULL"));
        assertTrue(collector.contains("? cuboidEmitter.prepare(model) : null"));
        assertTrue(collector.contains("renderFilteredHumanoid"));
    }

    @Test
    void vanillaHandSuppressionIsDefaultOnAndFailOpen() throws Exception {
        String config = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/CausticaConfig.java"));
        String mixin = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/mixin/GameRendererMixin.java"));

        assertTrue(config.contains(
                "\"caustica.rt.firstPerson.disableVanillaModel\", \"first-person.disable-vanilla-model\", true"));
        assertTrue(mixin.contains("VanillaRenderController.rtRuntimeWorkRequested()"));
        assertTrue(mixin.contains("RtEntities.enabled()"));
        assertTrue(mixin.contains("CausticaConfig.Rt.FirstPerson.ENABLED.value()"));
        assertTrue(mixin.contains("CausticaConfig.Rt.FirstPerson.DISABLE_VANILLA_MODEL.value()"));
        assertTrue(mixin.contains("options.getCameraType().isFirstPerson()"));
        assertTrue(mixin.contains("if (disableVanillaModel)"));
    }

    @Test
    void transmittedCameraPathsExcludeTheHeadUntilReflectionOrScattering() throws Exception {
        String raygen = Files.readString(Path.of("shaders/world/world.rgen.slang"))
                .replace("\r\n", "\n");

        assertTrue(raygen.contains("uint cameraVisibilityMask = CULL_PRIMARY;"));
        assertTrue(raygen.contains(
                "traceRadiance(cameraVisibilityMask, ro, RAY_TMIN, rd, 10000.0);"));
        assertTrue(raygen.contains("uint guideVisibilityMask = CULL_PRIMARY;"));
        assertTrue(raygen.contains(
                "traceGuide(RAY_FLAG_NONE, guideVisibilityMask, ro, RAY_TMIN, direction, 10000.0);"));
        assertTrue(raygen.contains(
                "guideVisibilityMask = CULL_SECONDARY;\n"
                        + "            direction = normalize(reflect(direction, interfaceNormal));"));
        assertTrue(raygen.contains(
                "if (chooseReflection) {\n                cameraVisibilityMask = CULL_SECONDARY;"));
        assertTrue(raygen.contains("cameraVisibilityMask = CULL_SECONDARY;\n        if (pbr)"));
        assertFalse(raygen.contains(
                "traceRadiance(segment == 0 ? CULL_PRIMARY : CULL_SECONDARY"));
    }
}
