package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/** Progressive reference renderer controls, status, convergence policy, and output selection. */
public final class OfflineRendererOptionsScreen extends OptionsSubScreen {
    private Button statusButton;
    private Button actionButton;
    private Button presetButton;
    private int presetIndex = detectPreset();
    private static final String[] PRESETS = {"Preview", "Production", "Reference"};

    public OfflineRendererOptionsScreen(Screen lastScreen, Options options) {
        super(lastScreen, options, Component.translatable("caustica.options.offline.title"));
    }

    @Override
    protected void addOptions() {
        statusButton = Button.builder(Component.empty(), ignored -> { })
                .width(Button.BIG_WIDTH).build();
        statusButton.active = false;
        list.addBig(statusButton);

        actionButton = Button.builder(Component.empty(), ignored -> {
            list.applyUnsavedChanges();
            CausticaConfig.save();
            OfflineGroundTruth.INSTANCE.toggle(minecraft);
            refresh();
        }).width(Button.BIG_WIDTH)
                .tooltip(Tooltip.create(Component.translatable("caustica.options.offline.action.tooltip")))
                .build();
        list.addBig(actionButton);

        presetButton = Button.builder(Component.empty(), ignored -> {
            presetIndex = (presetIndex + 1) % PRESETS.length;
            RtVideoOptions.applyOfflinePreset(PRESETS[presetIndex]);
            refresh();
        }).width(Button.BIG_WIDTH).build();
        list.addBig(presetButton);

        var controls = RtVideoOptions.offlineOutputOptions();
        for (var control : controls) {
            list.addBig(control);
        }
        list.addBig(Button.builder(Component.literal("Advanced convergence settings"), ignored -> {
            list.applyUnsavedChanges();
            minecraft.setScreenAndShow(new OfflineRendererAdvancedOptionsScreen(this, options));
        }).width(Button.BIG_WIDTH).build());
        refresh();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        refresh();
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void removed() {
        super.removed();
        CausticaConfig.save();
    }

    private void refresh() {
        if (statusButton == null) {
            return;
        }
        presetIndex = detectPreset();
        boolean engaged = OfflineGroundTruth.INSTANCE.engaged();
        boolean activeWorld = minecraft != null && minecraft.level != null && minecraft.player != null;
        boolean rayTracing = CausticaConfig.Rt.ENABLED.value();
        boolean captureFree = !UltraScreenshot.INSTANCE.active();
        String status = OfflineGroundTruth.INSTANCE.status();
        if (!engaged && !activeWorld) {
            status = "Active world required";
        } else if (!engaged && !rayTracing) {
            status = "Ray tracing required";
        } else if (!engaged && !captureFree) {
            status = "Cancel Ultra Screenshot first";
        }
        statusButton.setMessage(Component.literal("Offline Renderer: " + status));
        actionButton.active = engaged || (activeWorld && rayTracing && captureFree);
        actionButton.setMessage(Component.translatable(OfflineGroundTruth.INSTANCE.engaged()
                ? "caustica.options.offline.action.cancel" : "caustica.options.offline.action.start"));
        presetButton.setMessage(Component.literal("Quality preset: "
                + (presetIndex >= 0 ? PRESETS[presetIndex] : "Custom")));
    }

    private static int detectPreset() {
        if (matchesPreset(true, 8, 64, 1024, 12, 0.02f, 0.002f)) {
            return 0;
        }
        if (matchesPreset(true, 8, 256, 8192, 16, 0.01f, 0.001f)) {
            return 1;
        }
        if (matchesPreset(false, 8, 16384, 16384, 32, 0.005f, 0.0005f)) {
            return 2;
        }
        return -1;
    }

    private static boolean matchesPreset(boolean adaptive, int batch, int min, int max, int bounces,
                                         float relative, float absolute) {
        return CausticaConfig.Rt.Offline.ADAPTIVE.value() == adaptive
                && CausticaConfig.Rt.Offline.SAMPLES_PER_BATCH.value() == batch
                && CausticaConfig.Rt.Offline.MIN_SAMPLES.value() == min
                && CausticaConfig.Rt.Offline.MAX_SAMPLES.value() == max
                && CausticaConfig.Rt.Offline.MAX_BOUNCES.value() == bounces
                && Float.compare(CausticaConfig.Rt.Offline.RELATIVE_ERROR.value(), relative) == 0
                && Float.compare(CausticaConfig.Rt.Offline.ABSOLUTE_ERROR.value(), absolute) == 0;
    }
}
