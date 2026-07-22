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
import dev.comfyfluffy.caustica.client.ui.CollapsibleLayout;
import dev.comfyfluffy.caustica.client.ui.WidgetGridLayout;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtHdr;
import dev.comfyfluffy.caustica.rt.RtResolutionScale;
import dev.comfyfluffy.caustica.rt.RtSharcSupport;
import dev.comfyfluffy.caustica.rt.pipeline.RtReconstruction;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
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
import net.minecraft.client.gui.components.AbstractScrollArea;
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
    private static final int MIN_RAIL_WIDTH = 132;
    private static final int MAX_RAIL_WIDTH = 220;
    private static final int GRID_GAP = 3;
    private static final int CONTROL_HEIGHT = 20;
    private static final int MAX_CONTENT_WIDTH = 1600;
    private static final int TARGET_CELL_WIDTH = 180;
    private static final int MAX_GRID_COLUMNS = 8;
    private static final long TARGET_FLASH_MILLIS = 1800L;
    private static final List<Integer> DLSS_QUALITY_ORDER = List.of(3, 0, 1, 2, 5);
    private static final List<Integer> DLSS_RR_PRESETS = List.of(0, 4, 5);
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
    private AbstractScrollArea bodyScrollArea;
    private CategoryLayout body;
    private final Map<AbstractWidget, Runnable> toneResets = new IdentityHashMap<>();
    private final Map<AbstractWidget, String> usageLabels = new IdentityHashMap<>();
    private final Map<AbstractWidget, Category> usageCategories = new IdentityHashMap<>();
    private final Map<String, Category> controlCategories = new java.util.HashMap<>();
    private final List<Dropdown<?>> dropdowns = new ArrayList<>();
    private final List<QuickHeading> quickHeadings = new ArrayList<>();
    private final List<AbstractWidget> collectedSearchResults = new ArrayList<>();
    private EditBox searchBox;
    private String searchQuery = "";
    private String searchContext = "";
    private boolean collectingSearchResults;
    private boolean indexingControls;
    private boolean saved;
    private int contentWidth;
    private int gridColumns;
    private int railWidth;
    private Category category = Category.OVERVIEW;
    private Category buildingCategory = Category.OVERVIEW;
    private String pendingTargetId;
    private AbstractWidget targetWidget;
    private AbstractWidget flashWidget;
    private CollapsibleLayout activeBundle;
    private AbstractWidget selectedCategoryButton;
    private long flashUntilMillis;

    private enum CategoryGroup {
        ESSENTIALS("essentials"), IMAGE("image"), WORLD("world"), ADVANCED("advanced");
        final String key;
        CategoryGroup(String key) { this.key = "caustica.options.group." + key; }
        Component label() { return Component.translatable(key); }
    }

    private enum Category {
        OVERVIEW(CategoryGroup.ESSENTIALS, "overview"),
        OUTPUT(CategoryGroup.IMAGE, "output"),
        RECONSTRUCTION(CategoryGroup.IMAGE, "reconstruction"),
        EXPOSURE(CategoryGroup.IMAGE, "exposure"),
        LIGHTING(CategoryGroup.WORLD, "lighting"),
        SKY(CategoryGroup.WORLD, "sky"),
        GEOMETRY(CategoryGroup.WORLD, "geometry"),
        VIEW(CategoryGroup.WORLD, "view"),
        MATERIALS(CategoryGroup.ADVANCED, "materials"),
        SHARC(CategoryGroup.ADVANCED, "sharc"),
        DIAGNOSTICS(CategoryGroup.ADVANCED, "diagnostics");
        final CategoryGroup group;
        final String key;
        Category(CategoryGroup group, String key) {
            this.group = group;
            this.key = "caustica.options.category." + key;
        }
        Component label() { return Component.translatable(key); }
    }

    public CausticaSettingsScreen(Screen previous, Options options) {
        this(previous, options, null);
    }

    public CausticaSettingsScreen(Screen previous, Options options, String initialCategory) {
        super(Component.translatable("caustica.options.title"));
        this.previous = previous;
        this.options = options;
        String requestedCategory = initialCategory != null
                ? initialCategory : CausticaMenuUsage.INSTANCE.lastCategory();
        if (requestedCategory != null) {
            try {
                this.category = Category.valueOf(requestedCategory.toUpperCase(Locale.ROOT));
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
        usageCategories.clear();
        controlCategories.clear();
        dropdowns.clear();
        quickHeadings.clear();
        collectedSearchResults.clear();
        bodyScrollArea = null;
        targetWidget = null;
        flashWidget = null;
        selectedCategoryButton = null;
        activeBundle = null;
        railWidth = computeRailWidth();
        int paneLeft = MARGIN + railWidth + 8;
        int availableWidth = Math.max(180, width - paneLeft - MARGIN - 8);
        contentWidth = Math.min(MAX_CONTENT_WIDTH, availableWidth);
        gridColumns = Math.clamp((contentWidth + GRID_GAP) / (TARGET_CELL_WIDTH + GRID_GAP),
                1, MAX_GRID_COLUMNS);

        searchBox = new EditBox(font, MARGIN, MARGIN + 3, railWidth, 20,
                Component.translatable("caustica.options.search"));
        searchBox.setMaxLength(80);
        searchBox.setHint(Component.translatable("caustica.options.search.hint"));
        searchBox.setCanLoseFocus(true);
        searchBox.setValue(searchQuery);
        searchBox.setResponder(this::searchChanged);
        addRenderableWidget(searchBox);

        int railY = MARGIN + HEADER_HEIGHT + 6;
        CategoryGroup currentGroup = null;
        boolean groupCollapsed = false;
        for (Category value : Category.values()) {
            if (value.group != currentGroup) {
                currentGroup = value.group;
                String stateId = "group." + currentGroup.name().toLowerCase(Locale.ROOT);
                groupCollapsed = CausticaTreeState.INSTANCE.isCollapsed(stateId);
                CollapsibleLayout.TreeHeader heading = new CollapsibleLayout.TreeHeader(railWidth,
                        currentGroup.label(), () -> CausticaTreeState.INSTANCE.isCollapsed(stateId), () -> {
                            CausticaTreeState.INSTANCE.setCollapsed(stateId,
                                    !CausticaTreeState.INSTANCE.isCollapsed(stateId));
                            rememberMenuPosition();
                            rebuildScreen();
                        });
                heading.setRectangle(railWidth, 18, MARGIN, railY);
                addRenderableWidget(heading);
                railY += 18;
            }
            if (groupCollapsed) continue;
            Category target = value;
            ActionButton button = new ActionButton(railWidth,
                    target::label, () -> selectCategory(target), false);
            button.setRectangle(railWidth, 17, MARGIN, railY);
            addRenderableWidget(button);
            if (category == target) selectedCategoryButton = button;
            railY += 18;
        }
        body = new CategoryLayout(contentWidth, 4);
        indexControlCategories();
        addQuickLinks(railY + 2);
        if (!searchQuery.isBlank()) {
            addSearchResults();
        } else {
            addCategory(category);
        }
        body.addChild(SpacerElement.height(8));

        int bodyTop = MARGIN + HEADER_HEIGHT + 6;
        int bodyHeight = Math.max(40, height - bodyTop - FOOTER_HEIGHT - MARGIN - 4);
        bodyScroll = new ScrollableLayout(minecraft, body, bodyHeight);
        bodyScroll.setScrollbarSpacing(6);
        bodyScroll.setMinWidth(contentWidth);
        bodyScroll.setX(paneLeft);
        bodyScroll.setY(bodyTop);
        bodyScroll.visitWidgets(widget -> {
            if (widget instanceof AbstractScrollArea scrollArea) bodyScrollArea = scrollArea;
            addRenderableWidget(widget);
        });

        ActionButton done = new ActionButton(110, () -> Component.translatable("gui.done"), this::onClose, true);
        done.setRectangle(110, CONTROL_HEIGHT, paneLeft + contentWidth - 110,
                height - MARGIN - CONTROL_HEIGHT);
        addRenderableWidget(done);
        repositionElements();
        restorePositionOrRevealTarget(bodyTop);
        setInitialFocus(targetWidget != null ? targetWidget : searchBox);
    }

    private void selectCategory(Category target) {
        if (category == target && searchQuery.isBlank()) return;
        rememberMenuPosition();
        category = target;
        searchQuery = "";
        rebuildScreen();
    }

    private void addCategory(Category target) {
        buildingCategory = target;
        switch (target) {
            case OVERVIEW -> addOverview();
            case OUTPUT -> addOutput();
            case RECONSTRUCTION -> addReconstruction();
            case LIGHTING -> addLighting();
            case SKY -> addSky();
            case EXPOSURE -> addExposure();
            case GEOMETRY -> addGeometry();
            case MATERIALS -> addMaterials();
            case SHARC -> addSharc();
            case VIEW -> addView();
            case DIAGNOSTICS -> addDiagnostics();
        }
    }

    private void indexControlCategories() {
        indexingControls = true;
        collectingSearchResults = true;
        for (Category value : Category.values()) addCategory(value);
        collectingSearchResults = false;
        indexingControls = false;
        collectedSearchResults.clear();
        toneResets.clear();
        dropdowns.clear();
        usageLabels.clear();
        usageCategories.clear();
        buildingCategory = category;
    }

    private void addOverview() {
        addHeader("overview.rendering");
        List<AbstractWidget> rendering = new ArrayList<>();
        rendering.add(toggle(Component.translatable("caustica.options.enabled"), CausticaConfig.Rt.ENABLED));
        rendering.add(intSlider(Component.translatable("caustica.options.rt.spp"), CausticaConfig.Rt.Composite.SPP,
                1, 8, value -> String.format(Locale.ROOT, "%.0f spp", value)));
        rendering.add(intSlider(Component.translatable("caustica.options.rt.maxBounces"),
                CausticaConfig.Rt.Composite.MAX_BOUNCES, 2, 8,
                value -> String.format(Locale.ROOT, "%.0f bounces", value)));
        rendering.add(intSlider(Component.translatable("caustica.options.rt.celestialLightBounces"),
                CausticaConfig.Rt.Composite.CELESTIAL_LIGHT_BOUNCES, 0, 8,
                value -> String.format(Locale.ROOT, "%.0f bounces", value))
                .tooltip(Component.translatable("caustica.options.rt.celestialLightBounces.tooltip")));
        rendering.add(toggle(Component.translatable("caustica.options.rt.dlssRr"),
                CausticaConfig.Rt.DlssRr.ENABLED));
        rendering.add(new Dropdown<>(180, Component.translatable("caustica.options.rt.dlssQuality"),
                DLSS_QUALITY_ORDER, RtResolutionScale::displayedQuality,
                CausticaSettingsScreen::selectDlssQuality,
                CausticaSettingsScreen::dlssQualityLabel, null)
                .activeWhen(CausticaConfig.Rt.DlssRr.ENABLED::configuredValue));
        rendering.add(toggle(Component.translatable("caustica.options.rt.hdr"), CausticaConfig.Rt.Hdr.ENABLED));
        rendering.add(new Dropdown<>(180, Component.translatable("caustica.options.rt.fg.mode"),
                List.of("off", "fixed"), CausticaConfig.Rt.Fg::configuredMode, CausticaConfig.Rt.Fg::setMode,
                value -> Component.translatable("caustica.options.rt.fg.mode." + value), null));
        rendering.add(new Dropdown<>(180, Component.translatable("caustica.options.rt.fg.reflex"),
                List.of("off", "on", "boost"), this::reflexMode, this::setReflexMode,
                value -> Component.translatable("caustica.options.rt.fg.reflex." + value), null));
        rendering = ordered(rendering, 0, 4, 5, 1, 2, 3, 6, 7, 8);
        addGrid(rendering);

        addHeader("overview.scene");
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
        scene = ordered(scene, 4, 6, 5, 7, 0, 1, 2, 3, 8, 9);
        addGrid(scene);

        addInfo(() -> Component.literal(RtHdr.statusDescription()));
    }

    private void searchChanged(String value) {
        if (value.equals(searchQuery)) return;
        rememberMenuPosition();
        searchQuery = value;
        rebuildScreen();
    }

    private void addSearchResults() {
        collectingSearchResults = true;
        collectedSearchResults.clear();
        for (Category value : Category.values()) {
            if (value != Category.OVERVIEW) addCategory(value);
        }
        collectingSearchResults = false;
        buildingCategory = category;
        searchContext = "";

        addHeader(Component.translatable("caustica.options.search.results"),
                Component.translatable("caustica.options.search.matches", collectedSearchResults.size()));
        if (collectedSearchResults.isEmpty()) {
            addInfo(() -> Component.translatable("caustica.options.search.empty", searchQuery));
        } else {
            addGridDirect(collectedSearchResults);
        }
    }

    private void addQuickLinks(int top) {
        int available = height - FOOTER_HEIGHT - MARGIN - top;
        int itemLimit = Math.clamp((available - 32) / 36, 0, 8);
        if (itemLimit == 0) return;

        List<CausticaMenuUsage.Item> recent = CausticaMenuUsage.INSTANCE.recent(itemLimit);
        List<CausticaMenuUsage.Item> frequent = CausticaMenuUsage.INSTANCE.frequent(itemLimit, recent);
        int y = top;
        y = addQuickGroup(Component.translatable("caustica.options.quick.recent").getString(), recent, y);
        addQuickGroup(Component.translatable("caustica.options.quick.frequent").getString(), frequent, y);
    }

    private int addQuickGroup(String heading, List<CausticaMenuUsage.Item> items, int top) {
        if (items.isEmpty()) return top;
        quickHeadings.add(new QuickHeading(heading, top));
        int y = top + 12;
        for (CausticaMenuUsage.Item item : items) {
            ActionButton button = new ActionButton(railWidth,
                    () -> Component.literal(item.label()), () -> revealControl(item), false);
            button.setRectangle(railWidth, 16, MARGIN, y);
            addRenderableWidget(button);
            y += 18;
        }
        return y + 4;
    }

    private void revealControl(CausticaMenuUsage.Item item) {
        Category target = null;
        if (item.category() != null && !item.category().isBlank()) {
            try {
                target = Category.valueOf(item.category());
            } catch (IllegalArgumentException ignored) {
                // Older or externally edited history falls through to the live control index.
            }
        }
        if (target == null) target = controlCategories.get(item.id());
        if (target == null) {
            rememberMenuPosition();
            searchQuery = item.label();
            rebuildScreen();
            return;
        }
        rememberMenuPosition();
        category = target;
        searchQuery = "";
        pendingTargetId = item.id();
        rebuildScreen();
    }

    private void addOutput() {
        addHeader("output");
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
        controls.add(new Dropdown<>(180, Component.translatable("caustica.options.rt.fg.queueParallelism"),
                List.of("synchronized", "parallel"),
                CausticaConfig.Rt.Fg.QUEUE_PARALLELISM::configuredValue,
                CausticaConfig.Rt.Fg.QUEUE_PARALLELISM::set,
                value -> Component.translatable("caustica.options.rt.fg.queueParallelism." + value), null)
                .activeWhen(() -> !"off".equals(CausticaConfig.Rt.Fg.configuredMode()))
                .tooltip(Component.translatable("caustica.options.rt.fg.queueParallelism.tooltip")));
        controls.add(intSlider(Component.translatable("caustica.options.rt.fg.outputTarget"),
                CausticaConfig.Rt.Fg.OUTPUT_TARGET_FPS, 30, 480,
                fps -> String.format(java.util.Locale.ROOT, "%.0f displayed FPS", fps))
                .activeWhen(() -> !"off".equals(CausticaConfig.Rt.Fg.configuredMode())
                        && "parallel".equals(CausticaConfig.Rt.Fg.QUEUE_PARALLELISM.configuredValue()))
                .tooltip(Component.translatable("caustica.options.rt.fg.outputTarget.tooltip")));
        controls.add(toggle(Component.translatable("caustica.options.rt.fg.uiRecomposition"),
                CausticaConfig.Rt.Fg.UI_RECOMPOSITION));
        controls.add(toggle(Component.translatable("caustica.options.rt.fg.fullscreenMenu"),
                CausticaConfig.Rt.Fg.FULLSCREEN_MENU_DETECTION));
        addBundle("output.displayFormat");
        addGrid(controls.subList(0, 1));
        addBundle("output.frameGeneration");
        addGrid(controls.subList(1, controls.size()));
        addInfo(() -> Component.literal(RtHdr.statusDescription()));
    }

    private void addReconstruction() {
        addHeader("reconstruction");
        List<AbstractWidget> controls = new ArrayList<>();
        controls.add(new Dropdown<>(180, Component.translatable("caustica.options.rt.reconstructionBackend"),
                List.of("auto", "nrd", "dlss-rr", "off"),
                CausticaConfig.Rt.Reconstruction.BACKEND::configuredValue,
                CausticaConfig.Rt.Reconstruction.BACKEND::set,
                value -> Component.translatable("caustica.options.rt.reconstructionBackend." + value), null));
        controls.add(toggle(Component.translatable("caustica.options.rt.dlssRr"), CausticaConfig.Rt.DlssRr.ENABLED));
        controls.add(toggle(Component.translatable("caustica.options.rt.dlssDiffusePathGuide"),
                CausticaConfig.Rt.DlssRr.DIFFUSE_PATH_GUIDE)
                .tooltip(Component.translatable("caustica.options.rt.dlssDiffusePathGuide.tooltip"))
                .activeWhen(this::dlssControlsActive));
        controls.add(liveToggle(Component.translatable("caustica.options.rt.subpixelDetail"),
                CausticaConfig.Rt.DlssRr.SUBPIXEL_DETAIL,
                RtComposite.INSTANCE::requestTextureFilteringRefresh)
                .tooltip(Component.translatable("caustica.options.rt.subpixelDetail.tooltip"))
                .activeWhen(this::dlssControlsActive));
        controls.add(new Dropdown<>(180, Component.translatable("caustica.options.rt.dlssQuality"), DLSS_QUALITY_ORDER,
                RtResolutionScale::displayedQuality, CausticaSettingsScreen::selectDlssQuality,
                CausticaSettingsScreen::dlssQualityLabel, null)
                .activeWhen(this::dlssControlsActive));
        controls.add(new Dropdown<>(180, Component.translatable("caustica.options.rt.dlssPreset"),
                DLSS_RR_PRESETS, RtDlssRr::renderPreset, CausticaSettingsScreen::selectDlssPreset,
                value -> Component.translatable("caustica.options.rt.dlssPreset." + switch (value) {
                    case 4 -> "d";
                    case 5 -> "e";
                    default -> "default";
                }), null).tooltip(Component.translatable("caustica.options.rt.dlssPreset.tooltip"))
                .activeWhen(this::dlssControlsActive));
        controls.add(toggle(Component.translatable("caustica.options.rt.highQualityTransparency"),
                CausticaConfig.Rt.Reconstruction.ADVANCED_OPTICAL_TRANSPORT)
                .tooltip(Component.translatable("caustica.options.rt.highQualityTransparency.tooltip"))
                .activeWhen(this::highQualityTransparencyControlsActive));
        controls.add(toggle(Component.translatable("caustica.options.rt.particleTemporalHistory"),
                CausticaConfig.Rt.DlssRr.PARTICLE_TEMPORAL_HISTORY)
                .tooltip(Component.translatable("caustica.options.rt.particleTemporalHistory.tooltip"))
                .activeWhen(this::dlssControlsActive));
        addBundle("reconstruction.backend");
        addGrid(List.of(controls.get(0), controls.get(6)));
        int[] pendingInputScaleTenths = {
                CausticaConfig.Rt.DlssRr.INPUT_RATIO_TENTHS.configuredValue()
        };
        Runnable commitInputScale = () -> {
            int selectedTenths = Math.clamp(pendingInputScaleTenths[0], 10, 40);
            CausticaConfig.Rt.DlssRr.INPUT_RATIO_TENTHS.set(selectedTenths);
            RtComposite.INSTANCE.requestResolutionScaleCommit();
        };
        Slider outputSlider = new Slider(180,
                Component.translatable("caustica.options.rt.outputScale"),
                CausticaConfig.Rt.OutputScale.PERCENT::configuredValue,
                value -> {
                    int next = (int) Math.round(value);
                    CausticaConfig.Rt.OutputScale.PERCENT.set(next);
                    RtComposite.INSTANCE.requestResolutionScaleCommit();
                },
                unit -> 10.0 + unit * 190.0, value -> (value - 10.0) / 190.0,
                value -> String.format(Locale.ROOT, "%.0f%%", value))
                .tooltip(Component.translatable("caustica.options.rt.outputScale.tooltip"))
                .snapTo(List.of(33, 58, 67), 2)
                .resetOnShift(() -> {
                    CausticaConfig.Rt.OutputScale.PERCENT.set(CausticaConfig.Rt.OutputScale.PERCENT.defaultValue());
                    RtComposite.INSTANCE.requestResolutionScaleCommit();
                });
        Slider inputSlider = new Slider(180,
                Component.translatable("caustica.options.rt.inputScale"),
                () -> pendingInputScaleTenths[0],
                value -> {
                    pendingInputScaleTenths[0] = (int) Math.round(value);
                },
                unit -> 10.0 + unit * 30.0, value -> (value - 10.0) / 30.0,
                value -> String.format(Locale.ROOT, "%.1fx", value / 10.0))
                .tooltip(Component.translatable("caustica.options.rt.inputScale.tooltip"))
                .activeWhen(this::dlssControlsActive)
                .onRelease(commitInputScale)
                .resetOnShift(() -> {
                    int defaultTenths = CausticaConfig.Rt.DlssRr.INPUT_RATIO_TENTHS.defaultValue();
                    pendingInputScaleTenths[0] = defaultTenths;
                    commitInputScale.run();
                });
        addInfo(() -> Component.literal(String.format(Locale.ROOT,
                "Output Scale: %d%% of window  \u2022  Input Scale: %.1fx of output",
                CausticaConfig.Rt.OutputScale.PERCENT.configuredValue(),
                pendingInputScaleTenths[0] / 10.0)));
        addBundle("reconstruction.outputScaling");
        addGrid(List.of(outputSlider, inputSlider));
        addBundle("reconstruction.dlss");
        addGrid(List.of(controls.get(1), controls.get(4), controls.get(5),
                controls.get(2), controls.get(3), controls.get(7)));

        List<AbstractWidget> nrd = new ArrayList<>();
        nrd.add(new Dropdown<>(180, Component.translatable("caustica.options.rt.nrd.denoiser"),
                List.of("reblur", "relax"), CausticaConfig.Rt.Nrd.DENOISER::configuredValue,
                CausticaConfig.Rt.Nrd.DENOISER::set,
                value -> Component.translatable("caustica.options.rt.nrd.denoiser." + value), null)
                .activeWhen(this::nrdControlsActive));
        nrd.add(toggle(Component.translatable("caustica.options.rt.nrd.sh"),
                CausticaConfig.Rt.Nrd.SPHERICAL_HARMONICS).activeWhen(this::nrdControlsActive));
        nrd.add(new Dropdown<>(180, Component.translatable("caustica.options.rt.nrd.upscaleMode"),
                List.of("native", "quality", "balanced", "performance", "ultra-performance", "custom"),
                CausticaConfig.Rt.Nrd.UPSCALE_MODE::configuredValue,
                CausticaConfig.Rt.Nrd.UPSCALE_MODE::set,
                value -> Component.translatable("caustica.options.rt.nrd.upscaleMode." + value), null)
                .activeWhen(this::nrdControlsActive));
        nrd.add(percentSlider(Component.translatable("caustica.options.rt.nrd.renderScale"),
                CausticaConfig.Rt.Nrd.CUSTOM_RENDER_SCALE, 25.0, 100.0,
                value -> String.format("%.0f%%", value))
                .activeWhen(() -> nrdControlsActive()
                        && "custom".equals(CausticaConfig.Rt.Nrd.UPSCALE_MODE.configuredValue())));
        nrd.add(new Dropdown<>(180, Component.translatable("caustica.options.rt.nrd.upscaleFilter"),
                List.of("edge-adaptive", "linear", "nearest"),
                CausticaConfig.Rt.Nrd.UPSCALE_FILTER::configuredValue,
                CausticaConfig.Rt.Nrd.UPSCALE_FILTER::set,
                value -> Component.translatable("caustica.options.rt.nrd.upscaleFilter." + value), null)
                .activeWhen(() -> nrdControlsActive()
                        && !"native".equals(CausticaConfig.Rt.Nrd.UPSCALE_MODE.configuredValue())));
        nrd.add(floatSlider(Component.translatable("caustica.options.rt.nrd.upscaleSharpness"),
                CausticaConfig.Rt.Nrd.UPSCALE_SHARPNESS, 0.0, 1.0,
                value -> String.format("%.2f", value))
                .activeWhen(() -> nrdControlsActive()
                        && !"native".equals(CausticaConfig.Rt.Nrd.UPSCALE_MODE.configuredValue())
                        && "edge-adaptive".equals(CausticaConfig.Rt.Nrd.UPSCALE_FILTER.configuredValue())));
        nrd.add(toggle(Component.translatable("caustica.options.rt.nrd.antiFirefly"),
                CausticaConfig.Rt.Nrd.ANTI_FIREFLY).activeWhen(this::nrdControlsActive));
        nrd.add(toggle(Component.translatable("caustica.options.rt.nrd.antilag"),
                CausticaConfig.Rt.Nrd.ANTILAG).activeWhen(this::nrdControlsActive));
        nrd.add(intSlider(Component.translatable("caustica.options.rt.nrd.maxHistory"),
                CausticaConfig.Rt.Nrd.MAX_ACCUMULATED_FRAMES, 0, 255, value -> String.format("%.0f frames", value))
                .activeWhen(this::nrdControlsActive));
        nrd.add(intSlider(Component.translatable("caustica.options.rt.nrd.fastHistory"),
                CausticaConfig.Rt.Nrd.MAX_FAST_ACCUMULATED_FRAMES, 0, 63, value -> String.format("%.0f frames", value))
                .activeWhen(this::nrdControlsActive));
        nrd.add(intSlider(Component.translatable("caustica.options.rt.nrd.historyFix"),
                CausticaConfig.Rt.Nrd.HISTORY_FIX_FRAMES, 0, 3, value -> String.format("%.0f frames", value))
                .activeWhen(this::nrdControlsActive));
        nrd.add(intSlider(Component.translatable("caustica.options.rt.nrd.historyStride"),
                CausticaConfig.Rt.Nrd.HISTORY_FIX_STRIDE, 1, 32, value -> String.format("%.0f px", value))
                .activeWhen(this::nrdControlsActive));
        nrd.add(floatSlider(Component.translatable("caustica.options.rt.nrd.prepassRadius"),
                CausticaConfig.Rt.Nrd.PREPASS_BLUR_RADIUS, 0.0, 100.0, value -> String.format("%.1f px", value))
                .activeWhen(this::nrdControlsActive));
        nrd.add(floatSlider(Component.translatable("caustica.options.rt.nrd.minRadius"),
                CausticaConfig.Rt.Nrd.MIN_BLUR_RADIUS, 0.0, 10.0, value -> String.format("%.1f px", value))
                .activeWhen(this::nrdControlsActive));
        nrd.add(floatSlider(Component.translatable("caustica.options.rt.nrd.maxRadius"),
                CausticaConfig.Rt.Nrd.MAX_BLUR_RADIUS, 0.0, 100.0, value -> String.format("%.1f px", value))
                .activeWhen(this::nrdControlsActive));
        nrd.add(floatSlider(Component.translatable("caustica.options.rt.nrd.lobeFraction"),
                CausticaConfig.Rt.Nrd.LOBE_ANGLE_FRACTION, 0.0, 1.0, value -> String.format("%.3f", value))
                .activeWhen(this::nrdControlsActive));
        nrd.add(floatSlider(Component.translatable("caustica.options.rt.nrd.roughnessFraction"),
                CausticaConfig.Rt.Nrd.ROUGHNESS_FRACTION, 0.0, 1.0, value -> String.format("%.3f", value))
                .activeWhen(this::nrdControlsActive));
        nrd.add(floatSlider(Component.translatable("caustica.options.rt.nrd.planeSensitivity"),
                CausticaConfig.Rt.Nrd.PLANE_DISTANCE_SENSITIVITY, 0.001, 0.2, value -> String.format("%.4f", value))
                .activeWhen(this::nrdControlsActive));
        nrd.add(floatSlider(Component.translatable("caustica.options.rt.nrd.disocclusion"),
                CausticaConfig.Rt.Nrd.DISOCCLUSION_THRESHOLD, 0.001, 0.2, value -> String.format("%.4f", value))
                .activeWhen(this::nrdControlsActive));
        nrd.add(floatSlider(Component.translatable("caustica.options.rt.nrd.hitA"),
                CausticaConfig.Rt.Nrd.HIT_DISTANCE_A, 0.01, 100.0, value -> String.format("%.2f", value))
                .activeWhen(this::nrdControlsActive));
        nrd.add(floatSlider(Component.translatable("caustica.options.rt.nrd.hitB"),
                CausticaConfig.Rt.Nrd.HIT_DISTANCE_B, 0.001, 10.0, value -> String.format("%.3f", value))
                .activeWhen(this::nrdControlsActive));
        nrd.add(floatSlider(Component.translatable("caustica.options.rt.nrd.hitC"),
                CausticaConfig.Rt.Nrd.HIT_DISTANCE_C, 1.0, 100.0, value -> String.format("%.1f", value))
                .activeWhen(this::nrdControlsActive));
        nrd.add(floatSlider(Component.translatable("caustica.options.rt.nrd.antilagSigma"),
                CausticaConfig.Rt.Nrd.ANTILAG_SIGMA, 0.1, 10.0, value -> String.format("%.2f", value))
                .activeWhen(() -> nrdControlsActive() && CausticaConfig.Rt.Nrd.ANTILAG.configuredValue()));
        nrd.add(intSlider(Component.translatable("caustica.options.rt.nrd.atrous"),
                CausticaConfig.Rt.Nrd.RELAX_ATROUS_ITERATIONS, 2, 8, value -> String.format("%.0f passes", value))
                .activeWhen(() -> nrdControlsActive()
                        && "relax".equals(CausticaConfig.Rt.Nrd.DENOISER.configuredValue())));
        nrd.add(percentSlider(Component.translatable("caustica.options.rt.nrd.splitScreen"),
                CausticaConfig.Rt.Nrd.SPLIT_SCREEN, 0.0, 100.0, value -> String.format("%.0f%% noisy", value))
                .activeWhen(this::nrdControlsActive));
        addBundle("reconstruction.nrdMode");
        addGrid(nrd.subList(0, 7));
        addBundle("reconstruction.nrdHistory");
        addGrid(nrd.subList(7, 11));
        addBundle("reconstruction.nrdSpatial");
        addGrid(nrd.subList(11, 18));
        addBundle("reconstruction.nrdDiagnostics");
        addGrid(nrd.subList(18, nrd.size()));

        List<AbstractWidget> advanced = new ArrayList<>();
        advanced.add(intSlider(Component.translatable("caustica.options.rt.nrd.stabilizedHistory"),
                CausticaConfig.Rt.Nrd.MAX_STABILIZED_FRAMES, 0, 255, value -> String.format("%.0f frames", value))
                .activeWhen(this::nrdControlsActive));
        advanced.add(floatSlider(Component.translatable("caustica.options.rt.nrd.specularPrepass"),
                CausticaConfig.Rt.Nrd.SPECULAR_PREPASS_BLUR_RADIUS, 0.0, 100.0, value -> String.format("%.1f px", value))
                .activeWhen(this::nrdControlsActive));
        advanced.add(floatSlider(Component.translatable("caustica.options.rt.nrd.fastClampSigma"),
                CausticaConfig.Rt.Nrd.FAST_HISTORY_CLAMP_SIGMA, 1.0, 3.0, value -> String.format("%.2f", value))
                .activeWhen(this::nrdControlsActive));
        advanced.add(floatSlider(Component.translatable("caustica.options.rt.nrd.minHitWeight"),
                CausticaConfig.Rt.Nrd.MIN_HIT_DISTANCE_WEIGHT, 0.001, 0.2, value -> String.format("%.3f", value))
                .activeWhen(this::nrdControlsActive));
        advanced.add(floatSlider(Component.translatable("caustica.options.rt.nrd.fireflyScale"),
                CausticaConfig.Rt.Nrd.FIREFLY_SUPPRESSOR_SCALE, 1.0, 3.0, value -> String.format("%.2f", value))
                .activeWhen(this::nrdControlsActive));
        advanced.add(floatSlider(Component.translatable("caustica.options.rt.nrd.responsiveRoughness"),
                CausticaConfig.Rt.Nrd.RESPONSIVE_ROUGHNESS_THRESHOLD, 0.0, 1.0, value -> String.format("%.3f", value))
                .activeWhen(this::nrdControlsActive));
        advanced.add(intSlider(Component.translatable("caustica.options.rt.nrd.responsiveMinFrames"),
                CausticaConfig.Rt.Nrd.RESPONSIVE_MIN_FRAMES, 0, 3, value -> String.format("%.0f frames", value))
                .activeWhen(this::nrdControlsActive));
        advanced.add(floatSlider(Component.translatable("caustica.options.rt.nrd.convergenceScale"),
                CausticaConfig.Rt.Nrd.CONVERGENCE_SCALE, 0.1, 4.0, value -> String.format("%.2f", value))
                .activeWhen(this::nrdControlsActive));
        advanced.add(floatSlider(Component.translatable("caustica.options.rt.nrd.convergenceBase"),
                CausticaConfig.Rt.Nrd.CONVERGENCE_BASE, 0.0, 1.0, value -> String.format("%.3f", value))
                .activeWhen(this::nrdControlsActive));
        advanced.add(floatSlider(Component.translatable("caustica.options.rt.nrd.convergenceFraction"),
                CausticaConfig.Rt.Nrd.CONVERGENCE_HISTORY_FRACTION, 0.0, 1.0, value -> String.format("%.3f", value))
                .activeWhen(this::nrdControlsActive));
        advanced.add(floatSlider(Component.translatable("caustica.options.rt.nrd.antilagSensitivity"),
                CausticaConfig.Rt.Nrd.ANTILAG_SENSITIVITY, 0.1, 10.0, value -> String.format("%.2f", value))
                .activeWhen(() -> nrdControlsActive() && CausticaConfig.Rt.Nrd.ANTILAG.configuredValue()));
        addBundle("reconstruction.reblurAdvanced");
        addGrid(advanced);

        List<AbstractWidget> relax = new ArrayList<>();
        relax.add(floatSlider(Component.translatable("caustica.options.rt.nrd.relaxNormalPower"),
                CausticaConfig.Rt.Nrd.RELAX_HISTORY_NORMAL_POWER, 0.1, 64.0, value -> String.format("%.2f", value)));
        relax.add(floatSlider(Component.translatable("caustica.options.rt.nrd.relaxDiffusePhi"),
                CausticaConfig.Rt.Nrd.RELAX_DIFFUSE_PHI_LUMINANCE, 0.1, 10.0, value -> String.format("%.2f", value)));
        relax.add(floatSlider(Component.translatable("caustica.options.rt.nrd.relaxSpecularPhi"),
                CausticaConfig.Rt.Nrd.RELAX_SPECULAR_PHI_LUMINANCE, 0.1, 10.0, value -> String.format("%.2f", value)));
        relax.add(floatSlider(Component.translatable("caustica.options.rt.nrd.relaxDepthThreshold"),
                CausticaConfig.Rt.Nrd.RELAX_DEPTH_THRESHOLD, 0.0001, 0.1, value -> String.format("%.4f", value)));
        relax.add(floatSlider(Component.translatable("caustica.options.rt.nrd.relaxVarianceBoost"),
                CausticaConfig.Rt.Nrd.RELAX_SPECULAR_VARIANCE_BOOST, 0.0, 4.0, value -> String.format("%.2f", value)));
        relax.add(floatSlider(Component.translatable("caustica.options.rt.nrd.relaxLobeSlack"),
                CausticaConfig.Rt.Nrd.RELAX_SPECULAR_LOBE_SLACK, 0.0, 1.0, value -> String.format("%.3f", value)));
        relax.add(toggle(Component.translatable("caustica.options.rt.nrd.relaxRoughnessEdges"),
                CausticaConfig.Rt.Nrd.RELAX_ROUGHNESS_EDGE_STOPPING));
        relax.add(floatSlider(Component.translatable("caustica.options.rt.nrd.relaxAntilagAcceleration"),
                CausticaConfig.Rt.Nrd.RELAX_ANTILAG_ACCELERATION, 0.0, 1.0, value -> String.format("%.3f", value)));
        relax.add(floatSlider(Component.translatable("caustica.options.rt.nrd.relaxAntilagTemporal"),
                CausticaConfig.Rt.Nrd.RELAX_ANTILAG_TEMPORAL_SIGMA, 0.01, 10.0, value -> String.format("%.2f", value)));
        relax.add(floatSlider(Component.translatable("caustica.options.rt.nrd.relaxAntilagReset"),
                CausticaConfig.Rt.Nrd.RELAX_ANTILAG_RESET, 0.0, 1.0, value -> String.format("%.3f", value)));
        addBundle("reconstruction.relaxAdvanced");
        addGrid(relax);
    }

    private boolean dlssControlsActive() {
        String backend = CausticaConfig.Rt.Reconstruction.BACKEND.configuredValue();
        return CausticaConfig.Rt.DlssRr.ENABLED.configuredValue()
                && ("dlss-rr".equals(backend)
                || ("auto".equals(backend) && RtReconstruction.usesDlss()));
    }

    private boolean highQualityTransparencyControlsActive() {
        return dlssControlsActive() && RtReconstruction.usesDlss() && RtReconstruction.enabled();
    }

    private boolean nrdControlsActive() {
        String backend = CausticaConfig.Rt.Reconstruction.BACKEND.configuredValue();
        return "nrd".equals(backend)
                || ("auto".equals(backend) && RtReconstruction.usesNrd());
    }

    private void addLighting() {
        addHeader("lighting");
        List<AbstractWidget> controls = new ArrayList<>();
        controls.add(floatSlider(Component.translatable("caustica.options.rt.sunlightIntensity"),
                CausticaConfig.Rt.Composite.SUNLIGHT_INTENSITY_EV, -4.0, 4.0,
                value -> String.format(Locale.ROOT, "%+.1f EV", value)));
        controls.add(floatSlider(Component.translatable("caustica.options.rt.moonlightIntensity"),
                CausticaConfig.Rt.Composite.MOONLIGHT_INTENSITY_EV, -4.0, 8.0,
                value -> String.format(Locale.ROOT, "%+.1f EV", value)));
        controls.add(new Slider(180, Component.translatable("caustica.options.rt.torchIntensity"),
                () -> CausticaConfig.Rt.Composite.TORCH_EMISSION_MULTIPLIER.configuredValue() * 2000.0,
                value -> CausticaConfig.Rt.Composite.TORCH_EMISSION_MULTIPLIER.set((float)(value / 2000.0)),
                unit -> RtVideoOptions.torchMultiplierFromSlider((int)Math.round(unit * 100.0)) * 2000.0,
                value -> RtVideoOptions.torchSliderFromMultiplier((float)(value / 2000.0)) / 100.0,
                value -> String.format(Locale.ROOT, "%.0f cd/m²", value))
                .tooltip(Component.translatable("caustica.options.rt.torchIntensity.tooltip"))
                .resetOnShift(() -> CausticaConfig.Rt.Composite.TORCH_EMISSION_MULTIPLIER.set(
                        CausticaConfig.Rt.Composite.TORCH_EMISSION_MULTIPLIER.defaultValue())));
        addBundle("lighting.energy");
        addGrid(controls.subList(0, 2));
        addBundle("lighting.emissive");
        addGrid(controls.subList(2, 3));

        List<AbstractWidget> emitterSampling = new ArrayList<>();
        emitterSampling.add(risCandidatesSlider(CausticaConfig.Rt.Lights.RIS_CANDIDATES));
        emitterSampling.add(floatSlider(Component.translatable("caustica.options.rt.lightMinFillRatio"),
                CausticaConfig.Rt.Lights.MIN_FILL_RATIO, 0.0, 1.0,
                value -> String.format(Locale.ROOT, "%.0f%%", value * 100.0))
                .onRelease(() -> {
                    RtTerrain.requestFullClear();
                    RtComposite.INSTANCE.requestTemporalReset();
                }).tooltip(Component.translatable("caustica.options.rt.lightMinFillRatio.tooltip")));
        emitterSampling.add(toggle(Component.translatable("caustica.options.rt.lightStats"),
                CausticaConfig.Rt.Lights.STATS)
                .tooltip(Component.translatable("caustica.options.rt.lightStats.tooltip")));
        emitterSampling.add(toggle(Component.translatable("caustica.options.rt.lightDump"),
                CausticaConfig.Rt.Lights.DUMP)
                .tooltip(Component.translatable("caustica.options.rt.lightDump.tooltip")));
        emitterSampling.add(intSlider(Component.translatable("caustica.options.rt.lightDumpRadius"),
                CausticaConfig.Rt.Lights.DUMP_RADIUS, 1, 64,
                value -> String.format(Locale.ROOT, "%.0f blocks", value))
                .tooltip(Component.translatable("caustica.options.rt.lightDumpRadius.tooltip")));
        addBundle("lighting.emitterSampling");
        addGrid(emitterSampling);
    }

    private void addSky() {
        addHeader("sky");
        List<AbstractWidget> atmosphere = new ArrayList<>();
        atmosphere.add(skySlider("rayleigh", CausticaConfig.Rt.Composite.SKY_RAYLEIGH, 0.02, 4.0));
        atmosphere.add(skySlider("dayRayleigh", CausticaConfig.Rt.Composite.SKY_DAY_RAYLEIGH, 0.02, 4.0));
        atmosphere.add(skySlider("aerosolScatter", CausticaConfig.Rt.Composite.SKY_AEROSOL_SCATTER, 0.0, 4.0));
        atmosphere.add(skySlider("aerosolAbsorption", CausticaConfig.Rt.Composite.SKY_AEROSOL_ABSORPTION, 0.0, 4.0));
        atmosphere.add(skySlider("ozone", CausticaConfig.Rt.Composite.SKY_OZONE, 0.0, 4.0));
        atmosphere.add(skySlider("aerosolHeight", CausticaConfig.Rt.Composite.SKY_AEROSOL_HEIGHT_KM, 0.1, 4.0));
        atmosphere.add(skySlider("aerosolAnisotropy", CausticaConfig.Rt.Composite.SKY_AEROSOL_ANISOTROPY, 0.0, 0.95));
        atmosphere.add(skyEvSlider("brightness", CausticaConfig.Rt.Composite.SKY_BRIGHTNESS_EV, -4.0, 4.0));
        atmosphere.add(skySlider("saturation", CausticaConfig.Rt.Composite.SKY_SATURATION, 0.0, 2.0));
        addRgb(atmosphere, "tint", CausticaConfig.Rt.Composite.SKY_TINT_R,
                CausticaConfig.Rt.Composite.SKY_TINT_G, CausticaConfig.Rt.Composite.SKY_TINT_B);
        atmosphere.add(floatSlider(Component.translatable("caustica.options.rt.ambientLight"),
                CausticaConfig.Rt.Composite.AMBIENT_LIGHT_EV, -8.0, 8.0,
                value -> String.format(Locale.ROOT, "%+.1f EV", value))
                .tooltip(Component.translatable("caustica.options.rt.ambientLight.tooltip")));
        addBundle("sky.atmosphere");
        addGrid(atmosphere);

        List<AbstractWidget> celestial = new ArrayList<>();
        celestial.add(celestialSize("sunSize", CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS));
        celestial.add(celestialSize("moonSize", CausticaConfig.Rt.Composite.MOON_ANGULAR_RADIUS));
        celestial.add(skyEvSlider("sunDiscBrightness", CausticaConfig.Rt.Composite.SUN_DISC_BRIGHTNESS_EV, -4.0, 4.0));
        celestial.add(skyEvSlider("moonDiscBrightness", CausticaConfig.Rt.Composite.MOON_DISC_BRIGHTNESS_EV, -4.0, 4.0));
        celestial.add(skySlider("sunLimbDarkening", CausticaConfig.Rt.Composite.SUN_LIMB_DARKENING, 0.0, 1.0));
        addRgb(celestial, "sunTint", CausticaConfig.Rt.Composite.SUN_TINT_R,
                CausticaConfig.Rt.Composite.SUN_TINT_G, CausticaConfig.Rt.Composite.SUN_TINT_B);
        addRgb(celestial, "moonTint", CausticaConfig.Rt.Composite.MOON_TINT_R,
                CausticaConfig.Rt.Composite.MOON_TINT_G, CausticaConfig.Rt.Composite.MOON_TINT_B);
        addBundle("sky.celestial");
        addGrid(celestial);

        List<AbstractWidget> stars = new ArrayList<>();
        stars.add(skyEvSlider("starBrightness", CausticaConfig.Rt.Composite.STAR_BRIGHTNESS_EV, -8.0, 8.0));
        stars.add(skySlider("starDensity", CausticaConfig.Rt.Composite.STAR_DENSITY, 0.0, 2.0));
        stars.add(skySlider("starSize", CausticaConfig.Rt.Composite.STAR_SIZE, 0.25, 4.0));
        addRgb(stars, "starTint", CausticaConfig.Rt.Composite.STAR_TINT_R,
                CausticaConfig.Rt.Composite.STAR_TINT_G, CausticaConfig.Rt.Composite.STAR_TINT_B);
        addBundle("sky.stars");
        addGrid(stars);

        List<AbstractWidget> airglow = new ArrayList<>();
        airglow.add(skyEvSlider("nightAirglow", CausticaConfig.Rt.Composite.NIGHT_AIRGLOW_EV, -8.0, 8.0));
        addRgb(airglow, "airglowHorizon", CausticaConfig.Rt.Composite.AIRGLOW_HORIZON_R,
                CausticaConfig.Rt.Composite.AIRGLOW_HORIZON_G, CausticaConfig.Rt.Composite.AIRGLOW_HORIZON_B);
        addRgb(airglow, "airglowZenith", CausticaConfig.Rt.Composite.AIRGLOW_ZENITH_R,
                CausticaConfig.Rt.Composite.AIRGLOW_ZENITH_G, CausticaConfig.Rt.Composite.AIRGLOW_ZENITH_B);
        addBundle("sky.airglow");
        addGrid(airglow);
    }

    private void addGeometry() {
        addHeader("geometry");
        List<AbstractWidget> controls = new ArrayList<>();
        controls.add(toggle(Component.translatable("caustica.options.enabled"), CausticaConfig.Rt.ENABLED));
        controls.add(intSlider(Component.translatable("caustica.options.rt.spp"), CausticaConfig.Rt.Composite.SPP,
                1, 8, value -> String.format(Locale.ROOT, "%.0f spp", value)));
        controls.add(intSlider(Component.translatable("caustica.options.rt.maxBounces"),
                CausticaConfig.Rt.Composite.MAX_BOUNCES, 2, 8,
                value -> String.format(Locale.ROOT, "%.0f bounces", value)));
        controls.add(intSlider(Component.translatable("caustica.options.rt.celestialLightBounces"),
                CausticaConfig.Rt.Composite.CELESTIAL_LIGHT_BOUNCES, 0, 8,
                value -> String.format(Locale.ROOT, "%.0f bounces", value))
                .tooltip(Component.translatable("caustica.options.rt.celestialLightBounces.tooltip")));
        controls.add(toggle(Component.translatable("caustica.options.rt.entities"), CausticaConfig.Rt.Entities.ENABLED));
        controls.add(toggle(Component.translatable("caustica.options.rt.particles"),
                CausticaConfig.Rt.Entities.PARTICLES_ENABLED));
        controls.add(toggle(Component.translatable("caustica.options.rt.waterWaves"),
                CausticaConfig.Rt.Composite.WATER_WAVES));
        controls.add(intSlider(Component.translatable("caustica.options.rt.psrMirrorDepth"),
                CausticaConfig.Rt.Composite.PSR_MAX_MIRRORS, 1, 32,
                value -> String.format(Locale.ROOT, "%.0f", value)));
        controls.add(new Dropdown<>(180, Component.translatable("caustica.options.rt.pointSampleMaxSize"),
                List.of(0, 16, 32, 64, 128, 256, 512, 1024, 2048),
                CausticaConfig.Rt.Composite.POINT_SAMPLE_MAX_SIZE::configuredValue,
                CausticaConfig.Rt.Composite.POINT_SAMPLE_MAX_SIZE::set,
                value -> value == 0 ? Component.translatable("options.off")
                        : Component.literal(value + " px"), () -> {
                            RtComposite.INSTANCE.requestTemporalReset();
                            RtComposite.INSTANCE.requestSharcReset("texture filtering changed");
                        })
                .tooltip(Component.translatable("caustica.options.rt.pointSampleMaxSize.tooltip"))
                .resetOnShift(() -> CausticaConfig.Rt.Composite.POINT_SAMPLE_MAX_SIZE.set(
                        CausticaConfig.Rt.Composite.POINT_SAMPLE_MAX_SIZE.defaultValue())));
        addBundle("geometry.pathTracer");
        addGrid(controls.subList(0, 4));
        addBundle("geometry.scene");
        addGrid(controls.subList(4, 6));
        addBundle("geometry.surface");
        addGrid(controls.subList(6, 9));
    }

    private void addSharc() {
        addHeader("sharc");
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
        addBundle("sharc.foundation");
        addGrid(controls.subList(0, 4));
        addBundle("sharc.cadence");
        addGrid(controls.subList(4, 9));
        addBundle("sharc.transport");
        addGrid(controls.subList(9, 13));
        addBundle("sharc.telemetry");
        addGrid(controls.subList(13, 14));

        addInfo(this::sharcStatusText);
        addGrid(List.of(
                new ActionButton(180, () -> Component.translatable("caustica.options.rt.sharcReset"),
                        () -> RtComposite.INSTANCE.requestSharcReset("manual menu reset"), false),
                new ActionButton(180, () -> Component.translatable("caustica.options.rt.sharcRestoreParity"),
                        this::restoreSharcParityDefaults, true)
                        .tooltip(Component.translatable("caustica.options.rt.sharcRestoreParity.tooltip"))));
    }

    private void addMaterials() {
        addHeader("materials");
        List<AbstractWidget> controls = new ArrayList<>();
        controls.add(materialSlider("foliageBacklighting", CausticaConfig.Rt.Materials.FOLIAGE_BACKLIGHTING,
                0.0, 1.0));
        controls.add(materialSlider("soilRoughness", CausticaConfig.Rt.Materials.SOIL_ROUGHNESS,
                0.55, 1.0));
        controls.add(materialSlider("stoneRoughness", CausticaConfig.Rt.Materials.STONE_ROUGHNESS,
                0.45, 1.0));
        controls.add(materialSlider("woodRoughness", CausticaConfig.Rt.Materials.WOOD_ROUGHNESS,
                0.4, 1.0));
        controls.add(materialSlider("metalRoughness", CausticaConfig.Rt.Materials.METAL_ROUGHNESS,
                0.0, 0.7));
        controls.add(materialSlider("glassRoughness", CausticaConfig.Rt.Materials.GLASS_ROUGHNESS,
                0.02, 0.5));
        controls.add(materialSlider("woolFiberSheen", CausticaConfig.Rt.Materials.WOOL_FIBER_SHEEN,
                0.0, 0.5));
        controls.add(materialSlider("polishedRoughness", CausticaConfig.Rt.Materials.POLISHED_ROUGHNESS,
                0.15, 0.85));
        addBundle("materials.organic");
        addGrid(controls.subList(0, 4));
        addBundle("materials.finished");
        addGrid(controls.subList(4, controls.size()));
    }

    private void addExposure() {
        addHeader("exposure");
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
        controls.add(percentSlider(Component.translatable("caustica.options.rt.exposureLowPercentile"),
                CausticaConfig.Rt.Exposure.LOW_PERCENTILE, 0.0, 50.0,
                value -> String.format(Locale.ROOT, "%.0f%%", value))
                .tooltip(Component.translatable("caustica.options.rt.exposureLowPercentile.tooltip"))
                .activeWhen(() -> "auto".equals(CausticaConfig.Rt.Exposure.MODE.configuredValue())));
        controls.add(percentSlider(Component.translatable("caustica.options.rt.exposureHighPercentile"),
                CausticaConfig.Rt.Exposure.HIGH_PERCENTILE, 50.0, 100.0,
                value -> String.format(Locale.ROOT, "%.0f%%", value))
                .tooltip(Component.translatable("caustica.options.rt.exposureHighPercentile.tooltip"))
                .activeWhen(() -> "auto".equals(CausticaConfig.Rt.Exposure.MODE.configuredValue())));
        controls.add(percentSlider(Component.translatable("caustica.options.rt.exposureHighlightPercentile"),
                CausticaConfig.Rt.Exposure.HIGHLIGHT_PERCENTILE, 95.0, 100.0,
                value -> String.format(Locale.ROOT, "%.2f%%", value))
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
        controls.add(floatSlider(Component.translatable("caustica.options.rt.hdrUiBrightness"),
                CausticaConfig.Rt.Hdr.UI_BRIGHTNESS_NITS, 40, 1000,
                value -> String.format(Locale.ROOT, "%.0f nits", value))
                .tooltip(Component.translatable("caustica.options.rt.hdrUiBrightness.tooltip"))
                .activeWhen(CausticaConfig.Rt.Hdr.ENABLED::configuredValue));
        controls.add(floatSlider(Component.translatable("caustica.options.rt.hdrPeak"),
                CausticaConfig.Rt.Hdr.PEAK_NITS, 80, 10000,
                value -> String.format(Locale.ROOT, "%.0f nits", value))
                .activeWhen(CausticaConfig.Rt.Hdr.ENABLED::configuredValue));
        addBundle("exposure.intent");
        addGrid(controls.subList(0, 4));
        addBundle("exposure.metering");
        addGrid(List.of(controls.get(4), controls.get(5), controls.get(6), controls.get(7), controls.get(12)));
        addBundle("exposure.adaptation");
        addGrid(controls.subList(8, 12));
        addBundle("exposure.histogram");
        addGrid(controls.subList(13, 15));
        addBundle("exposure.curves");
        addGrid(controls.subList(15, 20));

        addInfo(() -> Component.translatable("caustica.options.info.exposure",
                String.format(Locale.ROOT, "%+.2f", RtComposite.INSTANCE.exposureActualEv()),
                String.format(Locale.ROOT, "%+.2f", RtComposite.INSTANCE.exposureTargetEv()),
                String.format(Locale.ROOT, "%.0f", RtComposite.INSTANCE.exposureConfidence() * 100.0f),
                String.format(Locale.ROOT, "%.1f", RtComposite.INSTANCE.exposureTrustedCoverage() * 100.0f),
                String.format(Locale.ROOT, "%+.1f", RtComposite.INSTANCE.exposureActiveCeilingEv())));
        addInfo(() -> Component.translatable("caustica.options.info.activeCurve", activeToneLabel()));
        List<AbstractWidget> active = toneWidgets(activeToneControls());
        if (!active.isEmpty()) {
            addBundle("exposure.activeCurve");
            addGrid(active);
        }
    }

    private void addView() {
        addHeader("view");
        List<AbstractWidget> controls = new ArrayList<>();
        controls.add(new Toggle(180, Component.translatable("caustica.options.view.dynamicFov"),
                () -> options.fovEffectScale().get() > 0.0,
                enabled -> options.fovEffectScale().set(enabled ? 1.0 : 0.0))
                .tooltip(Component.translatable("caustica.options.view.dynamicFov.tooltip"))
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
        addBundle("view.comfort");
        addGrid(controls.subList(0, 1));
        addBundle("view.firstPerson");
        addGrid(controls.subList(1, 3));
        addBundle("view.placement");
        addGrid(controls.subList(3, 6));
    }

    private void addDiagnostics() {
        addHeader("diagnostics");
        List<AbstractWidget> controls = new ArrayList<>();
        controls.add(toggle(Component.translatable("caustica.options.frameStats"),
                CausticaConfig.Rt.FrameStats.ENABLED));
        controls.add(new Dropdown<>(180, Component.translatable("caustica.options.rt.debugView"),
                IntStream.rangeClosed(0, CausticaConfig.Rt.Composite.DEBUG_VIEW_TONEMAP_COMPARISON).boxed().toList(),
                CausticaConfig.Rt.Composite.DEBUG_VIEW::configuredValue, CausticaConfig.Rt.Composite.DEBUG_VIEW::set,
                value -> Component.translatable("caustica.options.rt.debugView." + value), null));
        controls.add(new ActionButton(180,
                () -> Component.translatable("caustica.options.diagnostics.frameGeneration"),
                () -> minecraft.setScreenAndShow(new RtFrameGenerationDiagnosticsScreen(this, options)), false));
        addBundle("diagnostics.telemetry");
        addGrid(controls.subList(0, 1));
        addBundle("diagnostics.visual");
        addGrid(controls.subList(1, 3));
        addInfo(() -> Component.translatable("caustica.options.info.offlineRenderer"));
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

    private void addHeader(String id) {
        String key = "caustica.options.header." + id;
        addHeader(Component.translatable(key), Component.translatable(key + ".subtitle"));
    }

    private void addHeader(Component title, Component subtitle) {
        activeBundle = null;
        searchContext = title.getString() + ' ' + subtitle.getString();
        if (collectingSearchResults) return;
        body.addChild(new SectionHeader(contentWidth, title, subtitle));
    }

    private void addBundle(String id) {
        String key = "caustica.options.bundle." + id;
        Component title = Component.translatable(key);
        Component purpose = Component.translatable(key + ".description");
        searchContext = title.getString() + ' ' + purpose.getString();
        if (collectingSearchResults) return;
        String stateId = "bundle." + id;
        CollapsibleLayout bundle = new CollapsibleLayout(contentWidth, title,
                () -> CausticaTreeState.INSTANCE.isCollapsed(stateId), () -> {
                    boolean next = !CausticaTreeState.INSTANCE.isCollapsed(stateId);
                    CausticaTreeState.INSTANCE.setCollapsed(stateId, next);
                    repositionElements();
                });
        body.addChild(bundle);
        activeBundle = bundle;
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
        if (controls.isEmpty()) {
            activeBundle = null;
            return;
        }
        WidgetGridLayout grid = new WidgetGridLayout(contentWidth, columnsFor(controls), GRID_GAP, GRID_GAP,
                CONTROL_HEIGHT, controls);
        if (activeBundle != null) {
            activeBundle.setContent(grid);
            activeBundle = null;
        } else {
            body.addChild(grid);
        }
    }

    private int columnsFor(List<? extends AbstractWidget> controls) {
        int requiredWidth = TARGET_CELL_WIDTH;
        for (AbstractWidget control : controls) {
            int padding = control instanceof Dropdown<?> ? 34 : 18;
            requiredWidth = Math.max(requiredWidth, font.width(control.getMessage()) + padding);
        }
        requiredWidth = Math.min(contentWidth, requiredWidth);
        return Math.clamp((contentWidth + GRID_GAP) / (requiredWidth + GRID_GAP), 1, gridColumns);
    }

    private int computeRailWidth() {
        int widest = font.width("Search...");
        for (Category value : Category.values()) widest = Math.max(widest, font.width(value.label()));
        List<CausticaMenuUsage.Item> recent = CausticaMenuUsage.INSTANCE.recent(8);
        List<CausticaMenuUsage.Item> frequent = CausticaMenuUsage.INSTANCE.frequent(8, recent);
        for (CausticaMenuUsage.Item item : recent) widest = Math.max(widest, font.width(item.label()));
        for (CausticaMenuUsage.Item item : frequent) widest = Math.max(widest, font.width(item.label()));
        int available = Math.max(104, Math.min(MAX_RAIL_WIDTH, width / 4));
        return Math.min(available, Math.max(MIN_RAIL_WIDTH, widest + 32));
    }

    static <T> List<T> ordered(List<T> items, int... order) {
        List<T> result = new ArrayList<>(items.size());
        boolean[] selected = new boolean[items.size()];
        for (int index : order) {
            if (index < 0 || index >= items.size() || selected[index]) continue;
            result.add(items.get(index));
            selected[index] = true;
        }
        for (int index = 0; index < items.size(); index++) {
            if (!selected[index]) result.add(items.get(index));
        }
        return List.copyOf(result);
    }

    private void registerControls(List<? extends AbstractWidget> controls) {
        for (AbstractWidget control : controls) {
            boolean trackable = control instanceof LabeledControl || toneResets.containsKey(control);
            String label = trackable ? controlLabel(control) : null;
            String id = trackable ? CausticaMenuUsage.normalize(label) : null;
            if (indexingControls) {
                if (trackable) controlCategories.putIfAbsent(id, buildingCategory);
                continue;
            }
            if (control instanceof Dropdown<?> dropdown) dropdowns.add(dropdown);
            if (!trackable) continue;
            usageLabels.put(control, label);
            usageCategories.put(control, buildingCategory);
            controlCategories.putIfAbsent(id, buildingCategory);
            if (pendingTargetId != null && pendingTargetId.equals(id) && buildingCategory == category) {
                targetWidget = control;
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
        activeBundle = null;
        if (!collectingSearchResults) body.addChild(new InfoStrip(contentWidth, text));
    }

    private Toggle toggle(Component label, BooleanSetting setting) {
        return new Toggle(180, label, setting::configuredValue, setting::set)
                .resetOnShift(() -> setting.set(setting.defaultValue()));
    }

    private Toggle liveToggle(Component label, BooleanSetting setting, Runnable changed) {
        java.util.function.Consumer<Boolean> apply = value -> {
            setting.set(value);
            changed.run();
        };
        return new Toggle(180, label, setting::configuredValue, apply)
                .resetOnShift(() -> apply.accept(setting.defaultValue()));
    }

    private Slider intSlider(Component label, IntSetting setting, int min, int max, DoubleFunction<String> format) {
        return new Slider(180, label, setting::configuredValue, value -> setting.set((int)Math.round(value)),
                unit -> min + unit * (max - min), value -> (value - min) / (max - min), format)
                .resetOnShift(() -> setting.set(setting.defaultValue()));
    }

    private static Component dlssQualityLabel(int quality) {
        Component name = Component.translatable("caustica.options.rt.dlssQuality." + quality);
        if (quality == RtResolutionScale.CUSTOM_QUALITY) return name;
        return name.copy().append(String.format(Locale.ROOT, " (%.2fx)",
                RtResolutionScale.presetUpscaleRatio(quality)));
    }

    private Slider risCandidatesSlider(IntSetting setting) {
        int[] publishedValue = {setting.value()};
        return intSlider(Component.translatable("caustica.options.rt.risCandidates"), setting, 0, 32,
                value -> value == 0.0 ? "Off" : String.format(Locale.ROOT, "%.0f candidates", value))
                .tooltip(Component.translatable("caustica.options.rt.risCandidates.tooltip"))
                .onRelease(() -> {
                    int current = setting.value();
                    if (RtTerrain.requiresLightListRebuild(publishedValue[0], current)) {
                        RtTerrain.requestFullClear();
                        RtComposite.INSTANCE.requestTemporalReset();
                    }
                    publishedValue[0] = current;
                });
    }

    private static void selectDlssQuality(int quality) {
        RtResolutionScale.selectQuality(quality);
        RtComposite.INSTANCE.requestResolutionScaleCommit();
    }

    private static void selectDlssPreset(int preset) {
        RtDlssRr.selectPreset(preset);
        RtComposite.INSTANCE.requestResolutionScaleCommit();
    }

    private Slider floatSlider(Component label, FloatSetting setting, double min, double max,
                               DoubleFunction<String> format) {
        return new Slider(180, label, setting::configuredValue, value -> setting.set((float)value),
                unit -> min + unit * (max - min), value -> (value - min) / (max - min), format)
                .resetOnShift(() -> setting.set(setting.defaultValue()));
    }

    /** Slider domain is the displayed percentage; the config stores the normalized fraction. */
    private Slider percentSlider(Component label, FloatSetting setting, double minPercent, double maxPercent,
                                 DoubleFunction<String> format) {
        return new Slider(180, label,
                () -> setting.configuredValue() * 100.0,
                value -> setting.set((float)(value / 100.0)),
                unit -> minPercent + unit * (maxPercent - minPercent),
                value -> (value - minPercent) / (maxPercent - minPercent),
                format)
                .resetOnShift(() -> setting.set(setting.defaultValue()));
    }

    private Slider skySlider(String id, FloatSetting setting, double min, double max) {
        String key = "caustica.options.rt.sky." + id;
        return floatSlider(Component.translatable(key), setting, min, max,
                value -> String.format(Locale.ROOT, "%.2f", value))
                .tooltip(Component.translatable(key + ".tooltip"));
    }

    private Slider skyEvSlider(String id, FloatSetting setting, double min, double max) {
        String key = "caustica.options.rt.sky." + id;
        return floatSlider(Component.translatable(key), setting, min, max,
                value -> String.format(Locale.ROOT, "%+.1f EV", value))
                .tooltip(Component.translatable(key + ".tooltip"));
    }

    private void addRgb(List<AbstractWidget> controls, String id,
                        FloatSetting red, FloatSetting green, FloatSetting blue) {
        controls.add(skySlider(id + "R", red, 0.0, 2.0));
        controls.add(skySlider(id + "G", green, 0.0, 2.0));
        controls.add(skySlider(id + "B", blue, 0.0, 2.0));
    }

    private Slider celestialSize(String id, FloatSetting setting) {
        String key = "caustica.options.rt." + id;
        return new Slider(180, Component.translatable(key),
                () -> Math.toDegrees(setting.configuredValue()),
                value -> setting.set((float) value),
                unit -> 0.1 + unit * 4.9, value -> (value - 0.1) / 4.9,
                value -> String.format(Locale.ROOT, "%.1f deg", value))
                .tooltip(Component.translatable(key + ".tooltip"))
                .resetOnShift(() -> setting.set(setting.defaultValue()));
    }

    private Slider materialSlider(String id, FloatSetting setting, double min, double max) {
        String key = "caustica.options.rt.materials." + id;
        return floatSlider(Component.translatable(key), setting, min, max,
                value -> String.format(Locale.ROOT, "%.2f", value))
                .tooltip(Component.translatable(key + ".tooltip"))
                .onRelease(RtComposite.INSTANCE::requestMaterialParameterRefresh);
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
        String state = RtComposite.INSTANCE.sharcStatus();
        return Component.translatable("caustica.options.info.sharcStatus", state);
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
        rememberMenuPosition();
        rebuildScreen();
    }

    private void rebuildScreen() {
        clearWidgets();
        init();
    }

    private void rememberMenuPosition() {
        if (bodyScrollArea != null && searchQuery.isBlank()) {
            CausticaMenuUsage.INSTANCE.setMenuPosition(category.name(), bodyScrollArea.scrollAmount());
        }
    }

    private void restorePositionOrRevealTarget(int bodyTop) {
        if (bodyScrollArea == null) return;
        if (targetWidget != null) {
            double targetScroll = Math.max(0.0, targetWidget.getY() - bodyTop - CONTROL_HEIGHT);
            bodyScrollArea.setScrollAmount(targetScroll);
            flashWidget = targetWidget;
            flashUntilMillis = System.currentTimeMillis() + TARGET_FLASH_MILLIS;
            pendingTargetId = null;
        } else if (searchQuery.isBlank()) {
            bodyScrollArea.setScrollAmount(CausticaMenuUsage.INSTANCE.scrollPosition(category.name()));
        }
    }

    protected void repositionElements() {
        if (bodyScroll != null) bodyScroll.arrangeElements();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int paneLeft = MARGIN + railWidth + 8;
        int paneRight = paneLeft + contentWidth;
        int railBottom = height - FOOTER_HEIGHT - MARGIN;
        g.fill(MARGIN + railWidth + 4, MARGIN, MARGIN + railWidth + 5,
                railBottom, 0x70FFFFFF);
        g.fill(MARGIN, MARGIN + HEADER_HEIGHT, paneRight,
                MARGIN + HEADER_HEIGHT + 1, 0x50FFFFFF);
        g.text(font, searchQuery.isBlank() ? category.label() : Component.translatable("caustica.options.search.results"),
                paneLeft + 2, MARGIN + 5, CausticaWidgets.ACCENT);
        for (QuickHeading heading : quickHeadings) {
            g.text(font, Component.literal(heading.label()), MARGIN + 2, heading.y(), CausticaWidgets.MUTED);
            int lineLeft = MARGIN + 8 + font.width(heading.label());
            int lineRight = MARGIN + railWidth - 4;
            if (lineLeft < lineRight) g.fill(lineLeft, heading.y() + 4, lineRight, heading.y() + 5, 0x40777777);
        }
        g.fill(paneLeft, height - FOOTER_HEIGHT - MARGIN, paneRight,
                height - FOOTER_HEIGHT - MARGIN + 1, 0x50FFFFFF);
        g.text(font, Component.translatable("caustica.options.info.shiftReset"),
                paneLeft + 2, height - FOOTER_HEIGHT + 3, CausticaWidgets.MUTED);
        extractTargetFlash(g);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        if (selectedCategoryButton != null) {
            g.fill(selectedCategoryButton.getX(), selectedCategoryButton.getY(),
                    selectedCategoryButton.getX() + 2, selectedCategoryButton.getBottom(),
                    CausticaWidgets.ACCENT);
        }
        for (Dropdown<?> dropdown : dropdowns) {
            dropdown.extractOverlay(g, mouseX, mouseY, height);
        }
    }

    private void extractTargetFlash(GuiGraphicsExtractor g) {
        if (flashWidget == null) return;
        long remaining = flashUntilMillis - System.currentTimeMillis();
        if (remaining <= 0L) {
            flashWidget = null;
            return;
        }
        int alpha = ((remaining / 140L) & 1L) == 0L ? 0xE0 : 0x70;
        int color = (alpha << 24) | (CausticaWidgets.ACCENT & 0x00FFFFFF);
        int left = flashWidget.getX() - 2;
        int top = flashWidget.getY() - 2;
        int right = flashWidget.getRight() + 2;
        int bottom = flashWidget.getBottom() + 2;
        g.fill(left, top, right, top + 2, color);
        g.fill(left, bottom - 2, right, bottom, color);
        g.fill(left, top + 2, left + 2, bottom - 2, color);
        g.fill(right - 2, top + 2, right, bottom - 2, color);
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
        for (Dropdown<?> dropdown : dropdowns) {
            if (dropdown.keyPressed(input.key())) return true;
        }
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
                rebuildScreen();
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
        rememberMenuPosition();
        saved = true;
        CausticaMenuUsage.INSTANCE.save();
        CausticaTreeState.INSTANCE.save();
        CausticaConfig.save();
        options.save();
    }

    private void recordControl(AbstractWidget widget) {
        String label = usageLabels.get(widget);
        Category owner = usageCategories.getOrDefault(widget, category);
        if (label != null) CausticaMenuUsage.INSTANCE.record(label, owner.name());
    }

    private record QuickHeading(String label, int y) {
    }
}
