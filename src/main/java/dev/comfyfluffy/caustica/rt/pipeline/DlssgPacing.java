package dev.comfyfluffy.caustica.rt.pipeline;

import net.minecraft.client.Minecraft;

/** Pure fixed-MFG pacing math plus the active-window refresh-rate probe. */
public final class DlssgPacing {
    private static final int AUTO_CAP_CONSTANT = 3600;
    private static final int MAX_REFLEX_INTERVAL_US = 1_000_000;

    private DlssgPacing() {
    }

    /**
     * Apply the requested asymptotic headroom curve. At 240 Hz this returns 225 FPS:
     * {@code floor(3600 * refresh / (refresh + 3600))}.
     */
    public static int automaticOutputCapFps(int refreshRateHz) {
        if (refreshRateHz <= 0) {
            return 0;
        }
        long numerator = (long) AUTO_CAP_CONSTANT * refreshRateHz;
        return Math.max(1, (int) (numerator / (refreshRateHz + (long) AUTO_CAP_CONSTANT)));
    }

    /** MAILBOX does not block the producer, so VSync compatibility must always supply Reflex pacing. */
    public static boolean automaticPacingEnabled(boolean frameGenerationRequested, boolean pluginForSwapchain,
            boolean autoCapRequested, boolean mailboxVsyncCompatibility) {
        return frameGenerationRequested && pluginForSwapchain
                && (autoCapRequested || mailboxVsyncCompatibility);
    }

    /**
     * Convert a total real-plus-generated output target into the application-frame interval consumed by Reflex.
     */
    public static int reflexIntervalUs(int outputCapFps, int generatedFrames, int manualIntervalUs) {
        if (outputCapFps <= 0) {
            return Math.max(0, manualIntervalUs);
        }
        int totalMultiplier = Math.clamp(generatedFrames + 1, 1, 6);
        long numerator = (long) totalMultiplier * 1_000_000L;
        long interval = (numerator + outputCapFps - 1L) / outputCapFps;
        return (int) Math.clamp(interval, 1L, MAX_REFLEX_INTERVAL_US);
    }

    public static float renderedFrameLimitFps(int intervalUs) {
        return intervalUs > 0 ? 1_000_000.0f / intervalUs : 0.0f;
    }

    /** Return the refresh rate of the monitor currently driving Minecraft's window, or zero if unavailable. */
    public static int currentRefreshRateHz() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            return minecraft != null && minecraft.getWindow() != null
                    ? Math.max(0, minecraft.getWindow().getRefreshRate()) : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
