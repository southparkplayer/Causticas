package dev.comfyfluffy.caustica.rt.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RtMaterialDescTest {
    @Test
    void retainsCompilerSourceAndNormalizedEmissionMetadata() {
        RtMaterialDesc.EmissionSummary summary = new RtMaterialDesc.EmissionSummary(
                0.4f, 0.2f, 0.1f, 0.25f, 0.5f);
        RtMaterialDesc desc = new RtMaterialDesc(0, RtMaterialDesc.Source.HEURISTIC, 0,
                0.7f, 0.0f, 1.0f, 0.0f, RtMaterials.Profile.NEUTRAL, 0.0f, 0.0f,
                RtMaterialDesc.EmissionSource.HEURISTIC_MASK, 1.0f, summary);
        assertEquals(RtMaterialDesc.Source.HEURISTIC, desc.source());
        assertEquals(RtMaterialDesc.EmissionSource.HEURISTIC_MASK, desc.emissionSource());
        assertEquals(summary, desc.emissionSummary());
    }

    @Test
    void rejectsInvalidPhysicalParameters() {
        assertThrows(IllegalArgumentException.class, () -> new RtMaterialDesc(0,
                RtMaterialDesc.Source.HEURISTIC, 0, 1.1f, 0.0f, 1.0f, 0.0f,
                RtMaterials.Profile.NEUTRAL, 0.0f, 0.0f,
                RtMaterialDesc.EmissionSource.NONE, 0.0f, RtMaterialDesc.EmissionSummary.NONE));
        assertThrows(IllegalArgumentException.class, () -> new RtMaterialDesc(0,
                RtMaterialDesc.Source.HEURISTIC, 0, 0.5f, 0.0f, 0.0f, 0.0f,
                RtMaterials.Profile.NEUTRAL, 0.0f, 0.0f,
                RtMaterialDesc.EmissionSource.NONE, 0.0f, RtMaterialDesc.EmissionSummary.NONE));
    }

    @Test
    void glassRetainsAllAuthoredShadingFeatures() {
        int authoredFeatures = RtMaterialRegistry.FEATURE_SPEC
                | RtMaterialRegistry.FEATURE_NORMAL
                | RtMaterialRegistry.FEATURE_HEURISTIC_EMISSION;
        RtMaterialDesc glass = new RtMaterialDesc(RtMaterialRegistry.MODEL_GLASS,
                RtMaterialDesc.Source.OVERRIDE, authoredFeatures,
                0.12f, 0.0f, 1.52f, 0.85f, RtMaterials.Profile.GLASS, 0.0f, 0.0f,
                RtMaterialDesc.EmissionSource.HEURISTIC_MASK,
                1.5f, new RtMaterialDesc.EmissionSummary(0.2f, 0.3f, 0.4f, 0.25f, 0.5f));

        assertEquals(authoredFeatures, glass.features());
        assertEquals(RtMaterialRegistry.MODEL_GLASS, glass.model());
        assertEquals(RtMaterialDesc.Source.OVERRIDE, glass.source());
        assertEquals(0.12f, glass.roughness());
        assertEquals(1.52f, glass.ior());
        assertEquals(0.85f, glass.transmission());
        assertEquals(RtMaterialDesc.EmissionSource.HEURISTIC_MASK, glass.emissionSource());
    }
}
