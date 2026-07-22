package dev.comfyfluffy.caustica.client.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class SettingsCatalogTest {
    @Test
    void controlIdsAndLabelKeysAreUnique() {
        Set<String> ids = new HashSet<>();
        Set<String> labelKeys = new HashSet<>();
        for (SettingsCatalog.ControlDescriptor control : SettingsCatalog.allControls()) {
            assertTrue(ids.add(control.id()), () -> "Duplicate settings control id: " + control.id());
            assertTrue(labelKeys.add(control.labelKey()),
                    () -> "Duplicate settings control label key: " + control.labelKey());
            assertSame(control, SettingsCatalog.byId(control.id()));
            assertSame(control, SettingsCatalog.byLabelKey(control.labelKey()));
        }
        assertEquals(205, ids.size());
        assertEquals(205, SettingsCatalog.Control.values().length);
    }

    @Test
    void canonicalGroupRoutesAndAllLegacyPageAliasesRemainValid() {
        assertEquals("displayPerformance", SettingsCatalog.Group.DISPLAY_PERFORMANCE.routeId());
        assertEquals("world", SettingsCatalog.Group.WORLD.routeId());
        assertEquals("advanced", SettingsCatalog.Group.ADVANCED.routeId());

        Map<String, SettingsCatalog.Page> aliases = Map.of(
                "OVERVIEW", SettingsCatalog.Page.ESSENTIALS,
                "OUTPUT", SettingsCatalog.Page.DISPLAY_HDR,
                "VIEW", SettingsCatalog.Page.FIRST_PERSON,
                "EXPOSURE", SettingsCatalog.Page.EXPOSURE_TONEMAP,
                "SKY", SettingsCatalog.Page.SKY_ATMOSPHERE,
                "GEOMETRY", SettingsCatalog.Page.GEOMETRY_SCENE);
        aliases.forEach((alias, page) -> {
            assertEquals(page, SettingsCatalog.Page.parse(alias));
            assertTrue(page.aliases().contains(alias));
        });
    }

    @Test
    void essentialsProjectionIncludesConditionalHdrControlsInOrder() {
        assertEquals(List.of(
                "renderer.enabled",
                "rt.spp",
                "rt.maxBounces",
                "reconstruction.backend",
                "reconstruction.dlss.enabled",
                "reconstruction.dlss.quality",
                "display.hdr.enabled",
                "display.hdr.tonemapper",
                "display.hdr.paperWhite",
                "display.hdr.peak",
                "display.hdr.uiBrightness",
                "frameGeneration.mode",
                "frameGeneration.multiplier",
                "lighting.torchIntensity",
                "scene.entities"),
                SettingsCatalog.essentialsProjection().stream()
                        .map(SettingsCatalog.ControlDescriptor::id).toList());
    }

    @Test
    void internalControlsNeverAppearInEssentialsProjection() {
        assertFalse(SettingsCatalog.essentialsProjection().stream().anyMatch(control ->
                control.tier() == SettingsCatalog.Tier.INTERNAL
                        || control.tier() == SettingsCatalog.Tier.COMPATIBILITY));
    }

    @Test
    void everyControlReferencesACanonicalSectionOnItsOwnPage() {
        assertEquals(38, SettingsCatalog.allSections().size());
        assertEquals(38, SettingsCatalog.allSections().stream().map(SettingsCatalog.Section::id)
                .collect(Collectors.toSet()).size());

        Set<String> defaultCollapsed = SettingsCatalog.allSections().stream()
                .filter(section -> !section.defaultExpanded())
                .map(SettingsCatalog.Section::id)
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                "reconstruction.reblurAdvanced",
                "reconstruction.relaxAdvanced",
                "sky.atmosphere",
                "sky.celestial",
                "sky.stars",
                "sky.airglow",
                "sharc.foundation",
                "sharc.cadence",
                "sharc.transport",
                "sharc.telemetry",
                "exposure.activeCurve"), defaultCollapsed);

        for (SettingsCatalog.Section section : SettingsCatalog.allSections()) {
            assertNotNull(section.page(), () -> "Missing page owner for " + section.id());
            assertEquals("caustica.options.bundle." + section.id(), section.labelKey());
            assertEquals(section.labelKey() + ".description", section.descriptionKey());
            assertSame(section, SettingsCatalog.sectionById(section.id()));
            assertSame(section, SettingsCatalog.section(section.page(), section.id()));
        }
        assertEquals(SettingsCatalog.Page.DISPLAY_HDR,
                SettingsCatalog.section("output.displayFormat").page());
        assertNotNull(SettingsCatalog.section(SettingsCatalog.Page.DISPLAY_HDR, "output.displayFormat"));
        for (SettingsCatalog.ControlDescriptor control : SettingsCatalog.allControls()) {
            SettingsCatalog.Section section = control.sectionDescriptor();
            assertNotNull(section, () -> "Missing section for " + control.id());
            assertEquals(control.page(), section.page(), () -> "Wrong section page for " + control.id());
            assertEquals(section.labelKey(), control.sectionLabelKey());
        }
    }

    @Test
    void catalogCoversAllFixedPlacementsAndEveryDynamicToneLabel() {
        List<SettingsCatalog.ControlDescriptor> dynamic = SettingsCatalog.allControls().stream()
                .filter(control -> control.id().startsWith("tone."))
                .toList();
        assertEquals(39, dynamic.size());
        assertEquals(166, SettingsCatalog.allControls().size() - dynamic.size());
        // Five output controls are deliberately placed on both Display/HDR and Exposure.
        assertEquals(171, SettingsCatalog.allControls().size() - dynamic.size() + 5);

        Set<String> dynamicLabels = dynamic.stream().map(SettingsCatalog.ControlDescriptor::labelKey)
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                "caustica.options.rt.sdrAgxContrast",
                "caustica.options.rt.sdrAgxSaturation",
                "caustica.options.rt.sdrPbrStartCompression",
                "caustica.options.rt.sdrPbrDesaturation",
                "caustica.options.rt.sdrReinhardWhitePoint",
                "caustica.options.rt.sdrAcesExposure",
                "caustica.options.rt.sdrLottesContrast",
                "caustica.options.rt.sdrLottesShoulder",
                "caustica.options.rt.sdrLottesHdrMax",
                "caustica.options.rt.sdrLottesMidIn",
                "caustica.options.rt.sdrLottesMidOut",
                "caustica.options.rt.sdrFrostbiteLinearEnd",
                "caustica.options.rt.sdrFrostbiteShoulderStrength",
                "caustica.options.rt.sdrUnchartedA",
                "caustica.options.rt.sdrUnchartedB",
                "caustica.options.rt.sdrUnchartedC",
                "caustica.options.rt.sdrUnchartedD",
                "caustica.options.rt.sdrUnchartedE",
                "caustica.options.rt.sdrUnchartedF",
                "caustica.options.rt.sdrUnchartedWhitePoint",
                "caustica.options.rt.sdrGtContrast",
                "caustica.options.rt.sdrGtLinearStart",
                "caustica.options.rt.sdrGtLinearLength",
                "caustica.options.rt.sdrGtBlackCurve",
                "caustica.options.rt.sdrGtBlackLift",
                "caustica.options.rt.sdrPsychoPeak",
                "caustica.options.rt.sdrPsychoV23Peak",
                "caustica.options.rt.psychoV23Compression",
                "caustica.options.rt.psychoV23GamutCompression",
                "caustica.options.rt.hdrPsychoHighlights",
                "caustica.options.rt.hdrPsychoShadows",
                "caustica.options.rt.hdrPsychoContrast",
                "caustica.options.rt.hdrPsychoPurity",
                "caustica.options.rt.hdrPsychoHueRestore",
                "caustica.options.rt.hdrPsychoAdaptContrast",
                "caustica.options.rt.hdrPsychoConeExponent",
                "caustica.options.rt.hdrPsychoBleaching",
                "caustica.options.rt.hdrPsychoClipPoint",
                "caustica.options.rt.hdrPsychoWhiteCurve"), dynamicLabels);
    }

    @Test
    void broadPageCoverageMatchesTheAuditedCanonicalInventory() {
        Map<SettingsCatalog.Page, Long> expected = Map.ofEntries(
                Map.entry(SettingsCatalog.Page.DISPLAY_HDR, 6L),
                Map.entry(SettingsCatalog.Page.FRAME_GENERATION, 8L),
                Map.entry(SettingsCatalog.Page.RECONSTRUCTION, 8L),
                Map.entry(SettingsCatalog.Page.DENOISING, 46L),
                Map.entry(SettingsCatalog.Page.LIGHTING, 5L),
                Map.entry(SettingsCatalog.Page.SKY_ATMOSPHERE, 37L),
                Map.entry(SettingsCatalog.Page.GEOMETRY_SCENE, 8L),
                Map.entry(SettingsCatalog.Page.SHARC, 14L),
                Map.entry(SettingsCatalog.Page.MATERIALS, 8L),
                Map.entry(SettingsCatalog.Page.EXPOSURE_TONEMAP, 54L),
                Map.entry(SettingsCatalog.Page.FIRST_PERSON, 6L),
                Map.entry(SettingsCatalog.Page.DIAGNOSTICS, 5L));
        Map<SettingsCatalog.Page, Long> actual = SettingsCatalog.allControls().stream()
                .collect(Collectors.groupingBy(SettingsCatalog.ControlDescriptor::page,
                        Collectors.counting()));
        assertEquals(expected, actual);
        expected.forEach((page, count) -> assertEquals(count.intValue(), SettingsCatalog.forPage(page).size()));
    }

    @Test
    void aliasesAndMetadataParticipateInSideEffectFreeMatching() {
        assertTrue(SettingsCatalog.Control.RENDERER_ENABLED.matches("ray tracing"));
        assertTrue(SettingsCatalog.byId("tone.sdr.uncharted2.whitePoint").matches("uncharted white point"));
        assertTrue(SettingsCatalog.byLabelKey("caustica.options.rt.sky.aerosolAnisotropy")
                .matches("sky aerosol anisotropy"));

        Map<String, SettingsCatalog.ControlDescriptor> byId = SettingsCatalog.allControls().stream()
                .collect(Collectors.toMap(SettingsCatalog.ControlDescriptor::id, Function.identity()));
        assertEquals(SettingsCatalog.allControls().size(), byId.size());
    }
}
