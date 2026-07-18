package dev.comfyfluffy.caustica.rt;

/** Pure sizing and status rules for the realtime pre-UI output-scaling stage. */
public final class RtOutputScale {
    public static final int MIN_PERCENT = 10;
    public static final int NATIVE_PERCENT = 100;
    public static final int MAX_PERCENT = 200;
    public static final float RCAS_SHARPNESS = 0.2f;

    private RtOutputScale() {
    }

    public static int clampPercent(int percent) {
        return Math.clamp(percent, MIN_PERCENT, MAX_PERCENT);
    }

    public static int dimension(int physicalPixels, int percent) {
        if (physicalPixels <= 0) {
            return 1;
        }
        return Math.max(1, (int) Math.round(physicalPixels * (clampPercent(percent) / 100.0)));
    }

    public static String path(int percent, boolean linearFallback) {
        if (linearFallback) return "linear-fallback";
        int clamped = clampPercent(percent);
        if (clamped < NATIVE_PERCENT) return "fsr1";
        if (clamped > NATIVE_PERCENT) return "downsample";
        return "native";
    }
}
