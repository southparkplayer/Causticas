package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class OutputScaleContractTest {
    private static String read(String path) throws Exception { return Files.readString(Path.of(path)); }

    @Test
    void resolutionScaleControlsAreRemovedFromSettingsSurfaces() throws Exception {
        String config = read("src/main/java/dev/comfyfluffy/caustica/CausticaConfig.java");
        String caustica = read("src/main/java/dev/comfyfluffy/caustica/client/CausticaSettingsScreen.java");
        String vanilla = read("src/main/java/dev/comfyfluffy/caustica/mixin/VideoSettingsScreenMixin.java");
        String options = read("src/main/java/dev/comfyfluffy/caustica/client/RtVideoOptions.java");
        assertTrue(config.contains("\"output-scale.percent\", 100, 10, 200"));
        assertFalse(config.contains("FAST_PERCENT"));
        assertTrue(config.contains("\"dlss-rr.input-scale-percent\", 20, 10, 40"));
        assertFalse(caustica.contains("outputScale"));
        assertFalse(caustica.contains("inputScale"));
        assertFalse(caustica.contains("reconstruction.outputScaling"));
        assertFalse(caustica.contains("pendingInputScaleTenths"));
        String videoMixin = vanilla;
        assertFalse(options.contains("public static OptionInstance<Integer> outputScale()"));
        assertFalse(options.contains("public static OptionInstance<Integer> inputScale()"));
        assertFalse(videoMixin.contains("list.addBig(RtVideoOptions.outputScale())"));
        assertFalse(videoMixin.contains("fastOutputScale"));
        assertFalse(videoMixin.contains("list.addBig(RtVideoOptions.inputScale())"));
    }

    @Test
    void reconstructionUsesScaledDisplaySizeAndNativeIsARealBypass() throws Exception {
        String composite = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");
        assertTrue(composite.contains("RtReconstruction.queryDlssdPlan(width, height, desiredOutputW, desiredOutputH"));
        assertTrue(composite.contains("dlssdResolutionPlan.dlssdOutputWidth()"));
        assertTrue(composite.contains("if (scaledDisplayImage == null) return; // True zero-overhead native path."));
        assertTrue(composite.contains("OfflineGroundTruth.INSTANCE.active()"));
        assertTrue(composite.contains("int desiredScalePercent = 100"));
        assertFalse(composite.contains("desiredFastPercent"));
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
        assertFalse(bridge.contains("\"fastOutputScalePercent\""));
        assertTrue(bridge.contains("\"inputScalePercent\""));
        assertTrue(bridge.contains("\"inputRatioTenths\""));
        assertTrue(bridge.contains("\"inputUpscaleRatio\""));
        assertTrue(bridge.contains("\"requestedInputRatioTenths\""));
        assertTrue(bridge.contains("\"appliedInputRatioTenths\""));
        assertTrue(bridge.contains("\"outputWidth\""));
        assertTrue(bridge.contains("\"outputHeight\""));
        assertTrue(bridge.contains("\"renderWidth\""));
        assertTrue(bridge.contains("\"finalOutputWidth\""));
        assertTrue(bridge.contains("\"finalOutputHeight\""));
        assertTrue(bridge.contains("\"dlssdIntermediateWidth\""));
        assertTrue(bridge.contains("\"dlssdIntermediateHeight\""));
        assertTrue(bridge.contains("\"traceWidth\""));
        assertTrue(bridge.contains("\"traceHeight\""));
        assertTrue(bridge.contains("\"outputScalePath\""));
        assertTrue(bridge.contains("\"outputScaleFailure\""));
    }

    @Test
    void customDlssInputScaleNeverSubmitsOutsideTheAdvertisedRange() throws Exception {
        String nativeBridge = read("native/streamline_bridge/streamline_bridge.cpp");
        String library = read("src/main/java/dev/comfyfluffy/caustica/streamline/StreamlineLibrary.java");
        String dlss = read("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtDlssRr.java");
        String reconstruction = read("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtReconstruction.java");
        assertTrue(nativeBridge.contains("settings.renderWidthMin"));
        assertTrue(nativeBridge.contains("settings.renderHeightMin"));
        assertTrue(nativeBridge.contains("settings.renderWidthMax"));
        assertTrue(nativeBridge.contains("settings.renderHeightMax"));
        assertTrue(library.contains("MemorySegment renderWidthMin"));
        assertTrue(dlss.contains("queryResolutionPlan"));
        assertTrue(dlss.contains("DlssdResolutionPlanner.plan"));
        assertTrue(dlss.contains("plan.usesDlssd()"));
        assertFalse(dlss.contains("evaluation will be attempted"));
        assertTrue(reconstruction.contains("queryDlssdPlan("));
        assertTrue(reconstruction.contains("outputWidth, outputHeight, requestedWidth, requestedHeight"));
        String composite = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");
        assertTrue(composite.contains("renderAllocationW = optimal != null && optimal.length >= 4"));
        assertTrue(composite.contains("rrOperational ? renderAllocationW : 1"));
        assertTrue(composite.contains("DLSSD intermediate output"));
        assertTrue(composite.contains("dlssdSpatialUpscalePipeline.dispatch"));
        assertTrue(composite.contains("dlssdResolutionPlan.usesDlssd()"));
    }

    @Test
    void resizedImagesAlwaysRewritePersistentPipelineDescriptors() throws Exception {
        String bloom = read("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtBloomPipeline.java");
        String display = read("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtDisplayPipeline.java");
        String scale = read("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtOutputScalePipeline.java");
        assertFalse(bloom.contains("boundSource"));
        assertFalse(display.contains("boundOutputView"));
        assertFalse(scale.contains("boundViews"));
        assertTrue(bloom.contains("vkUpdateDescriptorSets"));
        assertTrue(display.contains("vkUpdateDescriptorSets"));
        assertTrue(scale.contains("vkUpdateDescriptorSets"));
    }
}
