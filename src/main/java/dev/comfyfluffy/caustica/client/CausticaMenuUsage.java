package dev.comfyfluffy.caustica.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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

/** Local-only, low-volume menu history used for Recent and Frequent quick links. */
final class CausticaMenuUsage {
    static final CausticaMenuUsage INSTANCE = new CausticaMenuUsage();

    private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;
    private static final double FREQUENCY_HALF_LIFE_DAYS = 30.0;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type ENTRY_MAP_TYPE = new TypeToken<Map<String, Entry>>() { }.getType();

    private final Path path = FabricLoader.getInstance().getConfigDir().resolve("caustica-menu-usage.json");
    private final Map<String, Entry> entries = new HashMap<>();
    private boolean dirty;

    private CausticaMenuUsage() {
        load();
    }

    synchronized void record(String label) {
        String trimmed = label == null ? "" : label.trim();
        if (trimmed.isEmpty()) return;
        String id = normalize(trimmed);
        Entry entry = entries.computeIfAbsent(id, ignored -> new Entry());
        entry.label = trimmed;
        entry.count++;
        entry.lastUsedMillis = System.currentTimeMillis();
        dirty = true;
    }

    synchronized List<Item> recent(int limit) {
        return entries.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Entry>>comparingLong(value -> value.getValue().lastUsedMillis)
                        .reversed())
                .limit(Math.max(0, limit))
                .map(value -> new Item(value.getKey(), value.getValue().label))
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
                .map(value -> new Item(value.getKey(), value.getValue().label))
                .toList();
    }

    synchronized void save() {
        if (!dirty) return;
        try {
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(entries, ENTRY_MAP_TYPE));
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
            Map<String, Entry> loaded = GSON.fromJson(Files.readString(path), ENTRY_MAP_TYPE);
            if (loaded != null) entries.putAll(loaded);
        } catch (RuntimeException | IOException ignored) {
            entries.clear();
        }
    }

    private static double score(Entry entry, long now) {
        double ageDays = Math.max(0.0, (now - entry.lastUsedMillis) / (double) DAY_MILLIS);
        return entry.count * Math.pow(0.5, ageDays / FREQUENCY_HALF_LIFE_DAYS);
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    record Item(String id, String label) {
    }

    private static final class Entry {
        String label = "";
        long count;
        long lastUsedMillis;
    }
}
