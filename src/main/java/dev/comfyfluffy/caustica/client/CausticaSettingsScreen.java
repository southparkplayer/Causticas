package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaConfig.BooleanSetting;
import dev.comfyfluffy.caustica.CausticaConfig.FloatSetting;
import dev.comfyfluffy.caustica.CausticaConfig.IntSetting;
import dev.comfyfluffy.caustica.CausticaConfig.StringSetting;
import dev.comfyfluffy.caustica.client.ui.CausticaWidgets;
import dev.comfyfluffy.caustica.client.ui.CausticaWidgets.ActionButton;
import dev.comfyfluffy.caustica.client.ui.CausticaWidgets.Dropdown;
import dev.comfyfluffy.caustica.client.ui.CausticaWidgets.InfoStrip;
import dev.comfyfluffy.caustica.client.ui.CausticaWidgets.SectionHeader;
import dev.comfyfluffy.caustica.client.ui.CausticaWidgets.Slider;
import dev.comfyfluffy.caustica.client.ui.CausticaWidgets.Toggle;
import dev.comfyfluffy.caustica.client.ui.CategoryLayout;
import dev.comfyfluffy.caustica.client.ui.WidgetGridLayout;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtSharcSupport;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleFunction;
import java.util.stream.IntStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Caustica's compact, category-based settings workstation. */
public class CausticaSettingsScreen extends Screen {
    private static final int MARGIN = 8;
    private static final int HEADER_HEIGHT = 28;
    private static final int FOOTER_HEIGHT = 28;
    private static final int RAIL_WIDTH = 126;
    private static final int GRID_GAP = 4;
    private static final int CONTROL_HEIGHT = 22;
    private static final int MAX_CONTENT_WIDTH = 760;
    private static final int TARGET_CELL_WIDTH = 250;
    private static final List<Integer> DLSS_QUALITY_ORDER = List.of(3, 0, 1, 2, 5);
    private static final List<Integer> SHARC_CACHE_EXPONENTS = List.of(16, 17, 18, 19, 20, 21, 22);
    private static final List<String> SDR_TONEMAPPERS = List.of(
            CausticaConfig.Rt.Sdr.TONEMAP_AGX, CausticaConfig.Rt.Sdr.TONEMAP_PBR_NEUTRAL,
            CausticaConfig.Rt.Sdr.TONEMAP_REINHARD, CausticaConfig.Rt.Sdr.TONEMAP_ACES,
            CausticaConfig.Rt.Sdr.TONEMAP_LOTTES, CausticaConfig.Rt.Sdr.TONEMAP_FROSTBITE,
            CausticaConfig.Rt.Sdr.TONEMAP_UNCHARTED2, CausticaConfig.Rt.Sdr.TONEMAP_GT,
            CausticaConfig.Rt.Sdr.TONEMAP_PSYCHOV, CausticaConfig.Rt.Sdr.TONEMAP_PSYCHOV23);
    private static final List<String> HDR_TONEMAPPERS = List.of(
            CausticaConfig.Rt.Hdr.TONEMAP_EETF, CausticaConfig.Rt.Hdr.TONEMAP_PSYCHOV,
            CausticaConfig.Rt.Hdr.TONEMAP_PSYCHOV23, CausticaConfig.Rt.Hdr.TONEMAP_CAUSTICA);

    private final Screen previous;
    protected final Options options;
    private ScrollableLayout bodyScroll;
    private CategoryLayout body;
    private final Map<AbstractWidget, Runnable> toneResets = new IdentityHashMap<>();
    private final List<Dropdown<?>> dropdowns = new ArrayList<>();
    private boolean saved;
    private int contentWidth;
    private int gridColumns;
    private Category category = Category.OUTPUT;

    private enum Category {
        OUTPUT("Output"), RENDERING("Rendering"), SHARC("SHaRC"), IMAGE("Image"), VIEW("View"), DIAGNOSTICS("Diagnostics");
        final String label;
        Category(String label) { this.label = label; }
    }

    public CausticaSettingsScreen(Screen previous, Options options) {
        this(previous, options, null);
    }

    public CausticaSettingsScreen(Screen previous, Options options, String initialCategory) {
        super(Component.translatable("caustica.options.title"));
        this.previous = previous;
        this.options = options;
        if (initialCategory != null) {
            try {
                this.category = Category.valueOf(initialCategory.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                this.category = Category.OUTPUT;
            }
        }
    }

    @Override
    protected void init() {
        saved = false;
        toneResets.clear();
        dropdowns.clear();
        int paneLeft = MARGIN + RAIL_WIDTH + 8;
        int availableWidth = Math.max(180, width - paneLeft - MARGIN - 8);
        contentWidth = Math.min(MAX_CONTENT_WIDTH, availableWidth);
        gridColumns = Math.clamp((contentWidth + GRID_GAP) / (TARGET_CELL_WIDTH + GRID_GAP), 1, 3);

        int railY = MARGIN + HEADER_HEIGHT + 6;
        for (Category value : Category.values()) {
            Category target = value;
            ActionButton button = new ActionButton(RAIL_WIDTH,
                    () -> Component.literal((category == target ? "\u25b8  " : "   ") + target.label),
                    () -> selectCategory(target), category == target);
            button.setRectangle(RAIL_WIDTH, CONTROL_HEIGHT, MARGIN, railY);
            addRenderableWidget(button);
            railY += CONTROL_HEIGHT + 3;
        }

        body = new CategoryLayout(contentWidth, 4);
        switch (category) {
            case OUTPUT -> addOutput();
            case RENDERING -> addRendering();
            case SHARC -> addSharc();
            case IMAGE -> addImage();
            case VIEW -> addView();
            case DIAGNOSTICS -> addDiagnostics();
        }
        body.addChild(SpacerElement.height(8));

        int bodyTop = MARGIN + HEADER_HEIGHT + 6;
        int bodyHeight = Math.max(40, height - bodyTop - FOOTER_HEIGHT - MARGIN - 4);
        bodyScroll = new ScrollableLayout(minecraft, body, bodyHeight);
        bodyScroll.setScrollbarSpacing(6);
        bodyScroll.setMinWidth(contentWidth);
        bodyScroll.setX(paneLeft);
        bodyScroll.setY(bodyTop);
        bodyScroll.visitWidgets(this::addRenderableWidget);

        ActionButton done = new ActionButton(110, () -> Component.translatable("gui.done"), this::onClose, true);
        done.setRectangle(110, CONTROL_HEIGHT, paneLeft + contentWidth - 110,
                height - MARGIN - CONTROL_HEIGHT);
        addRenderableWidget(done);
        repositionElements();
    }

    private void selectCategory(Category target) {
        if (category == target) return;
        category = target;
        clearWidgets();
        init();
    }

    private void addOutput() {
        addHeader("Output & latency", "HDR presentation, DLSS Frame Generation, and Reflex");
        List<AbstractWidget> controls = new ArrayList<>();
        controls.add(toggle(Component.translatable("caustica.options.rt.hdr"), CausticaConfig.Rt.Hdr.ENABLED)
                .tooltip(Component.translatable("caustica.options.rt.hdr.tooltip")));
        controls.add(new Dropdown<>(180, Component.translatable("caustica.options.rt.fg.mode"),
                List.of("off", "fixed"), CausticaConfig.Rt.Fg::configuredMode, CausticaConfig.Rt.Fg::setMode,
                value -> Component.translatable("caustica.options.rt.fg.mode." + value), null));
        int maximum = Math.max(1, CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT.configuredValue());
        maximum = Math.max(maximum, 5);
        List<Integer> multipliers = IntStream.rangeClosed(1, maximum).boxed().toList();
        controls.add(new Dropdown<>(180, Component.translatable("caustica.options.rt.fg.multiplier"), multipliers,
                CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT::configuredValue, CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT::set,
                generated -> Component.literal((generated + 1) + "x"), null)
                .activeWhen(() -> !"off".equals(CausticaConfig.Rt.Fg.configuredMode()))
                .resetOnShift(() -> CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT.set(
                        CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT.defaultValue())));
        controls.add(new Dropdown<>(180, Component.translatable("caustica.options.rt.fg.reflex"),
                List.of("off", "on", "boost"), this::reflexMode, this::setReflexMode,
                value -> Component.translatable("caustica.options.rt.fg.reflex." + value), null));
        controls.add(toggle(Component.translatable("caustica.options.rt.fg.uiRecomposition"),
                CausticaConfig.Rt.Fg.UI_RECOMPOSITION));
        controls.add(toggle(Component.translatable("caustica.options.rt.fg.fullscreenMenu"),
                CausticaConfig.Rt.Fg.FULLSCREEN_MENU_DETECTION));
        addGrid(controls);
        body.addChild(new InfoStrip(contentWidth, () -> CausticaConfig.Rt.Hdr.pendingRestart()
                ? Component.translatable("caustica.options.rt.hdr.restartRequired")
                : Component.literal("Output changes save automatically")));
    }

    private void addRendering() {
        addHeader("Rendering", "Path tracing, reconstruction, geometry, and optical effects");
        List<AbstractWidget> controls = new ArrayList<>();
        controls.add(toggle(Component.translatable("caustica.options.enabled"), CausticaConfig.Rt.ENABLED));
        controls.add(intSlider(Component.translatable("caustica.options.rt.spp"), CausticaConfig.Rt.Composite.SPP,
                1, 8, value -> String.format(Locale.ROOT, "%.0f spp", value)));
        controls.add(intSlider(Component.translatable("caustica.options.rt.maxBounces"),
                CausticaConfig.Rt.Composite.MAX_BOUNCES, 2, 8,
                value -> String.format(Locale.ROOT, "%.0f bounces", value)));
        controls.add(new Slider(180, Component.translatable("caustica.options.rt.sunSize"),
                () -> Math.toDegrees(CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS.configuredValue()),
                value -> CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS.set((float)Math.toRadians(value)),
                unit -> 0.1 + unit * 4.9, value -> (value - 0.1) / 4.9,
                value -> String.format(Locale.ROOT, "%.1f deg", value))
                .tooltip(Component.translatable("caustica.options.rt.sunSize.tooltip"))
                .resetOnShift(() -> CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS.set(
                        CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS.defaultValue())));
        controls.add(toggle(Component.translatable("caustica.options.rt.dlssRr"), CausticaConfig.Rt.DlssRr.ENABLED));
        controls.add(new Dropdown<>(180, Component.translatable("caustica.options.rt.dlssQuality"), DLSS_QUALITY_ORDER,
                CausticaConfig.Rt.DlssRr.QUALITY::configuredValue, CausticaConfig.Rt.DlssRr.QUALITY::set,
                value -> Component.translatable("caustica.options.rt.dlssQuality." + value), null)
                .activeWhen(CausticaConfig.Rt.DlssRr.ENABLED::configuredValue));
        controls.add(toggle(Component.translatable("caustica.options.rt.entities"), CausticaConfig.Rt.Entities.ENABLED));
        controls.add(toggle(Component.translatable("caustica.options.rt.particles"),
                CausticaConfig.Rt.Entities.PARTICLES_ENABLED));
        controls.add(toggle(Component.translatable("caustica.options.rt.waterWaves"),
                CausticaConfig.Rt.Composite.WATER_WAVES));
        controls.add(new Slider(180, Component.translatable("caustica.options.rt.torchIntensity"),
                CausticaConfig.Rt.Composite.TORCH_EMISSION_MULTIPLIER::configuredValue,
                value -> CausticaConfig.Rt.Composite.TORCH_EMISSION_MULTIPLIER.set((float)value),
                unit -> unit, value -> value,
                value -> String.format(Locale.ROOT, "%.1f", value))
                .resetOnShift(() -> CausticaConfig.Rt.Composite.TORCH_EMISSION_MULTIPLIER.set(
                        CausticaConfig.Rt.Composite.TORCH_EMISSION_MULTIPLIER.defaultValue())));
        controls.add(intSlider(Component.translatable("caustica.options.rt.psrMirrorDepth"),
                CausticaConfig.Rt.Composite.PSR_MAX_MIRRORS, 1, 32,
                value -> String.format(Locale.ROOT, "%.0f", value)));
        addGrid(controls);
    }

    private void addSharc() {
        addHeader("NVIDIA SHaRC", "Conservative visual-parity preset with the sparse radiance cache enabled");
        List<AbstractWidget> controls = new ArrayList<>();
        controls.add(toggle(Component.translatable("caustica.options.rt.sharc"), CausticaConfig.Rt.Sharc.ENABLED)
                .tooltip(Component.translatable("caustica.options.rt.sharc.tooltip")));
        controls.add(new Dropdown<>(180, Component.translatable("caustica.options.rt.sharcMemory"),
                SHARC_CACHE_EXPONENTS, CausticaConfig.Rt.Sharc.CACHE_EXPONENT::configuredValue,
                CausticaConfig.Rt.Sharc.CACHE_EXPONENT::set,
                value -> Component.translatable("caustica.options.rt.sharcMemory." + value),
                () -> RtComposite.INSTANCE.requestSharcReset("cache capacity changed"))
                .tooltip(Component.translatable("caustica.options.rt.sharcMemory.tooltip"))
                .resetOnShift(() -> CausticaConfig.Rt.Sharc.CACHE_EXPONENT.set(
                        CausticaConfig.Rt.Sharc.CACHE_EXPONENT.defaultValue())));
        controls.add(sharcFloatSlider("caustica.options.rt.sharcSceneScale",
                CausticaConfig.Rt.Sharc.SCENE_SCALE, 1.0, 100.0, 0.25,
                value -> String.format(Locale.ROOT, "%.2f", value)));
        controls.add(sharcFloatSlider("caustica.options.rt.sharcRadianceScale",
                CausticaConfig.Rt.Sharc.RADIANCE_SCALE, 50.0, 10000.0, 50.0,
                value -> String.format(Locale.ROOT, "%.0f", value)));
        controls.add(intSlider(Component.translatable("caustica.options.rt.sharcAccumulationFrames"),
                CausticaConfig.Rt.Sharc.ACCUMULATION_FRAMES, 1, 1024,
                value -> String.format(Locale.ROOT, "%.0f frames", value))
                .tooltip(Component.translatable("caustica.options.rt.sharcAccumulationFrames.tooltip")));
        controls.add(intSlider(Component.translatable("caustica.options.rt.sharcStaleFrames"),
                CausticaConfig.Rt.Sharc.STALE_FRAMES, 8, 1024,
                value -> String.format(Locale.ROOT, "%.0f frames", value))
                .tooltip(Component.translatable("caustica.options.rt.sharcStaleFrames.tooltip")));
        controls.add(intSlider(Component.translatable("caustica.options.rt.sharcUpdateTileSize"),
                CausticaConfig.Rt.Sharc.UPDATE_TILE_SIZE, 2, 64, value -> {
                    int tile = (int)Math.round(value);
                    return String.format(Locale.ROOT, "%dx%d (%.3f%%)", tile, tile, 100.0 / (tile * tile));
                }).tooltip(Component.translatable("caustica.options.rt.sharcUpdateTileSize.tooltip")));
        controls.add(intSlider(Component.translatable("caustica.options.rt.sharcUpdateMaxBounces"),
                CausticaConfig.Rt.Sharc.UPDATE_MAX_BOUNCES, 1, 8,
                value -> String.format(Locale.ROOT, "%.0f bounces", value))
                .tooltip(Component.translatable("caustica.options.rt.sharcUpdateMaxBounces.tooltip")));
        controls.add(sharcFloatSlider("caustica.options.rt.sharcMinSegmentRatio",
                CausticaConfig.Rt.Sharc.MIN_SEGMENT_RATIO, 0.25, 4.0, 0.25,
                value -> String.format(Locale.ROOT, "%.2fx voxel", value)));
        controls.add(toggle(Component.translatable("caustica.options.rt.sharcGlossyQuery"),
                CausticaConfig.Rt.Sharc.GLOSSY_QUERY)
                .tooltip(Component.translatable("caustica.options.rt.sharcGlossyQuery.tooltip")));
        controls.add(toggle(Component.translatable("caustica.options.rt.sharcLiveSecondaryDirect"),
                CausticaConfig.Rt.Sharc.LIVE_SECONDARY_DIRECT)
                .tooltip(Component.translatable("caustica.options.rt.sharcLiveSecondaryDirect.tooltip")));
        controls.add(toggle(Component.translatable("caustica.options.rt.sharcAntiFirefly"),
                CausticaConfig.Rt.Sharc.ANTI_FIREFLY)
                .tooltip(Component.translatable("caustica.options.rt.sharcAntiFirefly.tooltip")));
        controls.add(toggle(Component.translatable("caustica.options.rt.sharcDetailedStats"),
                CausticaConfig.Rt.Sharc.DETAILED_STATS)
                .tooltip(Component.translatable("caustica.options.rt.sharcDetailedStats.tooltip")));
        addGrid(controls);

        body.addChild(new InfoStrip(contentWidth, this::sharcStatusText));
        addGrid(List.of(
                new ActionButton(180, () -> Component.translatable("caustica.options.rt.sharcSettings.title"),
                        () -> minecraft.setScreenAndShow(new RtSharcOptionsScreen(this, options)), false),
                new ActionButton(180, () -> Component.translatable("caustica.options.rt.sharcReset"),
                        () -> RtComposite.INSTANCE.requestSharcReset("manual menu reset"), false),
                new ActionButton(180, () -> Component.translatable("caustica.options.rt.sharcRestoreParity"),
                        this::restoreSharcParityDefaults, true)
                        .tooltip(Component.translatable("caustica.options.rt.sharcRestoreParity.tooltip"))));
    }

    private void addImage() {
        addHeader("Exposure & tone mapping", "Only the selected output curve's controls are shown");
        List<AbstractWidget> controls = new ArrayList<>();
        controls.add(new Dropdown<>(180, Component.translatable("caustica.options.rt.exposureMode"),
                List.of("auto", "manual"), CausticaConfig.Rt.Exposure.MODE::configuredValue,
                CausticaConfig.Rt.Exposure.MODE::set,
                value -> Component.translatable("caustica.options.rt.exposureMode." + value), null)
                .resetOnShift(() -> CausticaConfig.Rt.Exposure.MODE.set(
                        CausticaConfig.Rt.Exposure.MODE.defaultValue())));
        controls.add(floatSlider(Component.translatable("caustica.options.rt.manualEv"),
                CausticaConfig.Rt.Exposure.MANUAL_EV, -5, 5,
                value -> String.format(Locale.ROOT, "%+.1f EV", value))
                .activeWhen(() -> "manual".equals(CausticaConfig.Rt.Exposure.MODE.configuredValue())));
        controls.add(new Dropdown<>(180, Component.translatable("caustica.options.rt.sdrTonemap"), SDR_TONEMAPPERS,
                CausticaConfig.Rt.Sdr.TONEMAP_MODE::configuredValue, CausticaConfig.Rt.Sdr.TONEMAP_MODE::set,
                value -> Component.translatable("caustica.options.rt.sdrTonemap." + value), this::rebuild)
                .resetOnShift(() -> {
                    CausticaConfig.Rt.Sdr.TONEMAP_MODE.set(CausticaConfig.Rt.Sdr.TONEMAP_MODE.defaultValue());
                    rebuild();
                }));
        controls.add(new Dropdown<>(180, Component.translatable("caustica.options.rt.hdrTonemap"), HDR_TONEMAPPERS,
                CausticaConfig.Rt.Hdr.TONEMAP_MODE::configuredValue, CausticaConfig.Rt.Hdr.TONEMAP_MODE::set,
                value -> Component.translatable("caustica.options.rt.hdrTonemap." + value), this::rebuild)
                .resetOnShift(() -> {
                    CausticaConfig.Rt.Hdr.TONEMAP_MODE.set(CausticaConfig.Rt.Hdr.TONEMAP_MODE.defaultValue());
                    rebuild();
                }));
        controls.add(floatSlider(Component.translatable("caustica.options.rt.hdrPaperWhite"),
                CausticaConfig.Rt.Hdr.PAPER_WHITE_NITS, 80, 1000,
                value -> String.format(Locale.ROOT, "%.0f nits", value))
                .activeWhen(CausticaConfig.Rt.Hdr.ENABLED::configuredValue));
        controls.add(floatSlider(Component.translatable("caustica.options.rt.hdrPeak"),
                CausticaConfig.Rt.Hdr.PEAK_NITS, 80, 10000,
                value -> String.format(Locale.ROOT, "%.0f nits", value))
                .activeWhen(CausticaConfig.Rt.Hdr.ENABLED::configuredValue));
        addGrid(controls);

        body.addChild(new InfoStrip(contentWidth, () -> Component.literal("Active curve  \u2022  " + activeToneLabel())));
        List<AbstractWidget> active = toneWidgets(activeToneControls());
        if (!active.isEmpty()) addGrid(active);
    }

    private void addView() {
        addHeader("View", "Camera stability and first-person ray-traced body placement");
        List<AbstractWidget> controls = new ArrayList<>();
        controls.add(new Toggle(180, Component.literal("Dynamic FOV effects"),
                () -> options.fovEffectScale().get() > 0.0,
                enabled -> options.fovEffectScale().set(enabled ? 1.0 : 0.0))
                .tooltip(Component.literal("Disable to prevent sprinting, speed effects, and aiming from changing FOV"))
                .resetOnShift(() -> options.fovEffectScale().set(1.0)));
        controls.add(toggle(Component.translatable("caustica.options.rt.firstPerson.enabled"),
                CausticaConfig.Rt.FirstPerson.ENABLED));
        controls.add(toggle(Component.translatable("caustica.options.rt.firstPerson.disableVanillaModel"),
                CausticaConfig.Rt.FirstPerson.DISABLE_VANILLA_MODEL));
        controls.add(offsetSlider(Component.translatable("caustica.options.rt.firstPerson.forward"),
                CausticaConfig.Rt.FirstPerson.FORWARD_OFFSET, -0.30, 0.30));
        controls.add(offsetSlider(Component.translatable("caustica.options.rt.firstPerson.vertical"),
                CausticaConfig.Rt.FirstPerson.VERTICAL_OFFSET, -0.30, 0.30));
        controls.add(offsetSlider(Component.translatable("caustica.options.rt.firstPerson.lateral"),
                CausticaConfig.Rt.FirstPerson.LATERAL_OFFSET, -0.20, 0.20));
        addGrid(controls);
    }

    private void addDiagnostics() {
        addHeader("Diagnostics", "Runtime counters and visual inspection modes");
        List<AbstractWidget> controls = new ArrayList<>();
        controls.add(toggle(Component.translatable("caustica.options.frameStats"),
                CausticaConfig.Rt.FrameStats.ENABLED));
        controls.add(new Dropdown<>(180, Component.translatable("caustica.options.rt.debugView"),
                IntStream.rangeClosed(0, CausticaConfig.Rt.Composite.DEBUG_VIEW_TONEMAP_COMPARISON).boxed().toList(),
                CausticaConfig.Rt.Composite.DEBUG_VIEW::configuredValue, CausticaConfig.Rt.Composite.DEBUG_VIEW::set,
                value -> Component.translatable("caustica.options.rt.debugView." + value), null));
        controls.add(new ActionButton(180, () -> Component.literal("Frame Generation diagnostics"),
                () -> minecraft.setScreenAndShow(new RtFrameGenerationDiagnosticsScreen(this, options)), false));
        addGrid(controls);
        body.addChild(new InfoStrip(contentWidth,
                () -> Component.literal("Offline renderer: use the configured capture key while in world")));
    }

    private RtVideoOptions.TonemapControl[] activeToneControls() {
        if (CausticaConfig.Rt.Hdr.ENABLED.configuredValue()) {
            String mode = CausticaConfig.Rt.Hdr.TONEMAP_MODE.configuredValue();
            if (CausticaConfig.Rt.Hdr.TONEMAP_PSYCHOV.equals(mode)) return combine(RtVideoOptions.psychoOptions(), RtVideoOptions.hdrPsychoOptions());
            if (CausticaConfig.Rt.Hdr.TONEMAP_PSYCHOV23.equals(mode)) return combine(RtVideoOptions.psychoOptions(), RtVideoOptions.sdrPsychoV23Options());
            return new RtVideoOptions.TonemapControl[0];
        }
        return switch (CausticaConfig.Rt.Sdr.TONEMAP_MODE.configuredValue()) {
            case CausticaConfig.Rt.Sdr.TONEMAP_AGX -> RtVideoOptions.sdrAgxOptions();
            case CausticaConfig.Rt.Sdr.TONEMAP_PBR_NEUTRAL -> RtVideoOptions.sdrPbrNeutralOptions();
            case CausticaConfig.Rt.Sdr.TONEMAP_REINHARD -> RtVideoOptions.sdrReinhardOptions();
            case CausticaConfig.Rt.Sdr.TONEMAP_ACES -> RtVideoOptions.sdrAcesOptions();
            case CausticaConfig.Rt.Sdr.TONEMAP_LOTTES -> RtVideoOptions.sdrLottesOptions();
            case CausticaConfig.Rt.Sdr.TONEMAP_FROSTBITE -> RtVideoOptions.sdrFrostbiteOptions();
            case CausticaConfig.Rt.Sdr.TONEMAP_UNCHARTED2 -> RtVideoOptions.sdrUncharted2Options();
            case CausticaConfig.Rt.Sdr.TONEMAP_GT -> RtVideoOptions.sdrGtOptions();
            case CausticaConfig.Rt.Sdr.TONEMAP_PSYCHOV -> combine(RtVideoOptions.psychoOptions(), RtVideoOptions.sdrPsychoOptions());
            case CausticaConfig.Rt.Sdr.TONEMAP_PSYCHOV23 -> combine(RtVideoOptions.psychoOptions(), RtVideoOptions.sdrPsychoV23Options());
            default -> new RtVideoOptions.TonemapControl[0];
        };
    }

    private List<AbstractWidget> toneWidgets(RtVideoOptions.TonemapControl[] controls) {
        List<AbstractWidget> widgets = new ArrayList<>();
        for (RtVideoOptions.TonemapControl control : controls) {
            AbstractWidget widget = control.option().createButton(options, 0, 0, 180);
            toneResets.put(widget, control::resetToDefault);
            widgets.add(widget);
        }
        return widgets;
    }

    private static RtVideoOptions.TonemapControl[] combine(RtVideoOptions.TonemapControl[] a,
                                                            RtVideoOptions.TonemapControl[] b) {
        RtVideoOptions.TonemapControl[] out = new RtVideoOptions.TonemapControl[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private void addHeader(String title, String subtitle) {
        body.addChild(new SectionHeader(contentWidth, Component.literal(title), Component.literal(subtitle)));
    }

    private void addGrid(List<? extends AbstractWidget> controls) {
        for (AbstractWidget control : controls) {
            if (control instanceof Dropdown<?> dropdown) dropdowns.add(dropdown);
        }
        body.addChild(new WidgetGridLayout(contentWidth, gridColumns, GRID_GAP, GRID_GAP, CONTROL_HEIGHT, controls));
    }

    private Toggle toggle(Component label, BooleanSetting setting) {
        return new Toggle(180, label, setting::configuredValue, setting::set)
                .resetOnShift(() -> setting.set(setting.defaultValue()));
    }

    private Slider intSlider(Component label, IntSetting setting, int min, int max, DoubleFunction<String> format) {
        return new Slider(180, label, setting::configuredValue, value -> setting.set((int)Math.round(value)),
                unit -> min + unit * (max - min), value -> (value - min) / (max - min), format)
                .resetOnShift(() -> setting.set(setting.defaultValue()));
    }

    private Slider floatSlider(Component label, FloatSetting setting, double min, double max,
                               DoubleFunction<String> format) {
        return new Slider(180, label, setting::configuredValue, value -> setting.set((float)value),
                unit -> min + unit * (max - min), value -> (value - min) / (max - min), format)
                .resetOnShift(() -> setting.set(setting.defaultValue()));
    }

    private Slider sharcFloatSlider(String key, FloatSetting setting, double min, double max, double step,
                                    DoubleFunction<String> format) {
        return new Slider(180, Component.translatable(key), setting::configuredValue,
                value -> setting.set((float)(Math.round(value / step) * step)),
                unit -> min + unit * (max - min), value -> (value - min) / (max - min), format)
                .tooltip(Component.translatable(key + ".tooltip"))
                .resetOnShift(() -> setting.set(setting.defaultValue()));
    }

    private Component sharcStatusText() {
        String state = !RtSharcSupport.available() ? RtSharcSupport.status()
                : RtComposite.INSTANCE.sharcActive() ? "Active"
                : CausticaConfig.Rt.Sharc.ENABLED.configuredValue() ? "Ready - parity preset" : "Off";
        return Component.literal("NVIDIA SHaRC 1.6.5.0  |  " + state);
    }

    private void restoreSharcParityDefaults() {
        CausticaConfig.Rt.Sharc.ENABLED.set(true);
        CausticaConfig.Rt.Sharc.CACHE_EXPONENT.set(CausticaConfig.Rt.Sharc.CACHE_EXPONENT.defaultValue());
        CausticaConfig.Rt.Sharc.SCENE_SCALE.set(CausticaConfig.Rt.Sharc.SCENE_SCALE.defaultValue());
        CausticaConfig.Rt.Sharc.RADIANCE_SCALE.set(CausticaConfig.Rt.Sharc.RADIANCE_SCALE.defaultValue());
        CausticaConfig.Rt.Sharc.ACCUMULATION_FRAMES.set(CausticaConfig.Rt.Sharc.ACCUMULATION_FRAMES.defaultValue());
        CausticaConfig.Rt.Sharc.STALE_FRAMES.set(CausticaConfig.Rt.Sharc.STALE_FRAMES.defaultValue());
        CausticaConfig.Rt.Sharc.UPDATE_TILE_SIZE.set(CausticaConfig.Rt.Sharc.UPDATE_TILE_SIZE.defaultValue());
        CausticaConfig.Rt.Sharc.UPDATE_MAX_BOUNCES.set(CausticaConfig.Rt.Sharc.UPDATE_MAX_BOUNCES.defaultValue());
        CausticaConfig.Rt.Sharc.MIN_SEGMENT_RATIO.set(CausticaConfig.Rt.Sharc.MIN_SEGMENT_RATIO.defaultValue());
        CausticaConfig.Rt.Sharc.GLOSSY_QUERY.set(CausticaConfig.Rt.Sharc.GLOSSY_QUERY.defaultValue());
        CausticaConfig.Rt.Sharc.LIVE_SECONDARY_DIRECT.set(CausticaConfig.Rt.Sharc.LIVE_SECONDARY_DIRECT.defaultValue());
        CausticaConfig.Rt.Sharc.ANTI_FIREFLY.set(CausticaConfig.Rt.Sharc.ANTI_FIREFLY.defaultValue());
        CausticaConfig.Rt.Sharc.DETAILED_STATS.set(false);
        CausticaConfig.Rt.Composite.DEBUG_VIEW.set(0);
        CausticaConfig.Rt.FrameStats.ENABLED.set(false);
        RtComposite.INSTANCE.requestSharcReset("visual-parity defaults restored");
        rebuild();
    }

    private Slider offsetSlider(Component label, FloatSetting setting, double min, double max) {
        return floatSlider(label, setting, min, max,
                value -> String.format(Locale.ROOT, "%+.2f m", value));
    }

    private String reflexMode() {
        return !CausticaConfig.Rt.Reflex.ENABLED.configuredValue() ? "off"
                : CausticaConfig.Rt.Reflex.LOW_LATENCY_BOOST.configuredValue() ? "boost" : "on";
    }

    private void setReflexMode(String value) {
        CausticaConfig.Rt.Reflex.ENABLED.set(!"off".equals(value));
        CausticaConfig.Rt.Reflex.LOW_LATENCY_BOOST.set("boost".equals(value));
    }

    private String activeToneLabel() {
        return CausticaConfig.Rt.Hdr.ENABLED.configuredValue()
                ? "HDR / " + CausticaConfig.Rt.Hdr.TONEMAP_MODE.configuredValue()
                : "SDR / " + CausticaConfig.Rt.Sdr.TONEMAP_MODE.configuredValue();
    }

    private void rebuild() {
        clearWidgets();
        init();
    }

    protected void repositionElements() {
        if (bodyScroll != null) bodyScroll.arrangeElements();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int paneLeft = MARGIN + RAIL_WIDTH + 8;
        int paneRight = paneLeft + contentWidth;
        int railBottom = MARGIN + HEADER_HEIGHT + 6
                + Category.values().length * (CONTROL_HEIGHT + 3);
        g.fill(MARGIN + RAIL_WIDTH + 4, MARGIN, MARGIN + RAIL_WIDTH + 5,
                railBottom, 0x70FFFFFF);
        g.fill(MARGIN, MARGIN + HEADER_HEIGHT, paneRight,
                MARGIN + HEADER_HEIGHT + 1, 0x50FFFFFF);
        g.text(font, Component.literal("Caustica"), MARGIN, MARGIN + 5, CausticaWidgets.TEXT);
        g.text(font, Component.literal(category.label), MARGIN + RAIL_WIDTH + 14, MARGIN + 5, CausticaWidgets.ACCENT);
        g.fill(paneLeft, height - FOOTER_HEIGHT - MARGIN, paneRight,
                height - FOOTER_HEIGHT - MARGIN + 1, 0x50FFFFFF);
        g.text(font, Component.literal("Shift+click a control to restore its default"),
                paneLeft + 2, height - FOOTER_HEIGHT + 3, CausticaWidgets.MUTED);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        for (Dropdown<?> dropdown : dropdowns) {
            dropdown.extractOverlay(g, mouseX, mouseY, height);
        }
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
        if (input.hasShiftDown()) {
            for (Map.Entry<AbstractWidget, Runnable> entry : toneResets.entrySet()) {
                AbstractWidget widget = entry.getKey();
                if (widget.visible && widget.active && widget.isMouseOver(input.x(), input.y())) {
                    entry.getValue().run();
                    rebuild();
                    return true;
                }
            }
        }
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
