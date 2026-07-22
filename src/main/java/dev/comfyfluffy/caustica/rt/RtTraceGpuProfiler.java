package dev.comfyfluffy.caustica.rt;

import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

import static dev.comfyfluffy.caustica.rt.RtContext.check;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;

/** Non-blocking, six-frame-ring Vulkan timestamps for the complete RT composite GPU pipeline. */
public final class RtTraceGpuProfiler {
    private static final int RING = 6;
    private static final int QUERIES = 17;
    private static final int OFFLINE_PILOT_BEGIN = 11;
    private static final int OFFLINE_PILOT_END = 12;
    private static final int OFFLINE_MAIN_BEGIN = 13;
    private static final int OFFLINE_MAIN_END = 14;
    private static final int OFFLINE_SCHEDULE_BEGIN = 15;
    private static final int OFFLINE_SCHEDULE_END = 16;
    private final RtContext ctx;
    private final long pool;
    private final double nanosPerTick;
    private static final class Slot {
        boolean used;
        boolean sharc;
        boolean fullPipeline;
        int frameSerial;
        OfflineDispatchMetadata offlineMetadata = OfflineDispatchMetadata.NONE;

        void reset() {
            used = false;
            sharc = false;
            fullPipeline = false;
            frameSerial = 0;
            offlineMetadata = OfflineDispatchMetadata.NONE;
        }
    }

    private final Slot[] slots = new Slot[RING];
    private int slot = -1;
    private int frameSerial;
    private volatile long baselineTraceNanos;
    private volatile long sharcUpdateNanos;
    private volatile long sharcResolveNanos;
    private volatile long sharcQueryNanos;
    private volatile long blasNanos;
    private volatile long tlasNanos;
    private volatile long reconstructionNanos;
    private volatile long disocclusionNanos;
    private volatile long dlssRrNanos;
    private volatile long exposureNanos;
    private volatile long displayNanos;
    private volatile long copyNanos;
    private volatile long pilotGpuNanos;
    private volatile long mainTraceGpuNanos;
    private volatile long scheduleGpuNanos;
    private volatile int completedOfflineFrameSerial;
    private volatile OfflineDispatchMetadata completedOfflineMetadata = OfflineDispatchMetadata.NONE;

    private RtTraceGpuProfiler(RtContext ctx, long pool, double nanosPerTick) {
        this.ctx = ctx; this.pool = pool; this.nanosPerTick = nanosPerTick;
        for (int i = 0; i < RING; i++) {
            slots[i] = new Slot();
        }
    }

    public static RtTraceGpuProfiler create(RtContext ctx) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                    .queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(RING * QUERIES);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateQueryPool(ctx.vk(), info, null, p), "vkCreateQueryPool(trace timestamps)");
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(ctx.vk().getPhysicalDevice(), props);
            return new RtTraceGpuProfiler(ctx, p.get(0), props.limits().timestampPeriod());
        }
    }

    public void beginFrame(VkCommandBuffer cmd, boolean isSharc, boolean profileFullPipeline) {
        slot = (slot + 1) % RING;
        readCompleted(slot);
        Slot current = slots[slot];
        current.reset();
        int base = slot * QUERIES;
        vkCmdResetQueryPool(cmd, pool, base, QUERIES);
        current.used = true;
        current.sharc = isSharc;
        current.fullPipeline = profileFullPipeline;
        current.frameSerial = ++frameSerial;
        if (profileFullPipeline) vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, pool, base);
    }

    /** Associate the workload submitted in the current timestamp slot with delayed query results. */
    public void setOfflineMetadata(OfflineDispatchMetadata metadata) {
        if (slot < 0 || !slots[slot].used) {
            return;
        }
        slots[slot].offlineMetadata = metadata == null ? OfflineDispatchMetadata.NONE : metadata;
    }

    public void blasEnd(VkCommandBuffer cmd) {
        if (slots[slot].fullPipeline) write(cmd, 1, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
    }
    // Query 2 is both the full profiler's TLAS end and the lightweight profiler's trace start.
    public void tlasEnd(VkCommandBuffer cmd) { write(cmd, 2, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT); }
    public void updateEnd(VkCommandBuffer cmd) { write(cmd, 3, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR); }
    public void resolveEnd(VkCommandBuffer cmd) { write(cmd, 4, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT); }
    public void queryEnd(VkCommandBuffer cmd) { write(cmd, 5, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR); }
    public void baselineEnd(VkCommandBuffer cmd) {
        write(cmd, 3, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);
        write(cmd, 4, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);
        write(cmd, 5, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);
    }
    public void disocclusionEnd(VkCommandBuffer cmd) {
        if (slots[slot].fullPipeline) write(cmd, 6, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
    }
    public void reconstructionEnd(VkCommandBuffer cmd) {
        if (slots[slot].fullPipeline) write(cmd, 7, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
    }
    public void exposureEnd(VkCommandBuffer cmd) {
        if (slots[slot].fullPipeline) write(cmd, 8, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
    }
    public void displayEnd(VkCommandBuffer cmd) {
        if (slots[slot].fullPipeline) write(cmd, 9, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
    }
    public void copyEnd(VkCommandBuffer cmd) {
        if (slots[slot].fullPipeline) write(cmd, 10, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
    }

    public void offlinePilotBegin(VkCommandBuffer cmd) {
        write(cmd, OFFLINE_PILOT_BEGIN, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);
    }

    public void offlinePilotEnd(VkCommandBuffer cmd) {
        write(cmd, OFFLINE_PILOT_END, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);
    }

    public void offlineMainBegin(VkCommandBuffer cmd) {
        write(cmd, OFFLINE_MAIN_BEGIN, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);
    }

    public void offlineMainEnd(VkCommandBuffer cmd) {
        write(cmd, OFFLINE_MAIN_END, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);
    }

    public void offlineScheduleBegin(VkCommandBuffer cmd) {
        write(cmd, OFFLINE_SCHEDULE_BEGIN, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    }

    public void offlineScheduleEnd(VkCommandBuffer cmd) {
        write(cmd, OFFLINE_SCHEDULE_END, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    }

    private void write(VkCommandBuffer cmd, int offset, int stage) {
        vkCmdWriteTimestamp(cmd, stage, pool, slot * QUERIES + offset);
    }

    private void readCompleted(int readSlot) {
        Slot completed = slots[readSlot];
        if (!completed.used) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            boolean full = completed.fullPipeline;
            int firstQuery = full ? 0 : 2;
            int count = full ? QUERIES : 4;
            LongBuffer values = stack.mallocLong(count);
            int result = vkGetQueryPoolResults(ctx.vk(), pool, readSlot * QUERIES + firstQuery, count, values,
                    Long.BYTES, VK_QUERY_RESULT_64_BIT);
            if (result != VK_SUCCESS) return; // never wait on the render thread
            int traceStart = full ? 2 : 0;
            if (full) {
                blasNanos = elapsed(values.get(0), values.get(1));
                tlasNanos = elapsed(values.get(1), values.get(2));
            }
            if (completed.sharc) {
                sharcUpdateNanos = elapsed(values.get(traceStart), values.get(traceStart + 1));
                sharcResolveNanos = elapsed(values.get(traceStart + 1), values.get(traceStart + 2));
                sharcQueryNanos = elapsed(values.get(traceStart + 2), values.get(traceStart + 3));
                RtFrameStats.FRAME.count("sharcUpdateGpuNanos", sharcUpdateNanos);
                RtFrameStats.FRAME.count("sharcResolveGpuNanos", sharcResolveNanos);
                RtFrameStats.FRAME.count("sharcQueryGpuNanos", sharcQueryNanos);
            } else {
                baselineTraceNanos = elapsed(values.get(traceStart), values.get(traceStart + 3));
                RtFrameStats.FRAME.count("baselineTraceGpuNanos", baselineTraceNanos);
                if (!completed.offlineMetadata.equals(OfflineDispatchMetadata.NONE)) {
                    completedOfflineMetadata = completed.offlineMetadata;
                    completedOfflineFrameSerial = completed.frameSerial;
                    LongBuffer offlineValues = stack.mallocLong(6);
                    int offlineResult = vkGetQueryPoolResults(ctx.vk(), pool,
                            readSlot * QUERIES + OFFLINE_PILOT_BEGIN, 6, offlineValues,
                            Long.BYTES, VK_QUERY_RESULT_64_BIT);
                    if (offlineResult == VK_SUCCESS) {
                        pilotGpuNanos = elapsed(offlineValues.get(0), offlineValues.get(1));
                        mainTraceGpuNanos = elapsed(offlineValues.get(2), offlineValues.get(3));
                        scheduleGpuNanos = elapsed(offlineValues.get(4), offlineValues.get(5));
                        RtFrameStats.FRAME.count("pilotGpuNanos", pilotGpuNanos);
                        RtFrameStats.FRAME.count("mainTraceGpuNanos", mainTraceGpuNanos);
                        RtFrameStats.FRAME.count("scheduleGpuNanos", scheduleGpuNanos);
                    }
                }
            }
            if (full) {
                disocclusionNanos = elapsed(values.get(5), values.get(6));
                dlssRrNanos = elapsed(values.get(6), values.get(7));
                reconstructionNanos = elapsed(values.get(5), values.get(7));
                exposureNanos = elapsed(values.get(7), values.get(8));
                displayNanos = elapsed(values.get(8), values.get(9));
                copyNanos = elapsed(values.get(9), values.get(10));
                RtFrameStats.FRAME.count("blasGpuNanos", blasNanos);
                RtFrameStats.FRAME.count("tlasGpuNanos", tlasNanos);
                RtFrameStats.FRAME.count("reconstructionGpuNanos", reconstructionNanos);
                RtFrameStats.FRAME.count("disocclusionGpuNanos", disocclusionNanos);
                RtFrameStats.FRAME.count("dlssRrGpuNanos", dlssRrNanos);
                RtFrameStats.FRAME.count("exposureGpuNanos", exposureNanos);
                RtFrameStats.FRAME.count("displayGpuNanos", displayNanos);
                RtFrameStats.FRAME.count("copyGpuNanos", copyNanos);
            }
        }
    }

    private long elapsed(long start, long end) {
        return Math.max(0L, Math.round((end - start) * nanosPerTick));
    }

    public long baselineTraceNanos() { return baselineTraceNanos; }
    public long sharcUpdateNanos() { return sharcUpdateNanos; }
    public long sharcResolveNanos() { return sharcResolveNanos; }
    public long sharcQueryNanos() { return sharcQueryNanos; }
    public long blasNanos() { return blasNanos; }
    public long tlasNanos() { return tlasNanos; }
    public long reconstructionNanos() { return reconstructionNanos; }
    public long disocclusionNanos() { return disocclusionNanos; }
    public long dlssRrNanos() { return dlssRrNanos; }
    public long exposureNanos() { return exposureNanos; }
    public long displayNanos() { return displayNanos; }
    public long copyNanos() { return copyNanos; }
    public long pilotGpuNanos() { return pilotGpuNanos; }
    public long mainTraceGpuNanos() { return mainTraceGpuNanos; }
    public long scheduleGpuNanos() { return scheduleGpuNanos; }

    public int completedOfflineFrameSerial() { return completedOfflineFrameSerial; }
    public OfflineDispatchMetadata completedOfflineMetadata() { return completedOfflineMetadata; }

    public void destroy() { vkDestroyQueryPool(ctx.vk(), pool, null); }
}
