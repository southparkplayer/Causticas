package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Keeps non-planar flowing-fluid quads from sharing one triangle's medium-facing normal. */
final class FluidTriangleNormalContractTest {
    @Test
    void fluidCaptureBuildsEachTriangleWithItsOwnNormal() throws IOException {
        String terrain = source("src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtTerrainMesher.java");
        String raygen = source("shaders/world/world.rgen.slang");

        assertTrue(terrain.contains("emitTriangle(g, base, 0, 1, 2"));
        assertTrue(terrain.contains("emitTriangle(g, base, 0, 2, 3"));
        assertTrue(terrain.contains("float ex1 = qx[b] - qx[a]"));
        assertTrue(terrain.contains("if (len <= 1.0e-6f)"));
        assertFalse(terrain.contains("float ex1 = qx[1] - qx[0]"));
        assertTrue(raygen.contains("float3 surfaceWaterGeometricNormal = geometricNormal;"));
        assertTrue(raygen.contains("offsetRayOrigin(hitPos, surfaceWaterGeometricNormal, rd)"));
        assertFalse(raygen.contains("static const float SURF_BIAS"));
    }

    private static String source(String relative) throws IOException {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            Path candidate = cursor.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate).replace("\r\n", "\n");
            }
            cursor = cursor.getParent();
        }
        throw new IOException("Could not locate " + relative);
    }
}
