package dev.comfyfluffy.caustica;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.electronwill.nightconfig.toml.TomlFormat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.DoubleUnaryOperator;
import java.util.function.IntUnaryOperator;
import java.util.function.UnaryOperator;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central mutable runtime configuration. Every setting keeps its user-configured TOML value separate from
 * an optional {@code -Dcaustica.*} launch override. Reads return the launch override while it is present,
 * but UI writes and {@link #save()} only change the configured value. A temporary launcher argument can
 * therefore diagnose a session without silently becoming permanent configuration.
 *
 * <p>The system property namespace ({@code caustica.rt.foo}) and the TOML layout are independent: the file
 * uses real nested tables (e.g. {@code [omm]} with a {@code subdivision} key) grouped for readability, while
 * the property namespace stays flat and dotted for convenient one-off {@code -D} overrides.
 */
public final class CausticaConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");
    private static final List<RuntimeSetting<?>> SETTINGS = new CopyOnWriteArrayList<>();
    static final int CONFIG_SCHEMA_VERSION = 13;

    private static final Path CONFIG_PATH = resolveConfigPath();
    private static final CommentedFileConfig FILE = loadAndMigrateFile(CONFIG_PATH);

    private CausticaConfig() {
    }

    public static List<RuntimeSetting<?>> settings() {
        ensureRegistered();
        return List.copyOf(SETTINGS);
    }

    public static List<RuntimeSetting<?>> activeOverrides() {
        ensureRegistered();
        return SETTINGS.stream().filter(RuntimeSetting::isOverridden).toList();
    }

    public static Path configPath() {
        return CONFIG_PATH;
    }

    public static void reloadFromSystemProperties() {
        for (RuntimeSetting<?> setting : SETTINGS) {
            setting.reloadFromSystemProperties();
        }
    }

    /** Fingerprint of every live renderer setting; offline sessions use it to reject mixed estimators. */
    public static int renderSettingsSignature() {
        ensureRegistered();
        int hash = 1;
        for (RuntimeSetting<?> setting : SETTINGS) {
            hash = 31 * hash + setting.key().hashCode();
            hash = 31 * hash + Objects.hashCode(setting.get());
        }
        return hash;
    }

    /**
     * Forces every settings holder to class-initialize so all settings are registered (and have applied
     * their file values). Call before {@link #save()} to write a complete file, and once at startup so the
     * file round-trips the full surface even for settings the renderer has not touched yet.
     */
    public static void ensureRegistered() {
        @SuppressWarnings("unused")
        Object[] touch = {
            Rt.ENABLED, Rt.Composite.SPP, Rt.Composite.MAX_BOUNCES, Rt.Composite.CELESTIAL_LIGHT_BOUNCES,
            Rt.Terrain.ASYNC_DISPATCH_PER_PASS, Rt.Terrain.COMPLETION_RESULTS_PER_PASS,
            Rt.Terrain.MAX_INFLIGHT_SECTIONS, Rt.Terrain.STREAM_BUDGET_MS,
            Rt.Terrain.STREAM_BUDGET_MAX_MS, Rt.Terrain.STREAM_FALLBACK_BUDGET_MS, Rt.Omm.ENABLED,
            Rt.Entities.ENABLED, Rt.Entities.GLOW_ENABLED, Rt.FirstPerson.ENABLED,
            Rt.FirstPerson.DISABLE_VANILLA_MODEL, Rt.EntityTextures.MAX_TEXTURES,
            Rt.Reconstruction.BACKEND, Rt.DlssRr.ENABLED, Rt.DlssRr.DIFFUSE_PATH_GUIDE,
            Rt.DlssRr.SUBPIXEL_DETAIL,
            Rt.DlssRr.HIGH_QUALITY_TRANSPARENCY, Rt.DlssRr.PARTICLE_TEMPORAL_HISTORY,
            Rt.DlssRr.PRESET, Rt.DlssRr.QUALITY, Rt.DlssRr.INPUT_RATIO_TENTHS, Rt.OutputScale.PERCENT,
            Rt.Nrd.DENOISER, Rt.Nrd.SPHERICAL_HARMONICS, Rt.Nrd.MAX_ACCUMULATED_FRAMES,
            Rt.Nrd.MAX_FAST_ACCUMULATED_FRAMES, Rt.Nrd.HISTORY_FIX_FRAMES,
            Rt.Nrd.PREPASS_BLUR_RADIUS, Rt.Nrd.MAX_BLUR_RADIUS, Rt.Nrd.ANTI_FIREFLY,
            Rt.Fg.ENABLED, Rt.Fg.MODE, Rt.Fg.MULTI_FRAME_COUNT, Rt.Fg.DYNAMIC_TARGET_FPS,
            Rt.Fg.OUTPUT_TARGET_FPS, Rt.Fg.QUEUE_PARALLELISM,
            Rt.Reflex.ENABLED, Rt.Exposure.MODE, Rt.FrameStats.ENABLED,
            Rt.Sharc.ENABLED, Rt.Sharc.PRIMARY_DIFFUSE_REUSE, Rt.Sharc.CACHE_EXPONENT, Rt.Sharc.UPDATE_TILE_SIZE,
            Rt.Sdr.TONEMAP_MODE, Rt.Hdr.ENABLED, Rt.Hdr.TONEMAP_MODE, Rt.PsychoV23.COMPRESSION,
            Rt.Composite.POINT_SAMPLE_MAX_SIZE,
            Rt.Materials.FOLIAGE_BACKLIGHTING, Rt.Materials.SOIL_ROUGHNESS,
            Rt.Materials.STONE_ROUGHNESS, Rt.Materials.WOOD_ROUGHNESS,
            Rt.Materials.METAL_ROUGHNESS, Rt.Materials.GLASS_ROUGHNESS,
            Rt.Materials.WOOL_FIBER_SHEEN, Rt.Materials.POLISHED_ROUGHNESS,
        };
    }

    /** Writes the default config file if it does not exist yet. */
    public static void saveIfMissing() {
        ensureRegistered();
        if (FILE.valueMap().isEmpty()) {
            save();
        }
    }

    /** Serializes all registered settings to the TOML config file. */
    public static synchronized void save() {
        ensureRegistered();
        // Streamline Dynamic MFG is D3D12-only. Caustica's Vulkan renderer preserves the user's generated
        // frame count and migrates a stale dynamic selection to the supported fixed mode on the next save.
        if ("dynamic".equals(Rt.Fg.MODE.configuredValue())) {
            Rt.Fg.setMode("fixed");
        }
        // Keep the legacy boolean synchronized with MODE so both TOML keys agree after save.
        Rt.Fg.ENABLED.set(!"off".equals(Rt.Fg.MODE.configuredValue()));
        // Pre-release settings used absolute first-person offsets and duplicated PsychoV23 output controls.
        // The tested pose is now the zero adjustment origin and PsychoV23's shared stages have one owner.
        FILE.remove("first-person.forward-offset");
        FILE.remove("first-person.vertical-offset");
        FILE.remove("first-person.lateral-offset");
        FILE.remove("sdr.psychov23.compression");
        FILE.remove("sdr.psychov23.gamut-compression");
        FILE.remove("hdr.psychov23.compression");
        FILE.remove("hdr.psychov23.gamut-compression");
        FILE.remove("offline-renderer.adaptive");
        FILE.remove("offline-renderer.samples-per-batch");
        FILE.remove("offline-renderer.min-samples");
        FILE.remove("offline-renderer.max-samples");
        FILE.remove("offline-renderer.max-bounces");
        FILE.remove("offline-renderer.relative-error");
        FILE.remove("offline-renderer.absolute-error");
        FILE.remove("offline-renderer.save-exr");
        FILE.remove("offline-renderer.save-png");
        // Vanilla's environment timeline is the sole celestial transform. The retired tilt key is
        // deliberately discarded so an old profile can never skew the sun, moon, stars, and shadows.
        FILE.remove("composite.sun-noon-south-tilt-deg");
        FILE.set("config-version", CONFIG_SCHEMA_VERSION);
        writeComments();
        for (RuntimeSetting<?> setting : SETTINGS) {
            setting.writeToFile(FILE);
        }
        FILE.save();
    }

    private static void writeComments() {
        FILE.setComment("config-version",
                " Internal migration level. Caustica only changes values that exactly match obsolete defaults.");
        FILE.setComment("enabled",
                " Caustica RT renderer configuration.\n"
                        + " A matching -Dcaustica.* system property overrides the value below.");
        FILE.setComment("terrain",
                " Render-thread terrain work is bounded by adaptive time budgets and hard dispatch/result ceilings.\n"
                        + " Buffer fill and BLAS/OMM preparation run on workers. max-inflight-sections bounds\n"
                        + " the complete snapshot -> worker -> GPU build -> publication lifecycle.");
        FILE.setComment("frame-generation",
                " Streamline DLSS Frame Generation. mode is off or fixed; legacy auto/dynamic values migrate to fixed.\n"
                        + " multi-frame-count is generated frames per rendered frame (1 = 2x, 2 = 3x, ...).\n"
                        + " Dynamic MFG is D3D12-only and is migrated to fixed because Caustica uses Vulkan.\n"
                        + " With VSync requested, Vulkan DLSS-G uses MAILBOX presentation. With VSync off,\n"
                        + " it uses IMMEDIATE presentation. DLSS-G itself is never frame-limited by Reflex.");
        FILE.setComment("reconstruction",
                " Global reconstruction backend: auto, nrd, dlss-rr, or off. Auto prefers NRD on Linux/AMD/Intel\n"
                        + " and DLSS-RR on supported Windows NVIDIA systems, with NRD as the support-failure fallback.");
        FILE.setComment("nrd",
                " Live NVIDIA Real-time Denoisers settings for the vendor-neutral Vulkan backend.\n"
                        + " REBLUR is the default; RELAX and directional SH/SG are explicit opt-ins.\n"
                        + " Backend, denoiser, and SH changes recreate resources and reset temporal history.");
        FILE.setComment("reflex",
                " Streamline Reflex Low Latency. DLSS-G forces effective On while generation is active.\n"
                        + " minimum-interval-us applies only while DLSS-G is off; DLSS-G always submits 0 (unlimited).\n"
                        + " PCL markers and sleep still run when Reflex is Off.");
        FILE.setComment("lights",
                " RIS direct lighting from block emitters (torches, glowstone, lava, ...): per diffuse\n"
                        + " vertex, resample ris-candidates power-weighted proposals and spend one shadow ray on\n"
                        + " the survivor. ris-candidates = 0 disables it entirely (emitters just gather on direct\n"
                        + " hit, same as with no NEE). Power-weighted sampling and the local per-section light\n"
                        + " grid are always active whenever RIS is on. min-fill-ratio drops emissive footprints\n"
                        + " below that fraction of their bounding rectangle (speckle/sparse crossed planes), so\n"
                        + " only reasonably compact glows become lights. stats/dump/dump-radius are debug logging.");
        FILE.setComment("offline-renderer",
                " Uncapped progressive native-resolution rendering started with F7.\n"
                        + " The scene, camera, water time, and exposure are frozen for the session.\n"
                        + " The HUD stays transparent; screenshots are always initiated by the player.");
        FILE.setComment("sdr",
                " SDR display mapping for the vanilla main target. tonemap-mode defaults to psychov23.\n"
                        + " Caustica's existing SDR look. Other modes are pbr-neutral, reinhard, aces, lottes,\n"
                        + " frostbite, uncharted2, gt, psychov, and the experimental psychov23. Nested tables hold\n"
                        + " per-tonemapper tuning controls; only the selected mode's controls are pushed to the display shader.");
        FILE.setComment("hdr",
                " HDR display output (ST.2084/PQ). When enabled the swapchain is created in PQ automatically\n"
                        + " (falls back to SDR if the surface doesn't advertise it). paper-white-nits / peak-nits\n"
                        + " drive the scene-HDR -> display mapping. tonemap-mode selects the HDR display map:\n"
                        + " eetf is the BT.2390 PQ electrical-electrical transfer function; caustica is the\n"
                        + " original Caustica highlight rolloff; psychov and psychov23 are perceptual PsychoV maps.\n"
                        + " psychov23.compression=0 uses the automatic psychophysical reference-range fit.");
    }

    private static Path resolveConfigPath() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve("caustica.toml");
        } catch (Throwable t) {
            return Path.of("config", "caustica.toml");
        }
    }

    private static CommentedFileConfig loadAndMigrateFile(Path path) {
        boolean existed = Files.isRegularFile(path);
        CommentedFileConfig config = CommentedFileConfig.builder(path, TomlFormat.instance())
                .onFileNotFound(FileNotFoundAction.CREATE_EMPTY)
                .preserveInsertionOrder()
                .sync()
                .build();
        try {
            config.load();
        } catch (Exception e) {
            LOGGER.warn("Failed to read Caustica config {}: {}", path, e.toString());
            preserveBrokenConfig(path);
            config.clear();
            return config;
        }
        boolean fresh = !existed || config.valueMap().isEmpty();
        if (!fresh && migrateLegacySceneConfig(config)) {
            try {
                config.save();
                LOGGER.info("Migrated obsolete physical-scene defaults in {} to schema {}",
                        path, CONFIG_SCHEMA_VERSION);
            } catch (Exception e) {
                LOGGER.warn("Failed to save migrated Caustica config {}: {}", path, e.toString());
            }
        }
        return config;
    }

    private static void preserveBrokenConfig(Path path) {
        if (!Files.isRegularFile(path)) return;
        Path backup = path.resolveSibling(path.getFileName() + ".broken-" + System.currentTimeMillis());
        try {
            Files.copy(path, backup, StandardCopyOption.COPY_ATTRIBUTES);
            LOGGER.warn("Preserved unreadable Caustica config as {}", backup);
        } catch (IOException backupFailure) {
            LOGGER.warn("Could not preserve unreadable Caustica config {}: {}", path, backupFailure.toString());
        }
    }

    /**
     * One-time, conservative migration for the pre-physical-exposure defaults. User-authored values are
     * preserved: a field changes only when it still equals the exact obsolete default. Package-private
     * so the migration contract can be tested without touching the real config file.
     */
    static boolean migrateLegacySceneConfig(CommentedConfig config) {
        Object rawVersion = config.get("config-version");
        int version = rawVersion instanceof Number number ? number.intValue() : 0;
        if (version >= CONFIG_SCHEMA_VERSION) {
            return false;
        }
        if (version < 2) {
            migrateExactNumber(config, "exposure.min-ev", -1.5, -20.0);
            migrateExactNumber(config, "exposure.max-ev", 2.0, 16.0);
            migrateExactNumber(config, "exposure.adapt-up", 0.12, 3.0);
            migrateExactNumber(config, "exposure.adapt-down", 0.35, 2.0);
        }
        if (version < 3) {
            // Schema 2's +20 EV default could amplify a signal-free cave by 1,048,576x. Retain enough
            // range for real full-moon illumination while the shader's reliability gate owns black scenes.
            migrateExactNumber(config, "exposure.max-ev", 20.0, 16.0);
        }
        if (version < 4) {
            config.remove("composite.sun-noon-south-tilt-deg");
        }
        if (version < 5) {
            Object legacyManual = config.get("exposure.manual-ev");
            if (legacyManual instanceof Number number) {
                Object modeValue = config.get("exposure.mode");
                String mode = modeValue == null ? "auto" : modeValue.toString();
                String destination = "manual".equalsIgnoreCase(mode)
                        ? "exposure.manual-exposure-ev" : "exposure.compensation-ev";
                if (!config.contains(destination)) {
                    config.set(destination, number.doubleValue());
                }
            }
            config.remove("exposure.manual-ev");
            migrateExactNumber(config, "exposure.max-ev", 16.0, 12.0);
        }
        if (version < 6) {
            // The full-moon base radiance is already calibrated from 0.25 lux. Schema 5's +3 EV
            // default multiplied both direct moonlight and the visible moon by eight.
            migrateExactNumber(config, "composite.moonlight-intensity-ev", 3.0, 0.0);
        }
        if (version < 7) {
            // Fork-only burst defaults admitted substantially more snapshot/worker/GPU work than upstream.
            // Change exact obsolete defaults only; explicit user tuning remains authoritative.
            migrateExactNumber(config, "terrain.async-dispatch-per-tick", 64.0, 32.0);
            migrateExactNumber(config, "terrain.section-results-per-tick", 64.0, 32.0);
            migrateExactNumber(config, "terrain.max-inflight-sections", 192.0, 32.0);
        }
        if (version < 8 && !config.contains("dlss-rr.input-scale-percent")) {
            Object rawQuality = config.get("dlss-rr.quality");
            int quality = rawQuality instanceof Number number ? number.intValue() : 0;
            int percent = switch (quality) {
                case 3 -> 33;
                case 1 -> 58;
                case 2 -> 66;
                case 5 -> 100;
                default -> 50;
            };
            config.set("dlss-rr.input-scale-percent", percent);
        }
        if (version < 9) {
            Object rawOutput = config.get("output-scale.percent");
            Object rawFast = config.get("output-scale.fast-percent");
            if (rawFast instanceof Number fast) {
                int outputPercent = rawOutput instanceof Number number ? number.intValue() : 100;
                int migrated = Math.clamp(Math.round(outputPercent * fast.floatValue() / 100.0f), 10, 200);
                config.set("output-scale.percent", migrated);
            }
            config.remove("output-scale.fast-percent");
        }
        if (version < 10) {
            Object modeValue = config.get("frame-generation.mode");
            if (modeValue == null && config.contains("frame-generation.enabled")) {
                Object rawEnabled = config.get("frame-generation.enabled");
                config.set("frame-generation.mode",
                        Boolean.TRUE.equals(rawEnabled) ? "fixed" : "off");
            }
        }
        if (version < 11) {
            migrateExactNumber(config, "composite.ambient-light-ev", -8.0, 0.025641026);
            migrateExactNumber(config, "composite.night-airglow-ev", 2.99, -8.0);
            migrateExactNumber(config, "composite.sky.day-rayleigh", 4.0, 1.0022436);
            migrateExactNumber(config, "composite.sky.aerosol-scatter", 0.55, 4.0);
            migrateExactNumber(config, "composite.sky.aerosol-absorption", 4.0, 0.0);
            migrateExactNumber(config, "composite.sky.ozone", 1.0, 2.0);
            migrateExactNumber(config, "composite.sky.aerosol-height-km", 4.0, 0.1);
            migrateExactNumber(config, "composite.sky.aerosol-anisotropy", 0.0, 0.8053686);
            migrateExactNumber(config, "composite.sky.star-brightness-ev", 8.0, 2.025641);
            migrateExactNumber(config, "composite.sky.star-size", 1.0, 0.50240386);
            migrateExactNumber(config, "dlss-rr.quality", 0.0, 2.0);
            migrateExactNumber(config, "nrd.upscale-sharpness", 0.0, 0.23453094);
            migrateExactNumber(config, "nrd.min-blur-radius", 8.02, 8.01626);
            migrateExactNumber(config, "frame-generation.multi-frame-count", 1.0, 2.0);
            migrateExactNumber(config, "exposure.high-percentile", 0.80, 0.99425286);
            migrateExactNumber(config, "sharc.cache-exponent", 24.0, 23.0);
            migrateExactString(config, "hdr.tonemap-mode", "psychov23", "eetf");
            migrateExactNumber(config, "hdr.paper-white-nits", 200.0, 203.0);
            migrateExactNumber(config, "hdr.ui-brightness-nits", 200.0, 100.0);
            migrateExactNumber(config, "hdr.peak-nits", 1000.0, 800.0);
        }
        if (version < 12) {
            Object rawOutput = config.get("output-scale.percent");
            Object rawInput = config.get("dlss-rr.input-scale-percent");
            int outputPercent = rawOutput instanceof Number rawOutputPercent
                    ? Math.clamp(rawOutputPercent.intValue(), 10, 200)
                    : 100;
            int inputPercent = rawInput instanceof Number rawInputPercent
                    ? Math.clamp(rawInputPercent.intValue(), 10, 200)
                    : 50;
            int ratioTenths = Math.clamp(Math.round(10.0 * outputPercent / (double) inputPercent), 10, 40);
            config.set("dlss-rr.input-scale-percent", ratioTenths);
        }
        if (version < 13) {
            applySchema13Defaults(config);
        }
        config.set("config-version", CONFIG_SCHEMA_VERSION);
        return true;
    }

    private static boolean isNrdBackend(CommentedConfig config) {
        Object rawBackend = config.get("reconstruction.backend");
        return rawBackend instanceof String value && "nrd".equalsIgnoreCase(value.trim());
    }

    private static void applySchema13Defaults(CommentedConfig config) {
        if (!isNrdBackend(config)) {
            config.set("reconstruction.backend", "dlss-rr");
            config.set("dlss-rr.enabled", true);
            config.set("dlss-rr.preset", "D");
            config.set("dlss-rr.input-scale-percent", 20);
        }
    }
    private static void migrateExactNumber(CommentedConfig config, String path, double obsolete, double replacement) {
        Object value = config.get(path);
        if (value instanceof Number number && Math.abs(number.doubleValue() - obsolete) <= 1.0e-6) {
            config.set(path, replacement);
        }
    }

    private static void migrateExactString(CommentedConfig config, String path, String obsolete, String replacement) {
        Object value = config.get(path);
        if (obsolete.equals(value)) {
            config.set(path, replacement);
        }
    }

    private static Boolean fileBoolean(String tomlPath) {
        Object value = FILE.get(tomlPath);
        return value instanceof Boolean result ? result : null;
    }

    private static Number fileNumber(String tomlPath) {
        Object value = FILE.get(tomlPath);
        return value instanceof Number result ? result : null;
    }

    private static String fileString(String tomlPath) {
        Object value = FILE.get(tomlPath);
        return value instanceof String result ? result : null;
    }

    public interface RuntimeSetting<T> {
        /** The {@code -Dcaustica.*} system property name that overrides this setting. */
        String key();

        /** The dotted path of this setting inside the nested {@code config/caustica.toml} tables. */
        String tomlPath();

        T defaultValue();

        /** The value owned by the UI/TOML, before any launch override is applied. */
        T configuredValue();

        /** The value currently visible to the renderer. */
        T get();

        /** Changes the UI/TOML-owned value. An active launch override remains effective until restart/reload. */
        void set(T value);

        boolean isOverridden();

        /** Human-readable source metadata for diagnostics; {@code null} when no override is active. */
        default String overrideSource() {
            return isOverridden() ? "system-property:" + key() : null;
        }

        /** Raw launcher value for diagnostics; {@code null} when no override is active. */
        default String overrideRawValue() {
            return isOverridden() ? System.getProperty(key()) : null;
        }

        void reloadFromSystemProperties();

        /** Writes only this setting's configured value into the given config at {@link #tomlPath()}. */
        void writeToFile(CommentedConfig config);
    }

    public static final class BooleanSetting implements RuntimeSetting<Boolean> {
        private final String key;
        private final String tomlPath;
        private final boolean defaultValue;
        private volatile boolean configuredValue;
        private volatile boolean overrideValue;
        private volatile boolean overrideActive;

        private BooleanSetting(String key, String tomlPath, boolean defaultValue) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.defaultValue = defaultValue;
            this.configuredValue = resolveConfigured();
            reloadFromSystemProperties();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
        }

        @Override
        public Boolean defaultValue() {
            return defaultValue;
        }

        @Override
        public Boolean configuredValue() {
            return configuredValue;
        }

        @Override
        public Boolean get() {
            return value();
        }

        public boolean value() {
            return overrideActive ? overrideValue : configuredValue;
        }

        @Override
        public void set(Boolean value) {
            this.configuredValue = value != null ? value : defaultValue;
        }

        @Override
        public boolean isOverridden() {
            return overrideActive;
        }

        @Override
        public void reloadFromSystemProperties() {
            String prop = System.getProperty(key);
            overrideActive = prop != null;
            overrideValue = prop != null ? Boolean.parseBoolean(prop.trim()) : defaultValue;
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            config.set(tomlPath, configuredValue);
        }

        private boolean resolveConfigured() {
            Boolean fromFile = fileBoolean(tomlPath);
            return fromFile != null ? fromFile : defaultValue;
        }
    }

    public static final class IntSetting implements RuntimeSetting<Integer> {
        private final String key;
        private final String tomlPath;
        private final int defaultValue;
        private final IntUnaryOperator sanitize;
        private volatile int configuredValue;
        private volatile int overrideValue;
        private volatile boolean overrideActive;

        private IntSetting(String key, String tomlPath, int defaultValue, IntUnaryOperator sanitize) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.defaultValue = sanitize.applyAsInt(defaultValue);
            this.sanitize = sanitize;
            this.configuredValue = resolveConfigured();
            reloadFromSystemProperties();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
        }

        @Override
        public Integer defaultValue() {
            return defaultValue;
        }

        @Override
        public Integer configuredValue() {
            return configuredValue;
        }

        @Override
        public Integer get() {
            return value();
        }

        public int value() {
            return overrideActive ? overrideValue : configuredValue;
        }

        @Override
        public void set(Integer value) {
            this.configuredValue = sanitize.applyAsInt(value != null ? value : defaultValue);
        }

        @Override
        public boolean isOverridden() {
            return overrideActive;
        }

        @Override
        public void reloadFromSystemProperties() {
            String prop = System.getProperty(key);
            overrideActive = prop != null;
            if (prop == null) {
                overrideValue = defaultValue;
                return;
            }
            try {
                overrideValue = sanitize.applyAsInt(Integer.parseInt(prop.trim()));
            } catch (NumberFormatException e) {
                overrideValue = defaultValue;
            }
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            config.set(tomlPath, configuredValue);
        }

        private int resolveConfigured() {
            Number fromFile = fileNumber(tomlPath);
            return fromFile != null ? sanitize.applyAsInt(fromFile.intValue()) : defaultValue;
        }
    }

    public static final class FloatSetting implements RuntimeSetting<Float> {
        private final String key;
        private final String tomlPath;
        private final float defaultValue;
        // Maps a raw external number (system property, file, or the constructor's raw default) into the
        // stored value domain, e.g. degrees -> radians.
        private final DoubleUnaryOperator inputTransform;
        // Inverse of inputTransform: maps the stored value domain back to the raw external domain (e.g.
        // radians -> degrees) for writeToFile, so a value round-trips through the file unchanged instead
        // of having inputTransform re-applied to an already-transformed number on the next load.
        private final DoubleUnaryOperator outputTransform;
        // Idempotent guard on a value-domain number (clamp / finite check); safe to apply to any source.
        private final DoubleUnaryOperator valueClamp;
        private volatile float configuredValue;
        private volatile float overrideValue;
        private volatile boolean overrideActive;

        private FloatSetting(String key, String tomlPath, float rawDefault, DoubleUnaryOperator inputTransform,
                             DoubleUnaryOperator outputTransform, DoubleUnaryOperator valueClamp) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.inputTransform = inputTransform;
            this.outputTransform = outputTransform;
            this.valueClamp = valueClamp;
            this.defaultValue = (float) valueClamp.applyAsDouble(inputTransform.applyAsDouble(rawDefault));
            this.configuredValue = resolveConfigured();
            reloadFromSystemProperties();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
        }

        @Override
        public Float defaultValue() {
            return defaultValue;
        }

        @Override
        public Float configuredValue() {
            return configuredValue;
        }

        @Override
        public Float get() {
            return value();
        }

        public float value() {
            return overrideActive ? overrideValue : configuredValue;
        }

        @Override
        public void set(Float value) {
            if (value == null) {
                this.configuredValue = defaultValue;
            } else {
                this.configuredValue = (float) valueClamp.applyAsDouble(inputTransform.applyAsDouble(value));
            }
        }

        @Override
        public boolean isOverridden() {
            return overrideActive;
        }

        @Override
        public void reloadFromSystemProperties() {
            String prop = System.getProperty(key);
            overrideActive = prop != null;
            if (prop == null) {
                overrideValue = defaultValue;
                return;
            }
            try {
                overrideValue = (float) valueClamp.applyAsDouble(
                        inputTransform.applyAsDouble(Double.parseDouble(prop.trim())));
            } catch (NumberFormatException e) {
                overrideValue = defaultValue;
            }
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            // Round-trip through Float.toString() so the file gets the shortest decimal that reproduces
            // this float (e.g. "0.6"), not outputTransform's raw double with float's binary noise spelled
            // out to 17 digits (e.g. 0.6000000487130328).
            float raw = (float) outputTransform.applyAsDouble(configuredValue);
            config.set(tomlPath, Double.parseDouble(Float.toString(raw)));
        }

        private float resolveConfigured() {
            Number fromFile = fileNumber(tomlPath);
            if (fromFile == null) {
                return defaultValue;
            }
            return (float) valueClamp.applyAsDouble(inputTransform.applyAsDouble(fromFile.doubleValue()));
        }
    }

    public static final class StringSetting implements RuntimeSetting<String> {
        private final String key;
        private final String tomlPath;
        private final String defaultValue;
        private final UnaryOperator<String> sanitize;
        private volatile String configuredValue;
        private volatile String overrideValue;
        private volatile boolean overrideActive;

        private StringSetting(String key, String tomlPath, String defaultValue, UnaryOperator<String> sanitize) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.defaultValue = sanitize.apply(defaultValue);
            this.sanitize = sanitize;
            this.configuredValue = resolveConfigured();
            reloadFromSystemProperties();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
        }

        @Override
        public String defaultValue() {
            return defaultValue;
        }

        @Override
        public String configuredValue() {
            return configuredValue;
        }

        @Override
        public String get() {
            return overrideActive ? overrideValue : configuredValue;
        }

        @Override
        public void set(String value) {
            this.configuredValue = sanitize.apply(value != null ? value : defaultValue);
        }

        @Override
        public boolean isOverridden() {
            return overrideActive;
        }

        @Override
        public void reloadFromSystemProperties() {
            String prop = System.getProperty(key);
            overrideActive = prop != null;
            overrideValue = sanitize.apply(prop != null ? prop : defaultValue);
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            config.set(tomlPath, configuredValue);
        }

        private String resolveConfigured() {
            String fromFile = fileString(tomlPath);
            return sanitize.apply(fromFile != null ? fromFile : defaultValue);
        }
    }

    public static final class OptionalStringSetting implements RuntimeSetting<String> {
        private final String key;
        private final String tomlPath;
        private volatile String configuredValue;
        private volatile String overrideValue;
        private volatile boolean overrideActive;

        private OptionalStringSetting(String key, String tomlPath) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.configuredValue = fileString(tomlPath);
            reloadFromSystemProperties();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
        }

        @Override
        public String defaultValue() {
            return null;
        }

        @Override
        public String configuredValue() {
            return configuredValue;
        }

        @Override
        public String get() {
            return overrideActive ? overrideValue : configuredValue;
        }

        @Override
        public void set(String value) {
            this.configuredValue = value;
        }

        @Override
        public boolean isOverridden() {
            return overrideActive;
        }

        @Override
        public void reloadFromSystemProperties() {
            overrideValue = System.getProperty(key);
            overrideActive = overrideValue != null;
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            if (configuredValue != null) {
                config.set(tomlPath, configuredValue);
            } else {
                config.remove(tomlPath);
            }
        }
    }

    public static final class Rt {
        public static final BooleanSetting ENABLED = bool("caustica.rt", "enabled", true);
        public static final IntSetting WORKER_THREADS =
                intAtLeast("caustica.rt.workerThreads", "worker-threads", defaultWorkerThreads(), 1);

        private Rt() {
        }

        public static final class Composite {
            public static final int DEBUG_VIEW_TONEMAP_COMPARISON = 8;
            public static final IntSetting DEBUG_VIEW = intValue("caustica.rt.debugView", "composite.debug-view", 0);
            public static final IntSetting SPP = intAtLeast("caustica.rt.spp", "composite.spp", 1, 1);
            public static final IntSetting MAX_BOUNCES =
                    clampedInt("caustica.rt.maxBounces", "composite.max-bounces", 8, 2, 8);
            public static final IntSetting CELESTIAL_LIGHT_BOUNCES =
                    clampedInt("caustica.rt.celestialLightBounces", "composite.celestial-light-bounces", 8, 0, 8);
            public static final BooleanSetting WATER_WAVES =
                    bool("caustica.rt.waterWaves", "composite.water-waves", true);
            public static final FloatSetting TORCH_EMISSION_MULTIPLIER = clampedFloat(
                    "caustica.rt.torchEmissionMultiplier", "composite.torch-emission-multiplier", 1.0f, 0.0f, 1.0f);
            public static final FloatSetting AMBIENT_LIGHT_EV = clampedFloat(
                    "caustica.rt.ambientLightEv", "composite.ambient-light-ev", 0.025641026f, -8.0f, 8.0f);
            public static final FloatSetting SUNLIGHT_INTENSITY_EV = clampedFloat(
                    "caustica.rt.sunlightIntensityEv", "composite.sunlight-intensity-ev", 0.0f, -4.0f, 4.0f);
            public static final FloatSetting MOONLIGHT_INTENSITY_EV = clampedFloat(
                    "caustica.rt.moonlightIntensityEv", "composite.moonlight-intensity-ev", 6.02f, -4.0f, 8.0f);
            public static final FloatSetting NIGHT_AIRGLOW_EV = clampedFloat(
                    "caustica.rt.nightAirglowEv", "composite.night-airglow-ev", 0.0f, -8.0f, 8.0f);
            public static final IntSetting PSR_MAX_MIRRORS = clampedInt(
                    "caustica.rt.psrMaxMirrors", "composite.psr-max-mirrors", 32, 1, 32);
            public static final IntSetting POINT_SAMPLE_MAX_SIZE = clampedInt(
                    "caustica.rt.pointSampleMaxSize", "composite.point-sample-max-size", 2048, 0, 8192);
            public static final FloatSetting SUN_ANGULAR_RADIUS =
                    radians("caustica.rt.sunAngularRadius", "composite.sun-angular-radius-deg", 1.5f);
            public static final FloatSetting MOON_ANGULAR_RADIUS =
                    radians("caustica.rt.moonAngularRadius", "composite.moon-angular-radius-deg", 5.0f);
            // Artist-facing spectral-sky controls. Rayleigh has independent nighttime and daytime endpoints;
            // the renderer interpolates between them across twilight.
            public static final FloatSetting SKY_RAYLEIGH = clampedFloat(
                    "caustica.rt.sky.rayleigh", "composite.sky.rayleigh", 0.02f, 0.02f, 4.0f);
            public static final FloatSetting SKY_DAY_RAYLEIGH = clampedFloat(
                    "caustica.rt.sky.dayRayleigh", "composite.sky.day-rayleigh", 1.0022436f, 0.02f, 4.0f);
            public static final FloatSetting SKY_AEROSOL_SCATTER = clampedFloat(
                    "caustica.rt.sky.aerosolScatter", "composite.sky.aerosol-scatter", 4.0f, 0.0f, 4.0f);
            public static final FloatSetting SKY_AEROSOL_ABSORPTION = clampedFloat(
                    "caustica.rt.sky.aerosolAbsorption", "composite.sky.aerosol-absorption", 0.0f, 0.0f, 4.0f);
            public static final FloatSetting SKY_OZONE = clampedFloat(
                    "caustica.rt.sky.ozone", "composite.sky.ozone", 2.0f, 0.0f, 4.0f);
            public static final FloatSetting SKY_AEROSOL_HEIGHT_KM = clampedFloat(
                    "caustica.rt.sky.aerosolHeightKm", "composite.sky.aerosol-height-km", 0.1f, 0.1f, 4.0f);
            public static final FloatSetting SKY_AEROSOL_ANISOTROPY = clampedFloat(
                    "caustica.rt.sky.aerosolAnisotropy", "composite.sky.aerosol-anisotropy", 0.8053686f, 0.0f, 0.95f);
            public static final FloatSetting SKY_BRIGHTNESS_EV = clampedFloat(
                    "caustica.rt.sky.brightnessEv", "composite.sky.brightness-ev", 0.0f, -4.0f, 4.0f);
            public static final FloatSetting SKY_SATURATION = clampedFloat(
                    "caustica.rt.sky.saturation", "composite.sky.saturation", 1.0f, 0.0f, 2.0f);
            public static final FloatSetting SKY_TINT_R = skyRgb("tint-r", 1.0f);
            public static final FloatSetting SKY_TINT_G = skyRgb("tint-g", 1.0f);
            public static final FloatSetting SKY_TINT_B = skyRgb("tint-b", 1.0f);
            public static final FloatSetting SUN_DISC_BRIGHTNESS_EV = skyEv("sun-disc-brightness-ev", -1.0f, -4.0f, 4.0f);
            public static final FloatSetting MOON_DISC_BRIGHTNESS_EV = skyEv("moon-disc-brightness-ev", 0.0f, -4.0f, 4.0f);
            public static final FloatSetting SUN_LIMB_DARKENING = clampedFloat(
                    "caustica.rt.sky.sunLimbDarkening", "composite.sky.sun-limb-darkening", 0.53f, 0.0f, 1.0f);
            public static final FloatSetting SUN_TINT_R = skyRgb("sun-tint-r", 1.0f);
            public static final FloatSetting SUN_TINT_G = skyRgb("sun-tint-g", 1.0f);
            public static final FloatSetting SUN_TINT_B = skyRgb("sun-tint-b", 1.0f);
            public static final FloatSetting MOON_TINT_R = skyRgb("moon-tint-r", 1.0f);
            public static final FloatSetting MOON_TINT_G = skyRgb("moon-tint-g", 1.0f);
            public static final FloatSetting MOON_TINT_B = skyRgb("moon-tint-b", 1.0f);
            public static final FloatSetting STAR_BRIGHTNESS_EV = skyEv("star-brightness-ev", 2.025641f, -8.0f, 8.0f);
            public static final FloatSetting STAR_DENSITY = clampedFloat(
                    "caustica.rt.sky.starDensity", "composite.sky.star-density", 2.0f, 0.0f, 2.0f);
            public static final FloatSetting STAR_SIZE = clampedFloat(
                    "caustica.rt.sky.starSize", "composite.sky.star-size", 0.50240386f, 0.25f, 4.0f);
            public static final FloatSetting STAR_TINT_R = skyRgb("star-tint-r", 1.0f);
            public static final FloatSetting STAR_TINT_G = skyRgb("star-tint-g", 1.0f);
            public static final FloatSetting STAR_TINT_B = skyRgb("star-tint-b", 1.0f);
            public static final FloatSetting AIRGLOW_HORIZON_R = skyRgb("airglow-horizon-r", 1.0f);
            public static final FloatSetting AIRGLOW_HORIZON_G = skyRgb("airglow-horizon-g", 1.18f);
            public static final FloatSetting AIRGLOW_HORIZON_B = skyRgb("airglow-horizon-b", 1.0f);
            public static final FloatSetting AIRGLOW_ZENITH_R = skyRgb("airglow-zenith-r", 0.0f);
            public static final FloatSetting AIRGLOW_ZENITH_G = skyRgb("airglow-zenith-g", 1.0f);
            public static final FloatSetting AIRGLOW_ZENITH_B = skyRgb("airglow-zenith-b", 1.0f);
            public static final FloatSetting JITTER_SIGN_X =
                    finiteFloat("caustica.rt.jitterSignX", "composite.jitter-sign-x", 1.0f);
            public static final FloatSetting JITTER_SIGN_Y =
                    finiteFloat("caustica.rt.jitterSignY", "composite.jitter-sign-y", -1.0f);

            private Composite() {
            }

            private static FloatSetting skyRgb(String name, float fallback) {
                String camel = java.util.Arrays.stream(name.split("-"))
                        .reduce((a, b) -> a + Character.toUpperCase(b.charAt(0)) + b.substring(1)).orElse(name);
                return clampedFloat("caustica.rt.sky." + camel, "composite.sky." + name,
                        fallback, 0.0f, 2.0f);
            }

            private static FloatSetting skyEv(String name, float fallback, float min, float max) {
                String camel = java.util.Arrays.stream(name.split("-"))
                        .reduce((a, b) -> a + Character.toUpperCase(b.charAt(0)) + b.substring(1)).orElse(name);
                return clampedFloat("caustica.rt.sky." + camel, "composite.sky." + name,
                        fallback, min, max);
            }
        }

        /** Semantic defaults used only when a resource pack has not authored that material property. */
        public static final class Materials {
            private Materials() {}

            public static final FloatSetting FOLIAGE_BACKLIGHTING = clampedFloat(
                    "caustica.rt.materials.foliageBacklighting", "materials.foliage-backlighting", 0.66f, 0.0f, 1.0f);
            public static final FloatSetting SOIL_ROUGHNESS = clampedFloat(
                    "caustica.rt.materials.soilRoughness", "materials.soil-roughness", 0.9f, 0.55f, 1.0f);
            public static final FloatSetting STONE_ROUGHNESS = clampedFloat(
                    "caustica.rt.materials.stoneRoughness", "materials.stone-roughness", 0.6f, 0.45f, 1.0f);
            public static final FloatSetting WOOD_ROUGHNESS = clampedFloat(
                    "caustica.rt.materials.woodRoughness", "materials.wood-roughness", 0.8f, 0.4f, 1.0f);
            public static final FloatSetting METAL_ROUGHNESS = clampedFloat(
                    "caustica.rt.materials.metalRoughness", "materials.metal-roughness", 0.05f, 0.0f, 0.7f);
            public static final FloatSetting GLASS_ROUGHNESS = clampedFloat(
                    "caustica.rt.materials.glassRoughness", "materials.glass-roughness", 0.02f, 0.02f, 0.5f);
            public static final FloatSetting WOOL_FIBER_SHEEN = clampedFloat(
                    "caustica.rt.materials.woolFiberSheen", "materials.wool-fiber-sheen", 0.3f, 0.0f, 0.5f);
            public static final FloatSetting POLISHED_ROUGHNESS = clampedFloat(
                    "caustica.rt.materials.polishedRoughness", "materials.polished-roughness", 0.30f, 0.15f, 0.85f);
        }

        public static final class Terrain {
            // External keys retain their historical "per-tick" names for config compatibility; terrain
            // streaming is render-pass driven and these Java names reflect the actual scheduling unit.
            public static final IntSetting ASYNC_DISPATCH_PER_PASS =
                    intAtLeast("caustica.rt.asyncDispatchPerTick", "terrain.async-dispatch-per-tick", 32, 0);
            public static final IntSetting COMPLETION_RESULTS_PER_PASS =
                    intAtLeast("caustica.rt.sectionResultsPerTick", "terrain.section-results-per-tick", 32, 0);
            public static final IntSetting MAX_INFLIGHT_SECTIONS =
                    intAtLeast("caustica.rt.maxInflightSections", "terrain.max-inflight-sections", 32, 0);
            public static final IntSetting GPU_BUILD_BATCH_SIZE =
                    clampedInt("caustica.rt.gpuBuildBatchSize", "terrain.gpu-build-batch-size", 1, 1, 4);
            public static final FloatSetting STREAM_BUDGET_MS =
                    clampedFloat("caustica.rt.streamBudgetMs", "terrain.stream-budget-ms", 1.5f, 0.0f, 100.0f);
            public static final FloatSetting STREAM_BUDGET_MAX_MS =
                    clampedFloat("caustica.rt.streamBudgetMaxMs", "terrain.stream-budget-max-ms", 6.0f, 0.0f, 100.0f);
            public static final FloatSetting STREAM_FALLBACK_BUDGET_MS =
                    clampedFloat("caustica.rt.streamFallbackBudgetMs", "terrain.stream-fallback-budget-ms", 8.0f, 0.0f, 100.0f);
            public static final IntSetting SECTION_TABLE_INITIAL_CAPACITY =
                    intAtLeast("caustica.rt.sectionTableInitialCapacity", "terrain.section-table-initial-capacity", 512, 1);
            public static final IntSetting REBASE_DISTANCE_BLOCKS =
                    intAtLeast("caustica.rt.rebaseDistanceBlocks", "terrain.rebase-distance-blocks", 128, 0);

            private Terrain() {
            }
        }

        /** RIS block-emitter lights. {@code ris-candidates = 0} disables everything. */
        public static final class Lights {
            public static final IntSetting RIS_CANDIDATES =
                    intAtLeast("caustica.rt.risCandidates", "lights.ris-candidates", 0, 0);
            public static final FloatSetting MIN_FILL_RATIO =
                    finiteFloat("caustica.rt.lightMinFillRatio", "lights.min-fill-ratio", 0.25f);
            public static final BooleanSetting STATS = bool("caustica.rt.lightStats", "lights.stats", false);
            public static final BooleanSetting DUMP = bool("caustica.rt.lightDump", "lights.dump", false);
            public static final IntSetting DUMP_RADIUS =
                    intAtLeast("caustica.rt.lightDumpRadius", "lights.dump-radius", 12, 1);

            private Lights() {
            }
        }

        public static final class Omm {
            public static final BooleanSetting ENABLED = bool("caustica.rt.omm", "omm.enabled", true);
            public static final IntSetting SUBDIVISION =
                    clampedInt("caustica.rt.ommSubdivision", "omm.subdivision", 4, 0, 12);
            public static final BooleanSetting STATS = bool("caustica.rt.ommStats", "omm.stats", false);

            private Omm() {
            }
        }

        public static final class Sharc {
            public static final BooleanSetting ENABLED = bool("caustica.rt.sharc", "sharc.enabled", true);
            public static final IntSetting CACHE_EXPONENT =
                      clampedInt("caustica.rt.sharcCacheExponent", "sharc.cache-exponent", 24, 16, 28);
            public static final FloatSetting SCENE_SCALE =
                    clampedFloat("caustica.rt.sharcSceneScale", "sharc.scene-scale", 32.0f, 1.0f, 100.0f);
            public static final FloatSetting RADIANCE_SCALE =
                    clampedFloat("caustica.rt.sharcRadianceScale", "sharc.radiance-scale", 10000.0f, 50.0f, 10000.0f);
            public static final IntSetting ACCUMULATION_FRAMES =
                    clampedInt("caustica.rt.sharcAccumulationFrames", "sharc.accumulation-frames", 128, 1, 1024);
            public static final IntSetting STALE_FRAMES =
                    clampedInt("caustica.rt.sharcStaleFrames", "sharc.stale-frames", 1024, 8, 1024);
            public static final BooleanSetting ANTI_FIREFLY =
                    bool("caustica.rt.sharcAntiFirefly", "sharc.anti-firefly", true);
            public static final IntSetting UPDATE_TILE_SIZE =
                    clampedInt("caustica.rt.sharcUpdateTileSize", "sharc.update-tile-size", 2, 2, 64);
            public static final IntSetting UPDATE_MAX_BOUNCES =
                    clampedInt("caustica.rt.sharcUpdateMaxBounces", "sharc.update-max-bounces", 8, 1, 8);
            public static final FloatSetting MIN_SEGMENT_RATIO =
                    clampedFloat("caustica.rt.sharcMinSegmentRatio", "sharc.min-segment-ratio", 0.2f, 0.25f, 4.0f);
            public static final BooleanSetting GLOSSY_QUERY =
                    bool("caustica.rt.sharcGlossyQuery", "sharc.glossy-query", false);
            public static final BooleanSetting LIVE_SECONDARY_DIRECT =
                    bool("caustica.rt.sharcLiveSecondaryDirect", "sharc.live-secondary-direct", true);
            public static final BooleanSetting PRIMARY_DIFFUSE_REUSE =
                    bool("caustica.rt.sharcPrimaryDiffuseReuse", "sharc.primary-diffuse-reuse", false);
            public static final BooleanSetting DETAILED_STATS =
                    bool("caustica.rt.sharcDetailedStats", "sharc.detailed-stats", false);

            private Sharc() {}
        }

        public static final class Entities {
            public static final BooleanSetting ENABLED = bool("caustica.rt.entities", "entities.enabled", true);
            public static final BooleanSetting PARTICLES_ENABLED =
                    bool("caustica.rt.particles", "particles.enabled", true);
            public static final BooleanSetting GLOW_ENABLED =
                    bool("caustica.rt.glow", "entities.glow.enabled", true);
            public static final BooleanSetting NAME_TAGS_ENABLED =
                    bool("caustica.rt.nameTags", "entities.name-tags.enabled", true);
            /** Debug-only: render each model submission twice and require bitwise-identical CPU captures. */
            public static final BooleanSetting CAPTURE_PARITY =
                    bool("caustica.rt.entityCaptureParity", "entities.debug.capture-parity", false);
            public static final IntSetting MAX_ORDINARY_ENTITIES =
                    intAtLeast("caustica.rt.maxOrdinaryEntities", "entities.max-ordinary-entities", 1024, 0);
            public static final IntSetting MAX_BLOCK_ENTITIES =
                    intAtLeast("caustica.rt.maxBlockEntities", "entities.block-entities.max-entities", 1024, 0);
            public static final IntSetting MAX_PARTICLES =
                    intAtLeast("caustica.rt.maxParticles", "particles.max-particles", 1024, 0);
            public static final IntSetting BE_VIEW_CHUNKS =
                    intAtLeast("caustica.rt.beViewChunks", "entities.block-entities.view-chunks", 8, 0);
            public static final IntSetting BE_BUILDS_PER_FRAME =
                    intAtLeast("caustica.rt.beBuildsPerFrame", "entities.block-entities.builds-per-frame", 8, 0);
            public static final IntSetting REFIT_REBUILD_INTERVAL =
                    intAtLeast("caustica.rt.refitRebuildInterval", "entities.refit.rebuild-interval", 120, 1);

            private Entities() {
            }

            public static int maxEntities() {
                return Math.addExact(Math.addExact(
                        MAX_ORDINARY_ENTITIES.value(), MAX_BLOCK_ENTITIES.value()), MAX_PARTICLES.value());
            }

            public static int entityListCapacity() {
                return Math.max(16, maxEntities());
            }

            public static int entityMapCapacity() {
                // Fastutil expected-size constructors apply their own load-factor headroom.
                return Math.max(16, MAX_ORDINARY_ENTITIES.value());
            }
        }

        public static final class FirstPerson {
            public static final BooleanSetting ENABLED =
                    bool("caustica.rt.firstPerson", "first-person.enabled", true);
            public static final BooleanSetting DISABLE_VANILLA_MODEL = bool(
                    "caustica.rt.firstPerson.disableVanillaModel", "first-person.disable-vanilla-model", true);
            public static final FloatSetting FORWARD_OFFSET = clampedFloat(
                    "caustica.rt.firstPerson.forwardAdjustment", "first-person.forward-adjustment", 0.0f, -0.30f, 0.30f);
            public static final FloatSetting VERTICAL_OFFSET = clampedFloat(
                    "caustica.rt.firstPerson.verticalAdjustment", "first-person.vertical-adjustment", 0.0f, -0.30f, 0.30f);
            public static final FloatSetting LATERAL_OFFSET = clampedFloat(
                    "caustica.rt.firstPerson.lateralAdjustment", "first-person.lateral-adjustment", 0.0f, -0.20f, 0.20f);

            private FirstPerson() {
            }
        }

        public static final class EntityTextures {
            public static final IntSetting MAX_TEXTURES =
                    intAtLeast("caustica.rt.maxEntityTextures", "entities.textures.max-textures", 256, 1);
            public static final BooleanSetting PBR = bool("caustica.rt.entityPbr", "entities.textures.pbr", true);

            private EntityTextures() {
            }
        }

        public static final class Overlay {
            public static final BooleanSetting BLOCK_OUTLINE_ENABLED =
                    bool("caustica.rt.blockOutline", "overlay.block-outline.enabled", true);

            private Overlay() {
            }
        }

        public static final class DlssRr {
            public static final BooleanSetting ENABLED = bool("caustica.rt.dlssRr", "dlss-rr.enabled", true);
            public static final BooleanSetting DIFFUSE_PATH_GUIDE = bool(
                    "caustica.rt.dlssRr.diffusePathGuide", "dlss-rr.diffuse-path-guide", true);
            public static final BooleanSetting SUBPIXEL_DETAIL = bool(
                    "caustica.rt.dlssRr.subpixelDetail", "dlss-rr.subpixel-detail", true);
            /** Compatibility alias for the setting's original DLSS-only owner. */
            public static final BooleanSetting HIGH_QUALITY_TRANSPARENCY =
                    Reconstruction.ADVANCED_OPTICAL_TRANSPORT;
            public static final BooleanSetting PARTICLE_TEMPORAL_HISTORY = bool(
                    "caustica.rt.dlssRr.particleTemporalHistory",
                    "dlss-rr.particle-temporal-history", true);
            public static final IntSetting PRESET = intValue("caustica.rt.dlssRr.preset", "dlss-rr.preset", 5);
            public static final IntSetting QUALITY = intValue("caustica.rt.dlssRr.quality", "dlss-rr.quality", 0);
            public static final IntSetting INPUT_RATIO_TENTHS = clampedInt(
                    "caustica.rt.dlssRr.inputScale", "dlss-rr.input-scale-percent", 20, 10, 40);

            private DlssRr() {
            }
        }

        /** Global reconstruction policy. Auto is vendor/OS aware but never prevents an explicit choice. */
        public static final class Reconstruction {
            public static final StringSetting BACKEND = string(
                    "caustica.rt.reconstruction.backend", "reconstruction.backend", "auto",
                    value -> switch (value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT)) {
                        case "nrd", "dlss-rr", "off" -> value.trim().toLowerCase(java.util.Locale.ROOT);
                        default -> "auto";
                    });
            public static final BooleanSetting ADVANCED_OPTICAL_TRANSPORT = bool(
                    "caustica.rt.advancedOpticalTransport",
                    "dlss-rr.high-quality-transparency", true);

            private Reconstruction() {
            }
        }

        /** NVIDIA NRD 4.17.x controls. All fields below are copied into the active denoiser every frame. */
        public static final class Nrd {
            public static final StringSetting DENOISER = string(
                    "caustica.rt.nrd.denoiser", "nrd.denoiser", "relax",
                    value -> "relax".equalsIgnoreCase(value) ? "relax" : "reblur");
            public static final BooleanSetting SPHERICAL_HARMONICS = bool(
                    "caustica.rt.nrd.sphericalHarmonics", "nrd.spherical-harmonics", true);
            public static final StringSetting UPSCALE_MODE = string(
                    "caustica.rt.nrd.upscaleMode", "nrd.upscale-mode", "native",
                    value -> switch (value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT)) {
                        case "native", "quality", "balanced", "performance", "ultra-performance", "custom" ->
                                value.trim().toLowerCase(java.util.Locale.ROOT);
                        default -> "quality";
                    });
            public static final FloatSetting CUSTOM_RENDER_SCALE = clampedFloat(
                    "caustica.rt.nrd.customRenderScale", "nrd.custom-render-scale", 0.67f, 0.25f, 1.0f);
            public static final StringSetting UPSCALE_FILTER = string(
                    "caustica.rt.nrd.upscaleFilter", "nrd.upscale-filter", "edge-adaptive",
                    value -> switch (value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT)) {
                        case "nearest", "linear", "edge-adaptive" ->
                                value.trim().toLowerCase(java.util.Locale.ROOT);
                        default -> "edge-adaptive";
                    });
            public static final FloatSetting UPSCALE_SHARPNESS = clampedFloat(
                    "caustica.rt.nrd.upscaleSharpness", "nrd.upscale-sharpness", 0.23453094f, 0.0f, 1.0f);
            public static final IntSetting MAX_ACCUMULATED_FRAMES = clampedInt(
                    "caustica.rt.nrd.maxAccumulatedFrames", "nrd.max-accumulated-frames", 30, 0, 255);
            public static final IntSetting MAX_FAST_ACCUMULATED_FRAMES = clampedInt(
                    "caustica.rt.nrd.maxFastAccumulatedFrames", "nrd.max-fast-accumulated-frames", 6, 0, 63);
            public static final IntSetting MAX_STABILIZED_FRAMES = clampedInt(
                    "caustica.rt.nrd.maxStabilizedFrames", "nrd.max-stabilized-frames", 30, 0, 255);
            public static final IntSetting HISTORY_FIX_FRAMES = clampedInt(
                    "caustica.rt.nrd.historyFixFrames", "nrd.history-fix-frames", 3, 0, 3);
            public static final IntSetting HISTORY_FIX_STRIDE = clampedInt(
                    "caustica.rt.nrd.historyFixStride", "nrd.history-fix-stride", 14, 1, 32);
            public static final FloatSetting PREPASS_BLUR_RADIUS = clampedFloat(
                    "caustica.rt.nrd.prepassBlurRadius", "nrd.prepass-blur-radius", 30.0f, 0.0f, 100.0f);
            public static final FloatSetting SPECULAR_PREPASS_BLUR_RADIUS = clampedFloat(
                    "caustica.rt.nrd.specularPrepassBlurRadius", "nrd.specular-prepass-blur-radius", 50.0f, 0.0f, 100.0f);
            public static final FloatSetting FAST_HISTORY_CLAMP_SIGMA = clampedFloat(
                    "caustica.rt.nrd.fastHistoryClampSigma", "nrd.fast-history-clamp-sigma", 2.0f, 1.0f, 3.0f);
            public static final FloatSetting MIN_HIT_DISTANCE_WEIGHT = clampedFloat(
                    "caustica.rt.nrd.minHitDistanceWeight", "nrd.min-hit-distance-weight", 0.1f, 0.001f, 0.2f);
            public static final FloatSetting FIREFLY_SUPPRESSOR_SCALE = clampedFloat(
                    "caustica.rt.nrd.fireflySuppressorScale", "nrd.firefly-suppressor-scale", 3.0f, 1.0f, 3.0f);
            public static final FloatSetting MIN_BLUR_RADIUS = clampedFloat(
                    "caustica.rt.nrd.minBlurRadius", "nrd.min-blur-radius", 8.01626f, 0.0f, 10.0f);
            public static final FloatSetting MAX_BLUR_RADIUS = clampedFloat(
                    "caustica.rt.nrd.maxBlurRadius", "nrd.max-blur-radius", 30.0f, 0.0f, 100.0f);
            public static final FloatSetting LOBE_ANGLE_FRACTION = clampedFloat(
                    "caustica.rt.nrd.lobeAngleFraction", "nrd.lobe-angle-fraction", 0.15f, 0.0f, 1.0f);
            public static final FloatSetting ROUGHNESS_FRACTION = clampedFloat(
                    "caustica.rt.nrd.roughnessFraction", "nrd.roughness-fraction", 0.15f, 0.0f, 1.0f);
            public static final FloatSetting PLANE_DISTANCE_SENSITIVITY = clampedFloat(
                    "caustica.rt.nrd.planeDistanceSensitivity", "nrd.plane-distance-sensitivity", 0.02f, 0.001f, 0.2f);
            public static final FloatSetting DISOCCLUSION_THRESHOLD = clampedFloat(
                    "caustica.rt.nrd.disocclusionThreshold", "nrd.disocclusion-threshold", 0.01f, 0.001f, 0.2f);
            public static final FloatSetting SPLIT_SCREEN = clampedFloat(
                    "caustica.rt.nrd.splitScreen", "nrd.split-screen", 0.0f, 0.0f, 1.0f);
            public static final FloatSetting HIT_DISTANCE_A = clampedFloat(
                    "caustica.rt.nrd.hitDistanceA", "nrd.hit-distance-a", 3.0f, 0.01f, 100.0f);
            public static final FloatSetting HIT_DISTANCE_B = clampedFloat(
                    "caustica.rt.nrd.hitDistanceB", "nrd.hit-distance-b", 0.1f, 0.001f, 10.0f);
            public static final FloatSetting HIT_DISTANCE_C = clampedFloat(
                    "caustica.rt.nrd.hitDistanceC", "nrd.hit-distance-c", 20.0f, 1.0f, 100.0f);
            public static final BooleanSetting ANTI_FIREFLY = bool(
                    "caustica.rt.nrd.antiFirefly", "nrd.anti-firefly", true);
            public static final BooleanSetting ANTILAG = bool(
                    "caustica.rt.nrd.antilag", "nrd.antilag", false);
            public static final FloatSetting ANTILAG_SIGMA = clampedFloat(
                    "caustica.rt.nrd.antilagSigma", "nrd.antilag-sigma", 2.0f, 0.1f, 10.0f);
            public static final FloatSetting ANTILAG_SENSITIVITY = clampedFloat(
                    "caustica.rt.nrd.antilagSensitivity", "nrd.antilag-sensitivity", 3.0f, 0.1f, 10.0f);
            public static final FloatSetting RESPONSIVE_ROUGHNESS_THRESHOLD = clampedFloat(
                    "caustica.rt.nrd.responsiveRoughnessThreshold", "nrd.responsive-roughness-threshold", 0.0f, 0.0f, 1.0f);
            public static final IntSetting RESPONSIVE_MIN_FRAMES = clampedInt(
                    "caustica.rt.nrd.responsiveMinFrames", "nrd.responsive-min-frames", 3, 0, 3);
            public static final FloatSetting CONVERGENCE_SCALE = clampedFloat(
                    "caustica.rt.nrd.convergenceScale", "nrd.convergence-scale", 1.0f, 0.1f, 4.0f);
            public static final FloatSetting CONVERGENCE_BASE = clampedFloat(
                    "caustica.rt.nrd.convergenceBase", "nrd.convergence-base", 0.2f, 0.0f, 1.0f);
            public static final FloatSetting CONVERGENCE_HISTORY_FRACTION = clampedFloat(
                    "caustica.rt.nrd.convergenceHistoryFraction", "nrd.convergence-history-fraction", 0.8f, 0.0f, 1.0f);
            public static final IntSetting RELAX_ATROUS_ITERATIONS = clampedInt(
                    "caustica.rt.nrd.relaxAtrousIterations", "nrd.relax-atrous-iterations", 5, 2, 8);
            public static final FloatSetting RELAX_HISTORY_NORMAL_POWER = clampedFloat(
                    "caustica.rt.nrd.relaxHistoryNormalPower", "nrd.relax-history-normal-power", 64.0f, 0.1f, 64.0f);
            public static final FloatSetting RELAX_DIFFUSE_PHI_LUMINANCE = clampedFloat(
                    "caustica.rt.nrd.relaxDiffusePhiLuminance", "nrd.relax-diffuse-phi-luminance", 10.0f, 0.1f, 10.0f);
            public static final FloatSetting RELAX_SPECULAR_PHI_LUMINANCE = clampedFloat(
                    "caustica.rt.nrd.relaxSpecularPhiLuminance", "nrd.relax-specular-phi-luminance", 10.0f, 0.1f, 10.0f);
            public static final FloatSetting RELAX_DEPTH_THRESHOLD = clampedFloat(
                    "caustica.rt.nrd.relaxDepthThreshold", "nrd.relax-depth-threshold", 0.003f, 0.0001f, 0.1f);
            public static final FloatSetting RELAX_SPECULAR_VARIANCE_BOOST = clampedFloat(
                    "caustica.rt.nrd.relaxSpecularVarianceBoost", "nrd.relax-specular-variance-boost", 0.0f, 0.0f, 4.0f);
            public static final FloatSetting RELAX_SPECULAR_LOBE_SLACK = clampedFloat(
                    "caustica.rt.nrd.relaxSpecularLobeSlack", "nrd.relax-specular-lobe-slack", 0.0f, 0.0f, 1.0f);
            public static final BooleanSetting RELAX_ROUGHNESS_EDGE_STOPPING = bool(
                    "caustica.rt.nrd.relaxRoughnessEdgeStopping", "nrd.relax-roughness-edge-stopping", true);
            public static final FloatSetting RELAX_ANTILAG_ACCELERATION = clampedFloat(
                    "caustica.rt.nrd.relaxAntilagAcceleration", "nrd.relax-antilag-acceleration", 1.0f, 0.0f, 1.0f);
            public static final FloatSetting RELAX_ANTILAG_TEMPORAL_SIGMA = clampedFloat(
                    "caustica.rt.nrd.relaxAntilagTemporalSigma", "nrd.relax-antilag-temporal-sigma", 0.0f, 0.01f, 10.0f);
            public static final FloatSetting RELAX_ANTILAG_RESET = clampedFloat(
                    "caustica.rt.nrd.relaxAntilagReset", "nrd.relax-antilag-reset", 0.5f, 0.0f, 1.0f);

            private Nrd() {
            }
        }

        /** Realtime pre-UI world-image scale. Offline ground-truth rendering owns its resolution separately. */
        public static final class OutputScale {
            public static final IntSetting PERCENT = clampedInt(
                    "caustica.rt.outputScale", "output-scale.percent", 100, 10, 200);
            private OutputScale() {
            }
        }

        /** DLSS Frame Generation. The selected fixed-generation look is the personal default. */
        public static final class Fg {
            /** Legacy compatibility switch; new code and UI use {@link #MODE}. */
            public static final BooleanSetting ENABLED = bool("caustica.rt.fg", "frame-generation.enabled", false);
            public static final StringSetting MODE = string(
                    "caustica.rt.fg.mode", "frame-generation.mode", "off", Fg::sanitizeMode);
            public static final IntSetting MULTI_FRAME_COUNT =
                    clampedInt("caustica.rt.fg.multiFrameCount", "frame-generation.multi-frame-count", 1, 1, 5);
            public static final FloatSetting DYNAMIC_TARGET_FPS = clampedFloat(
                    "caustica.rt.fg.dynamicTargetFps", "frame-generation.dynamic-target-fps", 0.0f, 0.0f, 1000.0f);
            /** Desired physical display cadence for Vulkan fixed MFG's Parallel queue policy. */
            public static final IntSetting OUTPUT_TARGET_FPS = clampedInt(
                    "caustica.rt.fg.outputTargetFps", "frame-generation.output-target-fps", 225, 30, 1000);
            public static final BooleanSetting UI_RECOMPOSITION = bool(
                    "caustica.rt.fg.uiRecomposition", "frame-generation.ui-recomposition", true);
            public static final BooleanSetting FULLSCREEN_MENU_DETECTION = bool(
                    "caustica.rt.fg.fullscreenMenuDetection", "frame-generation.fullscreen-menu-detection", true);
            public static final BooleanSetting SHOW_ONLY_INTERPOLATED = bool(
                    "caustica.rt.fg.showOnlyInterpolated", "frame-generation.show-only-interpolated", false);
            public static final StringSetting QUEUE_PARALLELISM = string(
                    "caustica.rt.fg.queueParallelism", "frame-generation.queue-parallelism", "synchronized",
                    value -> switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
                        case "parallel", "no-client-queues" -> "parallel";
                        // Migrate the former diagnostic names to the safe canonical policy.
                        case "auto", "safe", "synchronized" -> "synchronized";
                        default -> "synchronized";
                    });

            private Fg() {
            }

            public static String mode() {
                if (MODE.isOverridden()) {
                    return MODE.get();
                }
                if (ENABLED.isOverridden()) {
                    return ENABLED.value() ? "fixed" : "off";
                }
                return MODE.get();
            }

            public static String configuredMode() {
                return MODE.configuredValue();
            }

            public static boolean requested() {
                return !"off".equals(mode());
            }

            public static void setMode(String mode) {
                String sanitized = sanitizeMode(mode);
                MODE.set(sanitized);
                ENABLED.set(!"off".equals(sanitized));
            }

            private static String sanitizeMode(String value) {
                if (value == null) {
                    return "off";
                }
                return switch (value.toLowerCase(java.util.Locale.ROOT)) {
                    case "fixed", "dynamic", "auto" -> "fixed";
                    default -> "off";
                };
            }
        }

        /** Streamline Reflex modes. Sleep and PCL markers remain active when the visible mode is Off. */
        public static final class Reflex {
            public static final BooleanSetting ENABLED = bool("caustica.rt.reflex", "reflex.enabled", true);
            public static final BooleanSetting LOW_LATENCY_BOOST =
                    bool("caustica.rt.reflex.boost", "reflex.low-latency-boost", false);
            public static final IntSetting MINIMUM_INTERVAL_US =
                    intAtLeast("caustica.rt.reflex.minIntervalUs", "reflex.minimum-interval-us", 0, 0);

            private Reflex() {
            }
        }

        public static final class Exposure {
            public static final StringSetting MODE =
                    string("caustica.rt.exposure.mode", "exposure.mode", "auto", Exposure::sanitizeMode);
            public static final FloatSetting MANUAL_EXPOSURE_EV =
                    finiteFloat("caustica.rt.exposure.manualExposureEv", "exposure.manual-exposure-ev", -1.5f);
            public static final FloatSetting COMPENSATION_EV = clampedFloat(
                    "caustica.rt.exposure.compensationEv", "exposure.compensation-ev", 0.0f, -4.0f, 4.0f);
            public static final FloatSetting KEY = exposureScale("caustica.rt.exposure.key", "exposure.key", 0.18f);
            public static final FloatSetting MIN_EV =
                    finiteFloat("caustica.rt.exposure.minEv", "exposure.min-ev", -24.0f);
            public static final FloatSetting MAX_EV =
                    clampedFloat("caustica.rt.exposure.maxEv", "exposure.max-ev", 4.0f, 0.0f, 12.0f);
            public static final FloatSetting ADAPT_UP =
                    clampedFloat("caustica.rt.exposure.adaptUp", "exposure.adapt-up", 3.0f, 0.05f, 20.0f);
            public static final FloatSetting ADAPT_DOWN =
                    clampedFloat("caustica.rt.exposure.adaptDown", "exposure.adapt-down", 2.0f, 0.05f, 20.0f);
            public static final FloatSetting LOW_PERCENTILE = clampedFloat(
                    "caustica.rt.exposure.lowPercentile", "exposure.low-percentile", 0.10f, 0.0f, 0.95f);
            public static final FloatSetting HIGH_PERCENTILE = clampedFloat(
                    "caustica.rt.exposure.highPercentile", "exposure.high-percentile", 0.99425286f, 0.05f, 1.0f);
            public static final FloatSetting HIGHLIGHT_PERCENTILE = clampedFloat(
                    "caustica.rt.exposure.highlightPercentile", "exposure.highlight-percentile", 0.99f, 0.5f, 1.0f);
            public static final FloatSetting HIGHLIGHT_HEADROOM = clampedFloat(
                    "caustica.rt.exposure.highlightHeadroom", "exposure.highlight-headroom", 4.0f, 0.18f, 64.0f);
            public static final FloatSetting CENTER_WEIGHT = clampedFloat(
                    "caustica.rt.exposure.centerWeight", "exposure.center-weight", 3.0f, 0.0f, 8.0f);
            public static final FloatSetting LOG_MIN = clampedFloat(
                    "caustica.rt.exposure.logMin", "exposure.histogram-min-ev", -20.0f, -32.0f, 8.0f);
            public static final FloatSetting LOG_MAX = clampedFloat(
                    "caustica.rt.exposure.logMax", "exposure.histogram-max-ev", 32.0f, -8.0f, 32.0f);

            private Exposure() {
            }

            public static float minEv() {
                return Math.min(MIN_EV.value(), MAX_EV.value());
            }

            public static float maxEv() {
                return Math.max(MIN_EV.value(), MAX_EV.value());
            }

            public static float lowPercentile() {
                return Math.min(LOW_PERCENTILE.value(), HIGH_PERCENTILE.value() - 0.01f);
            }

            public static float highPercentile() {
                return Math.max(HIGH_PERCENTILE.value(), LOW_PERCENTILE.value() + 0.01f);
            }

            public static float highlightPercentile() {
                return Math.max(HIGHLIGHT_PERCENTILE.value(), highPercentile());
            }

            public static float logMin() {
                return Math.min(LOG_MIN.value(), LOG_MAX.value() - 1.0f);
            }

            public static float logMax() {
                return Math.max(LOG_MAX.value(), LOG_MIN.value() + 1.0f);
            }

            public static float clampScale(float value) {
                return Math.clamp(value, 1.0e-8f, 1.0e8f);
            }

            private static String sanitizeMode(String value) {
                if ("auto".equalsIgnoreCase(value)) {
                    return "auto";
                }
                if ("manual".equalsIgnoreCase(value)) {
                    return "manual";
                }
                return "auto";
            }
        }

        /** Render-frame timing + hitch logging. See {@code RtFrameStats}. */
        public static final class FrameStats {
            public static final BooleanSetting ENABLED = bool("caustica.rt.frameStats", "frame-stats.enabled", false);

            private FrameStats() {
            }
        }

        /** SDR output controls for the vanilla main target. */
        public static final class Sdr {
            public static final String TONEMAP_PBR_NEUTRAL = "pbr-neutral";
            public static final String TONEMAP_REINHARD = "reinhard";
            public static final String TONEMAP_ACES = "aces";
            public static final String TONEMAP_AGX = "agx";
            public static final String TONEMAP_LOTTES = "lottes";
            public static final String TONEMAP_FROSTBITE = "frostbite";
            public static final String TONEMAP_UNCHARTED2 = "uncharted2";
            public static final String TONEMAP_GT = "gt";
            public static final String TONEMAP_PSYCHOV = "psychov";
            public static final String TONEMAP_PSYCHOV23 = "psychov23";

            public static final int TONEMAP_ID_PBR_NEUTRAL = 0;
            public static final int TONEMAP_ID_REINHARD = 1;
            public static final int TONEMAP_ID_ACES = 2;
            public static final int TONEMAP_ID_AGX = 3;
            public static final int TONEMAP_ID_LOTTES = 4;
            public static final int TONEMAP_ID_FROSTBITE = 5;
            public static final int TONEMAP_ID_UNCHARTED2 = 6;
            public static final int TONEMAP_ID_GT = 7;
            public static final int TONEMAP_ID_PSYCHOV = 8;
            public static final int TONEMAP_ID_PSYCHOV23 = 9;

            public static final StringSetting TONEMAP_MODE =
                    string("caustica.rt.sdr.tonemapMode", "sdr.tonemap-mode", TONEMAP_PSYCHOV23, Sdr::sanitizeTonemapMode);
            public static final FloatSetting AGX_CONTRAST =
                    clampedFloat("caustica.rt.sdr.agx.contrast", "sdr.agx.contrast", 1.0f, 0.0f, 2.0f);
            public static final FloatSetting AGX_SATURATION =
                    clampedFloat("caustica.rt.sdr.agx.saturation", "sdr.agx.saturation", 1.0f, 0.0f, 3.0f);
            public static final FloatSetting PBR_START_COMPRESSION =
                    clampedFloat("caustica.rt.sdr.pbrNeutral.startCompression", "sdr.pbr-neutral.start-compression", 0.76f, 0.0f, 0.99f);
            public static final FloatSetting PBR_DESATURATION =
                    clampedFloat("caustica.rt.sdr.pbrNeutral.desaturation", "sdr.pbr-neutral.desaturation", 0.15f, 0.0f, 1.0f);
            public static final FloatSetting REINHARD_WHITE_POINT =
                    clampedFloat("caustica.rt.sdr.reinhard.whitePoint", "sdr.reinhard.white-point", 4.0f, 1.0f, 20.0f);
            public static final FloatSetting ACES_EXPOSURE =
                    clampedFloat("caustica.rt.sdr.aces.exposure", "sdr.aces.exposure", 1.0f, 0.0f, 4.0f);
            public static final FloatSetting LOTTES_CONTRAST =
                    clampedFloat("caustica.rt.sdr.lottes.contrast", "sdr.lottes.contrast", 2.0f, 0.1f, 5.0f);
            public static final FloatSetting LOTTES_SHOULDER =
                    clampedFloat("caustica.rt.sdr.lottes.shoulder", "sdr.lottes.shoulder", 1.0f, 0.1f, 5.0f);
            public static final FloatSetting LOTTES_HDR_MAX =
                    clampedFloat("caustica.rt.sdr.lottes.hdrMax", "sdr.lottes.hdr-max", 16.0f, 1.0f, 64.0f);
            public static final FloatSetting LOTTES_MID_IN =
                    clampedFloat("caustica.rt.sdr.lottes.midIn", "sdr.lottes.mid-in", 0.18f, 0.01f, 1.0f);
            public static final FloatSetting LOTTES_MID_OUT =
                    clampedFloat("caustica.rt.sdr.lottes.midOut", "sdr.lottes.mid-out", 0.18f, 0.01f, 1.0f);
            public static final FloatSetting FROSTBITE_LINEAR_END =
                    clampedFloat("caustica.rt.sdr.frostbite.linearEnd", "sdr.frostbite.linear-end", 0.25f, 0.0f, 1.0f);
            public static final FloatSetting FROSTBITE_SHOULDER_STRENGTH =
                    clampedFloat("caustica.rt.sdr.frostbite.shoulderStrength", "sdr.frostbite.shoulder-strength", 2.0f, 0.0f, 8.0f);
            public static final FloatSetting UNCHARTED_A =
                    clampedFloat("caustica.rt.sdr.uncharted2.a", "sdr.uncharted2.a", 0.15f, 0.01f, 1.0f);
            public static final FloatSetting UNCHARTED_B =
                    clampedFloat("caustica.rt.sdr.uncharted2.b", "sdr.uncharted2.b", 0.50f, 0.01f, 2.0f);
            public static final FloatSetting UNCHARTED_C =
                    clampedFloat("caustica.rt.sdr.uncharted2.c", "sdr.uncharted2.c", 0.10f, 0.0f, 1.0f);
            public static final FloatSetting UNCHARTED_D =
                    clampedFloat("caustica.rt.sdr.uncharted2.d", "sdr.uncharted2.d", 0.20f, 0.01f, 2.0f);
            public static final FloatSetting UNCHARTED_E =
                    clampedFloat("caustica.rt.sdr.uncharted2.e", "sdr.uncharted2.e", 0.02f, 0.0f, 1.0f);
            public static final FloatSetting UNCHARTED_F =
                    clampedFloat("caustica.rt.sdr.uncharted2.f", "sdr.uncharted2.f", 0.30f, 0.01f, 2.0f);
            public static final FloatSetting UNCHARTED_WHITE_POINT =
                    clampedFloat("caustica.rt.sdr.uncharted2.whitePoint", "sdr.uncharted2.white-point", 11.2f, 1.0f, 32.0f);
            public static final FloatSetting GT_CONTRAST =
                    clampedFloat("caustica.rt.sdr.gt.contrast", "sdr.gt.contrast", 1.0f, 0.1f, 4.0f);
            public static final FloatSetting GT_LINEAR_START =
                    clampedFloat("caustica.rt.sdr.gt.linearStart", "sdr.gt.linear-start", 0.22f, 0.01f, 0.99f);
            public static final FloatSetting GT_LINEAR_LENGTH =
                    clampedFloat("caustica.rt.sdr.gt.linearLength", "sdr.gt.linear-length", 0.4f, 0.01f, 4.0f);
            public static final FloatSetting GT_BLACK_CURVE =
                    clampedFloat("caustica.rt.sdr.gt.blackCurve", "sdr.gt.black-curve", 1.33f, 0.1f, 4.0f);
            public static final FloatSetting GT_BLACK_LIFT =
                    clampedFloat("caustica.rt.sdr.gt.blackLift", "sdr.gt.black-lift", 0.0f, -0.5f, 0.5f);
            public static final FloatSetting PSYCHO_PEAK =
                    clampedFloat("caustica.rt.sdr.psychov.peak", "sdr.psychov.peak", 1.0f, 0.5f, 8.0f);
            public static final FloatSetting PSYCHOV23_PEAK =
                    clampedFloat("caustica.rt.sdr.psychov23.peak", "sdr.psychov23.peak",
                            1.0f, 0.5f, 64.0f);

            private Sdr() {
            }

            public static int tonemapModeId() {
                return switch (TONEMAP_MODE.get()) {
                    case TONEMAP_PBR_NEUTRAL -> TONEMAP_ID_PBR_NEUTRAL;
                    case TONEMAP_REINHARD -> TONEMAP_ID_REINHARD;
                    case TONEMAP_ACES -> TONEMAP_ID_ACES;
                    case TONEMAP_LOTTES -> TONEMAP_ID_LOTTES;
                    case TONEMAP_FROSTBITE -> TONEMAP_ID_FROSTBITE;
                    case TONEMAP_UNCHARTED2 -> TONEMAP_ID_UNCHARTED2;
                    case TONEMAP_GT -> TONEMAP_ID_GT;
                    case TONEMAP_PSYCHOV -> TONEMAP_ID_PSYCHOV;
                    case TONEMAP_PSYCHOV23 -> TONEMAP_ID_PSYCHOV23;
                    default -> TONEMAP_ID_AGX;
                };
            }

            public static float tonemapParam(int index) {
                return switch (TONEMAP_MODE.get()) {
                    case TONEMAP_PBR_NEUTRAL -> switch (index) {
                        case 0 -> PBR_START_COMPRESSION.value();
                        case 1 -> PBR_DESATURATION.value();
                        default -> 0.0f;
                    };
                    case TONEMAP_REINHARD -> index == 0 ? REINHARD_WHITE_POINT.value() : 0.0f;
                    case TONEMAP_ACES -> index == 0 ? ACES_EXPOSURE.value() : 0.0f;
                    case TONEMAP_LOTTES -> switch (index) {
                        case 0 -> LOTTES_CONTRAST.value();
                        case 1 -> LOTTES_SHOULDER.value();
                        case 2 -> LOTTES_HDR_MAX.value();
                        case 3 -> LOTTES_MID_IN.value();
                        case 4 -> LOTTES_MID_OUT.value();
                        default -> 0.0f;
                    };
                    case TONEMAP_FROSTBITE -> switch (index) {
                        case 0 -> FROSTBITE_LINEAR_END.value();
                        case 1 -> FROSTBITE_SHOULDER_STRENGTH.value();
                        default -> 0.0f;
                    };
                    case TONEMAP_UNCHARTED2 -> switch (index) {
                        case 0 -> UNCHARTED_A.value();
                        case 1 -> UNCHARTED_B.value();
                        case 2 -> UNCHARTED_C.value();
                        case 3 -> UNCHARTED_D.value();
                        case 4 -> UNCHARTED_E.value();
                        case 5 -> UNCHARTED_F.value();
                        case 6 -> UNCHARTED_WHITE_POINT.value();
                        default -> 0.0f;
                    };
                    case TONEMAP_GT -> switch (index) {
                        case 0 -> GT_CONTRAST.value();
                        case 1 -> GT_LINEAR_START.value();
                        case 2 -> GT_LINEAR_LENGTH.value();
                        case 3 -> GT_BLACK_CURVE.value();
                        case 4 -> GT_BLACK_LIFT.value();
                        default -> 0.0f;
                    };
                    case TONEMAP_PSYCHOV -> 0.0f;
                    case TONEMAP_PSYCHOV23 -> 0.0f;
                    default -> switch (index) {
                        case 0 -> AGX_CONTRAST.value();
                        case 1 -> AGX_SATURATION.value();
                        default -> 0.0f;
                    };
                };
            }

            private static String sanitizeTonemapMode(String value) {
                if (value != null) {
                    String normalized = value.trim().toLowerCase().replace('_', '-');
                    if ("pbr".equals(normalized) || "neutral".equals(normalized)
                            || "pbrneutral".equals(normalized) || TONEMAP_PBR_NEUTRAL.equals(normalized)) {
                        return TONEMAP_PBR_NEUTRAL;
                    }
                    if ("reinhard-extended".equals(normalized) || TONEMAP_REINHARD.equals(normalized)) {
                        return TONEMAP_REINHARD;
                    }
                    if ("aces-hill".equals(normalized) || TONEMAP_ACES.equals(normalized)) {
                        return TONEMAP_ACES;
                    }
                    if (TONEMAP_AGX.equals(normalized)) {
                        return TONEMAP_AGX;
                    }
                    if (TONEMAP_LOTTES.equals(normalized)) {
                        return TONEMAP_LOTTES;
                    }
                    if (TONEMAP_FROSTBITE.equals(normalized)) {
                        return TONEMAP_FROSTBITE;
                    }
                    if ("uncharted".equals(normalized) || "uncharted-2".equals(normalized)
                            || TONEMAP_UNCHARTED2.equals(normalized)) {
                        return TONEMAP_UNCHARTED2;
                    }
                    if ("gran-turismo".equals(normalized) || TONEMAP_GT.equals(normalized)) {
                        return TONEMAP_GT;
                    }
                    if (TONEMAP_PSYCHOV.equals(normalized) || "psycho".equals(normalized)
                            || "psychovisual".equals(normalized) || "psycho-visual".equals(normalized)) {
                        return TONEMAP_PSYCHOV;
                    }
                    if (TONEMAP_PSYCHOV23.equals(normalized) || "psycho-v23".equals(normalized)
                            || "psychov-23".equals(normalized) || "psycho-visual-23".equals(normalized)) {
                        return TONEMAP_PSYCHOV23;
                    }
                }
                return TONEMAP_PSYCHOV23;
            }
        }

        /** PsychoV23 stages that are mathematically shared by SDR and HDR output transforms. */
        public static final class PsychoV23 {
            public static final FloatSetting COMPRESSION = clampedFloat(
                    "caustica.rt.psychov23.compression", "psychov23.compression", 0.0f, 0.0f, 8.0f);
            public static final FloatSetting GAMUT_COMPRESSION = clampedFloat(
                    "caustica.rt.psychov23.gamutCompression", "psychov23.gamut-compression", 1.0f, 0.0f, 1.0f);

            private PsychoV23() {
            }
        }

        /**
         * HDR display output. When enabled the swapchain is created in PQ (ST.2084/HDR10 — the display-ready
         * encoding both HDR10 swapchains and DLSS Frame Generation require; whatever pixel format the surface
         * pairs with that color space, commonly a 10-bit UNORM), falling back to SDR if the surface doesn't
         * advertise it. The nit values drive the scene-HDR → display mapping: SDR paper white maps to
         * {@code paperWhiteNits}, and highlights roll off toward {@code peakNits}.
         */
        /** Optional heavy vendor crash instrumentation; ordinary device-fault reporting stays enabled. */
        public static final class Diagnostics {
            public static final BooleanSetting HEAVY_CRASH_DIAGNOSTICS =
                    bool("caustica.rt.heavyCrashDiagnostics", "diagnostics.heavy-crash-diagnostics", false);

            private Diagnostics() {
            }
        }

        public static final class Hdr {
            public static final String TONEMAP_EETF = "eetf";
            public static final String TONEMAP_CAUSTICA = "caustica";
            public static final String TONEMAP_PSYCHOV = "psychov";
            public static final String TONEMAP_PSYCHOV23 = "psychov23";

            public static final int TONEMAP_ID_EETF = 0;
            public static final int TONEMAP_ID_CAUSTICA = 1;
            public static final int TONEMAP_ID_PSYCHOV = 2;
            public static final int TONEMAP_ID_PSYCHOV23 = 3;

            public static final BooleanSetting ENABLED = bool("caustica.rt.hdr", "hdr.enabled", false);
            public static final StringSetting TONEMAP_MODE =
                    string("caustica.rt.hdr.tonemapMode", "hdr.tonemap-mode", "eetf", Hdr::sanitizeTonemapMode);
            public static final FloatSetting PAPER_WHITE_NITS =
                    clampedFloat("caustica.rt.hdr.paperWhiteNits", "hdr.paper-white-nits", 203.0f, 80.0f, 1000.0f);
            public static final FloatSetting UI_BRIGHTNESS_NITS =
                    clampedFloat("caustica.rt.hdr.uiBrightnessNits", "hdr.ui-brightness-nits", 100.0f, 40.0f, 1000.0f);
            public static final FloatSetting PEAK_NITS =
                    clampedFloat("caustica.rt.hdr.peakNits", "hdr.peak-nits", 800.0f, 80.0f, 10000.0f);
            // Shared by the SDR and HDR PsychoV output paths. Keep the existing hdr.psychov TOML paths
            // for configuration compatibility even though these controls are no longer presented as HDR-only.
            public static final FloatSetting PSYCHO_HIGHLIGHTS =
                    clampedFloat("caustica.rt.hdr.psychov.highlights", "hdr.psychov.highlights", 1.0f, 0.0f, 3.0f);
            public static final FloatSetting PSYCHO_SHADOWS =
                    clampedFloat("caustica.rt.hdr.psychov.shadows", "hdr.psychov.shadows", 1.0f, 0.0f, 3.0f);
            public static final FloatSetting PSYCHO_CONTRAST =
                    clampedFloat("caustica.rt.hdr.psychov.contrast", "hdr.psychov.contrast", 1.0f, 0.0f, 3.0f);
            public static final FloatSetting PSYCHO_PURITY =
                    clampedFloat("caustica.rt.hdr.psychov.purity", "hdr.psychov.purity", 1.0f, 0.0f, 3.0f);
            public static final FloatSetting PSYCHO_BLEACHING =
                    clampedFloat("caustica.rt.hdr.psychov.bleaching", "hdr.psychov.bleaching", 0.0f, 0.0f, 1.0f);
            public static final FloatSetting PSYCHO_HUE_RESTORE =
                    clampedFloat("caustica.rt.hdr.psychov.hue-restore", "hdr.psychov.hue-restore", 1.0f, 0.0f, 1.0f);
            public static final FloatSetting PSYCHO_ADAPT_CONTRAST =
                    clampedFloat("caustica.rt.hdr.psychov.adapt-contrast", "hdr.psychov.adapt-contrast", 1.0f, 0.0f, 3.0f);
            public static final FloatSetting PSYCHO_CLIP_POINT =
                    clampedFloat("caustica.rt.hdr.psychov.clip-point", "hdr.psychov.clip-point", 100.0f, 1.0f, 1000.0f);
            public static final FloatSetting PSYCHO_CONE_EXPONENT =
                    clampedFloat("caustica.rt.hdr.psychov.cone-exponent", "hdr.psychov.cone-exponent", 1.0f, 0.1f, 3.0f);
            public static final StringSetting PSYCHO_WHITE_CURVE =
                    string("caustica.rt.hdr.psychov.whiteCurve", "hdr.psychov.white-curve", "naka-rushton",
                            Hdr::sanitizePsychoWhiteCurve);
            // HDR request is live; the coordinator applies it on the next safe client tick.
            private Hdr() {
            }

            /** Current HDR request; the swapchain coordinator applies it on the next safe client tick. */
            public static boolean enabled() {
                return ENABLED.get();
            }

            /** Absolute nits SDR paper white maps to in the PQ encode (ST.2084 is referenced to 10000 nits). */
            public static float paperWhiteNits() {
                return PAPER_WHITE_NITS.value();
            }

            /** Absolute luminance used for SDR-authored HUD and menu white in the HDR composite. */
            public static float uiBrightnessNits() {
                return UI_BRIGHTNESS_NITS.value();
            }

            /** Highlight headroom above paper white, in paper-white-referred units ({@code >= 1}). */
            public static float headroom() {
                return Math.max(1.0f, PEAK_NITS.value() / Math.max(1.0f, PAPER_WHITE_NITS.value()));
            }

            public static int tonemapModeId() {
                return switch (TONEMAP_MODE.get()) {
                    case TONEMAP_CAUSTICA -> TONEMAP_ID_CAUSTICA;
                    case TONEMAP_PSYCHOV -> TONEMAP_ID_PSYCHOV;
                    case TONEMAP_PSYCHOV23 -> TONEMAP_ID_PSYCHOV23;
                    default -> TONEMAP_ID_EETF;
                };
            }

            public static float psychoWhiteCurveId() {
                return "neutwo".equals(PSYCHO_WHITE_CURVE.get()) ? 0.0f : 1.0f;
            }

            private static String sanitizeTonemapMode(String value) {
                if (value != null) {
                    String normalized = value.trim().toLowerCase();
                    if (TONEMAP_CAUSTICA.equals(normalized)) {
                        return TONEMAP_CAUSTICA;
                    }
                    if (TONEMAP_PSYCHOV.equals(normalized) || "psycho".equals(normalized)
                            || "psychovisual".equals(normalized)) {
                        return TONEMAP_PSYCHOV;
                    }
                    if (TONEMAP_PSYCHOV23.equals(normalized) || "psycho-v23".equals(normalized)
                            || "psychov-23".equals(normalized) || "psycho-visual-23".equals(normalized)) {
                        return TONEMAP_PSYCHOV23;
                    }
                    // Common typo for the BT.2390 display map. PQ itself defines an EOTF; this mode is an EETF.
                    if (TONEMAP_EETF.equals(normalized) || "eotf".equals(normalized)
                            || "bt2390".equals(normalized) || "bt.2390".equals(normalized)) {
                        return TONEMAP_EETF;
                    }
                }
                return TONEMAP_PSYCHOV23;
            }

            private static String sanitizePsychoWhiteCurve(String value) {
                if (value != null && "neutwo".equalsIgnoreCase(value.trim())) {
                    return "neutwo";
                }
                return "naka-rushton";
            }
        }
    }

    private static BooleanSetting bool(String key, String tomlPath, boolean fallback) {
        return new BooleanSetting(key, tomlPath, fallback);
    }

    private static StringSetting string(String key, String tomlPath, String fallback, UnaryOperator<String> sanitize) {
        return new StringSetting(key, tomlPath, fallback, sanitize);
    }

    private static OptionalStringSetting optionalString(String key, String tomlPath) {
        return new OptionalStringSetting(key, tomlPath);
    }

    private static IntSetting intValue(String key, String tomlPath, int fallback) {
        return new IntSetting(key, tomlPath, fallback, v -> v);
    }

    private static IntSetting intAtLeast(String key, String tomlPath, int fallback, int min) {
        return new IntSetting(key, tomlPath, fallback, v -> Math.max(min, v));
    }

    private static IntSetting clampedInt(String key, String tomlPath, int fallback, int min, int max) {
        return new IntSetting(key, tomlPath, fallback, v -> Math.clamp(v, min, max));
    }

    private static FloatSetting finiteFloat(String key, String tomlPath, float fallback) {
        return new FloatSetting(key, tomlPath, fallback, v -> v, v -> v, v -> Double.isFinite(v) ? v : fallback);
    }

    private static FloatSetting exposureScale(String key, String tomlPath, float fallback) {
        return new FloatSetting(key, tomlPath, fallback, v -> v, v -> v, v -> Math.clamp(v, 1.0e-4, 1.0e4));
    }

    private static FloatSetting clampedFloat(String key, String tomlPath, float fallback, float min, float max) {
        return new FloatSetting(key, tomlPath, fallback, v -> v, v -> v, v -> Math.clamp(v, min, max));
    }

    private static FloatSetting radians(String key, String tomlPath, float fallbackDegrees) {
        return new FloatSetting(key, tomlPath, fallbackDegrees, Math::toRadians, Math::toDegrees, v -> Double.isFinite(v) ? v : 0.0);
    }

    private static int defaultWorkerThreads() {
        return Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 4);
    }
}
