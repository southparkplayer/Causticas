package dev.upscaler.rt;

import org.lwjgl.system.MemoryUtil;

import java.util.List;

/**
 * The P0 hardcoded-triangle scene: a position/index buffer pair, its BLAS, and a single-instance
 * TLAS. Geometry sits on the z = 0 plane in [-1, 1] so it matches {@code triangle.rgen}'s ortho
 * rays. Kept as a regression check while P1 builds real terrain geometry alongside it.
 */
public final class RtTriangleScene {
    private static RtTriangleScene instance;

    private final RtBuffer positions;
    private final RtBuffer indices;
    private final RtAccel blas;
    private final RtAccel tlas;

    private RtTriangleScene(RtBuffer positions, RtBuffer indices, RtAccel blas, RtAccel tlas) {
        this.positions = positions;
        this.indices = indices;
        this.blas = blas;
        this.tlas = tlas;
    }

    public static RtTriangleScene get(RtContext ctx) {
        if (instance == null) {
            instance = build(ctx);
        }
        return instance;
    }

    public static RtTriangleScene currentOrNull() {
        return instance;
    }

    public long tlas() {
        return tlas.handle;
    }

    private static RtTriangleScene build(RtContext ctx) {
        float[] verts = {
                0.0f, 0.6f, 0.0f,
                -0.6f, -0.5f, 0.0f,
                0.6f, -0.5f, 0.0f,
        };
        int[] idx = {0, 1, 2};
        int asInput = org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;

        RtBuffer positions = ctx.createBuffer((long) verts.length * Float.BYTES, asInput, true);
        RtBuffer indices = ctx.createBuffer((long) idx.length * Integer.BYTES, asInput, true);
        MemoryUtil.memFloatBuffer(positions.mapped, verts.length).put(verts);
        MemoryUtil.memIntBuffer(indices.mapped, idx.length).put(idx);

        RtAccel blas = RtAccel.buildTrianglesBlas(ctx, positions, verts.length / 3, indices, idx.length);
        float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
        RtAccel tlas = RtAccel.buildTlas(ctx, List.of(new RtAccel.Instance(identity, blas.deviceAddress)));
        return new RtTriangleScene(positions, indices, blas, tlas);
    }

    public void destroy() {
        tlas.destroy();
        blas.destroy();
        indices.destroy();
        positions.destroy();
        if (instance == this) {
            instance = null;
        }
    }
}
