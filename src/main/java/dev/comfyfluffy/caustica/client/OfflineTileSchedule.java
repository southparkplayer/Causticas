package dev.comfyfluffy.caustica.client;

/** Pure CPU mirror of the tile cadence policy used by offline scheduling. */
final class OfflineTileSchedule {
    static final int MAX_CADENCE = 32;
    static final long MIN_ADAPTIVE_SAMPLES = 64L;

    private OfflineTileSchedule() {
    }

    static int cadence(float relativeVariance, long sampleCount, float targetRelativeError) {
        if (!Float.isFinite(relativeVariance) || relativeVariance < 0.0f
                || !Float.isFinite(targetRelativeError) || targetRelativeError <= 0.0f
                || sampleCount < MIN_ADAPTIVE_SAMPLES) {
            return 1;
        }
        double predictedError = Math.sqrt(relativeVariance)
                / Math.sqrt(Math.max(1.0, (double) sampleCount));
        int cadence = 1;
        while (cadence < MAX_CADENCE && predictedError * cadence < targetRelativeError) {
            cadence <<= 1;
        }
        return cadence;
    }

    static boolean active(int tileX, int tileY, long frameIndex, int cadence) {
        if (cadence <= 0 || (cadence & (cadence - 1)) != 0) {
            throw new IllegalArgumentException("cadence must be a positive power of two");
        }
        int hash = mix(tileX ^ mix(tileY + 0x9e3779b9));
        int phase = hash & (cadence - 1);
        return ((frameIndex + phase) & (cadence - 1L)) == 0L;
    }

    private static int mix(int value) {
        int x = value;
        x ^= x >>> 16;
        x *= 0x21f0aaad;
        x ^= x >>> 15;
        x *= 0xf35a2d97;
        x ^= x >>> 15;
        return x;
    }
}
