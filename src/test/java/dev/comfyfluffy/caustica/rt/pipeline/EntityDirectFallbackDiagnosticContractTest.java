package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EntityDirectFallbackDiagnosticContractTest {
    @Test
    void rejectedTopologyKeepsFallbackAndAvoidsRepeatedTreeValidation() throws Exception {
        String emitter = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/entity/RtCuboidEmitter.java"));
        String collector = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/entity/RtEntityCollector.java"));

        assertTrue(emitter.contains("structurallyRejectedRoots.get(model) == root"));
        assertTrue(emitter.contains("structurallyRejectedRoots.put(model, root)"));
        assertTrue(emitter.contains("logFallbackOnce(loggedNamespaceRejects"));
        assertTrue(emitter.contains("logFallbackOnce(loggedTopologyRejects"));
        assertTrue(collector.contains("model.renderToBuffer(poseStack, capture"),
                "Every rejected model must retain the existing visual fallback");
    }
}
