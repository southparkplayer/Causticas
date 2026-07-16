package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

/**
 * Host-generated multidimensional Sobol net used by the world path tracer.
 *
 * <p>The table keeps direction-number expansion out of the SER raygen. Values are interleaved by sample
 * ({@code sample * DIMENSIONS + dimension}) so all dimensions for the current frame share cache lines.
 * Raygen applies a per-pixel, per-bounce nested uniform Owen scramble; the table itself remains immutable.
 */
public final class RtPathSampleSequence {
    public static final int DIMENSIONS = 8;
    // Covers the full supported offline sample range as one progressive net; no table cycling/re-scrambling.
    public static final int SAMPLE_COUNT = 1 << 20;
    private static final long BYTE_SIZE = (long) DIMENSIONS * SAMPLE_COUNT * Integer.BYTES;

    // Primitive-polynomial degree, coefficient bits, then initial odd direction numerators. Dimension
    // zero is the Van der Corput base used by Sobol and is handled separately. These are the standard
    // Bratley-Fox parameters for the first eight dimensions.
    private static final int[][] PARAMETERS = {
            {},
            {1, 0, 1},
            {2, 1, 1, 3},
            {3, 1, 1, 3, 1},
            {3, 2, 1, 1, 1},
            {4, 1, 1, 1, 3, 3},
            {4, 4, 1, 3, 5, 13},
            {5, 2, 1, 1, 5, 5, 17}
    };

    private static final int[][] DIRECTIONS = createDirections();

    private final RtBuffer buffer;

    private RtPathSampleSequence(RtBuffer buffer) {
        this.buffer = buffer;
    }

    public static RtPathSampleSequence create(RtContext ctx) {
        RtBuffer buffer = ctx.createBuffer(BYTE_SIZE, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true,
                "8D Sobol path sample sequence");
        if (buffer.mapped == 0L) {
            buffer.destroy();
            throw new IllegalStateException("Sobol path sample sequence is not host mapped");
        }
        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            long sampleOffset = buffer.mapped + (long) sample * DIMENSIONS * Integer.BYTES;
            for (int dimension = 0; dimension < DIMENSIONS; dimension++) {
                MemoryUtil.memPutInt(sampleOffset + (long) dimension * Integer.BYTES,
                        sobolBits(sample, dimension));
            }
        }
        return new RtPathSampleSequence(buffer);
    }

    public long deviceAddress() {
        return buffer.deviceAddress;
    }

    public void destroy() {
        buffer.destroy();
    }

    static int sobolBits(int sampleIndex, int dimension) {
        if (dimension < 0 || dimension >= DIMENSIONS) {
            throw new IllegalArgumentException("Sobol dimension out of range: " + dimension);
        }
        int value = 0;
        int bits = sampleIndex;
        int bit = 0;
        while (bits != 0) {
            if ((bits & 1) != 0) {
                value ^= DIRECTIONS[dimension][bit];
            }
            bits >>>= 1;
            bit++;
        }
        return value;
    }

    private static int[][] createDirections() {
        int[][] result = new int[DIMENSIONS][Integer.SIZE];
        for (int bit = 0; bit < Integer.SIZE; bit++) {
            result[0][bit] = 1 << (Integer.SIZE - 1 - bit);
        }
        for (int dimension = 1; dimension < DIMENSIONS; dimension++) {
            int[] parameters = PARAMETERS[dimension];
            int degree = parameters[0];
            int coefficient = parameters[1];
            for (int bit = 1; bit <= degree; bit++) {
                result[dimension][bit - 1] = parameters[bit + 1] << (Integer.SIZE - bit);
            }
            for (int bit = degree + 1; bit <= Integer.SIZE; bit++) {
                int value = result[dimension][bit - degree - 1]
                        ^ (result[dimension][bit - degree - 1] >>> degree);
                for (int k = 1; k < degree; k++) {
                    if (((coefficient >>> (degree - 1 - k)) & 1) != 0) {
                        value ^= result[dimension][bit - k - 1];
                    }
                }
                result[dimension][bit - 1] = value;
            }
        }
        return result;
    }
}
