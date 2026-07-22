package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ResponsiveOptionsMenuContractTest {
    @Test
    void workstationUsesBoundedResponsiveGeometryAndDeterministicState() throws IOException {
        String entry = source("src/main/java/dev/comfyfluffy/caustica/client/CausticaOptionsScreen.java");
        String workstation = source("src/main/java/dev/comfyfluffy/caustica/client/CausticaSettingsScreen.java");
        String catalog = source("src/main/java/dev/comfyfluffy/caustica/client/settings/SettingsCatalog.java");
        String metrics = source("src/main/java/dev/comfyfluffy/caustica/client/ui/SettingsUiMetrics.java");

        assertTrue(entry.contains("extends CausticaSettingsScreen"));
        assertTrue(workstation.contains("SettingsUiMetrics.calculate(width, height"));
        assertTrue(workstation.contains("ReserveStrategy.RIGHT"));
        assertTrue(workstation.contains("new SectionColumnsLayout"));
        assertTrue(workstation.contains("SettingsUiState.PageBookmark"));
        assertTrue(workstation.contains("setNavigationOpen(true)"));
        assertTrue(workstation.contains("insideBody"));
        assertTrue(workstation.contains("new EditBox"));
        assertTrue(workstation.contains("setResponder(this::searchChanged)"));
        assertTrue(workstation.contains("setInitialFocus(targetWidget != null ? targetWidget : searchBox)"));
        assertTrue(workstation.contains("addSearchResults()"));
        assertTrue(workstation.contains("new CollapsibleLayout(sectionWidth"));
        assertTrue(workstation.contains("new CollapsibleLayout.TreeHeader(navigationContentWidth"));
        assertTrue(workstation.contains("restoreBookmark"));
        assertTrue(workstation.contains("revealControl(Control control)"));
        assertTrue(workstation.contains("SettingsRevealPlanner.planReveal"));
        assertFalse(workstation.contains("prepareToneControl"));
        assertFalse(workstation.contains("RtResolutionScale.ensurePresetSelection"));
        assertTrue(workstation.contains("migrateLegacyExpansionState"));
        assertTrue(workstation.contains("uiState.save()"));
        assertTrue(workstation.contains("addEssentialsGrid(controls)"));
        assertTrue(workstation.contains("case WIDE -> 3"));
        assertTrue(workstation.contains("essentialsColumns(contentWidth"));
        assertTrue(workstation.contains("caustica.options.navigation.pages"));
        assertTrue(workstation.contains("controls.addAll(hdrBrightnessControls())"));
        assertFalse(workstation.contains("dlssInputRatioControl()"));
        assertFalse(workstation.contains("RtResolutionScale.CUSTOM_QUALITY"));
        assertFalse(workstation.contains("|| category.group() == group"));
        assertFalse(workstation.contains("setGroupExpanded(groupStateId(target.group()), true)"));
        assertFalse(workstation.contains("SettingsCatalog.byLocalizedLabel"));
        assertTrue(metrics.contains("enum Mode"));
        assertTrue(metrics.contains("WIDE_MIN_WIDTH = 1080"));
        assertTrue(metrics.contains("STANDARD_MIN_WIDTH = 760"));
        assertTrue(metrics.contains("SCROLLBAR_RESERVE"));
        assertTrue(catalog.contains("ESSENTIALS(null, \"essentials\""));
        assertTrue(catalog.contains("DISPLAY_HDR(Group.DISPLAY_PERFORMANCE"));
        assertTrue(catalog.contains("SHARC(Group.ADVANCED"));
        assertTrue(catalog.contains("essentialsProjection()"));
        assertTrue(catalog.contains("allControls()"));
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
