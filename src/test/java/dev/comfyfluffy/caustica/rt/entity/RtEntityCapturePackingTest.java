package dev.comfyfluffy.caustica.rt.entity;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RtEntityCapturePackingTest {
    @Test
    void stablePartitionPreservesTriangleAndPrimitiveBits() {
        RtEntityCapture capture = new RtEntityCapture();
        int[] buckets = {1, 0, 1, 0};
        for (int tri = 0; tri < buckets.length; tri++) {
            capture.idx.add(tri * 3);
            capture.idx.add(tri * 3 + 1);
            capture.idx.add(tri * 3 + 2);
            capture.alphaBuckets.add(buckets[tri]);
            for (int lane = 0; lane < 12; lane++) {
                capture.prim.add(Float.intBitsToFloat(0x3f000000 + tri * 16 + lane));
            }
        }

        RtEntityCapture.PackedGeometry packed = capture.packGeometry();
        assertArrayEquals(new int[] {3, 4, 5, 9, 10, 11, 0, 1, 2, 6, 7, 8},
                packed.indices().toIntArray());
        assertArrayEquals(new int[] {2, 2}, packed.copyBucketTris());
        int[] expectedTriangleOrder = {1, 3, 0, 2};
        for (int output = 0; output < expectedTriangleOrder.length; output++) {
            int source = expectedTriangleOrder[output];
            for (int lane = 0; lane < 12; lane++) {
                int expected = 0x3f000000 + source * 16 + lane;
                int actual = Float.floatToRawIntBits(packed.primitives().getFloat(output * 12 + lane));
                assertEquals(expected, actual, "primitive bit mismatch at triangle " + output + ", lane " + lane);
            }
        }
    }
}
