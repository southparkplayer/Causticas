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
    private static final int QUERIES = 10;
    private final RtContext ctx;
    private final long pool;
    private final double nanosPerTick;
    private final boolean[] used = new boolean[RING];
    private final boolean[] sharc = new boolean[RING];
    private final boolean[] fullPipeline = new boolean[RING];
    private int slot = -1;
    private volatile long baselineTraceNanos;
    private volatile long sharcUpdateNanos;
    private volatile long sharcResolveNanos;
    private volatile long sharcQueryNanos;
    private volatile long blasNanos;
    private volatile long tlasNanos;
    private volatile long reconstructionNanos;
    private volatile long exposureNanos;
    private volatile long displayNanos;
    private volatile long copyNanos;

    private RtTraceGpuProfiler(RtContext ctx, long pool, double nanosPerTick) {
        this.ctx = ctx; this.pool = pool; this.nanosPerTick = nanosPerTick;
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
        int base = slot * QUERIES;
        vkCmdResetQueryPool(cmd, pool, base, QUERIES);
        used[slot] = true;
        sharc[slot] = isSharc;
        fullPipeline[slot] = profileFullPipeline;
        if (profileFullPipeline) vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, pool, base);
    }

    public void blasEnd(VkCommandBuffer cmd) {
        if (fullPipeline[slot]) write(cmd, 1, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
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
    public void reconstructionEnd(VkCommandBuffer cmd) {
        if (fullPipeline[slot]) write(cmd, 6, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
    }
    public void exposureEnd(VkCommandBuffer cmd) {
        if (fullPipeline[slot]) write(cmd, 7, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
    }
    public void displayEnd(VkCommandBuffer cmd) {
        if (fullPipeline[slot]) write(cmd, 8, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
    }
    public void copyEnd(VkCommandBuffer cmd) {
        if (fullPipeline[slot]) write(cmd, 9, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
    }

    private void write(VkCommandBuffer cmd, int offset, int stage) {
        vkCmdWriteTimestamp(cmd, stage, pool, slot * QUERIES + offset);
    }

    private void readCompleted(int readSlot) {
        if (!used[readSlot]) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            boolean full = fullPipeline[readSlot];
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
            if (sharc[readSlot]) {
                sharcUpdateNanos = elapsed(values.get(traceStart), values.get(traceStart + 1));
                sharcResolveNanos = elapsed(values.get(traceStart + 1), values.get(traceStart + 2));
                sharcQueryNanos = elapsed(values.get(traceStart + 2), values.get(traceStart + 3));
                RtFrameStats.FRAME.count("sharcUpdateGpuNanos", sharcUpdateNanos);
                RtFrameStats.FRAME.count("sharcResolveGpuNanos", sharcResolveNanos);
                RtFrameStats.FRAME.count("sharcQueryGpuNanos", sharcQueryNanos);
            } else {
                baselineTraceNanos = elapsed(values.get(traceStart), values.get(traceStart + 3));
                RtFrameStats.FRAME.count("baselineTraceGpuNanos", baselineTraceNanos);
            }
            if (full) {
                reconstructionNanos = elapsed(values.get(5), values.get(6));
                exposureNanos = elapsed(values.get(6), values.get(7));
                displayNanos = elapsed(values.get(7), values.get(8));
                copyNanos = elapsed(values.get(8), values.get(9));
                RtFrameStats.FRAME.count("blasGpuNanos", blasNanos);
                RtFrameStats.FRAME.count("tlasGpuNanos", tlasNanos);
                RtFrameStats.FRAME.count("reconstructionGpuNanos", reconstructionNanos);
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
    public long exposureNanos() { return exposureNanos; }
    public long displayNanos() { return displayNanos; }
    public long copyNanos() { return copyNanos; }

    public void destroy() { vkDestroyQueryPool(ctx.vk(), pool, null); }
}
