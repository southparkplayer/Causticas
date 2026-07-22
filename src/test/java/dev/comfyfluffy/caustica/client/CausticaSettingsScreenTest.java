package dev.comfyfluffy.caustica.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.comfyfluffy.caustica.client.settings.SettingsCatalog.Group;
import java.util.List;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CausticaSettingsScreenTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void essentialsColumnsPreserveReadableControlWidths() {
        assertEquals(1, CausticaSettingsScreen.essentialsColumns(516, 4, 2));
        assertEquals(2, CausticaSettingsScreen.essentialsColumns(884, 4, 3));
        assertEquals(3, CausticaSettingsScreen.essentialsColumns(968, 4, 3));
    }

    @Test
    void orderedRetainsControlsOmittedFromThePreferredPermutation() {
        assertEquals(List.of("third", "first", "second", "fourth"),
                CausticaSettingsScreen.ordered(List.of("first", "second", "third", "fourth"), 2, 0));
    }

    @Test
    void orderedIgnoresInvalidAndDuplicateIndicesWithoutBreakingTheMenu() {
        assertEquals(List.of("second", "first", "third"),
                CausticaSettingsScreen.ordered(List.of("first", "second", "third"), 1, 1, -1, 99));
    }

    @Test
    void aCollapsedWorldGroupRemainsCollapsedEvenWhenItOwnsTheActivePage() {
        SettingsUiState state = SettingsUiState.load(temporaryDirectory.resolve("ui.json"));
        state.setGroupExpanded("group.world", false);

        assertFalse(CausticaSettingsScreen.groupExpanded(state, Group.WORLD, true));
    }
}
