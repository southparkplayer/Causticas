package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ResponsiveOptionsMenuContractTest {
    @Test
    void workstationIsCompactSearchFirstAndPersonalized() throws IOException {
        String entry = source("src/main/java/dev/comfyfluffy/caustica/client/CausticaOptionsScreen.java");
        String workstation = source("src/main/java/dev/comfyfluffy/caustica/client/CausticaSettingsScreen.java");
        String usage = source("src/main/java/dev/comfyfluffy/caustica/client/CausticaMenuUsage.java");

        assertTrue(entry.contains("extends CausticaSettingsScreen"));
        assertTrue(workstation.contains("OVERVIEW(\"Overview\")"));
        assertTrue(workstation.contains("MAX_CONTENT_WIDTH = 1600"));
        assertTrue(workstation.contains("MAX_GRID_COLUMNS = 6"));
        assertTrue(workstation.contains("new EditBox"));
        assertTrue(workstation.contains("setResponder(this::searchChanged)"));
        assertTrue(workstation.contains("setInitialFocus(searchBox)"));
        assertTrue(workstation.contains("addSearchResults()"));
        assertTrue(workstation.contains("matchesSearch"));
        assertTrue(workstation.contains("addQuickLinks"));
        assertTrue(workstation.contains("recent(itemLimit)"));
        assertTrue(workstation.contains("frequent(itemLimit, recent)"));
        assertTrue(usage.contains("FREQUENCY_HALF_LIFE_DAYS = 30.0"));
        assertTrue(usage.contains("caustica-menu-usage.json"));
    }

    @Test
    void everyCausticaKeyIsInOneCategoryAndMenuUsesBacktick() throws IOException {
        String mappings = source("src/main/java/dev/comfyfluffy/caustica/client/CausticaKeyMappings.java");
        String optionsMixin = source("src/main/java/dev/comfyfluffy/caustica/mixin/OptionsMixin.java");
        String client = source("src/main/java/dev/comfyfluffy/caustica/client/CausticaClient.java");

        assertTrue(mappings.contains("KeyMapping.Category.register"));
        assertTrue(mappings.contains("GLFW.GLFW_KEY_GRAVE_ACCENT"));
        assertTrue(mappings.contains("OPEN_MENU, UltraScreenshot.KEY, OfflineGroundTruth.KEY"));
        assertTrue(optionsMixin.contains("CausticaKeyMappings.all()"));
        assertTrue(optionsMixin.contains("System.arraycopy"));
        assertTrue(client.contains("CausticaKeyMappings.OPEN_MENU.consumeClick()"));
        assertTrue(client.contains("new CausticaOptionsScreen(null, client.options)"));
    }

    private static String source(String relative) throws IOException {
        return Files.readString(Path.of(relative)).replace("\r\n", "\n");
    }
}
