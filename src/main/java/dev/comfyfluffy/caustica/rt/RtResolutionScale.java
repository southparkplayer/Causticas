package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaConfig;

/** Shared preset and input ratio rules for realtime reconstruction sizing. */
public final class RtResolutionScale {
    public static final int CUSTOM_QUALITY = -1;
    public static final int MIN_INPUT_RATIO_TENTHS = 10;
    public static final int MAX_INPUT_RATIO_TENTHS = 40;

    private RtResolutionScale() {
    }

    public static int clampInputTenths(int tenths) {
        return Math.clamp(tenths, MIN_INPUT_RATIO_TENTHS, MAX_INPUT_RATIO_TENTHS);
    }

    /** Display pixels per input pixel for each DLSS quality shortcut. */
    public static double presetUpscaleRatio(int quality) {
        return switch (quality) {
            case 3 -> 3.0;
            case 1 -> 1.7;
            case 2 -> 1.5;
            case 5 -> 1.0;
            default -> 2.0;
        };
    }

    public static int presetRatioTenths(int quality) {
        return (int)Math.round(presetUpscaleRatio(quality) * 10.0);
    }

    public static int displayedQuality() {
        return displayedQuality(CausticaConfig.Rt.DlssRr.INPUT_RATIO_TENTHS.configuredValue());
    }

    public static int displayedQuality(int inputRatioTenths) {
        int quality = CausticaConfig.Rt.DlssRr.QUALITY.configuredValue();
        int presetRatioTenths = presetRatioTenths(quality);
        return inputRatioTenths == presetRatioTenths
                ? quality : CUSTOM_QUALITY;
    }

    public static void selectQuality(int quality) {
        CausticaConfig.Rt.DlssRr.QUALITY.set(quality);
        CausticaConfig.Rt.DlssRr.INPUT_RATIO_TENTHS.set(presetRatioTenths(quality));
    }

    /** Exact input dimension from output dimension for ratio-tenths input scale values. */
    public static int inputDimension(int outputDimension, int inputRatioTenths) {
        if (outputDimension <= 0) {
            return 1;
        }
        return Math.max(1, (int) (((long) outputDimension * 10L
                + clampInputTenths(inputRatioTenths) - 1L)
                / clampInputTenths(inputRatioTenths)));
    }
}
