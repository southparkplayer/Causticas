package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class RtOfflineExporterTest {
    @Test
    void writesUncompressedFloatScanlineExrHeaderAndPayload() throws Exception {
        ByteBuffer pixels = ByteBuffer.allocateDirect(2 * 16).order(ByteOrder.nativeOrder());
        pixels.putFloat(0, 1.0f);
        pixels.putFloat(4, 0.5f);
        pixels.putFloat(8, 0.25f);
        pixels.putFloat(16, 0.125f);
        pixels.putFloat(20, 0.0625f);
        pixels.putFloat(24, 0.03125f);

        Path output = Files.createTempFile("caustica-offline-", ".exr");
        try {
            RtOfflineExporter.writeExr(output, pixels, 2, 1);
            byte[] bytes = Files.readAllBytes(output);
            assertArrayEquals(new byte[] {0x76, 0x2f, 0x31, 0x01},
                    java.util.Arrays.copyOf(bytes, 4));
            assertTrue(new String(bytes, StandardCharsets.ISO_8859_1).contains("channels\0chlist\0"));
            assertTrue(bytes.length > 200);
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
