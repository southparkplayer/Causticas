package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
        assertTrue(workstation.contains("OVERVIEW(CategoryGroup.ESSENTIALS, \"overview\")"));
        assertTrue(workstation.contains("IMAGE(\"image\")"));
        assertTrue(workstation.contains("WORLD(\"world\")"));
        assertTrue(workstation.contains("ADVANCED(\"advanced\")"));
        assertTrue(workstation.contains("MAX_CONTENT_WIDTH = 1400"));
        assertTrue(workstation.contains("TARGET_CELL_WIDTH = 280"));
        assertTrue(workstation.contains("MAX_GRID_COLUMNS = 3"));
        assertTrue(workstation.contains("CONTROL_HEIGHT = 34"));
        assertTrue(workstation.contains("computeRailWidth()"));
        assertTrue(workstation.contains("widest + 32"));
        assertTrue(workstation.contains("columnsFor(controls)"));
        assertTrue(workstation.contains("font.width(control.getMessage())"));
        assertTrue(workstation.contains("new EditBox"));
        assertTrue(workstation.contains("setResponder(this::searchChanged)"));
        assertTrue(workstation.contains("setInitialFocus(targetWidget != null ? targetWidget : searchBox)"));
        assertTrue(workstation.contains("addSearchResults()"));
        assertTrue(workstation.contains("matchesSearch"));
        assertTrue(workstation.contains("addQuickLinks"));
        assertTrue(workstation.contains("recent(itemLimit)"));
        assertTrue(workstation.contains("frequent(itemLimit, recent)"));
        assertTrue(workstation.contains("Math.clamp((available - 32) / 36, 0, 8)"));
        assertTrue(workstation.contains("selectedCategoryButton"));
        assertTrue(workstation.contains("addBundle(\"exposure.intent\""));
        assertTrue(workstation.contains("addBundle(\"exposure.metering\""));
        assertTrue(workstation.contains("addBundle(\"output.frameGeneration\""));
        assertTrue(workstation.contains("List.of(\"synchronized\", \"parallel\")"));
        assertTrue(workstation.contains("CausticaConfig.Rt.Fg.QUEUE_PARALLELISM::set"));
        assertTrue(workstation.contains("addBundle(\"sharc.transport\""));
        assertTrue(workstation.contains("new CollapsibleLayout(contentWidth"));
        assertTrue(workstation.contains("new CollapsibleLayout.TreeHeader(railWidth"));
        assertTrue(workstation.contains("group."));
        assertTrue(workstation.contains("CausticaTreeState.INSTANCE.save()"));
        String treeState = source("src/main/java/dev/comfyfluffy/caustica/client/CausticaTreeState.java");
        assertTrue(treeState.contains("caustica-menu-tree.json"));
        assertTrue(treeState.contains("setCollapsed"));
        assertTrue(workstation.contains("lineLeft"));
        assertTrue(workstation.contains("revealControl(item)"));
        assertTrue(workstation.contains("pendingTargetId"));
        assertTrue(workstation.contains("TARGET_FLASH_MILLIS"));
        assertTrue(workstation.contains("bodyScrollArea.setScrollAmount"));
        assertTrue(workstation.contains("usageCategories"));
        assertTrue(workstation.contains("addGrid(controls.subList(0, 4))"));
        assertTrue(workstation.contains("addGrid(controls.subList(4, 6))"));
        assertTrue(workstation.contains("addGrid(controls.subList(6, 9))"));
        assertFalse(workstation.contains("ordered(rendering,"));
        assertFalse(workstation.contains("throw new IllegalArgumentException(\"Incomplete menu order\")"));
        assertTrue(usage.contains("FREQUENCY_HALF_LIFE_DAYS = 30.0"));
        assertTrue(usage.contains("caustica-menu-usage.json"));
        assertTrue(usage.contains("lastCategory"));
        assertTrue(usage.contains("scrollPositions"));
        assertTrue(usage.contains("setMenuPosition"));
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
