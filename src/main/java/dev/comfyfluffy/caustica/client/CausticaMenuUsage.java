package dev.comfyfluffy.caustica.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import dev.comfyfluffy.caustica.client.settings.SettingsCatalog;

/** Local-only, low-volume menu history used for Recent and Frequent quick links. */
final class CausticaMenuUsage {
    static final CausticaMenuUsage INSTANCE = new CausticaMenuUsage();

    private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;
    private static final double FREQUENCY_HALF_LIFE_DAYS = 30.0;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type ENTRY_MAP_TYPE = new TypeToken<Map<String, Entry>>() { }.getType();
    private static final Type SNAPSHOT_TYPE = new TypeToken<Snapshot>() { }.getType();

    private final Path path = FabricLoader.getInstance().getConfigDir().resolve("caustica-menu-usage.json");
    private final Map<String, Entry> entries = new HashMap<>();
    private final Map<String, Double> scrollPositions = new HashMap<>();
    private String lastCategory = SettingsCatalog.Page.ESSENTIALS.routeId();
    private boolean dirty;

    private CausticaMenuUsage() {
        load();
    }

    synchronized void record(String id, String label, SettingsCatalog.Page page) {
        String trimmed = label == null ? "" : label.trim();
        if (trimmed.isEmpty()) return;
        String stableId = id == null || id.isBlank() ? normalize(trimmed) : id;
        Entry entry = entries.computeIfAbsent(stableId, ignored -> new Entry());
        entry.label = trimmed;
        entry.category = page == null ? "" : page.routeId();
        entry.count++;
        entry.lastUsedMillis = System.currentTimeMillis();
        dirty = true;
    }

    synchronized String lastCategory() {
        return lastCategory;
    }

    synchronized double scrollPosition(String category) {
        return scrollPositions.getOrDefault(category, 0.0);
    }

    synchronized double scrollPosition(SettingsCatalog.Page page) {
        Double canonical = scrollPositions.get(page.routeId());
        if (canonical != null) return canonical;
        return scrollPositions.entrySet().stream()
                .filter(entry -> page.recognizes(entry.getKey()))
                .mapToDouble(Map.Entry::getValue)
                .findFirst().orElse(0.0);
    }

    synchronized void setMenuPosition(String category, double scrollAmount) {
        if (category == null || category.isBlank() || !Double.isFinite(scrollAmount)) return;
        double normalizedScroll = Math.max(0.0, scrollAmount);
        Double previous = scrollPositions.put(category, normalizedScroll);
        boolean categoryChanged = !category.equals(lastCategory);
        lastCategory = category;
        if (categoryChanged || previous == null || Math.abs(previous - normalizedScroll) > 0.5) dirty = true;
    }

    synchronized List<Item> recent(int limit) {
        return entries.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Entry>>comparingLong(value -> value.getValue().lastUsedMillis)
                        .reversed())
                .limit(Math.max(0, limit))
                .map(value -> new Item(value.getKey(), value.getValue().label, value.getValue().category))
                .toList();
    }

    synchronized List<Item> frequent(int limit, List<Item> excluded) {
        Set<String> excludedIds = new HashSet<>();
        excluded.forEach(item -> excludedIds.add(item.id()));
        long now = System.currentTimeMillis();
        return entries.entrySet().stream()
                .filter(value -> !excludedIds.contains(value.getKey()))
                .sorted(Comparator.<Map.Entry<String, Entry>>comparingDouble(
                                value -> score(value.getValue(), now)).reversed()
                        .thenComparing(value -> value.getValue().label, String.CASE_INSENSITIVE_ORDER))
                .limit(Math.max(0, limit))
                .map(value -> new Item(value.getKey(), value.getValue().label, value.getValue().category))
                .toList();
    }

    synchronized void save() {
        if (!dirty) return;
        try {
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(
                    new Snapshot(entries, lastCategory, scrollPositions), SNAPSHOT_TYPE));
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnavailable) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException ignored) {
            // Menu history is optional personalization; renderer settings must remain unaffected.
        }
    }

    private void load() {
        if (!Files.isRegularFile(path)) return;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(path));
            JsonObject object = root.isJsonObject() ? root.getAsJsonObject() : null;
            if (object != null && object.has("entries")) {
                Snapshot loaded = GSON.fromJson(root, SNAPSHOT_TYPE);
                if (loaded != null && loaded.entries() != null) loadEntries(loaded.entries());
                if (loaded != null && loaded.lastCategory() != null) lastCategory = loaded.lastCategory();
                if (loaded != null && loaded.scrollPositions() != null) {
                    loaded.scrollPositions().forEach((category, scroll) -> {
                        if (category != null && scroll != null && Double.isFinite(scroll)) {
                            scrollPositions.put(category, Math.max(0.0, scroll));
                        }
                    });
                }
            } else {
                // Backward-compatible migration from the original label -> usage-entry map.
                Map<String, Entry> loaded = GSON.fromJson(root, ENTRY_MAP_TYPE);
                if (loaded != null) loadEntries(loaded);
            }
        } catch (RuntimeException | IOException ignored) {
            entries.clear();
            scrollPositions.clear();
            lastCategory = SettingsCatalog.Page.ESSENTIALS.routeId();
        }
    }

    private void loadEntries(Map<String, Entry> loaded) {
        loaded.forEach((id, entry) -> {
            if (id == null || id.isBlank() || entry == null || entry.label == null
                    || entry.label.isBlank() || entry.category == null || entry.count < 0
                    || entry.lastUsedMillis < 0) {
                return;
            }
            entries.put(id, entry);
        });
    }

    private static double score(Entry entry, long now) {
        double ageDays = Math.max(0.0, (now - entry.lastUsedMillis) / (double) DAY_MILLIS);
        return entry.count * Math.pow(0.5, ageDays / FREQUENCY_HALF_LIFE_DAYS);
    }

    static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    record Item(String id, String label, String category) {
    }

    private record Snapshot(Map<String, Entry> entries, String lastCategory,
                            Map<String, Double> scrollPositions) {
    }

    private static final class Entry {
        String label = "";
        String category = "";
        long count;
        long lastUsedMillis;
    }
}
