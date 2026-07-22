package dev.comfyfluffy.caustica.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.comfyfluffy.caustica.client.SettingsUiState.PageBookmark;
import dev.comfyfluffy.caustica.client.settings.SettingsCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SettingsUiStateTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void missingStateStartsAtEssentialsWithoutCreatingAFile() {
        Path path = temporaryDirectory.resolve("caustica-settings-ui.json");

        SettingsUiState state = SettingsUiState.load(path);

        assertEquals(SettingsUiState.SCHEMA_VERSION, state.schemaVersion());
        assertEquals(SettingsCatalog.Page.ESSENTIALS, state.lastPage());
        assertEquals("essentials", state.lastPageId());
        assertEquals(SettingsCatalog.Page.DISPLAY_HDR,
                SettingsCatalog.section("output.displayFormat").page());
        assertTrue(state.pageBookmarks().isEmpty());
        assertTrue(state.groupExpansion().isEmpty());
        assertTrue(state.sectionExpansion().isEmpty());
        assertFalse(state.isDirty());
        assertFalse(Files.exists(path));
    }

    @Test
    void roundTripUsesCanonicalRoutesAndPreservesExplicitExpansionOverrides() throws Exception {
        Path path = temporaryDirectory.resolve("state").resolve("ui.json");
        SettingsUiState state = SettingsUiState.load(path);
        state.setLastPageId("OUTPUT");
        state.setPageBookmark(SettingsCatalog.Page.DISPLAY_HDR,
                new PageBookmark("display.hdr.enabled", 1.4, -0.2));
        state.setGroupExpanded("group.displayPerformance", false);
        state.setSectionExpanded("output.displayFormat", true);

        assertTrue(state.save());
        assertFalse(state.isDirty());
        String json = Files.readString(path);
        assertTrue(json.contains("\"schemaVersion\": 1"));
        assertTrue(json.contains("\"lastPageId\": \"displayHdr\""));
        assertFalse(json.contains("OUTPUT"));

        SettingsUiState loaded = SettingsUiState.load(path);
        assertEquals(SettingsCatalog.Page.DISPLAY_HDR, loaded.lastPage());
        assertEquals(new PageBookmark("display.hdr.enabled", 1.0, 0.0),
                loaded.pageBookmark(SettingsCatalog.Page.DISPLAY_HDR));
        assertFalse(loaded.groupExpanded("group.displayPerformance", true));
        assertTrue(loaded.sectionExpanded("output.displayFormat", false));
        assertTrue(loaded.groupExpanded("group.world", true));
        assertFalse(loaded.isDirty());
        try (var files = Files.list(path.getParent())) {
            assertFalse(files.anyMatch(candidate -> candidate.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void loadCanonicalizesAliasBookmarkKeysAndClampsFractions() throws Exception {
        Path path = temporaryDirectory.resolve("ui.json");
        Files.writeString(path, """
                {
                  "schemaVersion": 1,
                  "lastPageId": "VIEW",
                  "pageBookmarks": {
                    "OUTPUT": {
                      "anchorId": "  display.hdr.enabled  ",
                      "anchorViewportFraction": -4.0,
                      "fallbackPageProgress": 7.0
                    }
                  },
                  "groupExpansion": {},
                  "sectionExpansion": {}
                }
                """);

        SettingsUiState state = SettingsUiState.load(path);

        assertEquals(SettingsCatalog.Page.FIRST_PERSON, state.lastPage());
        assertEquals(new PageBookmark("display.hdr.enabled", 0.0, 1.0),
                state.pageBookmark(SettingsCatalog.Page.DISPLAY_HDR));
        assertNull(state.pageBookmark(SettingsCatalog.Page.ESSENTIALS));
        assertTrue(state.isDirty());
        assertTrue(state.save());
        String normalized = Files.readString(path);
        assertTrue(normalized.contains("\"displayHdr\""));
        assertFalse(normalized.contains("OUTPUT"));
        assertFalse(normalized.contains("VIEW"));
    }

    @Test
    void malformedStateIsBackedUpAndReplacedOnlyWhenFlushed() throws Exception {
        Path path = temporaryDirectory.resolve("ui.json");
        String malformed = "{ not-json";
        Files.writeString(path, malformed);

        SettingsUiState state = SettingsUiState.load(path);

        assertEquals(SettingsCatalog.Page.ESSENTIALS, state.lastPage());
        assertTrue(state.isDirty());
        assertEquals(malformed, Files.readString(path));
        List<Path> backups;
        try (var files = Files.list(temporaryDirectory)) {
            backups = files.filter(candidate -> candidate.getFileName().toString()
                            .startsWith("ui.json.broken-"))
                    .toList();
        }
        assertEquals(1, backups.size());
        assertEquals(malformed, Files.readString(backups.getFirst()));

        assertTrue(state.save());
        assertTrue(Files.readString(path).contains("\"schemaVersion\": 1"));
        assertFalse(state.isDirty());
    }

    @Test
    void bookmarkGeometryUsesSemanticAnchorThenNormalizedFallback() {
        PageBookmark bookmark = PageBookmark.capture("section.sky", 175.0, 100.0,
                300.0, 450.0, 900.0);

        assertEquals(0.25, bookmark.anchorViewportFraction());
        assertEquals(0.5, bookmark.fallbackPageProgress());
        assertEquals(75.0, bookmark.restoredScroll(250.0, 100.0, 300.0, 900.0));
        assertEquals(450.0, bookmark.fallbackScroll(900.0));
        assertEquals(0.0, bookmark.restoredScroll(50.0, 100.0, 300.0, 900.0));
        assertEquals(900.0, bookmark.restoredScroll(2_000.0, 100.0, 300.0, 900.0));
        assertEquals(450.0, bookmark.restoredScroll(Double.NaN, 100.0, 300.0, 900.0));
    }

    @Test
    void serializedOutputIsStableAcrossMutationOrder() throws Exception {
        Path firstPath = temporaryDirectory.resolve("first.json");
        SettingsUiState first = SettingsUiState.load(firstPath);
        first.setSectionExpanded("section.z", false);
        first.setSectionExpanded("section.a", true);
        first.setGroupExpanded("group.z", true);
        first.setGroupExpanded("group.a", false);
        assertTrue(first.save());

        Path secondPath = temporaryDirectory.resolve("second.json");
        SettingsUiState second = SettingsUiState.load(secondPath);
        second.setGroupExpanded("group.a", false);
        second.setGroupExpanded("group.z", true);
        second.setSectionExpanded("section.a", true);
        second.setSectionExpanded("section.z", false);
        assertTrue(second.save());

        assertEquals(Files.readString(firstPath), Files.readString(secondPath));
    }
}
