package dev.comfyfluffy.caustica.rt.pipeline;

/** Pure state machine for associating acquired backbuffers with Streamline input retirement values. */
final class DlssgInputSlotRing {
    private long[] fences = new long[0];
    private long[] values = new long[0];
    private boolean[] pending = new boolean[0];
    private int active = -1;
    private int acquiredApplicationImage = -1;
    private boolean prepared;

    void reset(int count) {
        fences = new long[Math.max(0, count)];
        values = new long[fences.length];
        pending = new boolean[fences.length];
        active = -1;
        acquiredApplicationImage = -1;
        prepared = false;
    }

    void acquire(int imageIndex) {
        acquiredApplicationImage = imageIndex;
        // Tagged depth/motion/HUD-less/UI images are independent of the swapchain images. Rotate them
        // uniformly by application present instead of inheriting MAILBOX's intentionally nonuniform image
        // acquisition order, which otherwise creates irregular retirement waits and visible pumping.
        active = imageIndex >= 0 && fences.length > 0 ? (active + 1) % fences.length : -1;
        prepared = false;
    }

    void retireActive(long fence, long value) {
        if (!hasActive()) {
            return;
        }
        fences[active] = fence;
        values[active] = value;
        pending[active] = true;
    }

    int count() { return fences.length; }
    int active() { return active; }
    int acquiredApplicationImage() { return acquiredApplicationImage; }
    boolean hasActive() { return active >= 0 && active < fences.length; }
    boolean prepared() { return prepared; }
    void markPrepared() { prepared = true; }
    boolean pending(int slot) { return pending[slot]; }
    boolean valid(int slot) { return fences[slot] != 0L && values[slot] != 0L; }
    long fence(int slot) { return fences[slot]; }
    long value(int slot) { return values[slot]; }

    void clear(int slot) {
        fences[slot] = 0L;
        values[slot] = 0L;
        pending[slot] = false;
    }

    void clearAll() {
        for (int slot = 0; slot < fences.length; slot++) {
            clear(slot);
        }
    }

    String snapshot() {
        StringBuilder result = new StringBuilder();
        for (int slot = 0; slot < fences.length; slot++) {
            if (slot > 0) result.append(';');
            result.append(slot).append(':')
                    .append(Long.toUnsignedString(fences[slot], 16)).append('@')
                    .append(values[slot]).append(':')
                    .append(pending[slot] ? "pending" : "free");
        }
        return result.toString();
    }
}
