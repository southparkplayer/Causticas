package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EntityBakedQuadBatchContractTest {
    @Test
    void adjacentMaterialRunsPreserveEveryBakedQuad() throws Exception {
        String collector = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/entity/RtEntityCollector.java"));
        int method = collector.indexOf("private void addQuads(");
        int nextMethod = collector.indexOf("private void countBakedOutput", method);
        String addQuads = collector.substring(method, nextMethod);

        assertTrue(addQuads.contains("sprite != lastSprite || layer != lastLayer"));
        assertTrue(addQuads.contains("applyBakedMaterial(sprite, layer)"));
        assertTrue(addQuads.contains("for (BakedQuad q : quads)"));
        assertTrue(addQuads.contains("capture.addBakedQuad(pose, q,"),
                "Batching may cache material state but must still emit every authored quad");
        assertFalse(addQuads.contains("continue;"));
    }
}
