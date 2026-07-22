package dev.comfyfluffy.caustica.client.settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.network.chat.Component;

/** Stable, widget-independent metadata for settings navigation and search. */
public final class SettingsCatalog {
    private SettingsCatalog() {
    }

    public enum Group {
        DISPLAY_PERFORMANCE("displayPerformance"), WORLD("world"), ADVANCED("advanced");

        private final String routeId;

        Group(String routeId) {
            this.routeId = routeId;
        }

        public String routeId() {
            return routeId;
        }

        public String labelKey() {
            return "caustica.options.group." + routeId;
        }

        public Component label() {
            return Component.translatable(labelKey());
        }
    }

    public enum Page {
        ESSENTIALS(null, "essentials", "OVERVIEW"),
        DISPLAY_HDR(Group.DISPLAY_PERFORMANCE, "displayHdr", "OUTPUT"),
        FRAME_GENERATION(Group.DISPLAY_PERFORMANCE, "frameGeneration"),
        RECONSTRUCTION(Group.DISPLAY_PERFORMANCE, "reconstruction"),
        EXPOSURE_TONEMAP(Group.DISPLAY_PERFORMANCE, "exposureTonemap", "EXPOSURE"),
        LIGHTING(Group.WORLD, "lighting"),
        SKY_ATMOSPHERE(Group.WORLD, "skyAtmosphere", "SKY"),
        GEOMETRY_SCENE(Group.WORLD, "geometryScene", "GEOMETRY"),
        FIRST_PERSON(Group.WORLD, "firstPerson", "VIEW"),
        DENOISING(Group.ADVANCED, "denoising"),
        MATERIALS(Group.ADVANCED, "materials"),
        SHARC(Group.ADVANCED, "sharc"),
        DIAGNOSTICS(Group.ADVANCED, "diagnostics");

        private final Group group;
        private final String routeId;
        private final Set<String> aliases;

        Page(Group group, String routeId, String... aliases) {
            this.group = group;
            this.routeId = routeId;
            // Keep enum initialization independent from the outer catalog. Page can be the
            // first type touched by SettingsUiState, before the outer section registry exists.
            this.aliases = Arrays.stream(aliases).map(Page::routeToken)
                    .collect(Collectors.toUnmodifiableSet());
        }

        public Group group() {
            return group;
        }

        public String routeId() {
            return routeId;
        }

        public Set<String> aliases() {
            return aliases;
        }

        public String labelKey() {
            return "caustica.options.category." + routeId;
        }

        public Component label() {
            return Component.translatable(labelKey());
        }

        public boolean recognizes(String raw) {
            String token = routeToken(raw);
            return !token.isBlank() && (routeToken(name()).equals(token) || routeToken(routeId).equals(token)
                    || aliases.contains(token));
        }

        private static String routeToken(String value) {
            return value == null ? "" : value.trim().toUpperCase(Locale.ROOT)
                    .replace('-', '_').replace(' ', '_');
        }

        public static Page parse(String raw) {
            if (raw == null || raw.isBlank()) return ESSENTIALS;
            String token = routeToken(raw);
            for (Page page : values()) {
                if (page.recognizes(token)) return page;
            }
            return ESSENTIALS;
        }
    }

    public enum Tier { ESSENTIAL, STANDARD, EXPERT, INTERNAL, COMPATIBILITY }

    public enum ChangeEffect {
        LIVE, TEMPORAL_RESET, SHARC_RESET, SCENE_REBUILD, RESOLUTION_REBUILD,
        SWAPCHAIN_RECREATE, RESTART_REQUIRED
    }

    /** Canonical metadata for one fixed rendered bundle. */
    public record Section(Page page, String id, String labelKey, String descriptionKey,
                          boolean defaultExpanded) {
    }

    /** Common side-effect-free metadata contract for named and supplemental controls. */
    public interface ControlDescriptor {
        String id();
        String labelKey();
        Page page();
        String section();
        Tier tier();
        ChangeEffect effect();
        List<String> aliases();

        default Section sectionDescriptor() {
            return sectionById(section());
        }

        default String sectionLabelKey() {
            Section descriptor = sectionDescriptor();
            return descriptor == null ? "caustica.options.bundle." + section() : descriptor.labelKey();
        }

        default boolean matches(String query) {
            return matches(query, "", "", "");
        }

        default boolean matches(String query, String label, String pageLabel, String sectionLabel) {
            String normalized = searchText(query);
            if (normalized.isBlank()) return false;
            String haystack = searchText(String.join(" ", id(), labelKey(), page().name(), page().routeId(),
                    section(), label, pageLabel, sectionLabel, String.join(" ", aliases())));
            return Arrays.stream(normalized.split("\\s+")).allMatch(haystack::contains);
        }
    }

    /**
     * Immutable control descriptor. The named fields preserve the former enum surface while
     * {@link #values()} exposes the complete canonical registry.
     */
    public static final class Control implements ControlDescriptor {
        private static Control control(String id, String labelKey, Page page, String section, Tier tier,
                                       ChangeEffect effect, String... aliases) {
            return new Control(id, labelKey, page, section, tier, effect, aliases);
        }

        public static final Control RENDERER_ENABLED = control("renderer.enabled", "caustica.options.enabled",
                Page.GEOMETRY_SCENE, "geometry.pathTracer", Tier.ESSENTIAL, ChangeEffect.LIVE,
                "renderer", "ray tracing");
        public static final Control SAMPLES_PER_PIXEL = control("rt.spp", "caustica.options.rt.spp",
                Page.GEOMETRY_SCENE, "geometry.pathTracer", Tier.ESSENTIAL, ChangeEffect.TEMPORAL_RESET,
                "spp", "samples");
        public static final Control MAX_BOUNCES = control("rt.maxBounces", "caustica.options.rt.maxBounces",
                Page.GEOMETRY_SCENE, "geometry.pathTracer", Tier.ESSENTIAL, ChangeEffect.TEMPORAL_RESET,
                "ray depth", "bounces");
        public static final Control RECONSTRUCTION_BACKEND = control("reconstruction.backend",
                "caustica.options.rt.reconstructionBackend", Page.RECONSTRUCTION, "reconstruction.backend",
                Tier.ESSENTIAL, ChangeEffect.RESOLUTION_REBUILD, "dlss", "nrd");
        public static final Control DLSS_RR_ENABLED = control("reconstruction.dlss.enabled",
                "caustica.options.rt.dlssRr", Page.RECONSTRUCTION, "reconstruction.dlss", Tier.ESSENTIAL,
                ChangeEffect.TEMPORAL_RESET, "ray reconstruction");
        public static final Control DLSS_QUALITY = control("reconstruction.dlss.quality",
                "caustica.options.rt.dlssQuality", Page.RECONSTRUCTION, "reconstruction.dlss", Tier.ESSENTIAL,
                ChangeEffect.RESOLUTION_REBUILD, "quality", "balanced", "performance");
        public static final Control DLSS_PRESET = control("reconstruction.dlss.preset",
                "caustica.options.rt.dlssPreset", Page.RECONSTRUCTION, "reconstruction.dlss", Tier.ESSENTIAL,
                ChangeEffect.TEMPORAL_RESET, "preset d", "preset e");
        public static final Control HDR_ENABLED = control("display.hdr.enabled", "caustica.options.rt.hdr",
                Page.DISPLAY_HDR, "output.displayFormat", Tier.ESSENTIAL, ChangeEffect.SWAPCHAIN_RECREATE,
                "hdr10", "pq");
        public static final Control HDR_TONEMAPPER = control("display.hdr.tonemapper",
                "caustica.options.rt.hdrTonemap", Page.DISPLAY_HDR, "output.displayFormat", Tier.ESSENTIAL,
                ChangeEffect.LIVE, "tone map", "eetf");
        public static final Control HDR_PAPER_WHITE = control("display.hdr.paperWhite",
                "caustica.options.rt.hdrPaperWhite", Page.DISPLAY_HDR, "output.displayFormat", Tier.ESSENTIAL,
                ChangeEffect.LIVE, "paper white", "nits");
        public static final Control HDR_PEAK = control("display.hdr.peak", "caustica.options.rt.hdrPeak",
                Page.DISPLAY_HDR, "output.displayFormat", Tier.ESSENTIAL, ChangeEffect.LIVE,
                "peak brightness", "nits");
        public static final Control HDR_UI_BRIGHTNESS = control("display.hdr.uiBrightness",
                "caustica.options.rt.hdrUiBrightness", Page.DISPLAY_HDR, "output.displayFormat", Tier.ESSENTIAL,
                ChangeEffect.LIVE, "hud brightness", "ui brightness");
        public static final Control SDR_TONEMAPPER = control("display.sdr.tonemapper",
                "caustica.options.rt.sdrTonemap", Page.DISPLAY_HDR, "output.displayFormat", Tier.ESSENTIAL,
                ChangeEffect.LIVE, "sdr", "tone map");
        public static final Control FG_MODE = control("frameGeneration.mode", "caustica.options.rt.fg.mode",
                Page.FRAME_GENERATION, "output.frameGeneration", Tier.ESSENTIAL,
                ChangeEffect.SWAPCHAIN_RECREATE, "fg", "dlss g");
        public static final Control FG_MULTIPLIER = control("frameGeneration.multiplier",
                "caustica.options.rt.fg.multiplier", Page.FRAME_GENERATION, "output.frameGeneration",
                Tier.ESSENTIAL, ChangeEffect.LIVE, "multi frame", "2x", "3x", "4x");
        public static final Control FG_REFLEX = control("frameGeneration.reflex", "caustica.options.rt.fg.reflex",
                Page.FRAME_GENERATION, "output.frameGeneration", Tier.ESSENTIAL, ChangeEffect.LIVE,
                "latency", "boost");
        public static final Control FG_VSYNC = control("frameGeneration.vsync", "caustica.options.rt.fg.vsync",
                Page.FRAME_GENERATION, "output.frameGeneration", Tier.ESSENTIAL,
                ChangeEffect.SWAPCHAIN_RECREATE, "vertical sync", "mailbox", "fifo");
        public static final Control EXPOSURE_MODE = control("exposure.mode", "caustica.options.rt.exposureMode",
                Page.EXPOSURE_TONEMAP, "exposure.intent", Tier.ESSENTIAL, ChangeEffect.LIVE,
                "auto exposure", "manual exposure");
        public static final Control MANUAL_EV = control("exposure.manualEv", "caustica.options.rt.manualEv",
                Page.EXPOSURE_TONEMAP, "exposure.intent", Tier.ESSENTIAL, ChangeEffect.LIVE, "ev");
        public static final Control EXPOSURE_COMPENSATION = control("exposure.compensation",
                "caustica.options.rt.exposureCompensation", Page.EXPOSURE_TONEMAP, "exposure.intent",
                Tier.ESSENTIAL, ChangeEffect.LIVE, "ev");
        public static final Control TORCH_INTENSITY = control("lighting.torchIntensity",
                "caustica.options.rt.torchIntensity", Page.LIGHTING, "lighting.emissive", Tier.ESSENTIAL,
                ChangeEffect.LIVE, "torch", "cd/m2");
        public static final Control ENTITIES = control("scene.entities", "caustica.options.rt.entities",
                Page.GEOMETRY_SCENE, "geometry.scene", Tier.ESSENTIAL, ChangeEffect.SCENE_REBUILD, "mobs");
        public static final Control PARTICLES = control("scene.particles", "caustica.options.rt.particles",
                Page.GEOMETRY_SCENE, "geometry.scene", Tier.ESSENTIAL, ChangeEffect.SCENE_REBUILD,
                "particle rendering");
        public static final Control WATER_WAVES = control("scene.waterWaves", "caustica.options.rt.waterWaves",
                Page.GEOMETRY_SCENE, "geometry.surface", Tier.ESSENTIAL, ChangeEffect.LIVE, "water");
        public static final Control FIRST_PERSON_ENABLED = control("firstPerson.enabled",
                "caustica.options.rt.firstPerson.enabled", Page.FIRST_PERSON, "view.firstPerson", Tier.ESSENTIAL,
                ChangeEffect.LIVE, "hand", "held item");
        public static final Control SHARC_ENABLED = control("sharc.enabled", "caustica.options.rt.sharc",
                Page.SHARC, "sharc.foundation", Tier.ESSENTIAL, ChangeEffect.SHARC_RESET,
                "radiance cache", "indirect lighting");
        public static final Control FG_QUEUE = control("frameGeneration.queueParallelism",
                "caustica.options.rt.fg.queueParallelism", Page.FRAME_GENERATION, "output.frameGeneration",
                Tier.EXPERT, ChangeEffect.LIVE, "parallel queue");
        public static final Control FG_OUTPUT_TARGET = control("frameGeneration.outputTarget",
                "caustica.options.rt.fg.outputTarget", Page.FRAME_GENERATION, "output.frameGeneration",
                Tier.EXPERT, ChangeEffect.LIVE, "target fps");
        public static final Control FG_UI_RECOMPOSITION = control("frameGeneration.uiRecomposition",
                "caustica.options.rt.fg.uiRecomposition", Page.FRAME_GENERATION, "output.frameGeneration",
                Tier.STANDARD, ChangeEffect.LIVE, "hud");
        public static final Control FG_FULLSCREEN_MENU = control("frameGeneration.fullscreenMenu",
                "caustica.options.rt.fg.fullscreenMenu", Page.FRAME_GENERATION, "output.frameGeneration",
                Tier.EXPERT, ChangeEffect.LIVE, "menu detection");
        public static final Control DLSS_PATH_GUIDE = control("reconstruction.dlss.pathGuide",
                "caustica.options.rt.dlssDiffusePathGuide", Page.RECONSTRUCTION, "reconstruction.dlss",
                Tier.EXPERT, ChangeEffect.TEMPORAL_RESET, "path guide");
        public static final Control DLSS_SUBPIXEL_DETAIL = control("reconstruction.dlss.subpixelDetail",
                "caustica.options.rt.subpixelDetail", Page.RECONSTRUCTION, "reconstruction.dlss", Tier.EXPERT,
                ChangeEffect.TEMPORAL_RESET, "texture detail");
        public static final Control TRANSPARENCY_TRANSPORT = control("reconstruction.transparency",
                "caustica.options.rt.highQualityTransparency", Page.RECONSTRUCTION, "reconstruction.backend",
                Tier.EXPERT, ChangeEffect.TEMPORAL_RESET, "optical transport");
        public static final Control PARTICLE_HISTORY = control("reconstruction.particleHistory",
                "caustica.options.rt.particleTemporalHistory", Page.RECONSTRUCTION, "reconstruction.dlss",
                Tier.EXPERT, ChangeEffect.TEMPORAL_RESET, "particle history");
        public static final Control NRD_DENOISER = control("denoising.denoiser",
                "caustica.options.rt.nrd.denoiser", Page.DENOISING, "reconstruction.nrdMode", Tier.STANDARD,
                ChangeEffect.TEMPORAL_RESET, "reblur", "relax");
        public static final Control NRD_SH = control("denoising.sphericalHarmonics",
                "caustica.options.rt.nrd.sh", Page.DENOISING, "reconstruction.nrdMode", Tier.EXPERT,
                ChangeEffect.TEMPORAL_RESET, "spherical harmonics");
        public static final Control NRD_UPSCALE_MODE = control("denoising.upscaleMode",
                "caustica.options.rt.nrd.upscaleMode", Page.DENOISING, "reconstruction.nrdMode", Tier.STANDARD,
                ChangeEffect.RESOLUTION_REBUILD, "render scale");
        public static final Control NRD_RENDER_SCALE = control("denoising.renderScale",
                "caustica.options.rt.nrd.renderScale", Page.DENOISING, "reconstruction.nrdMode", Tier.STANDARD,
                ChangeEffect.RESOLUTION_REBUILD, "custom scale");
        public static final Control NRD_UPSCALE_FILTER = control("denoising.upscaleFilter",
                "caustica.options.rt.nrd.upscaleFilter", Page.DENOISING, "reconstruction.nrdMode", Tier.EXPERT,
                ChangeEffect.LIVE, "edge adaptive");
        public static final Control NRD_SHARPNESS = control("denoising.sharpness",
                "caustica.options.rt.nrd.upscaleSharpness", Page.DENOISING, "reconstruction.nrdMode", Tier.EXPERT,
                ChangeEffect.LIVE, "sharpen");
        public static final Control SUNLIGHT = control("lighting.sunlight", "caustica.options.rt.sunlightIntensity",
                Page.LIGHTING, "lighting.energy", Tier.STANDARD, ChangeEffect.LIVE, "sun");
        public static final Control MOONLIGHT = control("lighting.moonlight", "caustica.options.rt.moonlightIntensity",
                Page.LIGHTING, "lighting.energy", Tier.STANDARD, ChangeEffect.LIVE, "moon");
        public static final Control RIS_CANDIDATES = control("lighting.risCandidates",
                "caustica.options.rt.risCandidates", Page.LIGHTING, "lighting.emitterSampling", Tier.EXPERT,
                ChangeEffect.SCENE_REBUILD, "light candidates");
        public static final Control LIGHT_FILL = control("lighting.minimumFill",
                "caustica.options.rt.lightMinFillRatio", Page.LIGHTING, "lighting.emitterSampling", Tier.EXPERT,
                ChangeEffect.SCENE_REBUILD, "fill ratio");
        public static final Control LIGHT_STATS = control("diagnostics.lightStats",
                "caustica.options.rt.lightStats", Page.DIAGNOSTICS, "diagnostics.lighting", Tier.INTERNAL,
                ChangeEffect.LIVE, "light statistics");
        public static final Control LIGHT_DUMP = control("diagnostics.lightDump", "caustica.options.rt.lightDump",
                Page.DIAGNOSTICS, "diagnostics.lighting", Tier.INTERNAL, ChangeEffect.LIVE, "dump lights");
        public static final Control LIGHT_DUMP_RADIUS = control("diagnostics.lightDumpRadius",
                "caustica.options.rt.lightDumpRadius", Page.DIAGNOSTICS, "diagnostics.lighting", Tier.INTERNAL,
                ChangeEffect.LIVE, "dump radius");
        public static final Control FRAME_STATS = control("diagnostics.frameStats", "caustica.options.frameStats",
                Page.DIAGNOSTICS, "diagnostics.telemetry", Tier.INTERNAL, ChangeEffect.LIVE, "timings");
        public static final Control DEBUG_VIEW = control("diagnostics.debugView", "caustica.options.rt.debugView",
                Page.DIAGNOSTICS, "diagnostics.visual", Tier.INTERNAL, ChangeEffect.LIVE, "visualization");
        public static final Control DYNAMIC_FOV = control("firstPerson.dynamicFov",
                "caustica.options.view.dynamicFov", Page.FIRST_PERSON, "view.comfort", Tier.STANDARD,
                ChangeEffect.LIVE, "fov");
        public static final Control FIRST_PERSON_VANILLA = control("firstPerson.disableVanilla",
                "caustica.options.rt.firstPerson.disableVanillaModel", Page.FIRST_PERSON, "view.firstPerson",
                Tier.EXPERT, ChangeEffect.LIVE, "vanilla hand");
        public static final Control FIRST_PERSON_FORWARD = control("firstPerson.forward",
                "caustica.options.rt.firstPerson.forward", Page.FIRST_PERSON, "view.placement", Tier.STANDARD,
                ChangeEffect.LIVE, "forward offset");
        public static final Control FIRST_PERSON_VERTICAL = control("firstPerson.vertical",
                "caustica.options.rt.firstPerson.vertical", Page.FIRST_PERSON, "view.placement", Tier.STANDARD,
                ChangeEffect.LIVE, "vertical offset");
        public static final Control FIRST_PERSON_LATERAL = control("firstPerson.lateral",
                "caustica.options.rt.firstPerson.lateral", Page.FIRST_PERSON, "view.placement", Tier.STANDARD,
                ChangeEffect.LIVE, "lateral offset");

        private static final Control[] LEGACY_VALUES = {
            RENDERER_ENABLED, SAMPLES_PER_PIXEL, MAX_BOUNCES, RECONSTRUCTION_BACKEND, DLSS_RR_ENABLED,
            DLSS_QUALITY, DLSS_PRESET, HDR_ENABLED, HDR_TONEMAPPER, HDR_PAPER_WHITE,
            HDR_PEAK, HDR_UI_BRIGHTNESS, SDR_TONEMAPPER, FG_MODE, FG_MULTIPLIER, FG_REFLEX, FG_VSYNC,
            EXPOSURE_MODE, MANUAL_EV, EXPOSURE_COMPENSATION, TORCH_INTENSITY, ENTITIES, PARTICLES,
            WATER_WAVES, FIRST_PERSON_ENABLED, SHARC_ENABLED, FG_QUEUE, FG_OUTPUT_TARGET,
            FG_UI_RECOMPOSITION, FG_FULLSCREEN_MENU, DLSS_PATH_GUIDE, DLSS_SUBPIXEL_DETAIL,
            TRANSPARENCY_TRANSPORT, PARTICLE_HISTORY, NRD_DENOISER, NRD_SH, NRD_UPSCALE_MODE,
            NRD_RENDER_SCALE, NRD_UPSCALE_FILTER, NRD_SHARPNESS, SUNLIGHT, MOONLIGHT, RIS_CANDIDATES,
            LIGHT_FILL, LIGHT_STATS, LIGHT_DUMP, LIGHT_DUMP_RADIUS, FRAME_STATS, DEBUG_VIEW, DYNAMIC_FOV,
            FIRST_PERSON_VANILLA, FIRST_PERSON_FORWARD, FIRST_PERSON_VERTICAL, FIRST_PERSON_LATERAL
        };

        private final String id;
        private final String labelKey;
        private final Page page;
        private final String section;
        private final Tier tier;
        private final ChangeEffect effect;
        private final List<String> aliases;

        private Control(String id, String labelKey, Page page, String section, Tier tier, ChangeEffect effect,
                        String... aliases) {
            this.id = id;
            this.labelKey = labelKey;
            this.page = page;
            this.section = section;
            this.tier = tier;
            this.effect = effect;
            this.aliases = List.of(aliases);
        }

        @Override public String id() { return id; }
        @Override public String labelKey() { return labelKey; }
        @Override public Page page() { return page; }
        @Override public String section() { return section; }
        @Override public Tier tier() { return tier; }
        @Override public ChangeEffect effect() { return effect; }
        @Override public List<String> aliases() { return aliases; }

        public static Control[] values() {
            return CONTROLS.toArray(Control[]::new);
        }

        private static Control[] legacyValues() {
            return LEGACY_VALUES.clone();
        }
    }

    private static final List<Section> SECTIONS = List.of(
            section(Page.DISPLAY_HDR, "output.displayFormat", true),
            section(Page.FRAME_GENERATION, "output.frameGeneration", true),
            section(Page.RECONSTRUCTION, "reconstruction.backend", true),
            section(Page.RECONSTRUCTION, "reconstruction.dlss", true),
            section(Page.DENOISING, "reconstruction.nrdMode", true),
            section(Page.DENOISING, "reconstruction.nrdHistory", true),
            section(Page.DENOISING, "reconstruction.nrdSpatial", true),
            section(Page.DENOISING, "reconstruction.nrdDiagnostics", true),
            section(Page.DENOISING, "reconstruction.reblurAdvanced", false),
            section(Page.DENOISING, "reconstruction.relaxAdvanced", false),
            section(Page.LIGHTING, "lighting.energy", true),
            section(Page.LIGHTING, "lighting.emissive", true),
            section(Page.LIGHTING, "lighting.emitterSampling", true),
            section(Page.SKY_ATMOSPHERE, "sky.atmosphere", false),
            section(Page.SKY_ATMOSPHERE, "sky.celestial", false),
            section(Page.SKY_ATMOSPHERE, "sky.stars", false),
            section(Page.SKY_ATMOSPHERE, "sky.airglow", false),
            section(Page.GEOMETRY_SCENE, "geometry.pathTracer", true),
            section(Page.GEOMETRY_SCENE, "geometry.scene", true),
            section(Page.GEOMETRY_SCENE, "geometry.surface", true),
            section(Page.SHARC, "sharc.foundation", false),
            section(Page.SHARC, "sharc.cadence", false),
            section(Page.SHARC, "sharc.transport", false),
            section(Page.SHARC, "sharc.telemetry", false),
            section(Page.MATERIALS, "materials.organic", true),
            section(Page.MATERIALS, "materials.finished", true),
            section(Page.EXPOSURE_TONEMAP, "exposure.intent", true),
            section(Page.EXPOSURE_TONEMAP, "exposure.metering", true),
            section(Page.EXPOSURE_TONEMAP, "exposure.adaptation", true),
            section(Page.EXPOSURE_TONEMAP, "exposure.histogram", true),
            section(Page.EXPOSURE_TONEMAP, "exposure.curves", true),
            section(Page.EXPOSURE_TONEMAP, "exposure.activeCurve", false),
            section(Page.FIRST_PERSON, "view.comfort", true),
            section(Page.FIRST_PERSON, "view.firstPerson", true),
            section(Page.FIRST_PERSON, "view.placement", true),
            section(Page.DIAGNOSTICS, "diagnostics.telemetry", true),
            section(Page.DIAGNOSTICS, "diagnostics.visual", true),
            section(Page.DIAGNOSTICS, "diagnostics.lighting", true)
    );
    private static final Map<String, Section> SECTIONS_BY_ID = indexSections();
    private static final List<Control> CONTROLS = createControls();
    private static final List<ControlDescriptor> ALL_CONTROLS = List.copyOf(CONTROLS);
    private static final Map<String, Control> CONTROLS_BY_ID = indexControlsById();
    private static final Map<String, Control> CONTROLS_BY_LABEL_KEY = indexControlsByLabelKey();
    private static final List<String> ESSENTIAL_IDS = List.of(
            "renderer.enabled", "rt.spp", "rt.maxBounces", "reconstruction.backend",
            "reconstruction.dlss.enabled", "reconstruction.dlss.quality", "display.hdr.enabled",
            "display.hdr.tonemapper", "display.hdr.paperWhite", "display.hdr.peak", "display.hdr.uiBrightness",
            "frameGeneration.mode", "frameGeneration.multiplier",
            "lighting.torchIntensity", "scene.entities");

    public static List<ControlDescriptor> allControls() {
        return ALL_CONTROLS;
    }

    /** Former tier-based landing-page selection, retained for source compatibility. */
    public static List<Control> essentials() {
        return CONTROLS.stream().filter(control -> control.tier() == Tier.ESSENTIAL).toList();
    }

    public static List<ControlDescriptor> essentialsProjection() {
        return ESSENTIAL_IDS.stream().map(CONTROLS_BY_ID::get).map(ControlDescriptor.class::cast).toList();
    }

    public static List<Control> forPage(Page page) {
        if (page == null) return List.of();
        return CONTROLS.stream().filter(control -> control.page() == page).toList();
    }

    public static Control byId(String id) {
        return id == null ? null : CONTROLS_BY_ID.get(id);
    }

    public static Control byLabelKey(String labelKey) {
        return labelKey == null ? null : CONTROLS_BY_LABEL_KEY.get(labelKey);
    }

    public static List<Section> allSections() {
        return SECTIONS;
    }

    public static List<Section> sectionsForPage(Page page) {
        if (page == null) return List.of();
        return SECTIONS.stream().filter(section -> section.page() == page).toList();
    }

    public static Section sectionById(String id) {
        return id == null ? null : SECTIONS_BY_ID.get(id);
    }

    public static Section section(String id) {
        return sectionById(id);
    }

    public static Section section(Page page, String id) {
        Section section = sectionById(id);
        return section != null && section.page() == page ? section : null;
    }

    private static Section section(Page page, String id, boolean defaultExpanded) {
        String key = "caustica.options.bundle." + id;
        return new Section(page, id, key, key + ".description", defaultExpanded);
    }

    private static Control control(String id, String labelKey, Page page, String section, Tier tier,
                                   ChangeEffect effect, String... aliases) {
        return new Control(id, labelKey, page, section, tier, effect, aliases);
    }

    private static List<Control> createControls() {
        List<Control> controls = new ArrayList<>(Arrays.asList(Control.legacyValues()));

        add(controls, Page.DENOISING, "reconstruction.nrdMode", Tier.EXPERT, ChangeEffect.TEMPORAL_RESET,
                "denoising", "caustica.options.rt.nrd.", "antiFirefly");
        add(controls, Page.DENOISING, "reconstruction.nrdHistory", Tier.EXPERT, ChangeEffect.TEMPORAL_RESET,
                "denoising", "caustica.options.rt.nrd.",
                "antilag", "maxHistory", "fastHistory", "historyFix");
        add(controls, Page.DENOISING, "reconstruction.nrdSpatial", Tier.EXPERT, ChangeEffect.TEMPORAL_RESET,
                "denoising", "caustica.options.rt.nrd.",
                "historyStride", "prepassRadius", "minRadius", "maxRadius", "lobeFraction",
                "roughnessFraction", "planeSensitivity");
        add(controls, Page.DENOISING, "reconstruction.nrdDiagnostics", Tier.EXPERT,
                ChangeEffect.TEMPORAL_RESET, "denoising", "caustica.options.rt.nrd.",
                "disocclusion", "hitA", "hitB", "hitC", "antilagSigma", "atrous", "splitScreen");
        add(controls, Page.DENOISING, "reconstruction.reblurAdvanced", Tier.EXPERT,
                ChangeEffect.TEMPORAL_RESET, "denoising", "caustica.options.rt.nrd.",
                "stabilizedHistory", "specularPrepass", "fastClampSigma", "minHitWeight", "fireflyScale",
                "responsiveRoughness", "responsiveMinFrames", "convergenceScale", "convergenceBase",
                "convergenceFraction", "antilagSensitivity");
        add(controls, Page.DENOISING, "reconstruction.relaxAdvanced", Tier.EXPERT,
                ChangeEffect.TEMPORAL_RESET, "denoising", "caustica.options.rt.nrd.",
                "relaxNormalPower", "relaxDiffusePhi", "relaxSpecularPhi", "relaxDepthThreshold",
                "relaxVarianceBoost", "relaxLobeSlack", "relaxRoughnessEdges",
                "relaxAntilagAcceleration", "relaxAntilagTemporal", "relaxAntilagReset");

        add(controls, Page.SKY_ATMOSPHERE, "sky.atmosphere", Tier.STANDARD, ChangeEffect.LIVE,
                "sky", "caustica.options.rt.sky.",
                "rayleigh", "dayRayleigh", "aerosolScatter", "aerosolAbsorption", "ozone", "aerosolHeight",
                "aerosolAnisotropy", "brightness", "saturation", "tintR", "tintG", "tintB");
        controls.add(control("sky.ambientLight", "caustica.options.rt.ambientLight", Page.SKY_ATMOSPHERE,
                "sky.atmosphere", Tier.STANDARD, ChangeEffect.LIVE, "environment light"));
        add(controls, Page.SKY_ATMOSPHERE, "sky.celestial", Tier.STANDARD, ChangeEffect.LIVE,
                "sky", "caustica.options.rt.sky.",
                "sunDiscBrightness", "moonDiscBrightness", "sunLimbDarkening", "sunTintR", "sunTintG",
                "sunTintB", "moonTintR", "moonTintG", "moonTintB");
        controls.add(control("sky.sunSize", "caustica.options.rt.sunSize", Page.SKY_ATMOSPHERE,
                "sky.celestial", Tier.STANDARD, ChangeEffect.LIVE, "sun disc size"));
        controls.add(control("sky.moonSize", "caustica.options.rt.moonSize", Page.SKY_ATMOSPHERE,
                "sky.celestial", Tier.STANDARD, ChangeEffect.LIVE, "moon disc size"));
        add(controls, Page.SKY_ATMOSPHERE, "sky.stars", Tier.STANDARD, ChangeEffect.LIVE,
                "sky", "caustica.options.rt.sky.",
                "starBrightness", "starDensity", "starSize", "starTintR", "starTintG", "starTintB");
        add(controls, Page.SKY_ATMOSPHERE, "sky.airglow", Tier.STANDARD, ChangeEffect.LIVE,
                "sky", "caustica.options.rt.sky.",
                "nightAirglow", "airglowHorizonR", "airglowHorizonG", "airglowHorizonB",
                "airglowZenithR", "airglowZenithG", "airglowZenithB");

        controls.add(control("geometry.psrMirrorDepth", "caustica.options.rt.psrMirrorDepth",
                Page.GEOMETRY_SCENE, "geometry.surface", Tier.EXPERT, ChangeEffect.LIVE,
                "mirror reflection depth"));
        controls.add(control("geometry.pointSampleMaxSize", "caustica.options.rt.pointSampleMaxSize",
                Page.GEOMETRY_SCENE, "geometry.surface", Tier.EXPERT, ChangeEffect.TEMPORAL_RESET,
                "point sample textures", "nearest filtering"));

        add(controls, Page.SHARC, "sharc.foundation", Tier.EXPERT, ChangeEffect.SHARC_RESET,
                "sharc", "caustica.options.rt.", "sharcMemory", "sharcSceneScale", "sharcRadianceScale");
        add(controls, Page.SHARC, "sharc.cadence", Tier.EXPERT, ChangeEffect.SHARC_RESET,
                "sharc", "caustica.options.rt.",
                "sharcAccumulationFrames", "sharcStaleFrames", "sharcUpdateTileSize",
                "sharcUpdateMaxBounces", "sharcMinSegmentRatio");
        add(controls, Page.SHARC, "sharc.transport", Tier.EXPERT, ChangeEffect.SHARC_RESET,
                "sharc", "caustica.options.rt.",
                "sharcGlossyQuery", "sharcLiveSecondaryDirect", "sharcPrimaryDiffuseReuse", "sharcAntiFirefly");
        controls.add(control("sharc.detailedStats", "caustica.options.rt.sharcDetailedStats", Page.SHARC,
                "sharc.telemetry", Tier.INTERNAL, ChangeEffect.LIVE, "cache statistics"));

        add(controls, Page.MATERIALS, "materials.organic", Tier.STANDARD, ChangeEffect.LIVE,
                "materials", "caustica.options.rt.materials.",
                "foliageBacklighting", "soilRoughness", "stoneRoughness", "woodRoughness");
        add(controls, Page.MATERIALS, "materials.finished", Tier.STANDARD, ChangeEffect.LIVE,
                "materials", "caustica.options.rt.materials.",
                "metalRoughness", "glassRoughness", "woolFiberSheen", "polishedRoughness");

        controls.add(control("exposure.key", "caustica.options.rt.exposureKey", Page.EXPOSURE_TONEMAP,
                "exposure.intent", Tier.STANDARD, ChangeEffect.LIVE, "meter target", "middle gray"));
        add(controls, Page.EXPOSURE_TONEMAP, "exposure.metering", Tier.EXPERT, ChangeEffect.LIVE,
                "exposure", "caustica.options.rt.",
                "exposureLowPercentile", "exposureHighPercentile", "exposureHighlightPercentile",
                "exposureHighlightHeadroom", "exposureCenterWeight");
        add(controls, Page.EXPOSURE_TONEMAP, "exposure.adaptation", Tier.EXPERT, ChangeEffect.LIVE,
                "exposure", "caustica.options.rt.",
                "exposureDarkAdapt", "exposureBrightAdapt", "exposureMinEv", "exposureMaxEv");
        add(controls, Page.EXPOSURE_TONEMAP, "exposure.histogram", Tier.EXPERT, ChangeEffect.LIVE,
                "exposure", "caustica.options.rt.", "exposureLogMin", "exposureLogMax");

        addToneControls(controls);
        return List.copyOf(controls);
    }

    private static void addToneControls(List<Control> controls) {
        add(controls, Page.EXPOSURE_TONEMAP, "exposure.activeCurve", Tier.EXPERT, ChangeEffect.LIVE,
                "tone.sdr.agx", "caustica.options.rt.", "sdrAgxContrast", "sdrAgxSaturation");
        add(controls, Page.EXPOSURE_TONEMAP, "exposure.activeCurve", Tier.EXPERT, ChangeEffect.LIVE,
                "tone.sdr.pbrNeutral", "caustica.options.rt.", "sdrPbrStartCompression", "sdrPbrDesaturation");
        add(controls, Page.EXPOSURE_TONEMAP, "exposure.activeCurve", Tier.EXPERT, ChangeEffect.LIVE,
                "tone.sdr.reinhard", "caustica.options.rt.", "sdrReinhardWhitePoint");
        add(controls, Page.EXPOSURE_TONEMAP, "exposure.activeCurve", Tier.EXPERT, ChangeEffect.LIVE,
                "tone.sdr.aces", "caustica.options.rt.", "sdrAcesExposure");
        add(controls, Page.EXPOSURE_TONEMAP, "exposure.activeCurve", Tier.EXPERT, ChangeEffect.LIVE,
                "tone.sdr.lottes", "caustica.options.rt.",
                "sdrLottesContrast", "sdrLottesShoulder", "sdrLottesHdrMax", "sdrLottesMidIn",
                "sdrLottesMidOut");
        add(controls, Page.EXPOSURE_TONEMAP, "exposure.activeCurve", Tier.EXPERT, ChangeEffect.LIVE,
                "tone.sdr.frostbite", "caustica.options.rt.",
                "sdrFrostbiteLinearEnd", "sdrFrostbiteShoulderStrength");
        add(controls, Page.EXPOSURE_TONEMAP, "exposure.activeCurve", Tier.EXPERT, ChangeEffect.LIVE,
                "tone.sdr.uncharted2", "caustica.options.rt.",
                "sdrUnchartedA", "sdrUnchartedB", "sdrUnchartedC", "sdrUnchartedD", "sdrUnchartedE",
                "sdrUnchartedF", "sdrUnchartedWhitePoint");
        add(controls, Page.EXPOSURE_TONEMAP, "exposure.activeCurve", Tier.EXPERT, ChangeEffect.LIVE,
                "tone.sdr.gt", "caustica.options.rt.",
                "sdrGtContrast", "sdrGtLinearStart", "sdrGtLinearLength", "sdrGtBlackCurve", "sdrGtBlackLift");
        controls.add(control("tone.sdr.psychov.peak", "caustica.options.rt.sdrPsychoPeak",
                Page.EXPOSURE_TONEMAP, "exposure.activeCurve", Tier.EXPERT, ChangeEffect.LIVE, "psycho v peak"));
        controls.add(control("tone.psychov23.sdrPeak", "caustica.options.rt.sdrPsychoV23Peak",
                Page.EXPOSURE_TONEMAP, "exposure.activeCurve", Tier.EXPERT, ChangeEffect.LIVE,
                "psycho v24 peak"));
        controls.add(control("tone.psychov23.compression", "caustica.options.rt.psychoV23Compression",
                Page.EXPOSURE_TONEMAP, "exposure.activeCurve", Tier.EXPERT, ChangeEffect.LIVE,
                "psycho v24 compression"));
        controls.add(control("tone.psychov23.gamutCompression",
                "caustica.options.rt.psychoV23GamutCompression", Page.EXPOSURE_TONEMAP,
                "exposure.activeCurve", Tier.EXPERT, ChangeEffect.LIVE, "psycho v24 gamut"));
        add(controls, Page.EXPOSURE_TONEMAP, "exposure.activeCurve", Tier.EXPERT, ChangeEffect.LIVE,
                "tone.psychov", "caustica.options.rt.",
                "hdrPsychoHighlights", "hdrPsychoShadows", "hdrPsychoContrast", "hdrPsychoPurity",
                "hdrPsychoHueRestore", "hdrPsychoAdaptContrast", "hdrPsychoConeExponent");
        add(controls, Page.EXPOSURE_TONEMAP, "exposure.activeCurve", Tier.EXPERT, ChangeEffect.LIVE,
                "tone.psychov", "caustica.options.rt.",
                "hdrPsychoBleaching", "hdrPsychoClipPoint", "hdrPsychoWhiteCurve");
    }

    private static void add(List<Control> controls, Page page, String section, Tier tier, ChangeEffect effect,
                            String idPrefix, String labelPrefix, String... names) {
        for (String name : names) {
            controls.add(control(idPrefix + "." + idSuffix(idPrefix, name), labelPrefix + name,
                    page, section, tier, effect, searchText(name)));
        }
    }

    private static String idSuffix(String idPrefix, String name) {
        String removablePrefix = switch (idPrefix) {
            case "exposure" -> "exposure";
            case "sharc" -> "sharc";
            case "tone.sdr.agx" -> "sdrAgx";
            case "tone.sdr.pbrNeutral" -> "sdrPbr";
            case "tone.sdr.reinhard" -> "sdrReinhard";
            case "tone.sdr.aces" -> "sdrAces";
            case "tone.sdr.lottes" -> "sdrLottes";
            case "tone.sdr.frostbite" -> "sdrFrostbite";
            case "tone.sdr.uncharted2" -> "sdrUncharted";
            case "tone.sdr.gt" -> "sdrGt";
            case "tone.psychov" -> "hdrPsycho";
            default -> "";
        };
        if (removablePrefix.isEmpty() || !name.startsWith(removablePrefix)
                || name.length() == removablePrefix.length()) return name;
        return Character.toLowerCase(name.charAt(removablePrefix.length()))
                + name.substring(removablePrefix.length() + 1);
    }

    private static Map<String, Section> indexSections() {
        Map<String, Section> indexed = new LinkedHashMap<>();
        for (Section section : SECTIONS) {
            if (indexed.putIfAbsent(section.id(), section) != null) {
                throw new IllegalStateException("Duplicate settings section id: " + section.id());
            }
        }
        return Map.copyOf(indexed);
    }

    private static Map<String, Control> indexControlsById() {
        Map<String, Control> indexed = new LinkedHashMap<>();
        for (Control control : CONTROLS) {
            if (indexed.putIfAbsent(control.id(), control) != null) {
                throw new IllegalStateException("Duplicate settings control id: " + control.id());
            }
            Section section = SECTIONS_BY_ID.get(control.section());
            if (section == null || section.page() != control.page()) {
                throw new IllegalStateException("Invalid section " + control.section() + " for " + control.id());
            }
        }
        return Map.copyOf(indexed);
    }

    private static Map<String, Control> indexControlsByLabelKey() {
        Map<String, Control> indexed = new LinkedHashMap<>();
        for (Control control : CONTROLS) {
            if (indexed.putIfAbsent(control.labelKey(), control) != null) {
                throw new IllegalStateException("Duplicate settings label key: " + control.labelKey());
            }
        }
        return Map.copyOf(indexed);
    }

    private static String routeToken(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private static String searchText(String value) {
        if (value == null) return "";
        String words = value.replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ");
        return words.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ').replace('.', ' ')
                .replaceAll("\\s+", " ").trim();
    }
}
