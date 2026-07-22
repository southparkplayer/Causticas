package dev.comfyfluffy.caustica.client.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class SettingsCatalogTest {
    @Test
    void controlIdsAreUnique() {
        Set<String> ids = new HashSet<>();
        for (SettingsCatalog.Control control : SettingsCatalog.Control.values()) {
            assertTrue(ids.add(control.id()), () -> "Duplicate settings control id: " + control.id());
        }
    }

    @Test
    void oldCategoryRoutesRemainValid() {
        assertEquals(SettingsCatalog.Page.ESSENTIALS, SettingsCatalog.Page.parse("OVERVIEW"));
        assertEquals(SettingsCatalog.Page.DISPLAY_HDR, SettingsCatalog.Page.parse("OUTPUT"));
        assertEquals(SettingsCatalog.Page.FIRST_PERSON, SettingsCatalog.Page.parse("VIEW"));
    }

    @Test
    void essentialsContainMandatoryLandingControls() {
        Set<SettingsCatalog.Control> essentials = new HashSet<>(SettingsCatalog.essentials());
        assertTrue(essentials.containsAll(Set.of(SettingsCatalog.Control.RENDERER_ENABLED,
                SettingsCatalog.Control.RECONSTRUCTION_BACKEND, SettingsCatalog.Control.DLSS_RR_ENABLED,
                SettingsCatalog.Control.DLSS_QUALITY, SettingsCatalog.Control.DLSS_PRESET,
                SettingsCatalog.Control.DLSS_INPUT_RATIO, SettingsCatalog.Control.HDR_ENABLED,
                SettingsCatalog.Control.HDR_TONEMAPPER, SettingsCatalog.Control.HDR_PAPER_WHITE,
                SettingsCatalog.Control.HDR_PEAK, SettingsCatalog.Control.HDR_UI_BRIGHTNESS,
                SettingsCatalog.Control.FG_MODE, SettingsCatalog.Control.FG_MULTIPLIER,
                SettingsCatalog.Control.FG_REFLEX, SettingsCatalog.Control.FG_VSYNC,
                SettingsCatalog.Control.EXPOSURE_MODE, SettingsCatalog.Control.SAMPLES_PER_PIXEL,
                SettingsCatalog.Control.MAX_BOUNCES)));
    }

    @Test
    void internalControlsNeverAppearOnEssentials() {
        assertFalse(SettingsCatalog.essentials().stream().anyMatch(control ->
                control.tier() == SettingsCatalog.Tier.INTERNAL
                        || control.tier() == SettingsCatalog.Tier.COMPATIBILITY));
    }
}
