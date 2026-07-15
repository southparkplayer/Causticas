package dev.comfyfluffy.caustica.rt.pipeline;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

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
}
