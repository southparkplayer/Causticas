package dev.comfyfluffy.caustica.rt.terrain;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TorchEmissionContractTest {
    private static final Path MATERIAL_ROOT = Path.of(
            "src/main/resources/assets/minecraft/textures/block");

    @Test
    void integratorBoostsOnlyTorchFamilyAfterSharedMaterialDecode() throws Exception {
        String closestHit = Files.readString(Path.of("shaders/world/world.rchit.slang"));
        String raygen = Files.readString(Path.of("shaders/world/world.rgen.slang"));
        String common = Files.readString(Path.of("shaders/world/world_common.slang"));
        String mesher = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtTerrainMesher.java"));
        assertTrue(closestHit.contains("emission = mappedEmission;"));
        assertFalse(closestHit.contains("torchIntensity"));
        assertTrue(closestHit.contains("payload.flags |= PAYLOAD_TORCH_EMITTER;"));
        assertTrue(common.contains("[vk::offset(420)] public float    torchIntensity"));
        assertTrue(common.contains("PAYLOAD_TORCH_EMITTER = 1u << 9u"));
        assertTrue(raygen.contains("if (payloadTorchEmitter())"));
        assertTrue(raygen.contains("emissiveRadiance *= EMISSIVE_MAX_MULTIPLIER"));
        assertTrue(mesher.contains("state.getBlock() instanceof BaseTorchBlock"));
        assertTrue(mesher.contains("(q.torch ? 4f : 0f)"));
        assertTrue(raygen.contains("L += throughput * albedo * emission * emissiveRadiance;"));
        assertTrue(raygen.contains("float3 materialEmissive = albedo * emission * emissiveRadiance;"));
        assertTrue(raygen.contains("cacheableDirectLighting + liveDirectLighting + materialEmissive"));
        assertTrue(raygen.contains("causticaSharcHit(hitPos, albedo, materialEmissive)"));
        assertFalse(raygen.contains("sampleTerrainEmitter"));
    }

    @Test
    void builtInMaterialMapsEmitOnlyFromTheTorchCaps() throws Exception {
        assertMask("torch_s.png", Set.of(point(7, 6), point(8, 6), point(7, 7), point(8, 7)));
        assertMask("soul_torch_s.png", Set.of(point(7, 6), point(8, 6), point(7, 7), point(8, 7)));
        assertMask("copper_torch_s.png", Set.of(point(7, 6), point(8, 6), point(7, 7), point(8, 7)));
        assertMask("redstone_torch_s.png", Set.of(
                point(6, 5), point(7, 5), point(8, 5), point(9, 5),
                point(6, 6), point(7, 6), point(8, 6), point(9, 6),
                point(6, 7), point(7, 7), point(8, 7), point(9, 7),
                point(6, 8), point(7, 8), point(8, 8), point(9, 8)));
    }

    private static void assertMask(String name, Set<Integer> emitting) throws Exception {
        BufferedImage image = ImageIO.read(MATERIAL_ROOT.resolve(name).toFile());
        assertEquals(16, image.getWidth(), name);
        assertEquals(16, image.getHeight(), name);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int rgba = image.getRGB(x, y);
                assertEquals(0, (rgba >>> 16) & 0xFF, name + " red");
                assertEquals(10, (rgba >>> 8) & 0xFF, name + " green");
                assertEquals(0, rgba & 0xFF, name + " blue");
                assertEquals(emitting.contains(point(x, y)) ? 254 : 255, (rgba >>> 24) & 0xFF,
                        name + " alpha at " + x + "," + y);
            }
        }
    }

    private static int point(int x, int y) {
        return (y << 8) | x;
    }
}
