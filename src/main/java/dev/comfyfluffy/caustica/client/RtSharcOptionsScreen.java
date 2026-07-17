package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaConfig.FloatSetting;
import dev.comfyfluffy.caustica.CausticaConfig.IntSetting;
import dev.comfyfluffy.caustica.client.ui.CausticaWidgets;
import dev.comfyfluffy.caustica.client.ui.CausticaWidgets.ActionButton;
import dev.comfyfluffy.caustica.client.ui.CausticaWidgets.Dropdown;
import dev.comfyfluffy.caustica.client.ui.CausticaWidgets.Slider;
import dev.comfyfluffy.caustica.client.ui.CausticaWidgets.Toggle;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtSharcSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Complete SHaRC settings surface using Caustica's clear, low-chrome workstation controls. */
public final class RtSharcOptionsScreen extends Screen {
    private static final int MARGIN = 16;
    private static final int TOP = 54;
    private static final int GAP = 4;
    private static final int CONTROL_HEIGHT = 22;
    private static final int MAX_CONTENT_WIDTH = 920;
    private static final List<Integer> CACHE_EXPONENTS = List.of(16, 17, 18, 19, 20, 21, 22);
    private static final List<Integer> DEBUG_VIEWS = List.of(
            0, 1, 2, 3, 4, 5, 6, 7,
            CausticaConfig.Rt.Composite.DEBUG_VIEW_TONEMAP_COMPARISON, 9, 10, 11, 12, 13, 14, 15, 16);

    private final Screen previous;
    private final Options options;
    private final List<AbstractWidget> controls = new ArrayList<>();
    private final List<Dropdown<?>> dropdowns = new ArrayList<>();
    private boolean saved;

    public RtSharcOptionsScreen(Screen previous, Options options) {
        super(Component.translatable("caustica.options.rt.sharcSettings.title"));
        this.previous = previous;
        this.options = options;
    }

    @Override
    protected void init() {
        saved = false;
        controls.clear();
        dropdowns.clear();

        addControl(toggle("caustica.options.rt.sharc", CausticaConfig.Rt.Sharc.ENABLED));
        Dropdown<Integer> memory = new Dropdown<>(180,
                Component.translatable("caustica.options.rt.sharcMemory"), CACHE_EXPONENTS,
                CausticaConfig.Rt.Sharc.CACHE_EXPONENT::configuredValue,
                CausticaConfig.Rt.Sharc.CACHE_EXPONENT::set,
                value -> Component.translatable("caustica.options.rt.sharcMemory." + value),
                () -> RtComposite.INSTANCE.requestSharcReset("cache capacity changed"))
                .tooltip(Component.translatable("caustica.options.rt.sharcMemory.tooltip"))
                .resetOnShift(() -> CausticaConfig.Rt.Sharc.CACHE_EXPONENT.set(
                        CausticaConfig.Rt.Sharc.CACHE_EXPONENT.defaultValue()));
        dropdowns.add(memory);
        addControl(memory);

        addControl(floatSlider("caustica.options.rt.sharcSceneScale", CausticaConfig.Rt.Sharc.SCENE_SCALE,
                1.0, 100.0, 0.25, value -> String.format(Locale.ROOT, "%.2f", value)));
        addControl(floatSlider("caustica.options.rt.sharcRadianceScale", CausticaConfig.Rt.Sharc.RADIANCE_SCALE,
                50.0, 10000.0, 50.0, value -> String.format(Locale.ROOT, "%.0f", value)));
        addControl(intSlider("caustica.options.rt.sharcAccumulationFrames",
                CausticaConfig.Rt.Sharc.ACCUMULATION_FRAMES, 1, 1024,
                value -> String.format(Locale.ROOT, "%.0f frames", value)));
        addControl(intSlider("caustica.options.rt.sharcStaleFrames", CausticaConfig.Rt.Sharc.STALE_FRAMES,
                8, 1024, value -> String.format(Locale.ROOT, "%.0f frames", value)));
        addControl(intSlider("caustica.options.rt.sharcUpdateTileSize", CausticaConfig.Rt.Sharc.UPDATE_TILE_SIZE,
                2, 64, value -> {
                    int tile = (int)Math.round(value);
                    return String.format(Locale.ROOT, "%dx%d (%.3f%%)", tile, tile, 100.0 / (tile * tile));
                }));
        addControl(intSlider("caustica.options.rt.sharcUpdateMaxBounces",
                CausticaConfig.Rt.Sharc.UPDATE_MAX_BOUNCES, 1, 8,
                value -> String.format(Locale.ROOT, "%.0f bounces", value)));
        addControl(floatSlider("caustica.options.rt.sharcMinSegmentRatio",
                CausticaConfig.Rt.Sharc.MIN_SEGMENT_RATIO, 0.25, 4.0, 0.25,
                value -> String.format(Locale.ROOT, "%.2fx voxel", value)));
        addControl(toggle("caustica.options.rt.sharcGlossyQuery", CausticaConfig.Rt.Sharc.GLOSSY_QUERY));
        addControl(toggle("caustica.options.rt.sharcLiveSecondaryDirect",
                CausticaConfig.Rt.Sharc.LIVE_SECONDARY_DIRECT));
        addControl(toggle("caustica.options.rt.sharcAntiFirefly", CausticaConfig.Rt.Sharc.ANTI_FIREFLY));
        addControl(toggle("caustica.options.rt.sharcDetailedStats", CausticaConfig.Rt.Sharc.DETAILED_STATS));

        Dropdown<Integer> debug = new Dropdown<>(180, Component.translatable("caustica.options.rt.debugView"),
                DEBUG_VIEWS, CausticaConfig.Rt.Composite.DEBUG_VIEW::configuredValue,
                CausticaConfig.Rt.Composite.DEBUG_VIEW::set,
                value -> Component.translatable("caustica.options.rt.debugView." + value), null)
                .tooltip(Component.translatable("caustica.options.rt.debugView.tooltip"))
                .resetOnShift(() -> CausticaConfig.Rt.Composite.DEBUG_VIEW.set(0));
        dropdowns.add(debug);
        addControl(debug);
        addControl(toggle("caustica.options.frameStats", CausticaConfig.Rt.FrameStats.ENABLED));

        int contentWidth = Math.min(MAX_CONTENT_WIDTH, Math.max(220, width - MARGIN * 2));
        int left = (width - contentWidth) / 2;
        int columns = width >= 620 ? 2 : 1;
        int cellWidth = (contentWidth - GAP * (columns - 1)) / columns;
        for (int i = 0; i < controls.size(); i++) {
            int column = i % columns;
            int row = i / columns;
            AbstractWidget widget = controls.get(i);
            widget.setRectangle(cellWidth, CONTROL_HEIGHT,
                    left + column * (cellWidth + GAP), TOP + row * (CONTROL_HEIGHT + GAP));
            addRenderableWidget(widget);
        }

        int rows = (controls.size() + columns - 1) / columns;
        int actionY = TOP + rows * (CONTROL_HEIGHT + GAP) + 5;
        int actionWidth = (contentWidth - GAP * 2) / 3;
        ActionButton restore = new ActionButton(actionWidth,
                () -> Component.translatable("caustica.options.rt.sharcRestoreParity"),
                this::restoreParityDefaults, true)
                .tooltip(Component.translatable("caustica.options.rt.sharcRestoreParity.tooltip"));
        List<ActionButton> actions = List.of(restore, new ActionButton(actionWidth,
                () -> Component.translatable("caustica.options.rt.sharcReset"),
                () -> RtComposite.INSTANCE.requestSharcReset("manual menu reset"), false),
                new ActionButton(actionWidth, () -> Component.translatable("gui.done"), this::onClose, true));
        for (int i = 0; i < actions.size(); i++) {
            ActionButton widget = actions.get(i);
            widget.setRectangle(actionWidth, CONTROL_HEIGHT,
                    left + i * (actionWidth + GAP), actionY);
            addRenderableWidget(widget);
        }
    }

    private void addControl(AbstractWidget widget) {
        controls.add(widget);
    }

    private Toggle toggle(String key, CausticaConfig.BooleanSetting setting) {
        return new Toggle(180, Component.translatable(key), setting::configuredValue, setting::set)
                .tooltip(Component.translatable(key + ".tooltip"))
                .resetOnShift(() -> setting.set(setting.defaultValue()));
    }

    private Slider intSlider(String key, IntSetting setting, int minimum, int maximum,
                             java.util.function.DoubleFunction<String> formatter) {
        return new Slider(180, Component.translatable(key), setting::configuredValue,
                value -> setting.set((int)Math.round(value)),
                unit -> minimum + unit * (maximum - minimum),
                value -> (value - minimum) / (maximum - minimum), formatter)
                .tooltip(Component.translatable(key + ".tooltip"))
                .resetOnShift(() -> setting.set(setting.defaultValue()));
    }

    private Slider floatSlider(String key, FloatSetting setting, double minimum, double maximum, double step,
                               java.util.function.DoubleFunction<String> formatter) {
        return new Slider(180, Component.translatable(key), setting::configuredValue,
                value -> setting.set((float)(Math.round(value / step) * step)),
                unit -> minimum + unit * (maximum - minimum),
                value -> (value - minimum) / (maximum - minimum), formatter)
                .tooltip(Component.translatable(key + ".tooltip"))
                .resetOnShift(() -> setting.set(setting.defaultValue()));
    }

    private void restoreParityDefaults() {
        CausticaConfig.Rt.Sharc.ENABLED.set(true);
        CausticaConfig.Rt.Sharc.CACHE_EXPONENT.set(CausticaConfig.Rt.Sharc.CACHE_EXPONENT.defaultValue());
        CausticaConfig.Rt.Sharc.SCENE_SCALE.set(CausticaConfig.Rt.Sharc.SCENE_SCALE.defaultValue());
        CausticaConfig.Rt.Sharc.RADIANCE_SCALE.set(CausticaConfig.Rt.Sharc.RADIANCE_SCALE.defaultValue());
        CausticaConfig.Rt.Sharc.ACCUMULATION_FRAMES.set(CausticaConfig.Rt.Sharc.ACCUMULATION_FRAMES.defaultValue());
        CausticaConfig.Rt.Sharc.STALE_FRAMES.set(CausticaConfig.Rt.Sharc.STALE_FRAMES.defaultValue());
        CausticaConfig.Rt.Sharc.ANTI_FIREFLY.set(CausticaConfig.Rt.Sharc.ANTI_FIREFLY.defaultValue());
        CausticaConfig.Rt.Sharc.UPDATE_TILE_SIZE.set(CausticaConfig.Rt.Sharc.UPDATE_TILE_SIZE.defaultValue());
        CausticaConfig.Rt.Sharc.UPDATE_MAX_BOUNCES.set(CausticaConfig.Rt.Sharc.UPDATE_MAX_BOUNCES.defaultValue());
        CausticaConfig.Rt.Sharc.MIN_SEGMENT_RATIO.set(CausticaConfig.Rt.Sharc.MIN_SEGMENT_RATIO.defaultValue());
        CausticaConfig.Rt.Sharc.GLOSSY_QUERY.set(CausticaConfig.Rt.Sharc.GLOSSY_QUERY.defaultValue());
        CausticaConfig.Rt.Sharc.LIVE_SECONDARY_DIRECT.set(
                CausticaConfig.Rt.Sharc.LIVE_SECONDARY_DIRECT.defaultValue());
        CausticaConfig.Rt.Sharc.DETAILED_STATS.set(false);
        CausticaConfig.Rt.Composite.DEBUG_VIEW.set(0);
        CausticaConfig.Rt.FrameStats.ENABLED.set(false);
        RtComposite.INSTANCE.requestSharcReset("visual-parity defaults restored");
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        init();
    }

    private Component statusText() {
        String status = !RtSharcSupport.available() ? RtSharcSupport.status()
                : RtComposite.INSTANCE.sharcActive() ? "Active"
                : CausticaConfig.Rt.Sharc.ENABLED.value() ? "Ready - parity preset" : "Off";
        return Component.literal("NVIDIA SHaRC 1.6.5.0  •  " + status);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.centeredText(font, statusText(), width / 2, 15, CausticaWidgets.TEXT);
        graphics.centeredText(font, Component.translatable("caustica.options.rt.sharcParityNotice"),
                width / 2, 31, CausticaWidgets.MUTED);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        for (Dropdown<?> dropdown : dropdowns) dropdown.extractOverlay(graphics, mouseX, mouseY, height);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent input, boolean doubleClick) {
        Dropdown<?> open = null;
        for (Dropdown<?> dropdown : dropdowns) {
            if (!dropdown.isOpen()) continue;
            if (dropdown.clickOverlay(input.x(), input.y(), height)) return true;
            if (open == null) open = dropdown;
            else dropdown.close();
        }
        if (open != null && !open.isMouseOver(input.x(), input.y())) open.close();
        return super.mouseClicked(input, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        for (Dropdown<?> dropdown : dropdowns) {
            if (dropdown.scrollOverlay(mouseX, mouseY, vertical, height)) return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
            boolean closed = false;
            for (Dropdown<?> dropdown : dropdowns) {
                if (dropdown.isOpen()) {
                    dropdown.close();
                    closed = true;
                }
            }
            if (closed) return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public void onClose() {
        saveAll();
        Minecraft.getInstance().setScreenAndShow(previous);
    }

    @Override
    public void removed() {
        saveAll();
        super.removed();
    }

    private void saveAll() {
        if (saved) return;
        saved = true;
        CausticaConfig.save();
        options.save();
    }
}
