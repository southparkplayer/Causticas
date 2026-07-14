package dev.comfyfluffy.caustica;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.electronwill.nightconfig.toml.TomlFormat;
import java.nio.file.Path;
import java.util.List;
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

    private static final Path CONFIG_PATH = resolveConfigPath();
    private static final CommentedFileConfig FILE = loadFile(CONFIG_PATH);

    private CausticaConfig() {
    }

    public static List<RuntimeSetting<?>> settings() {
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

    /**
     * Forces every settings holder to class-initialize so all settings are registered (and have applied
     * their file values). Call before {@link #save()} to write a complete file, and once at startup so the
     * file round-trips the full surface even for settings the renderer has not touched yet.
     */
    public static void ensureRegistered() {
        @SuppressWarnings("unused")
        Object[] touch = {
            Rt.ENABLED, Rt.Composite.SPP, Rt.Composite.MAX_BOUNCES, Rt.Terrain.ASYNC_DISPATCH_PER_TICK, Rt.Omm.ENABLED,
            Rt.Entities.ENABLED, Rt.Entities.GLOW_ENABLED, Rt.FirstPerson.ENABLED, Rt.EntityTextures.MAX_TEXTURES,
            Rt.DlssRr.ENABLED,
            Rt.Fg.ENABLED, Rt.Fg.MODE, Rt.Fg.MULTI_FRAME_COUNT, Rt.Fg.DYNAMIC_TARGET_FPS, Rt.Fg.AUTO_CAP,
            Rt.Reflex.ENABLED, Rt.Exposure.MODE, Rt.FrameStats.ENABLED,
            Rt.Sdr.TONEMAP_MODE, Rt.Hdr.ENABLED, Rt.Hdr.TONEMAP_MODE,
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
        writeComments();
        for (RuntimeSetting<?> setting : SETTINGS) {
            setting.writeToFile(FILE);
        }
        FILE.save();
    }

    private static void writeComments() {
        FILE.setComment("enabled",
                " Caustica RT renderer configuration.\n"
                        + " A matching -Dcaustica.* system property overrides the value below.");
        FILE.setComment("terrain",
                " Wall-clock budget for one streaming pass (snapshot dispatch + upload drain). The per-frame\n"
                        + " slice scales with queue pressure from stream-budget-ms (near-idle) up to\n"
                        + " stream-budget-max-ms (big backlog: initial fill, F3+A, teleport, fast flight) so fill\n"
                        + " throughput recovers when it matters and the cost drops back once the queue clears.\n"
                        + " stream-fallback-budget-ms is the per-tick slice used only when no world frame is\n"
                        + " streaming (loading screens), where a long pass hitches nothing.");
        FILE.setComment("frame-generation",
                " Streamline DLSS Frame Generation. mode: off, fixed, or auto (legacy).\n"
                        + " multi-frame-count is generated frames per rendered frame (1 = 2x, 2 = 3x, ...).\n"
                        + " auto-cap derives a total output FPS target from the active monitor using\n"
                        + " floor(3600 * refresh / (refresh + 3600)), then divides that budget across the\n"
                        + " rendered frame and all generated frames through Reflex.\n"
                        + " Dynamic MFG is D3D12-only and is migrated to fixed because Caustica uses Vulkan.\n"
                        + " With VSync requested, Vulkan DLSS-G uses RADSER's MAILBOX compatibility path\n"
                        + " because Streamline 2.12 does not support FIFO VSync on Vulkan. Auto Cap remains\n"
                        + " an independent opt-in and is never forced by VSync.");
        FILE.setComment("reflex",
                " Streamline Reflex Low Latency. DLSS-G forces effective On while generation is active.\n"
                        + " minimum-interval-us is the manual rendered-frame fallback used when FG Auto Cap is off;\n"
                        + " 0 = no manual cap. PCL markers and sleep still run when Reflex is Off.");
        FILE.setComment("sdr",
                " SDR display mapping for the vanilla main target. tonemap-mode defaults to agx to preserve\n"
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

    private static CommentedFileConfig loadFile(Path path) {
        CommentedFileConfig config = CommentedFileConfig.builder(path, TomlFormat.instance())
                .onFileNotFound(FileNotFoundAction.CREATE_EMPTY)
                .preserveInsertionOrder()
                .sync()
                .build();
        try {
            config.load();
        } catch (Exception e) {
            LOGGER.warn("Failed to read Caustica config {}: {}", path, e.toString());
        }
        return config;
    }

    private static Boolean fileBoolean(String tomlPath) {
        return FILE.contains(tomlPath) ? FILE.<Boolean>get(tomlPath) : null;
    }

    private static Number fileNumber(String tomlPath) {
        return FILE.contains(tomlPath) ? FILE.<Number>get(tomlPath) : null;
    }

    private static String fileString(String tomlPath) {
        return FILE.contains(tomlPath) ? FILE.<String>get(tomlPath) : null;
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
                    clampedInt("caustica.rt.maxBounces", "composite.max-bounces", 4, 2, 8);
            public static final BooleanSetting WATER_WAVES =
                    bool("caustica.rt.waterWaves", "composite.water-waves", true);
            public static final FloatSetting SUN_ANGULAR_RADIUS =
                    radians("caustica.rt.sunAngularRadius", "composite.sun-angular-radius-deg", 0.6f);
            public static final FloatSetting MOON_ANGULAR_RADIUS =
                    radians("caustica.rt.moonAngularRadius", "composite.moon-angular-radius-deg", 1.5f);
            public static final FloatSetting SUN_NOON_SOUTH_TILT =
                    radians("caustica.rt.sunNoonSouthDeg", "composite.sun-noon-south-tilt-deg", 30.0f);
            public static final FloatSetting JITTER_SIGN_X =
                    finiteFloat("caustica.rt.jitterSignX", "composite.jitter-sign-x", 1.0f);
            public static final FloatSetting JITTER_SIGN_Y =
                    finiteFloat("caustica.rt.jitterSignY", "composite.jitter-sign-y", -1.0f);

            private Composite() {
            }
        }

        public static final class Terrain {
            public static final IntSetting ASYNC_DISPATCH_PER_TICK =
                    intAtLeast("caustica.rt.asyncDispatchPerTick", "terrain.async-dispatch-per-tick", 64, 0);
            public static final IntSetting SECTION_RESULTS_PER_TICK =
                    intAtLeast("caustica.rt.sectionResultsPerTick", "terrain.section-results-per-tick", 64, 0);
            public static final FloatSetting STREAM_BUDGET_MS =
                    clampedFloat("caustica.rt.streamBudgetMs", "terrain.stream-budget-ms", 1.5f, 0.05f, 100f);
            public static final FloatSetting STREAM_BUDGET_MAX_MS =
                    clampedFloat("caustica.rt.streamBudgetMaxMs", "terrain.stream-budget-max-ms", 6f, 0.05f, 100f);
            public static final FloatSetting STREAM_FALLBACK_BUDGET_MS =
                    clampedFloat("caustica.rt.streamFallbackBudgetMs", "terrain.stream-fallback-budget-ms", 8f, 0.05f, 100f);
            public static final IntSetting MAX_INFLIGHT_SECTIONS =
                    intAtLeast("caustica.rt.maxInflightSections", "terrain.max-inflight-sections", 192, 0);
            public static final IntSetting SECTION_TABLE_INITIAL_CAPACITY =
                    intAtLeast("caustica.rt.sectionTableInitialCapacity", "terrain.section-table-initial-capacity", 512, 1);
            public static final IntSetting REBASE_DISTANCE_BLOCKS =
                    intAtLeast("caustica.rt.rebaseDistanceBlocks", "terrain.rebase-distance-blocks", 128, 0);

            private Terrain() {
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

        public static final class Entities {
            public static final BooleanSetting ENABLED = bool("caustica.rt.entities", "entities.enabled", true);
            public static final BooleanSetting PARTICLES_ENABLED =
                    bool("caustica.rt.particles", "particles.enabled", true);
            public static final BooleanSetting GLOW_ENABLED =
                    bool("caustica.rt.glow", "entities.glow.enabled", true);
            public static final BooleanSetting NAME_TAGS_ENABLED =
                    bool("caustica.rt.nameTags", "entities.name-tags.enabled", true);
            public static final IntSetting MAX_ENTITIES =
                    intAtLeast("caustica.rt.maxEntities", "entities.max-entities", 1024, 1);
            public static final IntSetting BE_VIEW_CHUNKS =
                    intAtLeast("caustica.rt.beViewChunks", "entities.block-entities.view-chunks", 8, 0);
            public static final IntSetting BE_BUILDS_PER_FRAME =
                    intAtLeast("caustica.rt.beBuildsPerFrame", "entities.block-entities.builds-per-frame", 8, 0);
            public static final IntSetting REFIT_REBUILD_INTERVAL =
                    intAtLeast("caustica.rt.refitRebuildInterval", "entities.refit.rebuild-interval", 120, 1);

            private Entities() {
            }

            public static int entityListCapacity() {
                return Math.max(16, MAX_ENTITIES.value());
            }

            public static int entityBufferListCapacity() {
                return (int) Math.min(Integer.MAX_VALUE, (long) entityListCapacity() * 5L);
            }

            public static int entityMapCapacity() {
                return (int) Math.min(Integer.MAX_VALUE, Math.max(16L, (long) MAX_ENTITIES.value() * 2L));
            }
        }

        public static final class FirstPerson {
            public static final BooleanSetting ENABLED =
                    bool("caustica.rt.firstPerson", "first-person.enabled", true);
            public static final FloatSetting FORWARD_OFFSET = clampedFloat(
                    "caustica.rt.firstPerson.forwardOffset", "first-person.forward-offset", -0.20f, -0.30f, 0.30f);
            public static final FloatSetting VERTICAL_OFFSET = clampedFloat(
                    "caustica.rt.firstPerson.verticalOffset", "first-person.vertical-offset", 0.0f, -0.30f, 0.30f);
            public static final FloatSetting LATERAL_OFFSET = clampedFloat(
                    "caustica.rt.firstPerson.lateralOffset", "first-person.lateral-offset", 0.0f, -0.20f, 0.20f);

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
            public static final IntSetting PRESET = intValue("caustica.rt.dlssRr.preset", "dlss-rr.preset", 0);
            public static final IntSetting QUALITY = intValue("caustica.rt.dlssRr.quality", "dlss-rr.quality", 0);

            private DlssRr() {
            }
        }

        /** DLSS Frame Generation. Default off; gated additionally by hardware/driver availability. */
        public static final class Fg {
            /** Legacy compatibility switch; new code and UI use {@link #MODE}. */
            public static final BooleanSetting ENABLED = bool("caustica.rt.fg", "frame-generation.enabled", false);
            public static final StringSetting MODE = string(
                    "caustica.rt.fg.mode", "frame-generation.mode", "off", Fg::sanitizeMode);
            public static final IntSetting MULTI_FRAME_COUNT =
                    clampedInt("caustica.rt.fg.multiFrameCount", "frame-generation.multi-frame-count", 1, 1, 5);
            public static final FloatSetting DYNAMIC_TARGET_FPS = clampedFloat(
                    "caustica.rt.fg.dynamicTargetFps", "frame-generation.dynamic-target-fps", 0.0f, 0.0f, 1000.0f);
            public static final BooleanSetting AUTO_CAP = bool(
                    "caustica.rt.fg.autoCap", "frame-generation.auto-cap", true);
            public static final BooleanSetting UI_RECOMPOSITION = bool(
                    "caustica.rt.fg.uiRecomposition", "frame-generation.ui-recomposition", true);
            public static final BooleanSetting FULLSCREEN_MENU_DETECTION = bool(
                    "caustica.rt.fg.fullscreenMenuDetection", "frame-generation.fullscreen-menu-detection", true);
            public static final BooleanSetting SHOW_ONLY_INTERPOLATED = bool(
                    "caustica.rt.fg.showOnlyInterpolated", "frame-generation.show-only-interpolated", false);
            public static final StringSetting QUEUE_PARALLELISM = string(
                    "caustica.rt.fg.queueParallelism", "frame-generation.queue-parallelism", "safe",
                    value -> "no-client-queues".equalsIgnoreCase(value) ? "no-client-queues" : "safe");

            private Fg() {
            }

            public static String mode() {
                String mode = MODE.get();
                return "off".equals(mode) && ENABLED.value() ? "fixed" : mode;
            }

            public static String configuredMode() {
                String mode = MODE.configuredValue();
                return "off".equals(mode) && ENABLED.configuredValue() ? "fixed" : mode;
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
                    case "fixed", "dynamic", "auto" -> value.toLowerCase(java.util.Locale.ROOT);
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
            public static final FloatSetting MANUAL_EV =
                    finiteFloat("caustica.rt.exposure.manualEv", "exposure.manual-ev", 0.0f);
            public static final FloatSetting KEY = exposureScale("caustica.rt.exposure.key", "exposure.key", 0.18f);
            public static final FloatSetting MIN_EV =
                    finiteFloat("caustica.rt.exposure.minEv", "exposure.min-ev", -1.5f);
            public static final FloatSetting MAX_EV =
                    finiteFloat("caustica.rt.exposure.maxEv", "exposure.max-ev", 2.0f);
            public static final FloatSetting ADAPT_UP =
                    exposureScale("caustica.rt.exposure.adaptUp", "exposure.adapt-up", 0.12f);
            public static final FloatSetting ADAPT_DOWN =
                    exposureScale("caustica.rt.exposure.adaptDown", "exposure.adapt-down", 0.35f);

            private Exposure() {
            }

            public static float minEv() {
                return Math.min(MIN_EV.value(), MAX_EV.value());
            }

            public static float maxEv() {
                return Math.max(MIN_EV.value(), MAX_EV.value());
            }

            public static float clampScale(float value) {
                return Math.clamp(value, 1.0e-4f, 1.0e4f);
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
                    string("caustica.rt.sdr.tonemapMode", "sdr.tonemap-mode", TONEMAP_AGX, Sdr::sanitizeTonemapMode);
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
                    clampedFloat("caustica.rt.sdr.psychov.peak", "sdr.psychov.peak", 2.0f, 0.5f, 8.0f);
            public static final FloatSetting PSYCHOV23_PEAK =
                    clampedFloat("caustica.rt.sdr.psychov23.peak", "sdr.psychov23.peak", 1000.0f / 203.0f, 0.5f, 8.0f);
            public static final FloatSetting PSYCHOV23_COMPRESSION =
                    clampedFloat("caustica.rt.sdr.psychov23.compression", "sdr.psychov23.compression", 1.0f, 0.0f, 8.0f);
            public static final FloatSetting PSYCHOV23_GAMUT_COMPRESSION =
                    clampedFloat("caustica.rt.sdr.psychov23.gamutCompression", "sdr.psychov23.gamut-compression", 1.0f, 0.0f, 1.0f);

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
                return TONEMAP_AGX;
            }
        }

        /**
         * HDR display output. When enabled the swapchain is created in PQ (ST.2084/HDR10 — the display-ready
         * encoding both HDR10 swapchains and DLSS Frame Generation require; whatever pixel format the surface
         * pairs with that color space, commonly a 10-bit UNORM), falling back to SDR if the surface doesn't
         * advertise it. The nit values drive the scene-HDR → display mapping: SDR paper white maps to
         * {@code paperWhiteNits}, and highlights roll off toward {@code peakNits}.
         */
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
                    string("caustica.rt.hdr.tonemapMode", "hdr.tonemap-mode", TONEMAP_EETF, Hdr::sanitizeTonemapMode);
            public static final FloatSetting PAPER_WHITE_NITS =
                    clampedFloat("caustica.rt.hdr.paperWhiteNits", "hdr.paper-white-nits", 200.0f, 80.0f, 1000.0f);
            public static final FloatSetting PEAK_NITS =
                    clampedFloat("caustica.rt.hdr.peakNits", "hdr.peak-nits", 1000.0f, 80.0f, 10000.0f);
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
                    clampedFloat("caustica.rt.hdr.psychov.hue-restore", "hdr.psychov.hue-restore", 0.0f, 0.0f, 1.0f);
            public static final FloatSetting PSYCHO_ADAPT_CONTRAST =
                    clampedFloat("caustica.rt.hdr.psychov.adapt-contrast", "hdr.psychov.adapt-contrast", 1.0f, 0.0f, 3.0f);
            public static final FloatSetting PSYCHO_CLIP_POINT =
                    clampedFloat("caustica.rt.hdr.psychov.clip-point", "hdr.psychov.clip-point", 100.0f, 1.0f, 1000.0f);
            public static final FloatSetting PSYCHO_CONE_EXPONENT =
                    clampedFloat("caustica.rt.hdr.psychov.cone-exponent", "hdr.psychov.cone-exponent", 1.0f, 0.1f, 3.0f);
            public static final StringSetting PSYCHO_WHITE_CURVE =
                    string("caustica.rt.hdr.psychov.whiteCurve", "hdr.psychov.white-curve", "naka-rushton",
                            Hdr::sanitizePsychoWhiteCurve);
            public static final FloatSetting PSYCHOV23_COMPRESSION =
                    clampedFloat("caustica.rt.hdr.psychov23.compression", "hdr.psychov23.compression", 1.0f, 0.0f, 8.0f);
            public static final FloatSetting PSYCHOV23_GAMUT_COMPRESSION =
                    clampedFloat("caustica.rt.hdr.psychov23.gamutCompression", "hdr.psychov23.gamut-compression", 1.0f, 0.0f, 1.0f);

            // Snapshot of ENABLED as resolved at startup (system property / config file), before any
            // in-session edit from the options screen. The swapchain's pixel format (PQ vs SDR) is fixed
            // at surface-creation time, so flipping ENABLED later cannot change what's actually presented
            // until a restart — every runtime/rendering check reads this frozen value via enabled(),
            // never ENABLED directly, so the live toggle is a no-op for the current session.
            private static final boolean ENABLED_AT_STARTUP = ENABLED.value();

            private Hdr() {
            }

            /** Whether the HDR display path (world HDR + PQ swapchain + UI overlay) is active this session. */
            public static boolean enabled() {
                return sessionRequest(ENABLED_AT_STARTUP, ENABLED.value());
            }

            /** Whether {@link #ENABLED} has been changed since startup and needs a restart to take effect. */
            public static boolean pendingRestart() {
                return settingRequiresRestart(ENABLED_AT_STARTUP, ENABLED.value());
            }

            static boolean sessionRequest(boolean enabledAtStartup, boolean ignoredLiveSetting) {
                return enabledAtStartup;
            }

            static boolean settingRequiresRestart(boolean enabledAtStartup, boolean liveSetting) {
                return liveSetting != enabledAtStartup;
            }

            /** Absolute nits SDR paper white maps to in the PQ encode (ST.2084 is referenced to 10000 nits). */
            public static float paperWhiteNits() {
                return PAPER_WHITE_NITS.value();
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
                return TONEMAP_EETF;
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
