package dev.comfyfluffy.caustica;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.CommentedConfig;
import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;

final class CausticaConfigOverridePersistenceTest {
    @Test
    void booleanOverrideNeverBecomesConfiguredToml() {
        CausticaConfig.BooleanSetting setting = CausticaConfig.Rt.DlssRr.ENABLED;
        Boolean original = setting.configuredValue();
        String property = System.getProperty(setting.key());
        try {
            setting.set(true);
            System.setProperty(setting.key(), "false");
            setting.reloadFromSystemProperties();
            assertTrue(setting.configuredValue());
            assertFalse(setting.value());
            assertOverrideMetadata(setting);
            assertEquals(true, serialized(setting));
        } finally {
            restore(setting, original, property);
        }
    }

    @Test
    void integerOverrideNeverBecomesConfiguredToml() {
        CausticaConfig.IntSetting setting = CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT;
        Integer original = setting.configuredValue();
        String property = System.getProperty(setting.key());
        try {
            setting.set(5);
            System.setProperty(setting.key(), "1");
            setting.reloadFromSystemProperties();
            assertEquals(5, setting.configuredValue());
            assertEquals(1, setting.value());
            assertOverrideMetadata(setting);
            assertEquals(5, ((Number) serialized(setting)).intValue());
        } finally {
            restore(setting, original, property);
        }
    }

    @Test
    void floatOverrideNeverBecomesConfiguredToml() {
        CausticaConfig.FloatSetting setting = CausticaConfig.Rt.Exposure.MANUAL_EXPOSURE_EV;
        Float original = setting.configuredValue();
        String property = System.getProperty(setting.key());
        try {
            setting.set(1.25f);
            System.setProperty(setting.key(), "-2.5");
            setting.reloadFromSystemProperties();
            assertEquals(1.25f, setting.configuredValue());
            assertEquals(-2.5f, setting.value());
            assertOverrideMetadata(setting);
            assertEquals(1.25, ((Number) serialized(setting)).doubleValue(), 0.0001);
        } finally {
            restore(setting, original, property);
        }
    }

    @Test
    void stringOverrideNeverBecomesConfiguredToml() {
        CausticaConfig.StringSetting setting = CausticaConfig.Rt.Fg.MODE;
        String original = setting.configuredValue();
        String property = System.getProperty(setting.key());
        try {
            setting.set("fixed");
            System.setProperty(setting.key(), "off");
            setting.reloadFromSystemProperties();
            assertEquals("fixed", setting.configuredValue());
            assertEquals("off", setting.get());
            assertOverrideMetadata(setting);
            assertEquals("fixed", serialized(setting));
        } finally {
            restore(setting, original, property);
        }
    }

    @Test
    void optionalStringOverrideNeverBecomesConfiguredToml() throws Exception {
        Constructor<CausticaConfig.OptionalStringSetting> constructor =
                CausticaConfig.OptionalStringSetting.class.getDeclaredConstructor(String.class, String.class);
        constructor.setAccessible(true);
        String key = "caustica.test.optional.override";
        CausticaConfig.OptionalStringSetting setting = constructor.newInstance(key, "test.optional-override");
        try {
            setting.set("configured");
            System.setProperty(key, "effective");
            setting.reloadFromSystemProperties();
            assertEquals("configured", setting.configuredValue());
            assertEquals("effective", setting.get());
            assertOverrideMetadata(setting);
            assertEquals("configured", serialized(setting));
            System.clearProperty(key);
            setting.reloadFromSystemProperties();
            assertFalse(setting.isOverridden());
            assertNull(setting.overrideSource());
            assertEquals("configured", setting.get());
        } finally {
            System.clearProperty(key);
            setting.reloadFromSystemProperties();
        }
    }

    private static Object serialized(CausticaConfig.RuntimeSetting<?> setting) {
        CommentedConfig config = CommentedConfig.inMemory();
        setting.writeToFile(config);
        return config.get(setting.tomlPath());
    }

    private static void assertOverrideMetadata(CausticaConfig.RuntimeSetting<?> setting) {
        assertTrue(setting.isOverridden());
        assertEquals("system-property:" + setting.key(), setting.overrideSource());
        assertEquals(System.getProperty(setting.key()), setting.overrideRawValue());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void restore(CausticaConfig.RuntimeSetting setting, Object configured, String property) {
        setting.set(configured);
        if (property == null) {
            System.clearProperty(setting.key());
        } else {
            System.setProperty(setting.key(), property);
        }
        setting.reloadFromSystemProperties();
    }
}
