package dev.comfyfluffy.caustica;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConfigReachabilityContractTest {
    private static final Set<String> COMPATIBILITY_ALIASES = Set.of(
            "DlssRr.HIGH_QUALITY_TRANSPARENCY",
            "OutputScale.PERCENT",
            "Fg.ENABLED");
    private static final Set<String> OWNER_MEDIATED_SETTINGS = Set.of(
            // Frame-generation policy deliberately funnels reads and writes through Fg's
            // requested/configuredMode/setMode helpers to keep the legacy ENABLED alias coherent.
            "Fg.MODE");

    @Test
    void registeredSettingsHaveUniqueRuntimeKeys() {
        CausticaConfig.ensureRegistered();
        var settings = CausticaConfig.settings();
        assertTrue(settings.size() > 100, "an incomplete class-initialization pass hid settings");
        assertEquals(settings.size(), settings.stream().map(CausticaConfig.RuntimeSetting::key).distinct().count(),
                "two independently persisted settings share a runtime key");
    }

    @Test
    void everySettingIsConsumedOrExplicitlyCompatibilityOnly() throws Exception {
        String production = Files.walk(Path.of("src/main/java"))
                .filter(Files::isRegularFile)
                .filter(path -> !path.endsWith("CausticaConfig.java"))
                .map(path -> {
                    try {
                        return Files.readString(path);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .reduce("", (left, right) -> left + '\n' + right);

        ArrayDeque<Class<?>> pending = new ArrayDeque<>();
        pending.add(CausticaConfig.Rt.class);
        Set<Object> seenSettings = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (!pending.isEmpty()) {
            Class<?> owner = pending.removeFirst();
            for (Class<?> nested : owner.getDeclaredClasses()) pending.addLast(nested);
            for (Field field : owner.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        || !CausticaConfig.RuntimeSetting.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Object setting = field.get(null);
                String id = owner.getSimpleName() + '.' + field.getName();
                if (!seenSettings.add(setting)) {
                    assertTrue(COMPATIBILITY_ALIASES.contains(id), id + " is an undocumented setting alias");
                    continue;
                }
                assertTrue(production.contains(id)
                                || OWNER_MEDIATED_SETTINGS.contains(id)
                                || COMPATIBILITY_ALIASES.contains(id),
                        id + " is persisted but has no production consumer or compatibility disposition");
            }
        }
    }
}
