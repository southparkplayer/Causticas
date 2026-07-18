package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EntityGeometryPackingContractTest {
    @Test
    void packingUsesStableBucketPrefixesAndBulkBitPreservingCopies() throws Exception {
        String capture = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/entity/RtEntityCapture.java"));
        int method = capture.indexOf("PackedGeometry packGeometry()");
        int end = capture.indexOf("record PackedGeometry", method);
        String pack = capture.substring(method, end);

        assertTrue(pack.contains("packedBucketTris[bucket]++"));
        assertTrue(pack.contains("packedBucketCursor[bucket] = triangleBase"));
        assertTrue(pack.contains("int outputTriangle = packedBucketCursor[buckets[tri]]++"));
        assertTrue(pack.contains("System.arraycopy(indices, tri * 3"));
        assertTrue(pack.contains("System.arraycopy(primitives, tri * 12"));
        assertTrue(pack.indexOf("for (int tri = 0; tri < triangleCount; tri++)")
                < pack.indexOf("int triangleBase = 0"),
                "Bucket counts must be known before stable output prefixes are assigned");
    }
}
