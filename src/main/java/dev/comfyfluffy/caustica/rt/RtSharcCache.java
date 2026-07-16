package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.gen.SharcFrameData;
import dev.comfyfluffy.caustica.rt.gen.SharcFrameData.Float3;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;

/** Owns SHaRC's persistent tables and the frames-in-flight-safe BDA frame/stat rings. */
public final class RtSharcCache {
    public static final int MIN_EXPONENT = 16;
    public static final int MAX_EXPONENT = 22;
    private static final int RING = 6;
    private static final int STATS_BYTES = 96;

    private final RtBuffer hashEntries;
    private final RtBuffer accumulation;
    private final RtBuffer resolved;
    private final RtBuffer[] frames = new RtBuffer[RING];
    private final RtBuffer[] stats = new RtBuffer[RING];
    private final long[] statsGeneration = new long[RING];
    private final int exponent;
    private final int capacity;
    private int slot = -1;
    private boolean pendingClear = true;
    private String lastResetReason = "enabled";
    private long resetCount;
    private long generation = 1;
    private Float3 previousCamera;
    private boolean destroyed;

    private RtSharcCache(RtBuffer hashEntries, RtBuffer accumulation, RtBuffer resolved,
                         RtBuffer[] frames, RtBuffer[] stats, int exponent, int capacity) {
        this.hashEntries = hashEntries;
        this.accumulation = accumulation;
        this.resolved = resolved;
        System.arraycopy(frames, 0, this.frames, 0, RING);
        System.arraycopy(stats, 0, this.stats, 0, RING);
        this.exponent = exponent;
        this.capacity = capacity;
    }

    public static RtSharcCache create(RtContext ctx, int requestedExponent) {
        int exponent = Math.clamp(requestedExponent, MIN_EXPONENT, MAX_EXPONENT);
        int capacity = 1 << exponent;
        int deviceUsage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        RtBuffer hash = null;
        RtBuffer accum = null;
        RtBuffer resolved = null;
        RtBuffer[] frameRing = new RtBuffer[RING];
        RtBuffer[] statsRing = new RtBuffer[RING];
        try {
            hash = ctx.createBuffer((long) capacity * 8L, deviceUsage, false, "SHaRC hash entries");
            accum = ctx.createBuffer((long) capacity * 16L, deviceUsage, false, "SHaRC accumulation");
            resolved = ctx.createBuffer((long) capacity * 16L, deviceUsage, false, "SHaRC resolved");
            for (int i = 0; i < RING; i++) {
                frameRing[i] = ctx.createBuffer(SharcFrameData.BYTE_SIZE, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                        true, "SHaRC frame " + i);
                statsRing[i] = ctx.createBuffer(STATS_BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                        true, "SHaRC stats " + i);
                MemoryUtil.memSet(statsRing[i].mapped, 0, STATS_BYTES);
                statsRing[i].flush(0, STATS_BYTES);
            }
            return new RtSharcCache(hash, accum, resolved, frameRing, statsRing, exponent, capacity);
        } catch (Throwable t) {
            if (hash != null) hash.destroy();
            if (accum != null) accum.destroy();
            if (resolved != null) resolved.destroy();
            for (RtBuffer buffer : frameRing) if (buffer != null) buffer.destroy();
            for (RtBuffer buffer : statsRing) if (buffer != null) buffer.destroy();
            throw t;
        }
    }

    public int exponent() { return exponent; }
    public int capacity() { return capacity; }
    public long bytes() { return hashEntries.size + accumulation.size + resolved.size + RING * (SharcFrameData.BYTE_SIZE + STATS_BYTES); }
    public String lastResetReason() { return lastResetReason; }
    public long resetCount() { return resetCount; }

    public void requestReset(String reason) {
        if (!pendingClear || !lastResetReason.equals(reason)) generation++;
        pendingClear = true;
        lastResetReason = reason;
    }

    /** Advance the ring, publish a new immutable frame block, and return its device address. */
    public Frame beginFrame(long frameIndex, float x, float y, float z, int width, int height) {
        slot = (slot + 1) % RING;
        RtBuffer statsBuffer = stats[slot];
        Stats snapshot = Stats.ZERO;
        if (statsGeneration[slot] == generation) {
            statsBuffer.invalidate(0, STATS_BYTES);
            ByteBuffer oldStats = MemoryUtil.memByteBuffer(statsBuffer.mapped, STATS_BYTES);
            snapshot = new Stats(u32(oldStats, 0), u32(oldStats, 4), u32(oldStats, 8),
                    u32(oldStats, 12), u32(oldStats, 16), u32(oldStats, 20),
                    u32(oldStats, 24), u32(oldStats, 28), u32(oldStats, 32),
                    u32(oldStats, 36), u32(oldStats, 40), u32(oldStats, 44),
                    u32(oldStats, 48), u32(oldStats, 52), u32(oldStats, 56),
                    u32(oldStats, 60), u32(oldStats, 64), u32(oldStats, 68));
        }
        MemoryUtil.memSet(statsBuffer.mapped, 0, STATS_BYTES);
        statsBuffer.flush(0, STATS_BYTES);
        statsGeneration[slot] = generation;

        Float3 camera = new Float3(x, y, z);
        if (previousCamera == null || pendingClear) previousCamera = camera;
        int flags = dev.comfyfluffy.caustica.CausticaConfig.Rt.Sharc.ANTI_FIREFLY.value() ? 1 : 0;
        // Per-path statistics use contended global atomics. Keep them out of the production hot path
        // unless the user explicitly enabled SHaRC detailed counters.
        if (dev.comfyfluffy.caustica.CausticaConfig.Rt.Sharc.DETAILED_STATS.value()) flags |= 2;
        ByteBuffer dst = MemoryUtil.memByteBuffer(frames[slot].mapped, SharcFrameData.BYTE_SIZE);
        new SharcFrameData(hashEntries.deviceAddress, accumulation.deviceAddress, resolved.deviceAddress,
                statsBuffer.deviceAddress, camera, capacity, previousCamera, (int) frameIndex,
                dev.comfyfluffy.caustica.CausticaConfig.Rt.Sharc.SCENE_SCALE.value(),
                dev.comfyfluffy.caustica.CausticaConfig.Rt.Sharc.RADIANCE_SCALE.value(),
                dev.comfyfluffy.caustica.CausticaConfig.Rt.Sharc.ACCUMULATION_FRAMES.value(),
                dev.comfyfluffy.caustica.CausticaConfig.Rt.Sharc.STALE_FRAMES.value(),
                flags, width, height,
                dev.comfyfluffy.caustica.CausticaConfig.Rt.Sharc.UPDATE_TILE_SIZE.value(),
                dev.comfyfluffy.caustica.CausticaConfig.Rt.Sharc.UPDATE_MAX_BOUNCES.value(),
                dev.comfyfluffy.caustica.CausticaConfig.Rt.Sharc.MIN_SEGMENT_RATIO.value(),
                (dev.comfyfluffy.caustica.CausticaConfig.Rt.Sharc.GLOSSY_QUERY.value() ? 1 : 0)
                        | (dev.comfyfluffy.caustica.CausticaConfig.Rt.Sharc.LIVE_SECONDARY_DIRECT.value() ? 2 : 0)).write(dst);
        frames[slot].flush(0, SharcFrameData.BYTE_SIZE);
        previousCamera = camera;
        return new Frame(frames[slot].deviceAddress, snapshot);
    }

    public boolean recordPendingClear(VkCommandBuffer cmd, MemoryStack stack) {
        if (!pendingClear) return false;
        vkCmdFillBuffer(cmd, hashEntries.handle, 0L, hashEntries.size, 0);
        vkCmdFillBuffer(cmd, accumulation.handle, 0L, accumulation.size, 0);
        vkCmdFillBuffer(cmd, resolved.handle, 0L, resolved.size, 0);
        barrier(cmd, stack, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
        pendingClear = false;
        resetCount++;
        return true;
    }

    private static long u32(ByteBuffer buffer, int offset) {
        return Integer.toUnsignedLong(buffer.getInt(offset));
    }

    public void updateToResolveBarrier(VkCommandBuffer cmd, MemoryStack stack) {
        barrier(cmd, stack, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
    }

    public void resolveToQueryBarrier(VkCommandBuffer cmd, MemoryStack stack) {
        barrier(cmd, stack, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR, VK_ACCESS_SHADER_READ_BIT);
    }

    private void barrier(VkCommandBuffer cmd, MemoryStack stack, int srcStage, int srcAccess, int dstStage, int dstAccess) {
        VkBufferMemoryBarrier.Buffer barriers = VkBufferMemoryBarrier.calloc(3, stack);
        RtBuffer[] buffers = {hashEntries, accumulation, resolved};
        for (int i = 0; i < buffers.length; i++) {
            barriers.get(i).sType$Default().srcAccessMask(srcAccess).dstAccessMask(dstAccess)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(buffers[i].handle).offset(0).size(buffers[i].size);
        }
        vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, barriers, null);
    }

    public void destroy() {
        if (destroyed) return;
        hashEntries.destroy(); accumulation.destroy(); resolved.destroy();
        for (RtBuffer b : frames) b.destroy();
        for (RtBuffer b : stats) b.destroy();
        destroyed = true;
    }

    public record Frame(long address, Stats previousStats) {}
    public record Stats(long queryAttempts, long queryHits, long queryMisses, long updateHits,
                        long updateMisses, long insertFailures, long terminatedBounceSum,
                        long terminatedPaths, long occupiedEntries, long insertions,
                        long collisions, long staleEvictions, long numericRisks,
                        long resolvedSaturations, long maxCachedLumaBits,
                        long shortSegmentRejects, long glossyRejects, long dynamicRejects) {
        public static final Stats ZERO = new Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0);
    }
}
