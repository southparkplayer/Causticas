package dev.comfyfluffy.caustica.rt;

/** Metadata captured with one submitted offline workload. */
public record OfflineDispatchMetadata(
        int configuredBatchLimit,
        long mainPaths,
        long pilotPaths,
        int activeTiles,
        int totalTiles,
        int indirectInvocations,
        boolean indirect) {
    public static final OfflineDispatchMetadata NONE = new OfflineDispatchMetadata(
            0, 0L, 0L, 0, 0, 0, false);

    public OfflineDispatchMetadata {
        if (configuredBatchLimit < 0) {
            throw new IllegalArgumentException("configuredBatchLimit must be non-negative");
        }
        if (mainPaths < 0L || pilotPaths < 0L) {
            throw new IllegalArgumentException("path counts must be non-negative");
        }
        if (activeTiles < 0 || totalTiles < 0 || activeTiles > totalTiles) {
            throw new IllegalArgumentException("invalid active/total tile counts");
        }
        if (indirectInvocations < 0) {
            throw new IllegalArgumentException("indirectInvocations must be non-negative");
        }
    }

    public double activeTileRatio() {
        return totalTiles == 0 ? 0.0 : (double) activeTiles / (double) totalTiles;
    }
}
