package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class OutputScaleContractTest {
    private static String read(String path) throws Exception { return Files.readString(Path.of(path)); }

    @Test
    void configAndBothSettingsSurfacesExposeTheFullPercentRange() throws Exception {
        String config = read("src/main/java/dev/comfyfluffy/caustica/CausticaConfig.java");
        String caustica = read("src/main/java/dev/comfyfluffy/caustica/client/CausticaSettingsScreen.java");
        String vanilla = read("src/main/java/dev/comfyfluffy/caustica/client/RtVideoOptions.java");
        assertTrue(config.contains("\"output-scale.percent\", 100, 10, 200"));
        assertTrue(caustica.contains("CausticaConfig.Rt.OutputScale.PERCENT, 10, 200"));
        assertTrue(caustica.contains("%.0f%%"));
        String videoMixin = read("src/main/java/dev/comfyfluffy/caustica/mixin/VideoSettingsScreenMixin.java");
        assertTrue(vanilla.contains("public static OptionInstance<Integer> outputScale()"));
        assertTrue(vanilla.contains("new OptionInstance.IntRange(10, 200)"));
        assertTrue(vanilla.contains("shiftDown() ? setting.defaultValue() : percent"));
        assertTrue(videoMixin.contains("list.addBig(RtVideoOptions.outputScale())"));
    }

    @Test
    void reconstructionUsesScaledDisplaySizeAndNativeIsARealBypass() throws Exception {
        String composite = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");
        assertTrue(composite.contains("RtReconstruction.queryRenderSize(outputW, outputH)"));
        assertTrue(composite.contains("renderW, renderH, outputW, outputH"));
        assertTrue(composite.contains("if (scaledDisplayImage == null) return; // True zero-overhead native path."));
        assertTrue(composite.contains("OfflineGroundTruth.INSTANCE.active()"));
        assertTrue(composite.contains("desiredScalePercent = offlineGroundTruth ? 100"));
        assertTrue(composite.contains("blitUpscale(cmd, stack, scaledDisplayImage, displayImage, VK10.VK_FILTER_LINEAR)"));
        assertTrue(composite.contains("desiredScalePercent == 100 && outputScalePipeline != null"));
    }

    @Test
    void shaderAndLicenseCarryFsr1RcasAndDownsampleContracts() throws Exception {
        String shader = read("shaders/display/output_scale.comp");
        String pipeline = read("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtOutputScalePipeline.java");
        String license = read("licenses/AMD-FidelityFX-FSR1.txt");
        assertTrue(shader.contains("FidelityFX FSR 1 EASU/RCAS GLSL port"));
        assertTrue(shader.contains("return max((lobe * (b + d + f + h) + e)"));
        assertTrue(shader.contains("float lanczos2"));
        assertTrue(shader.contains("max(imageLoad(workImage"));
        assertTrue(pipeline.contains("RCAS_SHARPNESS"));
        assertTrue(license.contains("Copyright (c) 2021 Advanced Micro Devices"));
        assertTrue(license.contains("Permission is hereby granted"));
    }

    @Test
    void runtimeStatusReportsConfiguredIntermediateTraceAndPath() throws Exception {
        String bridge = read("src/main/java/dev/comfyfluffy/caustica/client/CausticaDebugBridge.java");
        assertTrue(bridge.contains("\"outputScalePercent\""));
        assertTrue(bridge.contains("\"outputWidth\""));
        assertTrue(bridge.contains("\"outputHeight\""));
        assertTrue(bridge.contains("\"renderWidth\""));
        assertTrue(bridge.contains("\"outputScalePath\""));
        assertTrue(bridge.contains("\"outputScaleFailure\""));
    }
}
