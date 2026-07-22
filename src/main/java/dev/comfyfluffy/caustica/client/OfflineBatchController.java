package dev.comfyfluffy.caustica.client;

/** Bounded, hysteretic controller for the configured offline batch size. */
final class OfflineBatchController {
    static final int MIN_BATCH = 1;
    static final int MAX_BATCH = 64;

    private static final double EWMA_ALPHA = 0.20;
    private static final double LOWER_RATIO = 0.75;
    private static final double UPPER_RATIO = 1.25;
    private static final double OUTLIER_LOW = 0.25;
    private static final double OUTLIER_HIGH = 4.0;

    private final long targetGpuNanos;
    private int batch;
    private double nanosPerConfiguredSampleEwma = Double.NaN;
    private int acceptedMeasurements;

    OfflineBatchController(long targetGpuNanos, int initialBatch) {
        if (targetGpuNanos <= 0L) {
            throw new IllegalArgumentException("targetGpuNanos must be positive");
        }
        this.targetGpuNanos = targetGpuNanos;
        this.batch = clampPowerOfTwo(initialBatch);
    }

    int batch() {
        return batch;
    }

    void reset(int initialBatch) {
        batch = clampPowerOfTwo(initialBatch);
        nanosPerConfiguredSampleEwma = Double.NaN;
        acceptedMeasurements = 0;
    }

    int observe(long traceGpuNanos, int measuredBatch) {
        if (traceGpuNanos <= 0L || measuredBatch <= 0) {
            return batch;
        }
        double observed = (double) traceGpuNanos / (double) measuredBatch;
        if (!Double.isFinite(observed) || observed <= 0.0) {
            return batch;
        }
        if (Double.isNaN(nanosPerConfiguredSampleEwma)) {
            nanosPerConfiguredSampleEwma = observed;
        } else {
            double bounded = Math.max(nanosPerConfiguredSampleEwma * OUTLIER_LOW,
                    Math.min(nanosPerConfiguredSampleEwma * OUTLIER_HIGH, observed));
            nanosPerConfiguredSampleEwma += EWMA_ALPHA * (bounded - nanosPerConfiguredSampleEwma);
        }
        acceptedMeasurements++;
        if (acceptedMeasurements < 3) {
            return batch;
        }
        double ratio = nanosPerConfiguredSampleEwma * batch / (double) targetGpuNanos;
        if (ratio >= LOWER_RATIO && ratio <= UPPER_RATIO) {
            return batch;
        }
        int desired = nearestPowerOfTwo(targetGpuNanos / nanosPerConfiguredSampleEwma);
        int minimumStep = Math.max(MIN_BATCH, batch / 2);
        int maximumStep = Math.min(MAX_BATCH, batch * 2);
        batch = clampPowerOfTwo(Math.max(minimumStep, Math.min(maximumStep, desired)));
        return batch;
    }

    private static int nearestPowerOfTwo(double value) {
        if (!Double.isFinite(value) || value <= MIN_BATCH) {
            return MIN_BATCH;
        }
        if (value >= MAX_BATCH) {
            return MAX_BATCH;
        }
        int lower = Integer.highestOneBit((int) Math.floor(value));
        int upper = Math.min(MAX_BATCH, lower << 1);
        return value - lower <= upper - value ? lower : upper;
    }

    private static int clampPowerOfTwo(int value) {
        int clamped = Math.max(MIN_BATCH, Math.min(MAX_BATCH, value));
        int lower = Integer.highestOneBit(clamped);
        int upper = lower == MAX_BATCH ? MAX_BATCH : lower << 1;
        return clamped - lower <= upper - clamped ? lower : upper;
    }
}
