package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.comfyfluffy.caustica.streamline.StreamlineAbi;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class RtDlssRrResourceContractTest {
    @Test
    void coreContractRequiresExactlyEightResources() {
        assertEquals(8, RtDlssRr.requiredResourceCount());
    }

    @Test
    void vulkanTextureDescriptorUsesImageAndViewWithoutAllocatorMemory() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment resources = StreamlineAbi.allocate(arena, StreamlineAbi.RESOURCE_DESC_SIZE);
            RtDlssRr.writeResource(resources, 0, 0x1111L, 0x2222L, 126,
                    1280, 720, 14, 0x1f);
            ByteBuffer bytes = StreamlineAbi.bytes(resources);

            assertEquals(0x1111L, bytes.getLong(0));
            assertEquals(0x2222L, bytes.getLong(8));
            assertEquals(0L, bytes.getLong(16));
            assertEquals(1280, bytes.getInt(28));
            assertEquals(720, bytes.getInt(32));
            assertEquals(126, bytes.getInt(36));
            assertEquals(0x1f, bytes.getInt(52));
            assertEquals(14, bytes.getInt(56));
            assertEquals(2, bytes.getInt(60));
            assertEquals(1, bytes.get(64));
        }
    }

    @Test
    void acceptanceSchemaIsFour() {
        assertEquals(6, StreamlineAcceptanceReport.SCHEMA_VERSION);
    }
}
