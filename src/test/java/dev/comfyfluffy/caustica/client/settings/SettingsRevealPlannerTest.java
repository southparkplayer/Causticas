package dev.comfyfluffy.caustica.client.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.client.settings.SettingsCatalog.ControlDescriptor;
import org.junit.jupiter.api.Test;

final class SettingsRevealPlannerTest {
    private static final SettingsRevealPlanner.VisibilityContext SDR_PSYCHOV =
            new SettingsRevealPlanner.VisibilityContext(false,
                    CausticaConfig.Rt.Sdr.TONEMAP_PSYCHOV, CausticaConfig.Rt.Hdr.TONEMAP_EETF);

    @Test
    void planningAVisibleControlIsPureAndKeepsCanonicalIdentity() {
        ControlDescriptor control = SettingsCatalog.Control.DLSS_QUALITY;

        SettingsRevealPlanner.RevealPlan plan = SettingsRevealPlanner.planReveal(control, SDR_PSYCHOV);

        assertTrue(plan.available());
        assertEquals(control.page(), plan.page());
        assertEquals(control.section(), plan.sectionId());
        assertEquals(control.id(), plan.targetControlId());
    }

    @Test
    void hiddenToneControlReportsPrerequisiteWithoutChangingContext() {
        ControlDescriptor control = toneControl("tone.sdr.agx.");
        SettingsRevealPlanner.VisibilityContext context = new SettingsRevealPlanner.VisibilityContext(
                true, CausticaConfig.Rt.Sdr.TONEMAP_AGX, CausticaConfig.Rt.Hdr.TONEMAP_EETF);

        SettingsRevealPlanner.RevealPlan plan = SettingsRevealPlanner.planReveal(control, context);

        assertEquals("caustica.options.search.unavailable.hdr", plan.unavailableReasonKey());
        assertTrue(context.hdrEnabled());
        assertEquals(CausticaConfig.Rt.Sdr.TONEMAP_AGX, context.sdrToneMapper());
        assertEquals(CausticaConfig.Rt.Hdr.TONEMAP_EETF, context.hdrToneMapper());
    }

    @Test
    void psychov23ControlRequiresTheMatchingActiveMode() {
        ControlDescriptor control = toneControl("tone.psychov23.");
        SettingsRevealPlanner.VisibilityContext context = new SettingsRevealPlanner.VisibilityContext(
                false, CausticaConfig.Rt.Sdr.TONEMAP_AGX, CausticaConfig.Rt.Hdr.TONEMAP_EETF);

        SettingsRevealPlanner.RevealPlan plan = SettingsRevealPlanner.planReveal(control, context);

        assertEquals("caustica.options.search.unavailable.psychoV23", plan.unavailableReasonKey());
    }

    private static ControlDescriptor toneControl(String prefix) {
        return SettingsCatalog.allControls().stream()
                .filter(control -> control.id().startsWith(prefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No control with prefix " + prefix));
    }
}
