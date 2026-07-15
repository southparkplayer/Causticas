package dev.comfyfluffy.caustica.streamline;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Matrix4fc;

/** Versioned Caustica-owned C ABI shared with {@code streamlinebridge.dll}. */
public final class StreamlineAbi {
    public static final int VERSION = 8;
    public static final int RESOURCE_DESC_SIZE = 72;
    public static final int CONSTANTS_SIZE = 444;
    public static final int DLSSD_OPTIONS_SIZE = 144;
    public static final int DLSSG_OPTIONS_SIZE = 72;
    public static final int DLSSG_STATE_SIZE = 48;
    public static final int REFLEX_OPTIONS_SIZE = 8;
    public static final int REFLEX_STATE_SIZE = 88;
    public static final int TRACE_STATE_SIZE = 336;

    public static final int TRACE_SWAPCHAIN_HANDLE_OFFSET = 304;
    public static final int TRACE_SWAPCHAIN_PRESENT_MODE_OFFSET = 312;
    public static final int TRACE_SWAPCHAIN_MIN_IMAGE_COUNT_OFFSET = 316;
    public static final int TRACE_SWAPCHAIN_IMAGE_COUNT_OFFSET = 320;
    public static final int TRACE_SWAPCHAIN_CREATE_RESULT_OFFSET = 324;
    public static final int TRACE_SWAPCHAIN_PROXY_DISPATCH_OFFSET = 328;
    public static final int TRACE_SWAPCHAIN_PRESENT_MODE_KNOWN_OFFSET = 332;

    private static final int ABI_INFO_SIZE = 36;

    private StreamlineAbi() {
    }

    public static void validate(StreamlineLibrary library) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = arena.allocate(ABI_INFO_SIZE, 8);
            int result = library.getAbiInfo(info);
            if (result != 0) {
                throw new IllegalStateException("Could not query Streamline bridge ABI: " + library.lastError());
            }
            ByteBuffer bytes = bytes(info);
            require("version", bytes.getInt(0), VERSION);
            require("resource descriptor size", bytes.getInt(4), RESOURCE_DESC_SIZE);
            require("constants size", bytes.getInt(8), CONSTANTS_SIZE);
            require("DLSS-RR options size", bytes.getInt(12), DLSSD_OPTIONS_SIZE);
            require("DLSS-G options size", bytes.getInt(16), DLSSG_OPTIONS_SIZE);
            require("DLSS-G state size", bytes.getInt(20), DLSSG_STATE_SIZE);
            require("Reflex options size", bytes.getInt(24), REFLEX_OPTIONS_SIZE);
            require("Reflex state size", bytes.getInt(28), REFLEX_STATE_SIZE);
            require("trace state size", bytes.getInt(32), TRACE_STATE_SIZE);
        }
    }

    /** Serialize a JOML column-vector transform as Streamline's row-vector, row-major matrix. */
    public static void writeRowVectorMatrix(ByteBuffer bytes, int offset, Matrix4fc matrix) {
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                bytes.putFloat(offset + (row * 4 + column) * Float.BYTES, matrix.get(row, column));
            }
        }
    }

    public static ByteBuffer bytes(MemorySegment segment) {
        return segment.asByteBuffer().order(ByteOrder.nativeOrder());
    }

    public static MemorySegment allocate(Arena arena, int size) {
        return arena.allocate(size, 8);
    }

    public static int pollApiError(StreamlineLibrary library) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment output = arena.allocate(ValueLayout.JAVA_INT);
            return library.pollApiError(output) == 0 ? 0 : output.get(ValueLayout.JAVA_INT, 0);
        }
    }

    private static void require(String field, int actual, int expected) {
        if (actual != expected) {
            throw new IllegalStateException("Streamline bridge ABI " + field + " mismatch: expected "
                    + expected + ", got " + actual);
        }
    }
}
