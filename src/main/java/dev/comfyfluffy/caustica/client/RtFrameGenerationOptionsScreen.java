package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.client.settings.SettingsRuntimeStatus;
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
    private Button vsyncWidget;

    public RtFrameGenerationOptionsScreen(Screen lastScreen, Options options) {
        super(lastScreen, options, Component.translatable("caustica.options.rt.frameGeneration.title"));
        this.initiallyRequested = CausticaConfig.Rt.Fg.requested();
    }

    @Override
    protected void addOptions() {
        statusWidget = diagnosticButton();
        list.addBig(statusWidget);

        vsyncWidget = Button.builder(Component.empty(), button -> {
            options.enableVsync().set(!options.enableVsync().get());
            surfaceSettingChanged = true;
            refreshDiagnostics();
        }).width(Button.BIG_WIDTH)
                .tooltip(Tooltip.create(Component.translatable("caustica.options.rt.fg.vsync.tooltip")))
                .build();
        list.addBig(vsyncWidget);

        OptionInstance<?>[] controls = RtVideoOptions.frameGenerationOptions();
        for (var control : controls) {
            list.addBig(control);
        }
        list.addBig(Button.builder(Component.translatable("caustica.options.rt.fg.advancedDiagnostics"), button ->
                minecraft.setScreenAndShow(new RtFrameGenerationDiagnosticsScreen(this, options)))
                .width(Button.BIG_WIDTH).build());
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
        if (vsyncWidget != null) {
            vsyncWidget.setMessage(SettingsRuntimeStatus.vsync(options));
        }
        statusWidget.setMessage(SettingsRuntimeStatus.frameGeneration());
    }

    private static Button diagnosticButton() {
        Button button = Button.builder(Component.empty(), ignored -> {
        }).width(Button.BIG_WIDTH).build();
        button.active = false;
        return button;
    }
}
