package dev.comfyfluffy.caustica.rt.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RtMaterialDescTest {
    @Test
    void retainsCompilerSourceAndNormalizedEmissionMetadata() {
        RtMaterialDesc.EmissionSummary summary = new RtMaterialDesc.EmissionSummary(
                0.4f, 0.2f, 0.1f, 0.25f, 0.5f);
        RtMaterialDesc desc = new RtMaterialDesc(0, RtMaterialDesc.Source.HEURISTIC, 0,
                0.7f, 0.0f, 1.0f, 0.0f, RtMaterialDesc.EmissionSource.HEURISTIC_MASK,
                1.0f, summary);
        assertEquals(RtMaterialDesc.Source.HEURISTIC, desc.source());
        assertEquals(RtMaterialDesc.EmissionSource.HEURISTIC_MASK, desc.emissionSource());
        assertEquals(summary, desc.emissionSummary());
    }

    @Test
    void rejectsInvalidPhysicalParameters() {
        assertThrows(IllegalArgumentException.class, () -> new RtMaterialDesc(0,
                RtMaterialDesc.Source.HEURISTIC, 0, 1.1f, 0.0f, 1.0f, 0.0f,
                RtMaterialDesc.EmissionSource.NONE, 0.0f, RtMaterialDesc.EmissionSummary.NONE));
        assertThrows(IllegalArgumentException.class, () -> new RtMaterialDesc(0,
                RtMaterialDesc.Source.HEURISTIC, 0, 0.5f, 0.0f, 0.0f, 0.0f,
                RtMaterialDesc.EmissionSource.NONE, 0.0f, RtMaterialDesc.EmissionSummary.NONE));
    }

    @Test
    void glassDropsOnlyNormalMappingAtTheTransportBoundary() {
        int authoredFeatures = RtMaterialRegistry.FEATURE_SPEC
                | RtMaterialRegistry.FEATURE_NORMAL
                | RtMaterialRegistry.FEATURE_OVERRIDE_EMISSION;
        RtMaterialDesc glass = new RtMaterialDesc(RtMaterialRegistry.MODEL_GLASS,
                RtMaterialDesc.Source.OVERRIDE, authoredFeatures,
                0.12f, 0.0f, 1.52f, 0.85f, RtMaterialDesc.EmissionSource.OVERRIDE,
                1.5f, new RtMaterialDesc.EmissionSummary(0.2f, 0.3f, 0.4f, 0.25f, 0.5f));

        RtMaterialDesc normalized = RtMaterialRegistry.normalizeForTransport(glass);

        assertEquals(authoredFeatures & ~RtMaterialRegistry.FEATURE_NORMAL, normalized.features());
        assertEquals(glass.model(), normalized.model());
        assertEquals(glass.source(), normalized.source());
        assertEquals(glass.roughness(), normalized.roughness());
        assertEquals(glass.metalness(), normalized.metalness());
        assertEquals(glass.ior(), normalized.ior());
        assertEquals(glass.transmission(), normalized.transmission());
        assertEquals(glass.emissionSource(), normalized.emissionSource());
        assertEquals(glass.emissionStrength(), normalized.emissionStrength());
        assertEquals(glass.emissionSummary(), normalized.emissionSummary());

        RtMaterialDesc opaque = new RtMaterialDesc(RtMaterialRegistry.MODEL_OPAQUE,
                RtMaterialDesc.Source.LAB_PBR, authoredFeatures,
                0.5f, 0.1f, 1.0f, 0.0f, RtMaterialDesc.EmissionSource.LAB_PBR,
                1.0f, RtMaterialDesc.EmissionSummary.NONE);
        assertSame(opaque, RtMaterialRegistry.normalizeForTransport(opaque));
    }
}
