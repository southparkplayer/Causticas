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
import dev.comfyfluffy.caustica.client.ui.SettingsUiMetrics;
import dev.comfyfluffy.caustica.client.ui.SectionColumnsLayout;
import dev.comfyfluffy.caustica.client.ui.HeaderToolbarLayout;
import dev.comfyfluffy.caustica.client.ui.WidgetGridLayout;
import dev.comfyfluffy.caustica.client.settings.SettingsRuntimeStatus;
import dev.comfyfluffy.caustica.client.settings.SettingsCatalog;
import dev.comfyfluffy.caustica.client.settings.SettingsCatalog.Control;
import dev.comfyfluffy.caustica.client.settings.SettingsCatalog.Group;
import dev.comfyfluffy.caustica.client.settings.SettingsCatalog.Page;
import dev.comfyfluffy.caustica.client.settings.SettingsRevealPlanner;
import dev.comfyfluffy.caustica.client.settings.SettingsRevealPlanner.RevealPlan;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtHdr;
import dev.comfyfluffy.caustica.rt.RtResolutionScale;
import dev.comfyfluffy.caustica.rt.RtSharcSupport;
import dev.comfyfluffy.caustica.rt.pipeline.RtReconstruction;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
import dev.comfyfluffy.caustica.streamline.StreamlineSwapchainCoordinator;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.nio.file.Files;
import java.util.function.DoubleFunction;
import java.util.stream.IntStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.AbstractContainerWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.ScrollableLayout.ReserveStrategy;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.lwjgl.glfw.GLFW;

/** Caustica's compact, category-based settings workstation. */
public class CausticaSettingsScreen extends Screen {
    static final int ESSENTIALS_MIN_CELL_WIDTH = 320;
    private static final long TARGET_FLASH_MILLIS = 1800L;
    private static final List<Integer> DLSS_QUALITY_ORDER = List.of(5, 4, 2, 1, 0, 3);
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
    private final SettingsUiState uiState;
    private final boolean legacyStateFallback;
    private final boolean legacyTreeState;
    private ScrollableLayout bodyScroll;
    private AbstractScrollArea bodyScrollArea;
    private ScrollableLayout navigationScroll;
    private AbstractScrollArea navigationScrollArea;
    private AbstractContainerWidget navigationContainer;
    private CategoryLayout body;
    private SectionColumnsLayout sectionColumns;
    private final Map<AbstractWidget, Runnable> toneResets = new IdentityHashMap<>();
    private final Map<AbstractWidget, Runnable> controlResets = new IdentityHashMap<>();
    private final Map<Page, List<Runnable>> pageResets = new java.util.EnumMap<>(Page.class);
    private boolean pageResetAdded;
    private final Map<AbstractWidget, String> usageLabels = new IdentityHashMap<>();
    private final Map<AbstractWidget, Page> usageCategories = new IdentityHashMap<>();
    private final Map<AbstractWidget, String> usageIds = new IdentityHashMap<>();
    private final Map<AbstractWidget, String> boundControlIds = new IdentityHashMap<>();
    private final Map<AbstractWidget, String> anchorIds = new IdentityHashMap<>();
    private final Map<String, AbstractWidget> anchorWidgets = new java.util.HashMap<>();
    private final List<Dropdown<?>> dropdowns = new ArrayList<>();
    private EditBox searchBox;
    private String searchQuery = "";
    private String searchContext = "";
    private boolean saved;
    private int contentWidth;
    private int gridColumns;
    private int railWidth;
    private SettingsUiMetrics metrics;
    private Page category = Page.ESSENTIALS;
    private Page buildingCategory = Page.ESSENTIALS;
    private String pendingTargetId;
    private String pendingRevealReasonKey;
    private AbstractWidget targetWidget;
    private AbstractWidget flashWidget;
    private CollapsibleLayout activeBundle;
    private String activeBundleId;
    private String temporaryExpandedSectionId;
    private AbstractWidget selectedCategoryButton;
    private ActionButton compactNavigationButton;
    private boolean navigationOpen;
    private int navigationLeft;
    private int navigationTop;
    private int navigationWidth;
    private int navigationHeight;
    private int initializedWidth = -1;
    private int initializedHeight = -1;
    private long flashUntilMillis;
    private final boolean initiallyFgRequested = CausticaConfig.Rt.Fg.requested();
    private boolean pendingSwapchainRecreate;

    public CausticaSettingsScreen(Screen previous, Options options) {
        this(previous, options, null);
    }

    public CausticaSettingsScreen(Screen previous, Options options, String initialCategory) {
        super(Component.translatable("caustica.options.title"));
        this.previous = previous;
        this.options = options;
        this.uiState = SettingsUiState.load();
        this.legacyStateFallback = !Files.isRegularFile(uiState.path());
        this.legacyTreeState = legacyStateFallback && CausticaTreeState.INSTANCE.hasPersistedState();
        String requestedCategory = initialCategory != null
                ? initialCategory : legacyStateFallback
                        ? CausticaMenuUsage.INSTANCE.lastCategory() : uiState.lastPageId();
        this.category = Page.parse(requestedCategory);
        if (legacyTreeState) migrateLegacyExpansionState();
    }

    private void migrateLegacyExpansionState() {
        for (Group group : Group.values()) {
            uiState.setGroupExpanded(groupStateId(group),
                    !CausticaTreeState.INSTANCE.isCollapsed(legacyGroupStateId(group)));
        }
        for (SettingsCatalog.Section section : SettingsCatalog.allSections()) {
            uiState.setSectionExpanded(section.id(),
                    !CausticaTreeState.INSTANCE.isCollapsed("bundle." + section.id()));
        }
    }

    @Override
    protected void init() {
        saved = false;
        toneResets.clear();
        controlResets.clear();
        pageResets.clear();
        usageLabels.clear();
        usageCategories.clear();
        usageIds.clear();
        boundControlIds.clear();
        anchorIds.clear();
        anchorWidgets.clear();
        dropdowns.clear();
        bodyScrollArea = null;
        navigationScrollArea = null;
        navigationContainer = null;
        navigationScroll = null;
        navigationOpen = false;
        compactNavigationButton = null;
        targetWidget = null;
        flashWidget = null;
        selectedCategoryButton = null;
        activeBundle = null;
        activeBundleId = null;
        sectionColumns = null;
        metrics = SettingsUiMetrics.calculate(width, height, 0);
        railWidth = metrics.railWidth();
        contentWidth = metrics.contentWidth();
        gridColumns = metrics.principalColumns();
        int paneLeft = metrics.paneLeft();

        int headerY = metrics.margin() + 4;
        int searchHeight = Math.min(24, metrics.headerHeight() - 8);
        int searchLeft = metrics.workspaceLeft();
        int searchWidth = railWidth;
        if (metrics.compact()) {
            int navigationButtonWidth = Math.clamp(metrics.workspaceWidth() / 3, 140, 180);
            compactNavigationButton = new ActionButton(navigationButtonWidth,
                    () -> Component.translatable("caustica.options.navigation.pages", category.label()),
                    () -> setNavigationOpen(true), true)
                    .tooltip(Component.translatable("caustica.options.navigation.open"));
            compactNavigationButton.setRectangle(navigationButtonWidth, searchHeight,
                    metrics.workspaceLeft(), headerY);
            addRenderableWidget(compactNavigationButton);
            searchLeft += navigationButtonWidth + 4;
            searchWidth = Math.max(1, metrics.workspaceRight() - searchLeft);
        }
        searchBox = new EditBox(font, searchLeft, headerY, searchWidth, searchHeight,
                Component.translatable("caustica.options.search"));
        searchBox.setMaxLength(80);
        searchBox.setHint(Component.translatable("caustica.options.search.hint"));
        searchBox.setCanLoseFocus(true);
        searchBox.setValue(searchQuery);
        searchBox.setResponder(this::searchChanged);
        addRenderableWidget(searchBox);
        body = new CategoryLayout(contentWidth, metrics.categoryGap());
        buildingCategory = category;
        pageResetAdded = false;
        if (!searchQuery.isBlank()) {
            pageResetAdded = true;
            addSearchResults();
        } else {
            addCategory(category);
        }
        body.addChild(SpacerElement.height(8));

        int bodyTop = metrics.bodyTop();
        int bodyHeight = metrics.bodyHeight();
        bodyScroll = new ScrollableLayout(minecraft, body, bodyHeight, ReserveStrategy.RIGHT);
        bodyScroll.setScrollbarSpacing(SettingsUiMetrics.SCROLLBAR_SPACING);
        bodyScroll.setMinWidth(contentWidth);
        bodyScroll.setX(paneLeft);
        bodyScroll.setY(bodyTop);
        bodyScroll.arrangeElements();
        bodyScroll.visitWidgets(widget -> {
            if (widget instanceof AbstractScrollArea scrollArea) bodyScrollArea = scrollArea;
            addRenderableWidget(widget);
        });

        buildNavigation();

        ActionButton done = new ActionButton(110, () -> Component.translatable("gui.done"), this::onClose, true);
        int doneWidth = Math.min(120, metrics.paneWidth());
        done.setRectangle(doneWidth, metrics.controlHeight(), metrics.paneRight() - doneWidth,
                height - metrics.margin() - metrics.controlHeight());
        addRenderableWidget(done);
        if (navigationScroll != null) navigationScroll.arrangeElements();
        restorePositionOrRevealTarget(bodyTop);
        setInitialFocus(targetWidget != null ? targetWidget : searchBox);
        initializedWidth = width;
        initializedHeight = height;
    }

    private void buildNavigation() {
        navigationWidth = metrics.compact()
                ? Math.min(260, Math.max(1, metrics.workspaceWidth() - 16)) : railWidth;
        navigationLeft = metrics.compact()
                ? metrics.workspaceLeft() + (metrics.workspaceWidth() - navigationWidth) / 2
                : metrics.workspaceLeft();
        navigationTop = metrics.bodyTop();
        navigationHeight = metrics.bodyHeight();
        int navigationContentWidth = Math.max(1, navigationWidth - SettingsUiMetrics.SCROLLBAR_RESERVE);
        CategoryLayout navigation = new CategoryLayout(navigationContentWidth, 2);

        ActionButton essentials = new ActionButton(navigationContentWidth, Page.ESSENTIALS::label,
                () -> selectCategory(Page.ESSENTIALS), false);
        essentials.setHeight(26);
        navigation.addChild(essentials);
        if (category == Page.ESSENTIALS) selectedCategoryButton = essentials;

        Group currentGroup = null;
        boolean groupExpanded = true;
        for (Page value : Page.values()) {
            if (value == Page.ESSENTIALS) continue;
            if (value.group() != currentGroup) {
                currentGroup = value.group();
                Group group = currentGroup;
                String stateId = groupStateId(group);
                boolean legacyExpanded = !CausticaTreeState.INSTANCE.isCollapsed(legacyGroupStateId(group));
                if (legacyTreeState && !uiState.groupExpansion().containsKey(stateId)) {
                    uiState.setGroupExpanded(stateId, legacyExpanded);
                }
                groupExpanded = groupExpanded(uiState, group, legacyExpanded);
                CollapsibleLayout.TreeHeader heading = new CollapsibleLayout.TreeHeader(navigationContentWidth,
                        group.label(), () -> !groupExpanded(uiState, group, legacyExpanded), () -> {
                            boolean expanded = groupExpanded(uiState, group, legacyExpanded);
                            uiState.setGroupExpanded(stateId, !expanded);
                            rememberMenuPosition();
                            uiState.save();
                            rebuildScreen();
                        });
                navigation.addChild(heading);
            }
            if (!groupExpanded) continue;
            Page target = value;
            ActionButton button = new ActionButton(navigationContentWidth,
                    target::label, () -> selectCategory(target), false);
            button.setHeight(26);
            navigation.addChild(button);
            if (category == target) selectedCategoryButton = button;
        }

        navigationScroll = new ScrollableLayout(minecraft, navigation, navigationHeight, ReserveStrategy.RIGHT);
        navigationScroll.setScrollbarSpacing(SettingsUiMetrics.SCROLLBAR_SPACING);
        navigationScroll.setMinWidth(navigationContentWidth);
        navigationScroll.setX(navigationLeft);
        navigationScroll.setY(navigationTop);
        navigationScroll.arrangeElements();
        navigationScroll.visitWidgets(widget -> {
            if (widget instanceof AbstractScrollArea scrollArea) {
                navigationScrollArea = scrollArea;
                scrollArea.visible = !metrics.compact();
                scrollArea.active = !metrics.compact();
            }
            if (widget instanceof AbstractContainerWidget container) navigationContainer = container;
            addRenderableWidget(widget);
        });
        if (navigationScrollArea != null && selectedCategoryButton != null
                && (selectedCategoryButton.getY() < navigationTop
                || selectedCategoryButton.getBottom() > navigationTop + navigationHeight)) {
            navigationScrollArea.setScrollAmount(selectedCategoryButton.getY() - navigationTop
                    - Math.max(0, navigationHeight - selectedCategoryButton.getHeight()) / 2.0);
        }
    }

    private static String groupStateId(Group group) {
        return "group." + group.routeId();
    }

    private static String legacyGroupStateId(Group group) {
        return "group." + group.name().toLowerCase(Locale.ROOT);
    }

    static boolean groupExpanded(SettingsUiState state, Group group, boolean defaultExpanded) {
        return state.groupExpanded(groupStateId(group), defaultExpanded);
    }

    private void selectCategory(Page target) {
        if (category == target && searchQuery.isBlank()) {
            if (navigationOpen) setNavigationOpen(false);
            return;
        }
        rememberMenuPosition();
        category = target;
        uiState.setLastPage(target);
        uiState.save();
        searchQuery = "";
        temporaryExpandedSectionId = null;
        navigationOpen = false;
        rebuildScreen();
    }

    private void setNavigationOpen(boolean open) {
        if (!metrics.compact() || navigationScrollArea == null) return;
        if (open) dropdowns.forEach(Dropdown::close);
        navigationOpen = open;
        navigationScrollArea.visible = open;
        navigationScrollArea.active = open;
        if (open) {
            setFocused(navigationScrollArea);
            if (selectedCategoryButton != null && navigationContainer != null) {
                navigationContainer.setFocused(selectedCategoryButton);
            }
        } else if (compactNavigationButton != null) {
            setFocused(compactNavigationButton);
        }
    }

    private void addCategory(Page target) {
        buildingCategory = target;
        pageResetAdded = false;
        switch (target) {
            case ESSENTIALS -> addOverview();
            case DISPLAY_HDR -> addDisplayHdr();
            case FRAME_GENERATION -> addFrameGeneration();
            case RECONSTRUCTION -> addReconstruction();
            case LIGHTING -> addLighting();
            case SKY_ATMOSPHERE -> addSky();
            case EXPOSURE_TONEMAP -> addExposure();
            case GEOMETRY_SCENE -> addGeometry();
            case MATERIALS -> addMaterials();
            case SHARC -> addSharc();
            case FIRST_PERSON -> addView();
            case DENOISING -> addDenoising();
            case DIAGNOSTICS -> addDiagnostics();
        }
    }

    private void addOverview() {
        addHeader("essentials.status");
        addInfo(() -> Component.translatable("caustica.options.status.summary",
                SettingsRuntimeStatus.configuredEffective(Component.translatable("caustica.options.enabled"),
                        CausticaConfig.Rt.ENABLED),
                SettingsRuntimeStatus.configuredEffective(Component.translatable("caustica.options.rt.hdr"),
                        CausticaConfig.Rt.Hdr.ENABLED),
                SettingsRuntimeStatus.frameGeneration(), SettingsRuntimeStatus.overrideCount()));

        List<AbstractWidget> controls = new ArrayList<>();
        controls.add(toggle(Component.translatable("caustica.options.enabled"), CausticaConfig.Rt.ENABLED));
        controls.add(intSlider(Component.translatable("caustica.options.rt.spp"),
                CausticaConfig.Rt.Composite.SPP, 1, 8,
                value -> String.format(Locale.ROOT, "%.0f spp", value)));
        controls.add(intSlider(Component.translatable("caustica.options.rt.maxBounces"),
                CausticaConfig.Rt.Composite.MAX_BOUNCES, 2, 64,
                value -> String.format(Locale.ROOT, "%.0f bounces", value)));
        controls.add(reconstructionBackendControl());
        controls.add(toggle(Component.translatable("caustica.options.rt.dlssRr"), CausticaConfig.Rt.DlssRr.ENABLED));
        controls.add(dlssQualityControl());
        controls.add(hdrToggle());
        if (CausticaConfig.Rt.Hdr.ENABLED.configuredValue()) {
            controls.add(hdrTonemapper());
            controls.addAll(hdrBrightnessControls());
        }
        controls.add(fgModeControl());
        controls.add(fgMultiplierControl());
        controls.add(torchIntensityControl());
        controls.add(toggle(Component.translatable("caustica.options.rt.entities"),
                CausticaConfig.Rt.Entities.ENABLED));
        addEssentialsGrid(controls);
    }

    private void addEssentialsGrid(List<? extends AbstractWidget> controls) {
        registerControls(controls);
        int preferredColumns = switch (metrics.mode()) {
            case WIDE -> 3;
            case STANDARD -> 2;
            case COMPACT -> 1;
        };
        int columns = essentialsColumns(contentWidth, metrics.gridGap(), preferredColumns);
        body.addChild(new WidgetGridLayout(contentWidth, columns, metrics.gridGap(), metrics.gridGap(),
                metrics.controlHeight(), controls));
    }

    static int essentialsColumns(int width, int gap, int preferredColumns) {
        int fittingColumns = Math.max(1, (Math.max(1, width) + Math.max(0, gap))
                / (ESSENTIALS_MIN_CELL_WIDTH + Math.max(0, gap)));
        return Math.min(Math.max(1, preferredColumns), fittingColumns);
    }

    private void searchChanged(String value) {
        if (value.equals(searchQuery)) return;
        rememberMenuPosition();
        searchQuery = value;
        temporaryExpandedSectionId = null;
        rebuildScreen();
    }

    private void addSearchResults() {
        List<Control> matches = java.util.Arrays.stream(Control.values()).filter(control -> control.matches(searchQuery,
                Component.translatable(control.labelKey()).getString(), control.page().label().getString(),
                Component.translatable(control.sectionLabelKey()).getString())).toList();
        addHeader(Component.translatable("caustica.options.search.results"),
                Component.translatable("caustica.options.search.matches", matches.size()));
        if (matches.isEmpty()) {
            addInfo(() -> Component.translatable("caustica.options.search.empty", searchQuery));
        } else {
            List<AbstractWidget> results = matches.stream().map(control -> (AbstractWidget)new ActionButton(180,
                    () -> Component.translatable("caustica.options.search.result", control.page().label(),
                            Component.translatable(control.sectionLabelKey()), Component.translatable(control.labelKey())),
                    () -> revealControl(control), false)).toList();
            addGridDirect(results);
        }
    }

    private void revealControl(Control control) {
        rememberMenuPosition();
        RevealPlan plan = SettingsRevealPlanner.planReveal(control,
                new SettingsRevealPlanner.VisibilityContext(
                        CausticaConfig.Rt.Hdr.ENABLED.configuredValue(),
                        CausticaConfig.Rt.Sdr.TONEMAP_MODE.configuredValue(),
                        CausticaConfig.Rt.Hdr.TONEMAP_MODE.configuredValue()));
        category = plan.page();
        searchQuery = "";
        temporaryExpandedSectionId = null;
        pendingTargetId = plan.available() ? plan.targetControlId() : null;
        pendingRevealReasonKey = plan.unavailableReasonKey();
        rebuildScreen();
    }

    private void addDisplayHdr() {
        addHeader("displayHdr");
        List<AbstractWidget> controls = new ArrayList<>();
        controls.add(hdrToggle());
        controls.add(hdrTonemapper());
        controls.add(floatSlider(Component.translatable("caustica.options.rt.hdrPaperWhite"),
                CausticaConfig.Rt.Hdr.PAPER_WHITE_NITS, 80, 1000, value -> String.format(Locale.ROOT, "%.0f nits", value))
                .activeWhen(CausticaConfig.Rt.Hdr.ENABLED::configuredValue));
        controls.add(floatSlider(Component.translatable("caustica.options.rt.hdrPeak"),
                CausticaConfig.Rt.Hdr.PEAK_NITS, 80, 10000, value -> String.format(Locale.ROOT, "%.0f nits", value))
                .activeWhen(CausticaConfig.Rt.Hdr.ENABLED::configuredValue));
        controls.add(floatSlider(Component.translatable("caustica.options.rt.hdrUiBrightness"),
                CausticaConfig.Rt.Hdr.UI_BRIGHTNESS_NITS, 40, 1000, value -> String.format(Locale.ROOT, "%.0f nits", value))
                .activeWhen(CausticaConfig.Rt.Hdr.ENABLED::configuredValue));
        controls.add(sdrTonemapper());
        addBundle("output.displayFormat");
        addGrid(controls);
        addInfo(SettingsRuntimeStatus::hdr);
    }

    private void addFrameGeneration() {
        addHeader("frameGeneration");
        List<AbstractWidget> controls = new ArrayList<>();
        controls.add(fgModeControl());
        controls.add(fgMultiplierControl());
        controls.add(reflexControl());
        controls.add(vsyncControl());
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
        addBundle("output.frameGeneration");
        addGrid(controls);
        addInfo(SettingsRuntimeStatus::frameGeneration);
        addInfo(() -> SettingsRuntimeStatus.vsync(options));
    }

    private void addReconstruction() {
        addReconstruction(false);
    }

    private void addDenoising() {
        addReconstruction(true);
    }

    private void addReconstruction(boolean denoising) {
        addHeader(denoising ? "denoising" : "reconstruction");
        if (!denoising) {
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
        addBundle("reconstruction.dlss");
        addGrid(List.of(controls.get(1), controls.get(4), controls.get(5),
                controls.get(2), controls.get(3), controls.get(7)));
        addInfo(this::resolutionSummary);
        }
        if (!denoising) return;

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
                CausticaConfig.Rt.Composite.MAX_BOUNCES, 2, 64,
                value -> String.format(Locale.ROOT, "%.0f bounces", value)));
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
        addGrid(controls.subList(0, 3));
        addBundle("geometry.scene");
        addGrid(controls.subList(3, 5));
        addBundle("geometry.surface");
        addGrid(controls.subList(5, 8));
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
        if (pendingRevealReasonKey != null) {
            String reasonKey = pendingRevealReasonKey;
            pendingRevealReasonKey = null;
            addInfo(() -> Component.translatable(reasonKey));
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
        controls.add(toggle(Component.translatable("caustica.options.rt.lightStats"), CausticaConfig.Rt.Lights.STATS)
                .tooltip(Component.translatable("caustica.options.rt.lightStats.tooltip")));
        controls.add(toggle(Component.translatable("caustica.options.rt.lightDump"), CausticaConfig.Rt.Lights.DUMP)
                .tooltip(Component.translatable("caustica.options.rt.lightDump.tooltip")));
        controls.add(intSlider(Component.translatable("caustica.options.rt.lightDumpRadius"),
                CausticaConfig.Rt.Lights.DUMP_RADIUS, 1, 64,
                value -> String.format(Locale.ROOT, "%.0f blocks", value))
                .tooltip(Component.translatable("caustica.options.rt.lightDumpRadius.tooltip")));
        addBundle("diagnostics.telemetry");
        addGrid(controls.subList(0, 1));
        addBundle("diagnostics.visual");
        addGrid(controls.subList(1, 3));
        addBundle("diagnostics.lighting");
        addGrid(controls.subList(3, controls.size()));
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
            Control descriptor = SettingsCatalog.byLabelKey(control.labelKey());
            if (descriptor == null) {
                throw new IllegalStateException("Missing tone-control descriptor " + control.labelKey());
            }
            boundControlIds.put(widget, descriptor.id());
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
        sectionColumns = null;
        activeBundle = null;
        searchContext = title.getString() + ' ' + subtitle.getString();
        if (!pageResetAdded) {
            pageResetAdded = true;
            ActionButton reset = new ActionButton(metrics.actionWidth(),
                    () -> Component.translatable("caustica.options.resetPage"),
                    () -> resetAll(pageResets.getOrDefault(buildingCategory, List.of())), false);
            reset.setHeight(metrics.controlHeight());
            body.addChild(new HeaderToolbarLayout(contentWidth, metrics.gridGap(),
                    new SectionHeader(contentWidth, title, subtitle), reset));
        } else {
            body.addChild(new SectionHeader(contentWidth, title, subtitle));
        }
    }

    private void addBundle(String id) {
        SettingsCatalog.Section descriptor = SettingsCatalog.section(buildingCategory, id);
        if (descriptor == null) {
            throw new IllegalArgumentException("Unknown settings section " + id + " on "
                    + buildingCategory.routeId());
        }
        Component title = Component.translatable(descriptor.labelKey());
        Component purpose = Component.translatable(descriptor.descriptionKey());
        searchContext = title.getString() + ' ' + purpose.getString();
        int sectionWidth = sectionWidth();
        CollapsibleLayout bundle = new CollapsibleLayout(sectionWidth, title,
                () -> !sectionExpanded(id), () -> {
                    SettingsUiState.PageBookmark bookmark = captureBookmark();
                    boolean persistedExpanded = persistedSectionExpanded(id);
                    if (persistedExpanded && bodyScrollArea != null) {
                        AbstractWidget header = anchorWidgets.get(id);
                        if (header != null) {
                            bookmark = SettingsUiState.PageBookmark.capture(id, header.getY(),
                                    metrics.bodyTop(), metrics.bodyHeight(), bodyScrollArea.scrollAmount(),
                                    bodyScrollArea.maxScrollAmount());
                        }
                    }
                    if (id.equals(temporaryExpandedSectionId) && !persistedExpanded) {
                        temporaryExpandedSectionId = null;
                    } else {
                        temporaryExpandedSectionId = null;
                        uiState.setSectionExpanded(id, !persistedExpanded);
                    }
                    reflowPage(bookmark);
                    rememberMenuPosition();
                    uiState.save();
                });
        if (metrics.mode() == SettingsUiMetrics.Mode.WIDE) {
            if (sectionColumns == null) {
                sectionColumns = new SectionColumnsLayout(contentWidth, metrics.gridGap(),
                        metrics.categoryGap(), sectionSplit(buildingCategory));
                body.addChild(sectionColumns);
            }
            sectionColumns.addSection(bundle);
        } else {
            body.addChild(bundle);
        }
        anchorIds.put(bundle.header(), id);
        anchorWidgets.put(id, bundle.header());
        activeBundle = bundle;
        activeBundleId = id;
    }

    private boolean persistedSectionExpanded(String id) {
        boolean defaultExpanded = defaultSectionExpanded(id);
        if (legacyTreeState) {
            defaultExpanded = !CausticaTreeState.INSTANCE.isCollapsed("bundle." + id);
            if (!uiState.sectionExpansion().containsKey(id)) {
                uiState.setSectionExpanded(id, defaultExpanded);
            }
        }
        return uiState.sectionExpanded(id, defaultExpanded);
    }

    private boolean sectionExpanded(String id) {
        return id.equals(temporaryExpandedSectionId) || persistedSectionExpanded(id);
    }

    private static boolean defaultSectionExpanded(String id) {
        SettingsCatalog.Section section = SettingsCatalog.sectionById(id);
        return section == null || section.defaultExpanded();
    }

    private void addGrid(List<? extends AbstractWidget> controls) {
        if (controls.isEmpty()) {
            activeBundle = null;
            activeBundleId = null;
            return;
        }
        registerControls(controls);
        List<Runnable> resets = controls.stream().map(controlResets::get).filter(java.util.Objects::nonNull).toList();
        WidgetGridLayout grid = createGrid(controls);
        if (activeBundle != null) {
            activeBundle.setContent(grid);
            if (!resets.isEmpty()) activeBundle.setResetAction(() -> resetAll(resets));
            activeBundle = null;
            activeBundleId = null;
        } else {
            body.addChild(grid);
        }
    }

    private void addGridDirect(List<? extends AbstractWidget> controls) {
        if (controls.isEmpty()) {
            activeBundle = null;
            activeBundleId = null;
            return;
        }
        WidgetGridLayout grid = createGrid(controls);
        if (activeBundle != null) {
            activeBundle.setContent(grid);
            activeBundle = null;
            activeBundleId = null;
        } else {
            body.addChild(grid);
        }
    }

    private WidgetGridLayout createGrid(List<? extends AbstractWidget> controls) {
        int width = activeBundle == null ? contentWidth : sectionWidth();
        int columns = activeBundle == null ? columnsFor(controls) : 1;
        return new WidgetGridLayout(width, columns, metrics.gridGap(), metrics.gridGap(),
                metrics.controlHeight(), controls);
    }

    private int sectionWidth() {
        return metrics.mode() == SettingsUiMetrics.Mode.WIDE ? metrics.targetCellWidth() : contentWidth;
    }

    private static int sectionSplit(Page page) {
        return switch (page) {
            case RECONSTRUCTION, MATERIALS -> 1;
            case LIGHTING, SKY_ATMOSPHERE, GEOMETRY_SCENE, SHARC, FIRST_PERSON, DIAGNOSTICS -> 2;
            case DENOISING, EXPOSURE_TONEMAP -> 3;
            default -> 1;
        };
    }

    private int columnsFor(List<? extends AbstractWidget> controls) {
        return gridColumns;
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
            boolean trackable = control instanceof LabeledControl || toneResets.containsKey(control)
                    || boundControlIds.containsKey(control);
            String label = trackable ? controlLabel(control) : null;
            String labelKey = trackable ? controlLabelKey(control) : null;
            String boundId = boundControlIds.get(control);
            Control catalogControl = boundId == null ? SettingsCatalog.byLabelKey(labelKey)
                    : SettingsCatalog.byId(boundId);
            String id = catalogControl == null ? boundId : catalogControl.id();
            if (id == null && trackable) id = stableControlId(labelKey, buildingCategory);
            if (control instanceof Dropdown<?> dropdown) dropdowns.add(dropdown);
            if (!trackable) continue;
            usageLabels.put(control, label);
            usageIds.put(control, id);
            Page owner = catalogControl != null ? catalogControl.page() : buildingCategory;
            usageCategories.put(control, owner);
            anchorIds.put(control, id);
            anchorWidgets.putIfAbsent(id, control);
            Runnable reset = controlResets.get(control);
            if (reset != null) pageResets.computeIfAbsent(buildingCategory, ignored -> new ArrayList<>()).add(reset);
            if (pendingTargetId != null && pendingTargetId.equals(id) && buildingCategory == category) {
                targetWidget = control;
                temporaryExpandedSectionId = activeBundleId;
            }
        }
    }

    private static String controlLabelKey(AbstractWidget control) {
        Component component = control instanceof LabeledControl labeled
                ? labeled.causticaLabel() : control.getMessage();
        return translationKey(component);
    }

    private static String translationKey(Component component) {
        if (component == null) return null;
        if (component.getContents() instanceof TranslatableContents translated) {
            if (translated.getKey().startsWith("caustica.")) return translated.getKey();
            for (Object argument : translated.getArgs()) {
                if (argument instanceof Component child) {
                    String nested = translationKey(child);
                    if (nested != null) return nested;
                }
            }
        }
        for (Component sibling : component.getSiblings()) {
            String nested = translationKey(sibling);
            if (nested != null) return nested;
        }
        return null;
    }

    private static String stableControlId(String labelKey, Page page) {
        if (labelKey == null || labelKey.isBlank()) {
            throw new IllegalStateException("Trackable settings control has no stable translation key on "
                    + page.routeId());
        }
        String token = labelKey.startsWith("caustica.options.")
                ? labelKey.substring("caustica.options.".length()) : labelKey;
        return page.routeId() + "." + token;
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
        sectionColumns = null;
        activeBundle = null;
        body.addChild(new InfoStrip(contentWidth, text));
    }

    private Toggle hdrToggle() {
        return new Toggle(metrics.targetCellWidth(), Component.translatable("caustica.options.rt.hdr"),
                CausticaConfig.Rt.Hdr.ENABLED::configuredValue, enabled -> {
                    CausticaConfig.Rt.Hdr.ENABLED.set(enabled);
                    pendingSwapchainRecreate = true;
                    if (category == Page.ESSENTIALS) rebuild();
                }).tooltip(Component.translatable("caustica.options.rt.hdr.tooltip"))
                .resetOnShift(() -> {
                    CausticaConfig.Rt.Hdr.ENABLED.set(CausticaConfig.Rt.Hdr.ENABLED.defaultValue());
                    pendingSwapchainRecreate = true;
                    if (category == Page.ESSENTIALS) rebuild();
                });
    }

    private List<AbstractWidget> hdrBrightnessControls() {
        return List.of(
                floatSlider(Component.translatable("caustica.options.rt.hdrPaperWhite"),
                        CausticaConfig.Rt.Hdr.PAPER_WHITE_NITS, 80, 1000,
                        value -> String.format(Locale.ROOT, "%.0f nits", value)),
                floatSlider(Component.translatable("caustica.options.rt.hdrPeak"),
                        CausticaConfig.Rt.Hdr.PEAK_NITS, 80, 10000,
                        value -> String.format(Locale.ROOT, "%.0f nits", value)),
                floatSlider(Component.translatable("caustica.options.rt.hdrUiBrightness"),
                        CausticaConfig.Rt.Hdr.UI_BRIGHTNESS_NITS, 40, 1000,
                        value -> String.format(Locale.ROOT, "%.0f nits", value)));
    }

    private Dropdown<String> hdrTonemapper() {
        return new Dropdown<>(metrics.targetCellWidth(), Component.translatable("caustica.options.rt.hdrTonemap"),
                HDR_TONEMAPPERS, CausticaConfig.Rt.Hdr.TONEMAP_MODE::configuredValue,
                CausticaConfig.Rt.Hdr.TONEMAP_MODE::set,
                value -> Component.translatable("caustica.options.rt.hdrTonemap." + value), this::rebuild)
                .activeWhen(CausticaConfig.Rt.Hdr.ENABLED::configuredValue);
    }

    private Dropdown<String> sdrTonemapper() {
        return new Dropdown<>(metrics.targetCellWidth(), Component.translatable("caustica.options.rt.sdrTonemap"),
                SDR_TONEMAPPERS, CausticaConfig.Rt.Sdr.TONEMAP_MODE::configuredValue,
                CausticaConfig.Rt.Sdr.TONEMAP_MODE::set,
                value -> Component.translatable("caustica.options.rt.sdrTonemap." + value), this::rebuild)
                .activeWhen(() -> !CausticaConfig.Rt.Hdr.ENABLED.configuredValue());
    }

    private List<AbstractWidget> basicExposureControls() {
        return List.of(new Dropdown<>(metrics.targetCellWidth(), Component.translatable("caustica.options.rt.exposureMode"),
                        List.of("auto", "manual"), CausticaConfig.Rt.Exposure.MODE::configuredValue,
                        CausticaConfig.Rt.Exposure.MODE::set,
                        value -> Component.translatable("caustica.options.rt.exposureMode." + value), null),
                floatSlider(Component.translatable("caustica.options.rt.manualEv"),
                        CausticaConfig.Rt.Exposure.MANUAL_EXPOSURE_EV, -12, 12,
                        value -> String.format(Locale.ROOT, "%+.1f EV", value))
                        .activeWhen(() -> "manual".equals(CausticaConfig.Rt.Exposure.MODE.configuredValue())),
                floatSlider(Component.translatable("caustica.options.rt.exposureCompensation"),
                        CausticaConfig.Rt.Exposure.COMPENSATION_EV, -4, 4,
                        value -> String.format(Locale.ROOT, "%+.1f EV", value))
                        .activeWhen(() -> "auto".equals(CausticaConfig.Rt.Exposure.MODE.configuredValue())));
    }

    private Dropdown<String> reconstructionBackendControl() {
        return new Dropdown<>(metrics.targetCellWidth(), Component.translatable("caustica.options.rt.reconstructionBackend"),
                List.of("auto", "nrd", "dlss-rr", "off"),
                CausticaConfig.Rt.Reconstruction.BACKEND::configuredValue,
                CausticaConfig.Rt.Reconstruction.BACKEND::set,
                value -> Component.translatable("caustica.options.rt.reconstructionBackend." + value), null);
    }

    private Dropdown<Integer> dlssQualityControl() {
        return new Dropdown<>(metrics.targetCellWidth(), Component.translatable("caustica.options.rt.dlssQuality"),
                DLSS_QUALITY_ORDER, RtResolutionScale::displayedQuality,
                CausticaSettingsScreen::selectDlssQuality,
                CausticaSettingsScreen::dlssQualityLabel, null).activeWhen(this::dlssControlsActive);
    }

    private Dropdown<Integer> dlssPresetControl() {
        return new Dropdown<>(metrics.targetCellWidth(), Component.translatable("caustica.options.rt.dlssPreset"),
                DLSS_RR_PRESETS, RtDlssRr::renderPreset, CausticaSettingsScreen::selectDlssPreset,
                value -> Component.translatable("caustica.options.rt.dlssPreset." + switch (value) {
                    case 4 -> "d";
                    case 5 -> "e";
                    default -> "default";
                }), null).activeWhen(this::dlssControlsActive);
    }

    private Component resolutionSummary() {
        int outputWidth = minecraft.getWindow().getWidth();
        int outputHeight = minecraft.getWindow().getHeight();
        int ratioTenths = CausticaConfig.Rt.DlssRr.INPUT_RATIO_TENTHS.configuredValue();
        int inputWidth = RtResolutionScale.inputDimension(outputWidth, ratioTenths);
        int inputHeight = RtResolutionScale.inputDimension(outputHeight, ratioTenths);
        double ratio = ratioTenths / 10.0;
        double linearPercent = 100.0 / ratio;
        return Component.translatable("caustica.options.rt.resolutionSummary", inputWidth, inputHeight,
                outputWidth, outputHeight, String.format(Locale.ROOT, "%.0f", linearPercent),
                String.format(Locale.ROOT, "%.0f", linearPercent * linearPercent / 100.0),
                String.format(Locale.ROOT, "%.1f", ratio));
    }

    private Dropdown<String> fgModeControl() {
        return new Dropdown<>(metrics.targetCellWidth(), Component.translatable("caustica.options.rt.fg.mode"),
                List.of("off", "fixed"), CausticaConfig.Rt.Fg::configuredMode, value -> {
                    CausticaConfig.Rt.Fg.setMode(value);
                    pendingSwapchainRecreate = true;
                }, value -> Component.translatable("caustica.options.rt.fg.mode." + value), null);
    }

    private Dropdown<Integer> fgMultiplierControl() {
        int maximum = SettingsRuntimeStatus.maximumGeneratedFrames();
        return new Dropdown<>(metrics.targetCellWidth(), Component.translatable("caustica.options.rt.fg.multiplier"),
                IntStream.rangeClosed(1, maximum).boxed().toList(),
                CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT::configuredValue,
                CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT::set,
                generated -> Component.literal((generated + 1) + "x"), null)
                .activeWhen(() -> !"off".equals(CausticaConfig.Rt.Fg.configuredMode()));
    }

    private Dropdown<String> reflexControl() {
        return new Dropdown<>(metrics.targetCellWidth(), Component.translatable("caustica.options.rt.fg.reflex"),
                List.of("off", "on", "boost"), this::reflexMode, this::setReflexMode,
                value -> Component.translatable("caustica.options.rt.fg.reflex." + value), null);
    }

    private ActionButton vsyncControl() {
        ActionButton button = new ActionButton(metrics.targetCellWidth(), () -> SettingsRuntimeStatus.vsync(options), () -> {
            options.enableVsync().set(!options.enableVsync().get());
            pendingSwapchainRecreate = true;
        }, false).tooltip(Component.translatable("caustica.options.rt.fg.vsync.tooltip"));
        boundControlIds.put(button, Control.FG_VSYNC.id());
        return button;
    }

    private Slider torchIntensityControl() {
        return new Slider(metrics.targetCellWidth(), Component.translatable("caustica.options.rt.torchIntensity"),
                () -> CausticaConfig.Rt.Composite.TORCH_EMISSION_MULTIPLIER.configuredValue() * 2000.0,
                value -> CausticaConfig.Rt.Composite.TORCH_EMISSION_MULTIPLIER.set((float)(value / 2000.0)),
                unit -> RtVideoOptions.torchMultiplierFromSlider((int)Math.round(unit * 100.0)) * 2000.0,
                value -> RtVideoOptions.torchSliderFromMultiplier((float)(value / 2000.0)) / 100.0,
                value -> String.format(Locale.ROOT, "%.0f cd/m²", value));
    }

    private Toggle toggle(Component label, BooleanSetting setting) {
        Toggle widget = new Toggle(180, label, setting::configuredValue, setting::set);
        return resettable(widget, () -> setting.set(setting.defaultValue()));
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
        Slider widget = new Slider(180, label, setting::configuredValue, value -> setting.set((int)Math.round(value)),
                unit -> min + unit * (max - min), value -> (value - min) / (max - min), format);
        return resettable(widget, () -> setting.set(setting.defaultValue()));
    }

    private static Component dlssQualityLabel(int quality) {
        Component name = Component.translatable("caustica.options.rt.dlssQuality." + quality);
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
        Slider widget = new Slider(180, label, setting::configuredValue, value -> setting.set((float)value),
                unit -> min + unit * (max - min), value -> (value - min) / (max - min), format);
        return resettable(widget, () -> setting.set(setting.defaultValue()));
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
            uiState.setLastPage(category);
            SettingsUiState.PageBookmark bookmark = captureBookmark();
            if (bookmark != null) uiState.setPageBookmark(category, bookmark);
        }
    }

    private SettingsUiState.PageBookmark captureBookmark() {
        if (bodyScrollArea == null || metrics == null) return null;
        int viewportTop = metrics.bodyTop();
        int viewportBottom = viewportTop + metrics.bodyHeight();
        Map.Entry<AbstractWidget, String> anchor = anchorIds.entrySet().stream()
                .filter(entry -> entry.getKey().visible)
                .filter(entry -> entry.getKey().getBottom() > viewportTop
                        && entry.getKey().getY() < viewportBottom)
                .min(java.util.Comparator.<Map.Entry<AbstractWidget, String>>comparingInt(
                                entry -> entry.getKey().getY())
                        .thenComparingInt(entry -> entry.getKey().getX()))
                .orElse(null);
        String anchorId = anchor == null ? "" : anchor.getValue();
        double anchorTop = anchor == null ? viewportTop : anchor.getKey().getY();
        return SettingsUiState.PageBookmark.capture(anchorId, anchorTop, viewportTop,
                metrics.bodyHeight(), bodyScrollArea.scrollAmount(), bodyScrollArea.maxScrollAmount());
    }

    private void restorePositionOrRevealTarget(int bodyTop) {
        if (bodyScrollArea == null) return;
        if (targetWidget != null) {
            double targetScroll = Math.max(0.0, targetWidget.getY() - bodyTop - metrics.controlHeight());
            bodyScrollArea.setScrollAmount(targetScroll);
            flashWidget = targetWidget;
            flashUntilMillis = System.currentTimeMillis() + TARGET_FLASH_MILLIS;
            pendingTargetId = null;
        } else if (searchQuery.isBlank()) {
            SettingsUiState.PageBookmark bookmark = uiState.pageBookmark(category);
            if (bookmark != null) {
                restoreBookmark(bookmark);
            } else {
                bodyScrollArea.setScrollAmount(CausticaMenuUsage.INSTANCE.scrollPosition(category));
                SettingsUiState.PageBookmark migrated = captureBookmark();
                if (migrated != null) uiState.setPageBookmark(category, migrated);
            }
        }
    }

    private void restoreBookmark(SettingsUiState.PageBookmark bookmark) {
        if (bodyScrollArea == null || bookmark == null) return;
        AbstractWidget anchor = anchorWidgets.get(bookmark.anchorId());
        double scroll = anchor == null
                ? bookmark.fallbackScroll(bodyScrollArea.maxScrollAmount())
                : bookmark.restoredScroll(anchor.getY() + bodyScrollArea.scrollAmount(),
                        metrics.bodyTop(), metrics.bodyHeight(),
                        bodyScrollArea.maxScrollAmount());
        bodyScrollArea.setScrollAmount(scroll);
    }

    private void reflowPage(SettingsUiState.PageBookmark bookmark) {
        if (bodyScroll == null) return;
        bodyScroll.arrangeElements();
        restoreBookmark(bookmark);
    }

    @Override
    protected void repositionElements() {
        if (initializedWidth != width || initializedHeight != height) {
            rememberMenuPosition();
            super.repositionElements();
            return;
        }
        saved = false;
        if (bodyScroll != null) bodyScroll.arrangeElements();
        if (navigationScroll != null) navigationScroll.arrangeElements();
    }

    @Override
    public void added() {
        saved = false;
        navigationOpen = false;
        if (navigationScrollArea != null && metrics != null && metrics.compact()) {
            navigationScrollArea.visible = false;
            navigationScrollArea.active = false;
        }
        super.added();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (minecraft.level == null) g.fill(0, 0, width, height, 0xE0101010);
        minecraft.gui.hud.extractDeferredSubtitles();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int paneLeft = metrics.paneLeft();
        int paneRight = metrics.paneRight();
        int railLeft = metrics.workspaceLeft();
        int railBottom = metrics.footerTop(height);
        g.fill(metrics.workspaceLeft(), metrics.margin(), metrics.workspaceRight(),
                height - metrics.margin(), CausticaWidgets.PANEL);
        if (!metrics.compact()) {
            g.fill(railLeft + railWidth + 5, metrics.margin(), railLeft + railWidth + 6,
                    railBottom, 0x70FFFFFF);
        }
        g.fill(railLeft, metrics.margin() + metrics.headerHeight(), paneRight,
                metrics.margin() + metrics.headerHeight() + 1, 0x50FFFFFF);
        if (!metrics.compact()) {
            g.text(font, searchQuery.isBlank() ? category.label()
                            : Component.translatable("caustica.options.search.results"),
                    paneLeft + 2, metrics.margin() + 7, CausticaWidgets.ACCENT);
        }
        g.fill(paneLeft, metrics.footerTop(height), paneRight,
                metrics.footerTop(height) + 1, 0x50FFFFFF);
        g.text(font, Component.translatable("caustica.options.info.shiftReset"),
                paneLeft + 2, metrics.footerTop(height) + 8, CausticaWidgets.MUTED);
        boolean drawModalNavigation = metrics.compact() && navigationOpen && navigationScrollArea != null;
        if (drawModalNavigation) navigationScrollArea.visible = false;
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        if (drawModalNavigation) {
            navigationScrollArea.visible = true;
            g.fill(0, metrics.bodyTop(), width, metrics.footerTop(height), 0x78000000);
            g.fill(navigationLeft - 4, navigationTop - 4,
                    navigationLeft + navigationWidth + 4, navigationTop + navigationHeight + 4,
                    0xE0181818);
            navigationScrollArea.extractRenderState(g, mouseX, mouseY, partialTick);
        }
        extractTargetFlash(g);
        if (selectedCategoryButton != null
                && (!metrics.compact() || navigationOpen)
                && selectedCategoryButton.getBottom() > navigationTop
                && selectedCategoryButton.getY() < navigationTop + navigationHeight) {
            g.fill(selectedCategoryButton.getX(), selectedCategoryButton.getY(),
                    selectedCategoryButton.getX() + 2, selectedCategoryButton.getBottom(),
                    CausticaWidgets.ACCENT);
        }
        if (!navigationOpen) {
            for (Dropdown<?> dropdown : dropdowns) {
                dropdown.extractOverlay(g, mouseX, mouseY, height);
            }
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
        if (navigationOpen && navigationScrollArea != null) {
            if (insideNavigation(input.x(), input.y())) {
                navigationScrollArea.mouseClicked(input, doubleClick);
            } else {
                setNavigationOpen(false);
            }
            return true;
        }
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
        if (input.hasShiftDown() && insideBody(input.x(), input.y())) {
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
                .filter(widget -> insideBody(input.x(), input.y()))
                .filter(widget -> widget.visible && widget.active && widget.isMouseOver(input.x(), input.y()))
                .findFirst().orElse(null);
        boolean handled = super.mouseClicked(input, doubleClick);
        if (handled && used != null) recordControl(used);
        return handled;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (navigationOpen && navigationScrollArea != null) {
            if (insideNavigation(mouseX, mouseY) && vertical != 0.0) {
                navigationScrollArea.setScrollAmount(navigationScrollArea.scrollAmount()
                        - vertical * (metrics.controlHeight() + metrics.gridGap()));
            }
            return true;
        }
        for (Dropdown<?> dropdown : dropdowns) {
            if (dropdown.scrollOverlay(mouseX, mouseY, vertical, height)) return true;
        }
        if (!metrics.compact() && navigationScrollArea != null && insideNavigation(mouseX, mouseY)
                && vertical != 0.0) {
            navigationScrollArea.setScrollAmount(navigationScrollArea.scrollAmount()
                    - vertical * (metrics.controlHeight() + metrics.gridGap()));
            return true;
        }
        if (bodyScrollArea != null && insideBody(mouseX, mouseY) && vertical != 0.0) {
            dropdowns.forEach(Dropdown::close);
            bodyScrollArea.setScrollAmount(bodyScrollArea.scrollAmount()
                    - vertical * (metrics.controlHeight() + metrics.gridGap()));
            return true;
        }
        return false;
    }

    private boolean insideBody(double x, double y) {
        return metrics != null && x >= metrics.paneLeft() && x < metrics.paneRight()
                && y >= metrics.bodyTop() && y < metrics.bodyTop() + metrics.bodyHeight();
    }

    private boolean insideNavigation(double x, double y) {
        return x >= navigationLeft && x < navigationLeft + navigationWidth
                && y >= navigationTop && y < navigationTop + navigationHeight;
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (navigationOpen && input.key() == GLFW.GLFW_KEY_ESCAPE) {
            setNavigationOpen(false);
            return true;
        }
        if (navigationOpen && navigationContainer != null) {
            navigationContainer.keyPressed(input);
            return true;
        }
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
        uiState.save();
        CausticaConfig.save();
        options.save();
        if (pendingSwapchainRecreate || initiallyFgRequested != CausticaConfig.Rt.Fg.requested()) {
            StreamlineSwapchainCoordinator.INSTANCE.requestReconfigure();
            pendingSwapchainRecreate = false;
        }
    }

    private Toggle resettable(Toggle widget, Runnable reset) {
        controlResets.put(widget, reset);
        return widget.resetOnShift(reset);
    }

    private Slider resettable(Slider widget, Runnable reset) {
        controlResets.put(widget, reset);
        return widget.resetOnShift(reset);
    }

    private void resetAll(List<Runnable> resets) {
        resets.forEach(Runnable::run);
        rebuild();
    }

    private void recordControl(AbstractWidget widget) {
        String label = usageLabels.get(widget);
        Page owner = usageCategories.getOrDefault(widget, category);
        if (label != null) CausticaMenuUsage.INSTANCE.record(usageIds.get(widget), label, owner);
    }

}
