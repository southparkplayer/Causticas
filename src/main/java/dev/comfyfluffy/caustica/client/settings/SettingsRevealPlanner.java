package dev.comfyfluffy.caustica.client.settings;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.client.settings.SettingsCatalog.ControlDescriptor;

/** Computes search navigation without changing renderer configuration. */
public final class SettingsRevealPlanner {
    private SettingsRevealPlanner() {
    }

    public record VisibilityContext(boolean hdrEnabled, String sdrToneMapper, String hdrToneMapper) {
    }

    public record RevealPlan(SettingsCatalog.Page page, String sectionId, String targetControlId,
                             String unavailableReasonKey) {
        public boolean available() {
            return unavailableReasonKey == null;
        }
    }

    public static RevealPlan planReveal(ControlDescriptor control, VisibilityContext context) {
        if (control == null) {
            throw new IllegalArgumentException("control must not be null");
        }
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        return new RevealPlan(control.page(), control.section(), control.id(),
                unavailableReasonKey(control.id(), context));
    }

    private static String unavailableReasonKey(String id, VisibilityContext context) {
        if (!id.startsWith("tone.")) return null;
        if (id.startsWith("tone.sdr.")) {
            if (context.hdrEnabled()) return "caustica.options.search.unavailable.hdr";
            String mode = id.substring("tone.sdr.".length(), id.indexOf('.', "tone.sdr.".length()));
            String expected = switch (mode) {
                case "pbrNeutral" -> CausticaConfig.Rt.Sdr.TONEMAP_PBR_NEUTRAL;
                case "uncharted2" -> CausticaConfig.Rt.Sdr.TONEMAP_UNCHARTED2;
                case "psychov" -> CausticaConfig.Rt.Sdr.TONEMAP_PSYCHOV;
                default -> mode;
            };
            return expected.equals(context.sdrToneMapper())
                    ? null : "caustica.options.search.unavailable.sdrToneMapper";
        }
        if (id.startsWith("tone.psychov23.")) {
            String active = context.hdrEnabled() ? context.hdrToneMapper() : context.sdrToneMapper();
            return CausticaConfig.Rt.Sdr.TONEMAP_PSYCHOV23.equals(active)
                    || CausticaConfig.Rt.Hdr.TONEMAP_PSYCHOV23.equals(active)
                    ? null : "caustica.options.search.unavailable.psychoV23";
        }
        if (id.startsWith("tone.psychov.")) {
            boolean hdrOnly = id.endsWith(".bleaching") || id.endsWith(".clipPoint")
                    || id.endsWith(".whiteCurve");
            if (hdrOnly && !context.hdrEnabled()) {
                return "caustica.options.search.unavailable.hdr";
            }
            String active = context.hdrEnabled() ? context.hdrToneMapper() : context.sdrToneMapper();
            boolean psycho = CausticaConfig.Rt.Sdr.TONEMAP_PSYCHOV.equals(active)
                    || CausticaConfig.Rt.Hdr.TONEMAP_PSYCHOV.equals(active);
            return psycho ? null : "caustica.options.search.unavailable.psychoV";
        }
        return null;
    }
}
