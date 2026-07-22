package dev.comfyfluffy.caustica.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.client.settings.SettingsCatalog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Versioned persistence for deterministic settings navigation state.
 *
 * <p>This is deliberately independent of renderer configuration and menu-usage telemetry. Callers
 * capture the active page bookmark before changing layout, update expansion overrides as the user
 * changes them, and call {@link #save()} on close or shutdown. A failed save leaves the state dirty so
 * a later flush can retry it.
 */
public final class SettingsUiState {
    public static final int SCHEMA_VERSION = 1;
    public static final String FILE_NAME = "caustica-settings-ui.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path path;
    private final Map<String, PageBookmark> pageBookmarks = new TreeMap<>();
    private final Map<String, Boolean> groupExpansion = new TreeMap<>();
    private final Map<String, Boolean> sectionExpansion = new TreeMap<>();
    private String lastPageId = SettingsCatalog.Page.ESSENTIALS.routeId();
    private boolean dirty;

    private SettingsUiState(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    /** Loads state from the Fabric config directory. */
    public static SettingsUiState load() {
        return load(FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME));
    }

    /** Loads state from an explicit path, primarily for integration tests and alternate hosts. */
    public static SettingsUiState load(Path path) {
        SettingsUiState state = new SettingsUiState(Objects.requireNonNull(path, "path"));
        state.loadFromDisk();
        return state;
    }

    public int schemaVersion() {
        return SCHEMA_VERSION;
    }

    public Path path() {
        return path;
    }

    public synchronized String lastPageId() {
        return lastPageId;
    }

    public synchronized SettingsCatalog.Page lastPage() {
        return SettingsCatalog.Page.parse(lastPageId);
    }

    public synchronized void setLastPage(SettingsCatalog.Page page) {
        setLastPageId(page == null ? null : page.routeId());
    }

    /** Accepts canonical routes, enum names, and aliases recognized by {@link SettingsCatalog.Page#parse}. */
    public synchronized void setLastPageId(String route) {
        String canonical = canonicalPageId(route);
        if (!canonical.equals(lastPageId)) {
            lastPageId = canonical;
            dirty = true;
        }
    }

    public synchronized PageBookmark pageBookmark(SettingsCatalog.Page page) {
        return pageBookmarks.get(canonicalPageId(page == null ? null : page.routeId()));
    }

    public synchronized void setPageBookmark(SettingsCatalog.Page page, PageBookmark bookmark) {
        String pageId = canonicalPageId(page == null ? null : page.routeId());
        PageBookmark normalized = normalizeBookmark(Objects.requireNonNull(bookmark, "bookmark"));
        if (!normalized.equals(pageBookmarks.put(pageId, normalized))) dirty = true;
    }

    public synchronized void clearPageBookmark(SettingsCatalog.Page page) {
        String pageId = canonicalPageId(page == null ? null : page.routeId());
        if (pageBookmarks.remove(pageId) != null) dirty = true;
    }

    public synchronized Map<String, PageBookmark> pageBookmarks() {
        return immutableSortedCopy(pageBookmarks);
    }

    public synchronized boolean groupExpanded(String groupId, boolean defaultExpanded) {
        return groupExpansion.getOrDefault(normalizeId(groupId), defaultExpanded);
    }

    public synchronized void setGroupExpanded(String groupId, boolean expanded) {
        putExpansion(groupExpansion, groupId, expanded);
    }

    public synchronized void clearGroupExpansion(String groupId) {
        clearExpansion(groupExpansion, groupId);
    }

    public synchronized Map<String, Boolean> groupExpansion() {
        return immutableSortedCopy(groupExpansion);
    }

    public synchronized boolean sectionExpanded(String sectionId, boolean defaultExpanded) {
        return sectionExpansion.getOrDefault(normalizeId(sectionId), defaultExpanded);
    }

    public synchronized void setSectionExpanded(String sectionId, boolean expanded) {
        putExpansion(sectionExpansion, sectionId, expanded);
    }

    public synchronized void clearSectionExpansion(String sectionId) {
        clearExpansion(sectionExpansion, sectionId);
    }

    public synchronized Map<String, Boolean> sectionExpansion() {
        return immutableSortedCopy(sectionExpansion);
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    /**
     * Atomically replaces the persisted snapshot when possible. Returns false on failure and retains
     * the dirty flag so screen close or client shutdown can retry the write.
     */
    public synchronized boolean save() {
        if (!dirty) return true;

        Path temporary = null;
        try {
            Files.createDirectories(path.getParent());
            temporary = Files.createTempFile(path.getParent(), path.getFileName() + ".", ".tmp");
            Snapshot snapshot = new Snapshot(SCHEMA_VERSION, lastPageId, new TreeMap<>(pageBookmarks),
                    new TreeMap<>(groupExpansion), new TreeMap<>(sectionExpansion));
            Files.writeString(temporary, GSON.toJson(snapshot), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException atomicMoveUnavailable) {
                CausticaMod.LOGGER.warn(
                        "Settings UI state atomic replacement unavailable [path={}, error={}]",
                        path, atomicMoveUnavailable.toString());
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
            return true;
        } catch (IOException | RuntimeException failure) {
            CausticaMod.LOGGER.warn("Settings UI state save failed [path={}, error={}]",
                    path, failure.toString());
            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupFailure) {
                    CausticaMod.LOGGER.warn(
                            "Settings UI state temporary cleanup failed [path={}, temporary={}, error={}]",
                            path, temporary, cleanupFailure.toString());
                }
            }
        }
    }

    private void loadFromDisk() {
        if (!Files.isRegularFile(path)) return;

        try {
            JsonElement root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            JsonObject object = requireCurrentSchema(root);
            Snapshot loaded = GSON.fromJson(object, Snapshot.class);
            if (loaded == null) throw new IllegalArgumentException("snapshot is null");

            boolean normalized = loadSnapshot(loaded);
            dirty = normalized;
            if (normalized) {
                CausticaMod.LOGGER.info(
                        "Settings UI state normalized [path={}, schemaVersion={}, lastPageId={}]",
                        path, SCHEMA_VERSION, lastPageId);
            }
        } catch (IOException | RuntimeException failure) {
            reset();
            dirty = true;
            CausticaMod.LOGGER.warn("Settings UI state load failed [path={}, error={}]",
                    path, failure.toString());
            preserveMalformedState();
        }
    }

    private static JsonObject requireCurrentSchema(JsonElement root) {
        if (root == null || !root.isJsonObject()) {
            throw new IllegalArgumentException("root is not an object");
        }
        JsonObject object = root.getAsJsonObject();
        JsonElement rawVersion = object.get("schemaVersion");
        if (rawVersion == null || !rawVersion.isJsonPrimitive()
                || !rawVersion.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("schemaVersion is missing or not numeric");
        }
        int version = rawVersion.getAsInt();
        if (rawVersion.getAsDouble() != version || version != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported schemaVersion " + rawVersion);
        }
        return object;
    }

    private boolean loadSnapshot(Snapshot loaded) {
        boolean normalized = false;

        String loadedLastPage = loaded.lastPageId();
        lastPageId = canonicalPageId(loadedLastPage);
        normalized |= !lastPageId.equals(loadedLastPage);

        if (loaded.pageBookmarks() == null) {
            normalized = true;
        } else {
            Map<String, Integer> ranks = new TreeMap<>();
            for (Map.Entry<String, PageBookmark> entry : new TreeMap<>(loaded.pageBookmarks()).entrySet()) {
                String rawPageId = entry.getKey();
                PageBookmark rawBookmark = entry.getValue();
                if (rawPageId == null || rawPageId.isBlank() || rawBookmark == null) {
                    normalized = true;
                    continue;
                }
                String canonical = canonicalPageId(rawPageId);
                PageBookmark bookmark = normalizeBookmark(rawBookmark);
                int rank = canonical.equals(rawPageId.trim()) ? 1 : 0;
                Integer previousRank = ranks.get(canonical);
                if (previousRank == null || rank > previousRank) {
                    pageBookmarks.put(canonical, bookmark);
                    ranks.put(canonical, rank);
                }
                normalized |= !canonical.equals(rawPageId) || !bookmark.equals(rawBookmark)
                        || previousRank != null;
            }
        }

        normalized |= loadExpansions(groupExpansion, loaded.groupExpansion());
        normalized |= loadExpansions(sectionExpansion, loaded.sectionExpansion());
        return normalized;
    }

    private static boolean loadExpansions(Map<String, Boolean> destination, Map<String, Boolean> source) {
        if (source == null) return true;
        boolean normalized = false;
        for (Map.Entry<String, Boolean> entry : source.entrySet()) {
            String rawId = entry.getKey();
            Boolean value = entry.getValue();
            if (rawId == null || rawId.isBlank() || value == null) {
                normalized = true;
                continue;
            }
            String id = rawId.trim();
            destination.put(id, value);
            normalized |= !id.equals(rawId);
        }
        return normalized;
    }

    private void preserveMalformedState() {
        if (!Files.isRegularFile(path)) return;

        String baseName = path.getFileName() + ".broken-" + System.currentTimeMillis();
        Path backup = path.resolveSibling(baseName);
        for (int suffix = 1; Files.exists(backup); suffix++) {
            backup = path.resolveSibling(baseName + "-" + suffix);
        }
        try {
            Files.copy(path, backup, StandardCopyOption.COPY_ATTRIBUTES);
            CausticaMod.LOGGER.warn(
                    "Settings UI state malformed backup preserved [source={}, backup={}]", path, backup);
        } catch (IOException | RuntimeException backupFailure) {
            CausticaMod.LOGGER.warn(
                    "Settings UI state malformed backup failed [source={}, backup={}, error={}]",
                    path, backup, backupFailure.toString());
        }
    }

    private void reset() {
        lastPageId = SettingsCatalog.Page.ESSENTIALS.routeId();
        pageBookmarks.clear();
        groupExpansion.clear();
        sectionExpansion.clear();
    }

    private void putExpansion(Map<String, Boolean> expansion, String rawId, boolean expanded) {
        String id = normalizeId(rawId);
        if (!Objects.equals(expansion.put(id, expanded), expanded)) dirty = true;
    }

    private void clearExpansion(Map<String, Boolean> expansion, String rawId) {
        if (expansion.remove(normalizeId(rawId)) != null) dirty = true;
    }

    private static String canonicalPageId(String route) {
        return SettingsCatalog.Page.parse(route).routeId();
    }

    private static String normalizeId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("state id must not be blank");
        return id.trim();
    }

    private static PageBookmark normalizeBookmark(PageBookmark bookmark) {
        return new PageBookmark(bookmark.anchorId(), bookmark.anchorViewportFraction(),
                bookmark.fallbackPageProgress());
    }

    private static <V> Map<String, V> immutableSortedCopy(Map<String, V> source) {
        return Collections.unmodifiableMap(new TreeMap<>(source));
    }

    /** Semantic location plus normalized fallback for one settings page. */
    public record PageBookmark(String anchorId, double anchorViewportFraction,
                               double fallbackPageProgress) {
        public PageBookmark {
            anchorId = anchorId == null ? "" : anchorId.trim();
            anchorViewportFraction = clampFraction(anchorViewportFraction);
            fallbackPageProgress = clampFraction(fallbackPageProgress);
        }

        /** Captures the documented semantic anchor and normalized page-progress fallback. */
        public static PageBookmark capture(String anchorId, double anchorTop, double viewportTop,
                                           double viewportHeight, double scrollAmount,
                                           double maxScroll) {
            double viewportSpan = Double.isFinite(viewportHeight) ? Math.max(1.0, viewportHeight) : 1.0;
            double anchorFraction = Double.isFinite(anchorTop) && Double.isFinite(viewportTop)
                    ? (anchorTop - viewportTop) / viewportSpan : 0.0;
            double progress = Double.isFinite(maxScroll) && maxScroll > 0.0
                    && Double.isFinite(scrollAmount) ? scrollAmount / maxScroll : 0.0;
            return new PageBookmark(anchorId, anchorFraction, progress);
        }

        /** Restores against a resolved anchor; invalid geometry falls back to normalized page progress. */
        public double restoredScroll(double anchorTop, double viewportTop, double viewportHeight,
                                     double maxScroll) {
            if (!Double.isFinite(anchorTop) || !Double.isFinite(viewportTop)
                    || !Double.isFinite(viewportHeight)) {
                return fallbackScroll(maxScroll);
            }
            double restored = anchorTop - viewportTop - anchorViewportFraction * viewportHeight;
            return clampScroll(restored, maxScroll);
        }

        public double fallbackScroll(double maxScroll) {
            if (!Double.isFinite(maxScroll) || maxScroll <= 0.0) return 0.0;
            return fallbackPageProgress * maxScroll;
        }

        private static double clampFraction(double value) {
            if (Double.isNaN(value) || value <= 0.0) return 0.0;
            if (value >= 1.0) return 1.0;
            return value;
        }

        private static double clampScroll(double value, double maxScroll) {
            if (!Double.isFinite(maxScroll) || maxScroll <= 0.0 || Double.isNaN(value)) return 0.0;
            return Math.max(0.0, Math.min(value, maxScroll));
        }
    }

    private record Snapshot(int schemaVersion, String lastPageId,
                            Map<String, PageBookmark> pageBookmarks,
                            Map<String, Boolean> groupExpansion,
                            Map<String, Boolean> sectionExpansion) {
    }
}
