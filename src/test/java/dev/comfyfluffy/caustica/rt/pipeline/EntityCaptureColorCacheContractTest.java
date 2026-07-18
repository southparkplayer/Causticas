package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EntityCaptureColorCacheContractTest {
    @Test
    void repeatedArgbDecodeIsCachedAndResetPerCapture() throws Exception {
        String capture = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/entity/RtEntityCapture.java"));
        assertTrue(capture.contains("colorCacheValid && cachedColor == color"));
        assertTrue(capture.contains("cachedTr = tr;"));
        assertTrue(capture.contains("colorCacheValid = false;"));
        assertFalse(capture.contains("cachedNxBits"),
                "Normal memoization regressed the measured EMF fallback and must remain absent");
    }
}
