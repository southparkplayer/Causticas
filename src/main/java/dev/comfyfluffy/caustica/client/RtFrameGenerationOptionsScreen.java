package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg;
import dev.comfyfluffy.caustica.streamline.StreamlineRuntime;
import dev.comfyfluffy.caustica.streamline.StreamlineSwapchainCoordinator;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/** Scrollable, capability-aware Streamline DLSS-G, Multi Frame Generation, and Reflex settings. */
public final class RtFrameGenerationOptionsScreen extends OptionsSubScreen {
    private final boolean initiallyRequested;
    private boolean surfaceSettingChanged;
    private Button statusWidget;
    private Button runtimeWidget;
    private Button detailsWidget;
    private Button vsyncWidget;

    public RtFrameGenerationOptionsScreen(Screen lastScreen, Options options) {
        super(lastScreen, options, Component.translatable("caustica.options.rt.frameGeneration.title"));
        this.initiallyRequested = CausticaConfig.Rt.Fg.requested();
    }

    @Override
    protected void addOptions() {
        statusWidget = diagnosticButton();
        runtimeWidget = diagnosticButton();
        detailsWidget = diagnosticButton();
        list.addBig(statusWidget);
        list.addBig(runtimeWidget);
        list.addBig(detailsWidget);

        vsyncWidget = Button.builder(Component.empty(), button -> {
            options.enableVsync().set(!options.enableVsync().get());
            surfaceSettingChanged = true;
            refreshDiagnostics();
        }).width(Button.BIG_WIDTH)
                .tooltip(Tooltip.create(Component.translatable("caustica.options.rt.fg.vsync.tooltip")))
                .build();
        list.addBig(vsyncWidget);

        OptionInstance<?>[] controls = RtVideoOptions.frameGenerationOptions();
        for (int i = 0; i < controls.length; i += 2) {
            if (i + 1 < controls.length) {
                list.addSmall(controls[i], controls[i + 1]);
            } else {
                list.addBig(controls[i]);
            }
        }
        list.addBig(Button.builder(Component.translatable("caustica.options.rt.fg.estimateVram"), button -> {
            RtDlssFg.INSTANCE.requestVramEstimate();
            refreshDiagnostics();
        }).width(Button.BIG_WIDTH).build());
        refreshDiagnostics();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        refreshDiagnostics();
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void removed() {
        super.removed();
        CausticaConfig.save();
        if (surfaceSettingChanged || initiallyRequested != CausticaConfig.Rt.Fg.requested()) {
            StreamlineSwapchainCoordinator.INSTANCE.requestReconfigure();
        }
    }

    private void refreshDiagnostics() {
        if (statusWidget == null) {
            return;
        }
        RtDlssFg fg = RtDlssFg.INSTANCE;
        if (vsyncWidget != null) {
            boolean enabled = options.enableVsync().get();
            String state = !enabled ? "Off - immediate presentation"
                    : StreamlineSwapchainCoordinator.INSTANCE.mailboxSupported()
                            ? "On - MAILBOX VSync"
                            : "On - FIFO (MAILBOX unavailable; DLSS-G fail-closed)";
            vsyncWidget.setMessage(Component.literal("DLSS-G VSync: " + state));
        }
        String availability = fg.isActive() ? "Active"
                : fg.isAvailable() && fg.hasGeneratedFrames() ? "Verified (suspended in menu)"
                : fg.isAvailable() && RtDlssFg.requested() ? fg.submissionStatus()
                : fg.isAvailable() ? "Available"
                : fg.unavailableReason().isBlank() ? "Unavailable" : fg.unavailableReason();
        statusWidget.setMessage(Component.literal("Streamline DLSS-G: " + availability));
        String maximum = fg.multiFrameCountMax() > 0
                ? (fg.multiFrameCountMax() + 1) + "x"
                : "not probed";
        runtimeWidget.setMessage(Component.literal(
                "Runtime: " + RtDlssFg.statusDescription(fg.runtimeStatus())
                        + " | Max " + maximum
                        + " | Count configured/effective/native " + fg.configuredMultiFrameCount()
                        + "/" + fg.effectiveMultiFrameCount()
                        + "/" + fg.nativeSubmittedMultiFrameCount()
                        + " | Presented " + fg.framesActuallyPresented()
                        + " (max " + fg.maxFramesActuallyPresented() + ")"));
        String reflex = fg.isActive()
                ? CausticaConfig.Rt.Reflex.LOW_LATENCY_BOOST.value() ? "On + Boost" : "On (required by DLSS-G)"
                : CausticaConfig.Rt.Reflex.ENABLED.value()
                        ? CausticaConfig.Rt.Reflex.LOW_LATENCY_BOOST.value() ? "On + Boost" : "On"
                        : "Off";
        String vram = fg.estimatedVramUsage() > 0L
                ? (fg.estimatedVramUsage() / (1024L * 1024L)) + " MiB"
                : "not estimated";
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
                        + " | Pacer " + (fg.isActive() ? "DLSS-G generated" : "waiting")
                        + " | Flip metering " + StreamlineRuntime.flipMeteringState()
                        + " | SDK FG VSync " + (fg.vsyncSupportAvailable() ? "available" : "unsupported")
                        + " | Native images " + StreamlineSwapchainCoordinator.INSTANCE.nativeImageCount()
                        + " | Reflex interval " + fg.reflexIntervalUs() + "us"
                        + " | DLSSD configured/effective "
                        + CausticaConfig.Rt.DlssRr.ENABLED.configuredValue() + "/"
                        + CausticaConfig.Rt.DlssRr.ENABLED.value()
                        + " | Launch overrides " + overrideStatus + " | " + fg.featureVersion()));
    }

    private static Button diagnosticButton() {
        Button button = Button.builder(Component.empty(), ignored -> {
        }).width(Button.BIG_WIDTH).build();
        button.active = false;
        return button;
    }
}
