package dev.comfyfluffy.caustica.rt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VK10;

final class RtHdrSelectionTest {
    private static final int HDR10_ST2084 = 1000104008;

    @Test
    void effectiveHdrRequiresRequestedPqAndAnImplementedTenBitFormat() {
        assertTrue(RtHdr.effectiveHdrForSelection(true,
                VK10.VK_FORMAT_A2B10G10R10_UNORM_PACK32, HDR10_ST2084));
        assertTrue(RtHdr.effectiveHdrForSelection(true,
                VK10.VK_FORMAT_A2R10G10B10_UNORM_PACK32, HDR10_ST2084));
        assertFalse(RtHdr.effectiveHdrForSelection(true,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, HDR10_ST2084));
        assertFalse(RtHdr.effectiveHdrForSelection(true,
                VK10.VK_FORMAT_A2B10G10R10_UNORM_PACK32, 0));
        assertFalse(RtHdr.effectiveHdrForSelection(false,
                VK10.VK_FORMAT_A2B10G10R10_UNORM_PACK32, HDR10_ST2084));
    }

    @Test
    void surfaceRescanCannotRetainAStalePqColorSpace() {
        assertFalse(RtHdr.resetColorSpaceForSurfaceScan(HDR10_ST2084) == HDR10_ST2084);
        assertFalse(RtHdr.resetColorSpaceForSurfaceScan(0) == HDR10_ST2084);
    }
}
