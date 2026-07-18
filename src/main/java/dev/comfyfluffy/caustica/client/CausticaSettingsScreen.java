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
import dev.comfyfluffy.caustica.client.ui.CausticaWidgets.LabeledControl;
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
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Caustica's compact, category-based settings workstation. */
public class CausticaSettingsScreen extends Screen {
    private static final int MARGIN = 6;
    private static final int HEADER_HEIGHT = 28;
    private static final int FOOTER_HEIGHT = 28;
    private static final int RAIL_WIDTH = 104;
    private static final int GRID_GAP = 4;
    private static final int CONTROL_HEIGHT = 22;
    private static final int MAX_CONTENT_WIDTH = 1600;
    private static final int TARGET_CELL_WIDTH = 220;
    private static final int MAX_GRID_COLUMNS = 6;
    private static final List<Integer> DLSS_QUALITY_ORDER = List.of(3, 0, 1, 2, 5);
    private static final List<Integer> SHARC_CACHE_EXPONENTS = List.of(
            16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28);
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
    private final Map<AbstractWidget, String> usageLabels = new IdentityHashMap<>();
    private final List<Dropdown<?>> dropdowns = new ArrayList<>();
    private final List<QuickHeading> quickHeadings = new ArrayList<>();
    private final List<AbstractWidget> collectedSearchResults = new ArrayList<>();
    private EditBox searchBox;
    private String searchQuery = "";
    private String searchContext = "";
    private boolean collectingSearchResults;
    private boolean saved;
    private int contentWidth;
    private int gridColumns;
    private Category category = Category.OVERVIEW;

    private enum Category {
        OVERVIEW("Overview"), OUTPUT("Display & Latency"), RECONSTRUCTION("Reconstruction"),
        LIGHTING("Lighting & Sky"), EXPOSURE("Exposure & Tone Mapping"), GEOMETRY("Geometry & Effects"),
        SHARC("SHaRC"), VIEW("View"), DIAGNOSTICS("Diagnostics");
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
                this.category = Category.OVERVIEW;
            }
        }
    }

    @Override
    protected void init() {
        saved = false;
        toneResets.clear();
        usageLabels.clear();
        dropdowns.clear();
        quickHeadings.clear();
        collectedSearchResults.clear();
        int paneLeft = MARGIN + RAIL_WIDTH + 8;
        int availableWidth = Math.max(180, width - paneLeft - MARGIN - 8);
        contentWidth = Math.min(MAX_CONTENT_WIDTH, availableWidth);
        gridColumns = Math.clamp((contentWidth + GRID_GAP) / (TARGET_CELL_WIDTH + GRID_GAP),
                1, MAX_GRID_COLUMNS);

        searchBox = new EditBox(font, MARGIN, MARGIN + 3, RAIL_WIDTH, 20,
                Component.translatable("caustica.options.search"));
        searchBox.setMaxLength(80);
        searchBox.setHint(Component.translatable("caustica.options.search.hint"));
        searchBox.setCanLoseFocus(true);
        searchBox.setValue(searchQuery);
        searchBox.setResponder(this::searchChanged);
        addRenderableWidget(searchBox);

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
        addQuickLinks(railY + 4);

        body = new CategoryLayout(contentWidth, 4);
        if (!searchQuery.isBlank()) {
            addSearchResults();
        } else {
            switch (category) {
                case OVERVIEW -> addOverview();
                case OUTPUT -> addOutput();
                case RECONSTRUCTION -> addReconstruction();
                case LIGHTING -> addLighting();
                case EXPOSURE -> addExposure();
                case GEOMETRY -> addGeometry();
                case SHARC -> addSharc();
                case VIEW -> addView();
                case DIAGNOSTICS -> addDiagnostics();
            }
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
        setInitialFocus(searchBox);
    }

    private void selectCategory(Category target) {
        if (category == target && searchQuery.isBlank()) return;
        category = target;
        searchQuery = "";
        rebuild();
    }

    private void addOverview() {
        addHeader("Rendering & output", "The controls most players change during normal play");
        List<AbstractWidget> rendering = new ArrayList<>();
        rendering.add(toggle(Component.translatable("caustica.options.enabled"), CausticaConfig.Rt.ENABLED));
        rendering.add(intSlider(Component.translatable("caustica.options.rt.spp"), CausticaConfig.Rt.Composite.SPP,
                1, 8, value -> String.format(Locale.ROOT, "%.0f spp", value)));
        rendering.add(intSlider(Component.translatable("caustica.options.rt.maxBounces"),
                CausticaConfig.Rt.Composite.MAX_BOUNCES, 2, 8,
                value -> String.format(Locale.ROOT, "%.0f bounces", value)));
        rendering.add(toggle(Component.translatable("caustica.options.rt.dlssRr"),
                CausticaConfig.Rt.DlssRr.ENABLED));
        rendering.add(new Dropdown<>(180, Component.translatable("caustica.options.rt.dlssQuality"),
                DLSS_QUALITY_ORDER, CausticaConfig.Rt.DlssRr.QUALITY::configuredValue,
                CausticaConfig.Rt.DlssRr.QUALITY::set,
                value -> Component.translatable("caustica.options.rt.dlssQuality." + value), null)
                .activeWhen(CausticaConfig.Rt.DlssRr.ENABLED::configuredValue));
        rendering.add(toggle(Component.translatable("caustica.options.rt.hdr"), CausticaConfig.Rt.Hdr.ENABLED));
        rendering.add(new Dropdown<>(180, Component.translatable("caustica.options.rt.fg.mode"),
                List.of("off", "fixed"), CausticaConfig.Rt.Fg::configuredMode, CausticaConfig.Rt.Fg::setMode,
                value -> Component.translatable("caustica.options.rt.fg.mode." + value), null));
        rendering.add(new Dropdown<>(180, Component.translatable("caustica.options.rt.fg.reflex"),
                List.of("off", "on", "boost"), this::reflexMode, this::setReflexMode,
                value -> Component.translatable("caustica.options.rt.fg.reflex." + value), null));
        addGrid(rendering);

        addHeader("Scene & image", "Lighting, presentation, and view controls kept one click away");
        List<AbstractWidget> scene = new ArrayList<>();
        scene.add(new Slider(180, Component.translatable("caustica.options.rt.torchIntensity"),
                CausticaConfig.Rt.Composite.TORCH_EMISSION_MULTIPLIER::configuredValue,
                value -> CausticaConfig.Rt.Composite.TORCH_EMISSION_MULTIPLIER.set((float)value),
                unit -> unit, value -> value, value -> String.format(Locale.ROOT, "%.1f", value))
                .resetOnShift(() -> CausticaConfig.Rt.Composite.TORCH_EMISSION_MULTIPLIER.set(
                        CausticaConfig.Rt.Composite.TORCH_EMISSION_MULTIPLIER.defaultValue())));
        scene.add(toggle(Component.translatable("caustica.options.rt.waterWaves"),
                CausticaConfig.Rt.Composite.WATER_WAVES));
        scene.add(toggle(Component.translatable("caustica.options.rt.entities"),
                CausticaConfig.Rt.Entities.ENABLED));
        scene.add(toggle(Component.translatable("caustica.options.rt.particles"),
                CausticaConfig.Rt.Entities.PARTICLES_ENABLED));
        scene.add(new Dropdown<>(180, Component.translatable("caustica.options.rt.exposureMode"),
                List.of("auto", "manual"), CausticaConfig.Rt.Exposure.MODE::configuredValue,
                CausticaConfig.Rt.Exposure.MODE::set,
                value -> Component.translatable("caustica.options.rt.exposureMode." + value), null));
        scene.add(floatSlider(Component.translatable("caustica.options.rt.manualEv"),
                CausticaConfig.Rt.Exposure.MANUAL_EXPOSURE_EV, -12, 12,
                value -> String.format(Locale.ROOT, "%+.1f EV", value))
                .activeWhen(() -> "manual".equals(CausticaConfig.Rt.Exposure.MODE.configuredValue())));
        scene.add(floatSlider(Component.translatable("caustica.options.rt.exposureCompensation"),
                CausticaConfig.Rt.Exposure.COMPENSATION_EV, -4, 4,
                value -> String.format(Locale.ROOT, "%+.1f EV", value))
                .activeWhen(() -> "auto".equals(CausticaConfig.Rt.Exposure.MODE.configuredValue())));
        scene.add(new Dropdown<>(180, Component.translatable("caustica.options.rt.sdrTonemap"), SDR_TONEMAPPERS,
                CausticaConfig.Rt.Sdr.TONEMAP_MODE::configuredValue, CausticaConfig.Rt.Sdr.TONEMAP_MODE::set,
                value -> Component.translatable("caustica.options.rt.sdrTonemap." + value), this::rebuild));
        scene.add(toggle(Component.translatable("caustica.options.rt.firstPerson.enabled"),
                CausticaConfig.Rt.FirstPerson.ENABLED));
        scene.add(toggle(Component.translatable("caustica.options.rt.sharc"), CausticaConfig.Rt.Sharc.ENABLED));
        addGrid(scene);

        addInfo(() -> CausticaConfig.Rt.Hdr.pendingRestart()
                ? Component.translatable("caustica.options.rt.hdr.restartRequired")
                : Component.literal("Changes save automatically  |  Shift+click restores a default"));
    }

    private void searchChanged(String value) {
        if (value.equals(searchQuery)) return;
        searchQuery = value;
        rebuild();
    }

    private void addSearchResults() {
        collectingSearchResults = true;
        collectedSearchResults.clear();
        addOutput();
        addReconstruction();
        addLighting();
        addExposure();
        addGeometry();
        addSharc();
        addView();
        addDiagnostics();
        collectingSearchResults = false;
        searchContext = "";

        addHeader("Search results", collectedSearchResults.size() + " matching controls");
        if (collectedSearchResults.isEmpty()) {
            addInfo(() -> Component.literal("No Caustica setting matches \"" + searchQuery + "\""));
        } else {
            addGridDirect(collectedSearchResults);
        }
    }

    private void addQuickLinks(int top) {
        int available = height - FOOTER_HEIGHT - MARGIN - top;
        int itemLimit = Math.clamp((available - 24) / 40, 0, 3);
        if (itemLimit == 0) return;

        List<CausticaMenuUsage.Item> recent = CausticaMenuUsage.INSTANCE.recent(itemLimit);
        List<CausticaMenuUsage.Item> frequent = CausticaMenuUsage.INSTANCE.frequent(itemLimit, recent);
        int y = top;
        y = addQuickGroup("Recent", recent, y);
        addQuickGroup("Frequent", frequent, y);
    }

    private int addQuickGroup(String heading, List<CausticaMenuUsage.Item> items, int top) {
        if (items.isEmpty()) return top;
        quickHeadings.add(new QuickHeading(heading, top));
        int y = top + 12;
        for (CausticaMenuUsage.Item item : items) {
            ActionButton button = new ActionButton(RAIL_WIDTH,
                    () -> Component.literal(item.label()), () -> searchFor(item.label()), false);
            button.setRectangle(RAIL_WIDTH, 18, MARGIN, y);
            addRenderableWidget(button);
            y += 20;
        }
        return y + 4;
    }

    private void searchFor(String label) {
        searchQuery = label;
        rebuild();
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
        addInfo(() -> CausticaConfig.Rt.Hdr.pendingRestart()
                ? Component.translatable("caustica.options.rt.hdr.restartRequired")
                : Component.literal("Output changes save automatically"));
    }

    private void addReconstruction() {
        addHeader("Reconstruction", "DLSS Ray Reconstruction and its scene-linear guide inputs");
        List<AbstractWidget> controls = new ArrayList<>();
        controls.add(toggle(Component.translatable("caustica.options.rt.dlssRr"), CausticaConfig.Rt.DlssRr.ENABLED));
        controls.add(toggle(Component.translatable("caustica.options.rt.dlssDiffusePathGuide"),
                CausticaConfig.Rt.DlssRr.DIFFUSE_PATH_GUIDE)
                .tooltip(Component.translatable("caustica.options.rt.dlssDiffusePathGuide.tooltip"))
                .activeWhen(CausticaConfig.Rt.DlssRr.ENABLED::configuredValue));
        controls.add(new Dropdown<>(180, Component.translatable("caustica.options.rt.dlssQuality"), DLSS_QUALITY_ORDER,
                CausticaConfig.Rt.DlssRr.QUALITY::configuredValue, CausticaConfig.Rt.DlssRr.QUALITY::set,
                value -> Component.translatable("caustica.options.rt.dlssQuality." + value), null)
                .activeWhen(CausticaConfig.Rt.DlssRr.ENABLED::configuredValue));
        addGrid(controls);
    }

    private void addLighting() {
        addHeader("Lighting & Sky", "Celestial energy, atmospheric ambience, emissives, and shadow softness");
        List<AbstractWidget> controls = new ArrayList<>();
        controls.add(floatSlider(Component.translatable("caustica.options.rt.sunlightIntensity"),
                CausticaConfig.Rt.Composite.SUNLIGHT_INTENSITY_EV, -4.0, 4.0,
                value -> String.format(Locale.ROOT, "%+.1f EV", value)));
        controls.add(floatSlider(Component.translatable("caustica.options.rt.moonlightIntensity"),
                CausticaConfig.Rt.Composite.MOONLIGHT_INTENSITY_EV, -4.0, 8.0,
                value -> String.format(Locale.ROOT, "%+.1f EV", value)));
        controls.add(floatSlider(Component.translatable("caustica.options.rt.ambientLight"),
                CausticaConfig.Rt.Composite.AMBIENT_LIGHT_EV, -8.0, 8.0,
                value -> String.format(Locale.ROOT, "%+.1f EV", value))
                .tooltip(Component.translatable("caustica.options.rt.ambientLight.tooltip")));
        controls.add(floatSlider(Component.translatable("caustica.options.rt.nightAirglow"),
                CausticaConfig.Rt.Composite.NIGHT_AIRGLOW_EV, -8.0, 8.0,
                value -> String.format(Locale.ROOT, "%+.1f EV", value)));
        controls.add(floatSlider(Component.translatable("caustica.options.rt.astronomicalLatitude"),
                CausticaConfig.Rt.Composite.ASTRONOMICAL_LATITUDE_DEG, -90.0, 90.0,
                value -> String.format(Locale.ROOT, "%.1f deg %s", Math.abs(value), value >= 0.0 ? "N" : "S"))
                .tooltip(Component.translatable("caustica.options.rt.astronomicalLatitude.tooltip")));
        controls.add(intSlider(Component.translatable("caustica.options.rt.dayOfYearOffset"),
                CausticaConfig.Rt.Composite.DAY_OF_YEAR_OFFSET, 0, 364,
                value -> String.format(Locale.ROOT, "day %.0f", value + 1.0))
                .tooltip(Component.translatable("caustica.options.rt.dayOfYearOffset.tooltip")));
        controls.add(new Slider(180, Component.translatable("caustica.options.rt.sunSize"),
                () -> Math.toDegrees(CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS.configuredValue()),
                value -> CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS.set((float)value),
                unit -> 0.1 + unit * 4.9, value -> (value - 0.1) / 4.9,
                value -> String.format(Locale.ROOT, "%.1f deg", value))
                .tooltip(Component.translatable("caustica.options.rt.sunSize.tooltip"))
                .resetOnShift(() -> CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS.set(
                        (float)Math.toDegrees(CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS.defaultValue()))));
        controls.add(new Slider(180, Component.translatable("caustica.options.rt.moonSize"),
                () -> Math.toDegrees(CausticaConfig.Rt.Composite.MOON_ANGULAR_RADIUS.configuredValue()),
                value -> CausticaConfig.Rt.Composite.MOON_ANGULAR_RADIUS.set((float)value),
                unit -> 0.1 + unit * 4.9, value -> (value - 0.1) / 4.9,
                value -> String.format(Locale.ROOT, "%.1f deg", value))
                .tooltip(Component.translatable("caustica.options.rt.moonSize.tooltip"))
                .resetOnShift(() -> CausticaConfig.Rt.Composite.MOON_ANGULAR_RADIUS.set(
                        (float)Math.toDegrees(CausticaConfig.Rt.Composite.MOON_ANGULAR_RADIUS.defaultValue()))));
        controls.add(new Slider(180, Component.translatable("caustica.options.rt.torchIntensity"),
                CausticaConfig.Rt.Composite.TORCH_EMISSION_MULTIPLIER::configuredValue,
                value -> CausticaConfig.Rt.Composite.TORCH_EMISSION_MULTIPLIER.set((float)value),
                unit -> RtVideoOptions.torchMultiplierFromSlider((int)Math.round(unit * 100.0)),
                value -> RtVideoOptions.torchSliderFromMultiplier((float)value) / 100.0,
                value -> String.format(Locale.ROOT, "%.0f cd/m²", value * 2000.0))
                .tooltip(Component.translatable("caustica.options.rt.torchIntensity.tooltip"))
                .resetOnShift(() -> CausticaConfig.Rt.Composite.TORCH_EMISSION_MULTIPLIER.set(
                        CausticaConfig.Rt.Composite.TORCH_EMISSION_MULTIPLIER.defaultValue())));
        addGrid(controls);
    }

    private void addGeometry() {
        addHeader("Geometry & Effects", "Path depth, scene participation, water, mirrors, and material transport");
        List<AbstractWidget> controls = new ArrayList<>();
        controls.add(toggle(Component.translatable("caustica.options.enabled"), CausticaConfig.Rt.ENABLED));
        controls.add(intSlider(Component.translatable("caustica.options.rt.spp"), CausticaConfig.Rt.Composite.SPP,
                1, 8, value -> String.format(Locale.ROOT, "%.0f spp", value)));
        controls.add(intSlider(Component.translatable("caustica.options.rt.maxBounces"),
                CausticaConfig.Rt.Composite.MAX_BOUNCES, 2, 8,
                value -> String.format(Locale.ROOT, "%.0f bounces", value)));
        controls.add(toggle(Component.translatable("caustica.options.rt.entities"), CausticaConfig.Rt.Entities.ENABLED));
        controls.add(toggle(Component.translatable("caustica.options.rt.particles"),
                CausticaConfig.Rt.Entities.PARTICLES_ENABLED));
        controls.add(toggle(Component.translatable("caustica.options.rt.waterWaves"),
                CausticaConfig.Rt.Composite.WATER_WAVES));
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
        controls.add(toggle(Component.translatable("caustica.options.rt.sharcPrimaryDiffuseReuse"),
                CausticaConfig.Rt.Sharc.PRIMARY_DIFFUSE_REUSE)
                .tooltip(Component.translatable("caustica.options.rt.sharcPrimaryDiffuseReuse.tooltip")));
        controls.add(toggle(Component.translatable("caustica.options.rt.sharcAntiFirefly"),
                CausticaConfig.Rt.Sharc.ANTI_FIREFLY)
                .tooltip(Component.translatable("caustica.options.rt.sharcAntiFirefly.tooltip")));
        controls.add(toggle(Component.translatable("caustica.options.rt.sharcDetailedStats"),
                CausticaConfig.Rt.Sharc.DETAILED_STATS)
                .tooltip(Component.translatable("caustica.options.rt.sharcDetailedStats.tooltip")));
        addGrid(controls);

        addInfo(this::sharcStatusText);
        addGrid(List.of(
                new ActionButton(180, () -> Component.translatable("caustica.options.rt.sharcReset"),
                        () -> RtComposite.INSTANCE.requestSharcReset("manual menu reset"), false),
                new ActionButton(180, () -> Component.translatable("caustica.options.rt.sharcRestoreParity"),
                        this::restoreSharcParityDefaults, true)
                        .tooltip(Component.translatable("caustica.options.rt.sharcRestoreParity.tooltip"))));
    }

    private void addExposure() {
        addHeader("Exposure & tone mapping", "Only the selected output curve's controls are shown");
        List<AbstractWidget> controls = new ArrayList<>();
        controls.add(new Dropdown<>(180, Component.translatable("caustica.options.rt.exposureMode"),
                List.of("auto", "manual"), CausticaConfig.Rt.Exposure.MODE::configuredValue,
                CausticaConfig.Rt.Exposure.MODE::set,
                value -> Component.translatable("caustica.options.rt.exposureMode." + value), null)
                .resetOnShift(() -> CausticaConfig.Rt.Exposure.MODE.set(
                        CausticaConfig.Rt.Exposure.MODE.defaultValue())));
        controls.add(floatSlider(Component.translatable("caustica.options.rt.manualEv"),
                CausticaConfig.Rt.Exposure.MANUAL_EXPOSURE_EV, -12, 12,
                value -> String.format(Locale.ROOT, "%+.1f EV", value))
                .activeWhen(() -> "manual".equals(CausticaConfig.Rt.Exposure.MODE.configuredValue())));
        controls.add(floatSlider(Component.translatable("caustica.options.rt.exposureCompensation"),
                CausticaConfig.Rt.Exposure.COMPENSATION_EV, -4, 4,
                value -> String.format(Locale.ROOT, "%+.1f EV", value))
                .activeWhen(() -> "auto".equals(CausticaConfig.Rt.Exposure.MODE.configuredValue())));
        controls.add(floatSlider(Component.translatable("caustica.options.rt.exposureKey"),
                CausticaConfig.Rt.Exposure.KEY, 0.05, 0.50,
                value -> String.format(Locale.ROOT, "%.2f", value))
                .tooltip(Component.translatable("caustica.options.rt.exposureKey.tooltip"))
                .activeWhen(() -> "auto".equals(CausticaConfig.Rt.Exposure.MODE.configuredValue())));
        controls.add(floatSlider(Component.translatable("caustica.options.rt.exposureLowPercentile"),
                CausticaConfig.Rt.Exposure.LOW_PERCENTILE, 0.0, 0.50,
                value -> String.format(Locale.ROOT, "%.0f%%", value * 100.0))
                .tooltip(Component.translatable("caustica.options.rt.exposureLowPercentile.tooltip"))
                .activeWhen(() -> "auto".equals(CausticaConfig.Rt.Exposure.MODE.configuredValue())));
        controls.add(floatSlider(Component.translatable("caustica.options.rt.exposureHighPercentile"),
                CausticaConfig.Rt.Exposure.HIGH_PERCENTILE, 0.50, 1.0,
                value -> String.format(Locale.ROOT, "%.0f%%", value * 100.0))
                .tooltip(Component.translatable("caustica.options.rt.exposureHighPercentile.tooltip"))
                .activeWhen(() -> "auto".equals(CausticaConfig.Rt.Exposure.MODE.configuredValue())));
        controls.add(floatSlider(Component.translatable("caustica.options.rt.exposureHighlightPercentile"),
                CausticaConfig.Rt.Exposure.HIGHLIGHT_PERCENTILE, 0.95, 1.0,
                value -> String.format(Locale.ROOT, "%.2f%%", value * 100.0))
                .tooltip(Component.translatable("caustica.options.rt.exposureHighlightPercentile.tooltip"))
                .activeWhen(() -> "auto".equals(CausticaConfig.Rt.Exposure.MODE.configuredValue())));
        controls.add(floatSlider(Component.translatable("caustica.options.rt.exposureHighlightHeadroom"),
                CausticaConfig.Rt.Exposure.HIGHLIGHT_HEADROOM, 1.0, 32.0,
                value -> String.format(Locale.ROOT, "%.1fx", value))
                .tooltip(Component.translatable("caustica.options.rt.exposureHighlightHeadroom.tooltip"))
                .activeWhen(() -> "auto".equals(CausticaConfig.Rt.Exposure.MODE.configuredValue())));
        controls.add(floatSlider(Component.translatable("caustica.options.rt.exposureDarkAdapt"),
                CausticaConfig.Rt.Exposure.ADAPT_UP, 0.1, 10.0,
                value -> String.format(Locale.ROOT, "%.1f s", value))
                .tooltip(Component.translatable("caustica.options.rt.exposureDarkAdapt.tooltip"))
                .activeWhen(() -> "auto".equals(CausticaConfig.Rt.Exposure.MODE.configuredValue())));
        controls.add(floatSlider(Component.translatable("caustica.options.rt.exposureBrightAdapt"),
                CausticaConfig.Rt.Exposure.ADAPT_DOWN, 0.1, 10.0,
                value -> String.format(Locale.ROOT, "%.1f s", value))
                .tooltip(Component.translatable("caustica.options.rt.exposureBrightAdapt.tooltip"))
                .activeWhen(() -> "auto".equals(CausticaConfig.Rt.Exposure.MODE.configuredValue())));
        controls.add(floatSlider(Component.translatable("caustica.options.rt.exposureMinEv"),
                CausticaConfig.Rt.Exposure.MIN_EV, -24.0, 0.0,
                value -> String.format(Locale.ROOT, "%+.0f EV", value))
                .tooltip(Component.translatable("caustica.options.rt.exposureMinEv.tooltip"))
                .activeWhen(() -> "auto".equals(CausticaConfig.Rt.Exposure.MODE.configuredValue())));
        controls.add(floatSlider(Component.translatable("caustica.options.rt.exposureMaxEv"),
                CausticaConfig.Rt.Exposure.MAX_EV, 0.0, 12.0,
                value -> String.format(Locale.ROOT, "%+.0f EV", value))
                .tooltip(Component.translatable("caustica.options.rt.exposureMaxEv.tooltip"))
                .activeWhen(() -> "auto".equals(CausticaConfig.Rt.Exposure.MODE.configuredValue())));
        controls.add(floatSlider(Component.translatable("caustica.options.rt.exposureCenterWeight"),
                CausticaConfig.Rt.Exposure.CENTER_WEIGHT, 0.0, 8.0,
                value -> String.format(Locale.ROOT, "%.1fx", value))
                .tooltip(Component.translatable("caustica.options.rt.exposureCenterWeight.tooltip"))
                .activeWhen(() -> "auto".equals(CausticaConfig.Rt.Exposure.MODE.configuredValue())));
        controls.add(floatSlider(Component.translatable("caustica.options.rt.exposureLogMin"),
                CausticaConfig.Rt.Exposure.LOG_MIN, -32.0, 8.0,
                value -> String.format(Locale.ROOT, "%+.0f EV", value))
                .tooltip(Component.translatable("caustica.options.rt.exposureLogMin.tooltip"))
                .activeWhen(() -> "auto".equals(CausticaConfig.Rt.Exposure.MODE.configuredValue())));
        controls.add(floatSlider(Component.translatable("caustica.options.rt.exposureLogMax"),
                CausticaConfig.Rt.Exposure.LOG_MAX, -8.0, 32.0,
                value -> String.format(Locale.ROOT, "%+.0f EV", value))
                .tooltip(Component.translatable("caustica.options.rt.exposureLogMax.tooltip"))
                .activeWhen(() -> "auto".equals(CausticaConfig.Rt.Exposure.MODE.configuredValue())));
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

        addInfo(() -> Component.literal(String.format(Locale.ROOT,
                "Exposure  actual %+.2f EV  |  target %+.2f EV  |  confidence %.0f%%  |  trusted %.1f%%  |  ceiling %+.1f EV",
                RtComposite.INSTANCE.exposureActualEv(), RtComposite.INSTANCE.exposureTargetEv(),
                RtComposite.INSTANCE.exposureConfidence() * 100.0f,
                RtComposite.INSTANCE.exposureTrustedCoverage() * 100.0f,
                RtComposite.INSTANCE.exposureActiveCeilingEv())));
        addInfo(() -> Component.literal("Active curve  \u2022  " + activeToneLabel()));
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
        addInfo(() -> Component.literal("Offline renderer: use the configured capture key while in world"));
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
        searchContext = title + ' ' + subtitle;
        if (collectingSearchResults) return;
        body.addChild(new SectionHeader(contentWidth, Component.literal(title), Component.literal(subtitle)));
    }

    private void addGrid(List<? extends AbstractWidget> controls) {
        registerControls(controls);
        if (collectingSearchResults) {
            controls.stream().filter(this::matchesSearch).forEach(collectedSearchResults::add);
            return;
        }
        addGridDirect(controls);
    }

    private void addGridDirect(List<? extends AbstractWidget> controls) {
        if (controls.isEmpty()) return;
        body.addChild(new WidgetGridLayout(contentWidth, gridColumns, GRID_GAP, GRID_GAP,
                CONTROL_HEIGHT, controls));
    }

    private void registerControls(List<? extends AbstractWidget> controls) {
        for (AbstractWidget control : controls) {
            if (control instanceof Dropdown<?> dropdown) dropdowns.add(dropdown);
            if (control instanceof LabeledControl || toneResets.containsKey(control)) {
                usageLabels.put(control, controlLabel(control));
            }
        }
    }

    private boolean matchesSearch(AbstractWidget control) {
        String haystack = (searchContext + ' ' + controlLabel(control)).toLowerCase(Locale.ROOT);
        return java.util.Arrays.stream(searchQuery.toLowerCase(Locale.ROOT).trim().split("\\s+"))
                .allMatch(haystack::contains);
    }

    private static String controlLabel(AbstractWidget control) {
        if (control instanceof LabeledControl labeled) {
            return labeled.causticaLabel().getString();
        }
        String message = control.getMessage().getString();
        int separator = message.indexOf(": ");
        return separator > 0 ? message.substring(0, separator) : message;
    }

    private void addInfo(java.util.function.Supplier<Component> text) {
        if (!collectingSearchResults) body.addChild(new InfoStrip(contentWidth, text));
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
                : RtComposite.INSTANCE.sharcPrimaryDiffuseActive() ? "Active - C raw primary debug"
                : RtComposite.INSTANCE.sharcActive() ? "Active - B secondary only"
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
        CausticaConfig.Rt.Sharc.PRIMARY_DIFFUSE_REUSE.set(false);
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
        int railBottom = height - FOOTER_HEIGHT - MARGIN;
        g.fill(MARGIN + RAIL_WIDTH + 4, MARGIN, MARGIN + RAIL_WIDTH + 5,
                railBottom, 0x70FFFFFF);
        g.fill(MARGIN, MARGIN + HEADER_HEIGHT, paneRight,
                MARGIN + HEADER_HEIGHT + 1, 0x50FFFFFF);
        g.text(font, Component.literal(searchQuery.isBlank() ? category.label : "Search"),
                paneLeft + 2, MARGIN + 5, CausticaWidgets.ACCENT);
        for (QuickHeading heading : quickHeadings) {
            g.text(font, Component.literal(heading.label()), MARGIN + 2, heading.y(), CausticaWidgets.MUTED);
        }
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
            if (dropdown.clickOverlay(input.x(), input.y(), height)) {
                recordControl(dropdown);
                return true;
            }
            if (open == null) open = dropdown;
            else dropdown.close();
        }
        if (open != null && !open.isMouseOver(input.x(), input.y())) open.close();
        if (input.hasShiftDown()) {
            for (Map.Entry<AbstractWidget, Runnable> entry : toneResets.entrySet()) {
                AbstractWidget widget = entry.getKey();
                if (widget.visible && widget.active && widget.isMouseOver(input.x(), input.y())) {
                    entry.getValue().run();
                    recordControl(widget);
                    rebuild();
                    return true;
                }
            }
        }
        AbstractWidget used = usageLabels.keySet().stream()
                .filter(widget -> !(widget instanceof Dropdown<?>))
                .filter(widget -> widget.visible && widget.active && widget.isMouseOver(input.x(), input.y()))
                .findFirst().orElse(null);
        boolean handled = super.mouseClicked(input, doubleClick);
        if (handled && used != null) recordControl(used);
        return handled;
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
        if (input.hasControlDown() && input.key() == GLFW.GLFW_KEY_F) {
            setFocused(searchBox);
            searchBox.setFocused(true);
            return true;
        }
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
            boolean closed = false;
            for (Dropdown<?> dropdown : dropdowns) {
                if (dropdown.isOpen()) {
                    dropdown.close();
                    closed = true;
                }
            }
            if (closed) return true;
            if (!searchQuery.isBlank()) {
                searchQuery = "";
                rebuild();
                return true;
            }
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
        CausticaMenuUsage.INSTANCE.save();
        CausticaConfig.save();
        options.save();
    }

    private void recordControl(AbstractWidget widget) {
        String label = usageLabels.get(widget);
        if (label != null) CausticaMenuUsage.INSTANCE.record(label);
    }

    private record QuickHeading(String label, int y) {
    }
}
