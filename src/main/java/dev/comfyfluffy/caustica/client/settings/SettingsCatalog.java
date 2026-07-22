package dev.comfyfluffy.caustica.client.settings;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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

        public String labelKey() {
            return "caustica.options.group." + routeId;
        }

        public Component label() { return Component.translatable(labelKey()); }
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
            this.aliases = Arrays.stream(aliases).map(SettingsCatalog::routeToken)
                    .collect(Collectors.toUnmodifiableSet());
        }

        public Group group() {
            return group;
        }

        public String routeId() {
            return routeId;
        }

        public String labelKey() {
            return "caustica.options.category." + routeId;
        }

        public Component label() { return Component.translatable(labelKey()); }

        public static Page parse(String raw) {
            if (raw == null || raw.isBlank()) return ESSENTIALS;
            String token = routeToken(raw);
            for (Page page : values()) {
                if (routeToken(page.name()).equals(token) || routeToken(page.routeId).equals(token)
                        || page.aliases.contains(token)) return page;
            }
            return ESSENTIALS;
        }
    }

    public enum Tier { ESSENTIAL, STANDARD, EXPERT, INTERNAL, COMPATIBILITY }

    public enum ChangeEffect {
        LIVE, TEMPORAL_RESET, SHARC_RESET, SCENE_REBUILD, RESOLUTION_REBUILD,
        SWAPCHAIN_RECREATE, RESTART_REQUIRED
    }

    public enum Control {
        RENDERER_ENABLED("renderer.enabled", "caustica.options.enabled", Page.GEOMETRY_SCENE, "pathTracer", Tier.ESSENTIAL, ChangeEffect.LIVE, "renderer", "ray tracing"),
        SAMPLES_PER_PIXEL("rt.spp", "caustica.options.rt.spp", Page.GEOMETRY_SCENE, "pathTracer", Tier.ESSENTIAL, ChangeEffect.TEMPORAL_RESET, "spp", "samples"),
        MAX_BOUNCES("rt.maxBounces", "caustica.options.rt.maxBounces", Page.GEOMETRY_SCENE, "pathTracer", Tier.ESSENTIAL, ChangeEffect.TEMPORAL_RESET, "ray depth", "bounces"),
        CELESTIAL_BOUNCES("rt.celestialBounces", "caustica.options.rt.celestialLightBounces", Page.GEOMETRY_SCENE, "pathTracer", Tier.ESSENTIAL, ChangeEffect.TEMPORAL_RESET, "light bounces"),
        RECONSTRUCTION_BACKEND("reconstruction.backend", "caustica.options.rt.reconstructionBackend", Page.RECONSTRUCTION, "backend", Tier.ESSENTIAL, ChangeEffect.RESOLUTION_REBUILD, "dlss", "nrd"),
        DLSS_RR_ENABLED("reconstruction.dlss.enabled", "caustica.options.rt.dlssRr", Page.RECONSTRUCTION, "dlss", Tier.ESSENTIAL, ChangeEffect.TEMPORAL_RESET, "ray reconstruction"),
        DLSS_QUALITY("reconstruction.dlss.quality", "caustica.options.rt.dlssQuality", Page.RECONSTRUCTION, "dlss", Tier.ESSENTIAL, ChangeEffect.RESOLUTION_REBUILD, "quality", "balanced", "performance"),
        DLSS_PRESET("reconstruction.dlss.preset", "caustica.options.rt.dlssPreset", Page.RECONSTRUCTION, "dlss", Tier.ESSENTIAL, ChangeEffect.TEMPORAL_RESET, "preset d", "preset e"),
        DLSS_INPUT_RATIO("reconstruction.dlss.inputRatio", "caustica.options.rt.dlssInputRatio", Page.RECONSTRUCTION, "scaling", Tier.ESSENTIAL, ChangeEffect.RESOLUTION_REBUILD, "input scale", "upscale ratio", "resolution"),
        HDR_ENABLED("display.hdr.enabled", "caustica.options.rt.hdr", Page.DISPLAY_HDR, "output", Tier.ESSENTIAL, ChangeEffect.SWAPCHAIN_RECREATE, "hdr10", "pq"),
        HDR_TONEMAPPER("display.hdr.tonemapper", "caustica.options.rt.hdrTonemap", Page.DISPLAY_HDR, "calibration", Tier.ESSENTIAL, ChangeEffect.LIVE, "tone map", "eetf"),
        HDR_PAPER_WHITE("display.hdr.paperWhite", "caustica.options.rt.hdrPaperWhite", Page.DISPLAY_HDR, "calibration", Tier.ESSENTIAL, ChangeEffect.LIVE, "paper white", "nits"),
        HDR_PEAK("display.hdr.peak", "caustica.options.rt.hdrPeak", Page.DISPLAY_HDR, "calibration", Tier.ESSENTIAL, ChangeEffect.LIVE, "peak brightness", "nits"),
        HDR_UI_BRIGHTNESS("display.hdr.uiBrightness", "caustica.options.rt.hdrUiBrightness", Page.DISPLAY_HDR, "calibration", Tier.ESSENTIAL, ChangeEffect.LIVE, "hud brightness", "ui brightness"),
        SDR_TONEMAPPER("display.sdr.tonemapper", "caustica.options.rt.sdrTonemap", Page.DISPLAY_HDR, "output", Tier.ESSENTIAL, ChangeEffect.LIVE, "sdr", "tone map"),
        FG_MODE("frameGeneration.mode", "caustica.options.rt.fg.mode", Page.FRAME_GENERATION, "core", Tier.ESSENTIAL, ChangeEffect.SWAPCHAIN_RECREATE, "fg", "dlss g"),
        FG_MULTIPLIER("frameGeneration.multiplier", "caustica.options.rt.fg.multiplier", Page.FRAME_GENERATION, "core", Tier.ESSENTIAL, ChangeEffect.LIVE, "multi frame", "2x", "3x", "4x"),
        FG_REFLEX("frameGeneration.reflex", "caustica.options.rt.fg.reflex", Page.FRAME_GENERATION, "latency", Tier.ESSENTIAL, ChangeEffect.LIVE, "latency", "boost"),
        FG_VSYNC("frameGeneration.vsync", "caustica.options.rt.fg.vsync", Page.FRAME_GENERATION, "latency", Tier.ESSENTIAL, ChangeEffect.SWAPCHAIN_RECREATE, "vertical sync", "mailbox", "fifo"),
        EXPOSURE_MODE("exposure.mode", "caustica.options.rt.exposureMode", Page.EXPOSURE_TONEMAP, "basic", Tier.ESSENTIAL, ChangeEffect.LIVE, "auto exposure", "manual exposure"),
        MANUAL_EV("exposure.manualEv", "caustica.options.rt.manualEv", Page.EXPOSURE_TONEMAP, "basic", Tier.ESSENTIAL, ChangeEffect.LIVE, "ev"),
        EXPOSURE_COMPENSATION("exposure.compensation", "caustica.options.rt.exposureCompensation", Page.EXPOSURE_TONEMAP, "basic", Tier.ESSENTIAL, ChangeEffect.LIVE, "ev"),
        TORCH_INTENSITY("lighting.torchIntensity", "caustica.options.rt.torchIntensity", Page.LIGHTING, "emissive", Tier.ESSENTIAL, ChangeEffect.LIVE, "torch", "cd/m2"),
        ENTITIES("scene.entities", "caustica.options.rt.entities", Page.GEOMETRY_SCENE, "scene", Tier.ESSENTIAL, ChangeEffect.SCENE_REBUILD, "mobs"),
        PARTICLES("scene.particles", "caustica.options.rt.particles", Page.GEOMETRY_SCENE, "scene", Tier.ESSENTIAL, ChangeEffect.SCENE_REBUILD, "particle rendering"),
        WATER_WAVES("scene.waterWaves", "caustica.options.rt.waterWaves", Page.GEOMETRY_SCENE, "surface", Tier.ESSENTIAL, ChangeEffect.LIVE, "water"),
        FIRST_PERSON_ENABLED("firstPerson.enabled", "caustica.options.rt.firstPerson.enabled", Page.FIRST_PERSON, "model", Tier.ESSENTIAL, ChangeEffect.LIVE, "hand", "held item"),
        SHARC_ENABLED("sharc.enabled", "caustica.options.rt.sharc", Page.SHARC, "core", Tier.ESSENTIAL, ChangeEffect.SHARC_RESET, "radiance cache", "indirect lighting"),
        FG_QUEUE("frameGeneration.queueParallelism", "caustica.options.rt.fg.queueParallelism", Page.FRAME_GENERATION, "scheduling", Tier.EXPERT, ChangeEffect.LIVE, "parallel queue"),
        FG_OUTPUT_TARGET("frameGeneration.outputTarget", "caustica.options.rt.fg.outputTarget", Page.FRAME_GENERATION, "scheduling", Tier.EXPERT, ChangeEffect.LIVE, "target fps"),
        FG_UI_RECOMPOSITION("frameGeneration.uiRecomposition", "caustica.options.rt.fg.uiRecomposition", Page.FRAME_GENERATION, "presentation", Tier.STANDARD, ChangeEffect.LIVE, "hud"),
        FG_FULLSCREEN_MENU("frameGeneration.fullscreenMenu", "caustica.options.rt.fg.fullscreenMenu", Page.FRAME_GENERATION, "presentation", Tier.EXPERT, ChangeEffect.LIVE, "menu detection"),
        DLSS_PATH_GUIDE("reconstruction.dlss.pathGuide", "caustica.options.rt.dlssDiffusePathGuide", Page.RECONSTRUCTION, "dlss", Tier.EXPERT, ChangeEffect.TEMPORAL_RESET, "path guide"),
        DLSS_SUBPIXEL_DETAIL("reconstruction.dlss.subpixelDetail", "caustica.options.rt.subpixelDetail", Page.RECONSTRUCTION, "dlss", Tier.EXPERT, ChangeEffect.TEMPORAL_RESET, "texture detail"),
        TRANSPARENCY_TRANSPORT("reconstruction.transparency", "caustica.options.rt.highQualityTransparency", Page.RECONSTRUCTION, "backend", Tier.EXPERT, ChangeEffect.TEMPORAL_RESET, "optical transport"),
        PARTICLE_HISTORY("reconstruction.particleHistory", "caustica.options.rt.particleTemporalHistory", Page.RECONSTRUCTION, "dlss", Tier.EXPERT, ChangeEffect.TEMPORAL_RESET, "particle history"),
        NRD_DENOISER("denoising.denoiser", "caustica.options.rt.nrd.denoiser", Page.DENOISING, "mode", Tier.STANDARD, ChangeEffect.TEMPORAL_RESET, "reblur", "relax"),
        NRD_SH("denoising.sphericalHarmonics", "caustica.options.rt.nrd.sh", Page.DENOISING, "mode", Tier.EXPERT, ChangeEffect.TEMPORAL_RESET, "spherical harmonics"),
        NRD_UPSCALE_MODE("denoising.upscaleMode", "caustica.options.rt.nrd.upscaleMode", Page.DENOISING, "upscale", Tier.STANDARD, ChangeEffect.RESOLUTION_REBUILD, "render scale"),
        NRD_RENDER_SCALE("denoising.renderScale", "caustica.options.rt.nrd.renderScale", Page.DENOISING, "upscale", Tier.STANDARD, ChangeEffect.RESOLUTION_REBUILD, "custom scale"),
        NRD_UPSCALE_FILTER("denoising.upscaleFilter", "caustica.options.rt.nrd.upscaleFilter", Page.DENOISING, "upscale", Tier.EXPERT, ChangeEffect.LIVE, "edge adaptive"),
        NRD_SHARPNESS("denoising.sharpness", "caustica.options.rt.nrd.upscaleSharpness", Page.DENOISING, "upscale", Tier.EXPERT, ChangeEffect.LIVE, "sharpen"),
        SUNLIGHT("lighting.sunlight", "caustica.options.rt.sunlightIntensity", Page.LIGHTING, "energy", Tier.STANDARD, ChangeEffect.LIVE, "sun"),
        MOONLIGHT("lighting.moonlight", "caustica.options.rt.moonlightIntensity", Page.LIGHTING, "energy", Tier.STANDARD, ChangeEffect.LIVE, "moon"),
        RIS_CANDIDATES("lighting.risCandidates", "caustica.options.rt.risCandidates", Page.LIGHTING, "sampling", Tier.EXPERT, ChangeEffect.SCENE_REBUILD, "light candidates"),
        LIGHT_FILL("lighting.minimumFill", "caustica.options.rt.lightMinFillRatio", Page.LIGHTING, "sampling", Tier.EXPERT, ChangeEffect.SCENE_REBUILD, "fill ratio"),
        LIGHT_STATS("diagnostics.lightStats", "caustica.options.rt.lightStats", Page.DIAGNOSTICS, "lighting", Tier.INTERNAL, ChangeEffect.LIVE, "light statistics"),
        LIGHT_DUMP("diagnostics.lightDump", "caustica.options.rt.lightDump", Page.DIAGNOSTICS, "lighting", Tier.INTERNAL, ChangeEffect.LIVE, "dump lights"),
        LIGHT_DUMP_RADIUS("diagnostics.lightDumpRadius", "caustica.options.rt.lightDumpRadius", Page.DIAGNOSTICS, "lighting", Tier.INTERNAL, ChangeEffect.LIVE, "dump radius"),
        FRAME_STATS("diagnostics.frameStats", "caustica.options.frameStats", Page.DIAGNOSTICS, "telemetry", Tier.INTERNAL, ChangeEffect.LIVE, "timings"),
        DEBUG_VIEW("diagnostics.debugView", "caustica.options.rt.debugView", Page.DIAGNOSTICS, "visual", Tier.INTERNAL, ChangeEffect.LIVE, "visualization"),
        DYNAMIC_FOV("firstPerson.dynamicFov", "caustica.options.view.dynamicFov", Page.FIRST_PERSON, "comfort", Tier.STANDARD, ChangeEffect.LIVE, "fov"),
        FIRST_PERSON_VANILLA("firstPerson.disableVanilla", "caustica.options.rt.firstPerson.disableVanillaModel", Page.FIRST_PERSON, "model", Tier.EXPERT, ChangeEffect.LIVE, "vanilla hand"),
        FIRST_PERSON_FORWARD("firstPerson.forward", "caustica.options.rt.firstPerson.forward", Page.FIRST_PERSON, "placement", Tier.STANDARD, ChangeEffect.LIVE, "forward offset"),
        FIRST_PERSON_VERTICAL("firstPerson.vertical", "caustica.options.rt.firstPerson.vertical", Page.FIRST_PERSON, "placement", Tier.STANDARD, ChangeEffect.LIVE, "vertical offset"),
        FIRST_PERSON_LATERAL("firstPerson.lateral", "caustica.options.rt.firstPerson.lateral", Page.FIRST_PERSON, "placement", Tier.STANDARD, ChangeEffect.LIVE, "lateral offset");

        private final String id;
        private final String labelKey;
        private final Page page;
        private final String section;
        private final Tier tier;
        private final ChangeEffect effect;
        private final List<String> aliases;

        Control(String id, String labelKey, Page page, String section, Tier tier, ChangeEffect effect,
                String... aliases) {
            this.id = id;
            this.labelKey = labelKey;
            this.page = page;
            this.section = section;
            this.tier = tier;
            this.effect = effect;
            this.aliases = List.of(aliases);
        }

        public String id() { return id; }
        public String labelKey() { return labelKey; }
        public Page page() { return page; }
        public String section() { return section; }
        public Tier tier() { return tier; }
        public ChangeEffect effect() { return effect; }
        public List<String> aliases() { return aliases; }
        public String sectionLabelKey() { return "caustica.options.section." + page.routeId() + "." + section; }

        public boolean matches(String query, String label, String pageLabel, String sectionLabel) {
            String normalized = searchText(query);
            if (normalized.isBlank()) return false;
            String haystack = searchText(String.join(" ", id, labelKey, page.name(), page.routeId(), section,
                    label, pageLabel, sectionLabel, String.join(" ", aliases)));
            return Arrays.stream(normalized.split("\\s+")).allMatch(haystack::contains);
        }
    }

    public static List<Control> essentials() {
        return Arrays.stream(Control.values()).filter(control -> control.tier() == Tier.ESSENTIAL).toList();
    }

    public static List<Control> forPage(Page page) {
        return Arrays.stream(Control.values()).filter(control -> control.page() == page).toList();
    }

    public static Control byId(String id) {
        if (id == null) return null;
        return Arrays.stream(Control.values()).filter(control -> control.id().equals(id)).findFirst().orElse(null);
    }

    public static Control byLocalizedLabel(String label) {
        if (label == null) return null;
        return Arrays.stream(Control.values())
                .filter(control -> Component.translatable(control.labelKey()).getString().equals(label))
                .findFirst().orElse(null);
    }

    private static String routeToken(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private static String searchText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ')
                .replaceAll("\\s+", " ").trim();
    }
}
