package dev.comfyfluffy.caustica.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;

/** Persists presentation-only expansion state for the settings tree. */
final class CausticaTreeState {
    static final CausticaTreeState INSTANCE = new CausticaTreeState();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type STATE_TYPE = new TypeToken<Map<String, Boolean>>() { }.getType();

    private final Path path = FabricLoader.getInstance().getConfigDir().resolve("caustica-menu-tree.json");
    private final Map<String, Boolean> collapsed = new HashMap<>();
    private boolean dirty;

    private CausticaTreeState() {
        load();
    }

    synchronized boolean isCollapsed(String id) {
        return Boolean.TRUE.equals(collapsed.get(id));
    }

    boolean hasPersistedState() {
        return Files.isRegularFile(path);
    }

    synchronized void setCollapsed(String id, boolean value) {
        if (id == null || id.isBlank()) return;
        if (value) {
            if (!Boolean.TRUE.equals(collapsed.put(id, true))) dirty = true;
        } else if (collapsed.remove(id) != null) {
            dirty = true;
        }
    }

    synchronized void save() {
        if (!dirty) return;
        try {
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(collapsed, STATE_TYPE));
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnavailable) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException ignored) {
            // UI personalization is optional and must not affect renderer settings.
        }
    }

    private void load() {
        if (!Files.isRegularFile(path)) return;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(path));
            if (!root.isJsonObject()) return;
            Map<String, Boolean> loaded = GSON.fromJson(root, STATE_TYPE);
            if (loaded == null) return;
            loaded.forEach((id, value) -> {
                if (id != null && !id.isBlank() && Boolean.TRUE.equals(value)) collapsed.put(id, true);
            });
        } catch (RuntimeException | IOException ignored) {
            collapsed.clear();
        }
    }
}
