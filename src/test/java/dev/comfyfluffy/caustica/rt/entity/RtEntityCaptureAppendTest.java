package dev.comfyfluffy.caustica.rt.entity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

final class RtEntityCaptureAppendTest {
    @Test
    void bulkAppendMatchesScalarReferenceAcrossCapacityGrowthBitwise() {
        Random random = new Random(0xB01CA0571CAL);
        RtEntityCapture capture = new RtEntityCapture();
        int quadCount = 401; // crosses every default accumulator capacity
        int[] expectedIndices = new int[quadCount * 6];
        int[] expectedPrimitiveBits = new int[quadCount * 24];
        int[] expectedPositionBits = new int[quadCount * 12];
        int[] expectedUvBits = new int[quadCount * 8];
        int[] expectedBuckets = new int[quadCount * 2];

        for (int quad = 0; quad < quadCount; quad++) {
            float[] x = randomFloats(random, 4);
            float[] y = randomFloats(random, 4);
            float[] z = randomFloats(random, 4);
            float[] u = randomFloats(random, 4);
            float[] v = randomFloats(random, 4);
            float nx = random.nextFloat() * 2f - 1f;
            float ny = random.nextFloat() * 2f - 1f;
            float nz = random.nextFloat() * 2f - 1f;
            int color = random.nextInt();
            float emission = Float.intBitsToFloat(random.nextInt());
            capture.currentTexSlot = random.nextInt(65);
            capture.currentMaterialId = random.nextInt();
            capture.currentPrimFlags = random.nextInt();
            capture.currentAlphaBucket = random.nextInt(2);
            capture.addDirectQuad(x, y, z, u, v, nx, ny, nz, color, emission);

            int vertexBase = quad * 4;
            for (int i = 0; i < 4; i++) {
                int positionLane = quad * 12 + i * 3;
                expectedPositionBits[positionLane] = Float.floatToRawIntBits(x[i]);
                expectedPositionBits[positionLane + 1] = Float.floatToRawIntBits(y[i]);
                expectedPositionBits[positionLane + 2] = Float.floatToRawIntBits(z[i]);
                int uvLane = quad * 8 + i * 2;
                expectedUvBits[uvLane] = Float.floatToRawIntBits(u[i]);
                expectedUvBits[uvLane + 1] = Float.floatToRawIntBits(v[i]);
            }
            int indexLane = quad * 6;
            expectedIndices[indexLane] = vertexBase;
            expectedIndices[indexLane + 1] = vertexBase + 1;
            expectedIndices[indexLane + 2] = vertexBase + 2;
            expectedIndices[indexLane + 3] = vertexBase;
            expectedIndices[indexLane + 4] = vertexBase + 2;
            expectedIndices[indexLane + 5] = vertexBase + 3;

            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1.0e-6f) {
                nx /= len;
                ny /= len;
                nz /= len;
            }
            float tr = ((color >> 16) & 0xFF) * (1f / 255f);
            float tg = ((color >> 8) & 0xFF) * (1f / 255f);
            float tb = (color & 0xFF) * (1f / 255f);
            for (int triangle = 0; triangle < 2; triangle++) {
                int lane = quad * 24 + triangle * 12;
                expectedPrimitiveBits[lane] = Float.floatToRawIntBits(nx);
                expectedPrimitiveBits[lane + 1] = Float.floatToRawIntBits(ny);
                expectedPrimitiveBits[lane + 2] = Float.floatToRawIntBits(nz);
                expectedPrimitiveBits[lane + 3] = Float.floatToRawIntBits(emission);
                expectedPrimitiveBits[lane + 4] = Float.floatToRawIntBits(tr);
                expectedPrimitiveBits[lane + 5] = Float.floatToRawIntBits(tg);
                expectedPrimitiveBits[lane + 6] = Float.floatToRawIntBits(tb);
                expectedPrimitiveBits[lane + 7] = Float.floatToRawIntBits((float) capture.currentTexSlot);
                expectedPrimitiveBits[lane + 8] = capture.currentMaterialId;
                expectedPrimitiveBits[lane + 9] = capture.currentPrimFlags;
                expectedPrimitiveBits[lane + 10] = 0;
                expectedPrimitiveBits[lane + 11] = 0;
                expectedBuckets[quad * 2 + triangle] = capture.currentAlphaBucket;
            }
        }

        assertArrayEquals(expectedPositionBits, rawBits(capture.verts.toFloatArray()));
        assertArrayEquals(expectedUvBits, rawBits(capture.uvList.toFloatArray()));
        assertArrayEquals(expectedIndices, capture.idx.toIntArray());
        assertArrayEquals(expectedPrimitiveBits, rawBits(capture.prim.toFloatArray()));
        assertArrayEquals(expectedBuckets, capture.alphaBuckets.toIntArray());
    }

    @Test
    void vertexConsumerPreservesFirstVertexFlatAttributes() {
        RtEntityCapture capture = new RtEntityCapture();
        for (int vertex = 0; vertex < 4; vertex++) {
            capture.addVertex(vertex, 0f, 0f, 0xFF123456 + vertex, 0f, 0f,
                    0, 0, vertex == 0 ? 0f : 1f, 0f, vertex == 0 ? 1f : 0f);
        }
        assertArrayEquals(new int[] {0, 1, 2, 0, 2, 3}, capture.idx.toIntArray());
        int[] primitiveBits = rawBits(capture.prim.toFloatArray());
        for (int triangle = 0; triangle < 2; triangle++) {
            int lane = triangle * 12;
            assertArrayEquals(new int[] {
                    Float.floatToRawIntBits(0f), Float.floatToRawIntBits(0f), Float.floatToRawIntBits(1f)
            }, new int[] {primitiveBits[lane], primitiveBits[lane + 1], primitiveBits[lane + 2]});
        }
    }

    private static float[] randomFloats(Random random, int count) {
        float[] values = new float[count];
        for (int i = 0; i < count; i++) values[i] = Float.intBitsToFloat(random.nextInt());
        return values;
    }

    private static int[] rawBits(float[] values) {
        int[] bits = new int[values.length];
        for (int i = 0; i < values.length; i++) bits[i] = Float.floatToRawIntBits(values[i]);
        return bits;
    }
}
