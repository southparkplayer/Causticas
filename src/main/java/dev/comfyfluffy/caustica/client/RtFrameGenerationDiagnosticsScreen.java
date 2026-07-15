package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg;
import dev.comfyfluffy.caustica.streamline.StreamlineRuntime;
import dev.comfyfluffy.caustica.streamline.StreamlineSwapchainCoordinator;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/** Detailed Streamline state kept one level below the normal player-facing controls. */
public final class RtFrameGenerationDiagnosticsScreen extends OptionsSubScreen {
    private Button statusWidget;
    private Button runtimeWidget;
    private Button detailsWidget;
    private Button vramWidget;

    public RtFrameGenerationDiagnosticsScreen(Screen lastScreen, Options options) {
        super(lastScreen, options, Component.translatable("caustica.options.rt.fg.diagnostics.title"));
    }

    @Override
    protected void addOptions() {
        statusWidget = diagnosticButton();
        runtimeWidget = diagnosticButton();
        detailsWidget = diagnosticButton();
        vramWidget = Button.builder(Component.empty(), button -> {
            RtDlssFg.INSTANCE.requestVramEstimate();
            refreshDiagnostics();
        }).width(Button.BIG_WIDTH).build();
        list.addBig(statusWidget);
        list.addBig(runtimeWidget);
        list.addBig(detailsWidget);
        list.addBig(vramWidget);
        refreshDiagnostics();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        refreshDiagnostics();
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void refreshDiagnostics() {
        if (statusWidget == null) {
            return;
        }
        RtDlssFg fg = RtDlssFg.INSTANCE;
        String availability = fg.isActive() ? "Active"
                : fg.isAvailable() && fg.hasGeneratedFrames() ? "Verified (suspended)"
                : fg.isAvailable() && RtDlssFg.requested() ? fg.submissionStatus()
                : fg.isAvailable() ? "Available"
                : fg.unavailableReason().isBlank() ? "Unavailable" : fg.unavailableReason();
        statusWidget.setMessage(Component.literal("Streamline DLSS-G: " + availability));
        String maximum = fg.multiFrameCountMax() > 0 ? (fg.multiFrameCountMax() + 1) + "x" : "not probed";
        runtimeWidget.setMessage(Component.literal(
                "Runtime: " + RtDlssFg.statusDescription(fg.runtimeStatus())
                        + " | Max " + maximum
                        + " | Count configured/effective/native " + fg.configuredMultiFrameCount()
                        + "/" + fg.effectiveMultiFrameCount() + "/" + fg.nativeSubmittedMultiFrameCount()
                        + " | Presented " + fg.framesActuallyPresented()
                        + " (max " + fg.maxFramesActuallyPresented() + ")"));
        String reflex = fg.isActive()
                ? CausticaConfig.Rt.Reflex.LOW_LATENCY_BOOST.value() ? "On + Boost" : "On (required)"
                : CausticaConfig.Rt.Reflex.ENABLED.value()
                        ? CausticaConfig.Rt.Reflex.LOW_LATENCY_BOOST.value() ? "On + Boost" : "On"
                        : "Off";
        var overrides = CausticaConfig.activeOverrides();
        String overrideStatus = overrides.isEmpty() ? "none"
                : overrides.size() + " (first: " + overrides.getFirst().key() + ")";
        detailsWidget.setMessage(Component.literal(
                "Reflex " + reflex
                        + " | VSync " + StreamlineSwapchainCoordinator.INSTANCE.requestedPresentMode()
                        + " -> " + StreamlineSwapchainCoordinator.INSTANCE.normalizedPresentMode()
                        + " -> native " + StreamlineSwapchainCoordinator.INSTANCE.nativePresentMode()
                        + (StreamlineSwapchainCoordinator.INSTANCE.nativeProxyDispatch()
                                ? " (Streamline proxy)" : " (native dispatch)")
                        + " | Queue " + fg.effectiveQueueMode() + " (" + fg.queuePolicyReason() + ")"
                        + (fg.queueFallbackActive() ? " (fallback: " + fg.queueFallbackReason() + ")" : "")
                        + " | Flip metering " + StreamlineRuntime.flipMeteringState()
                        + " | SDK FG VSync " + (fg.vsyncSupportAvailable() ? "available" : "unsupported")
                        + " | Images app/proxy-visible " + StreamlineSwapchainCoordinator.INSTANCE.applicationImageCount()
                        + "/" + StreamlineSwapchainCoordinator.INSTANCE.proxyVisibleImageCount()
                        + " | Reflex interval " + fg.reflexIntervalUs() + "us"
                        + " | DLSSD configured/effective "
                        + CausticaConfig.Rt.DlssRr.ENABLED.configuredValue() + "/"
                        + CausticaConfig.Rt.DlssRr.ENABLED.value()
                        + " | Launch overrides " + overrideStatus + " | " + fg.featureVersion()));
        String vram = fg.estimatedVramUsage() > 0L
                ? (fg.estimatedVramUsage() / (1024L * 1024L)) + " MiB" : "not estimated";
        vramWidget.setMessage(Component.literal("Estimate DLSS-G VRAM: " + vram));
    }

    private static Button diagnosticButton() {
        Button button = Button.builder(Component.empty(), ignored -> {
        }).width(Button.BIG_WIDTH).build();
        button.active = false;
        return button;
    }
}
