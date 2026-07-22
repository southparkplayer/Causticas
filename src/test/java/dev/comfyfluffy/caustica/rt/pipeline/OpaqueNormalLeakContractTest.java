package dev.comfyfluffy.caustica.rt.pipeline;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class OpaqueNormalLeakContractTest {
    @Test
    void normalControlledContributionsRejectOpposingGeometricDirections() throws IOException {
        String raygen = source("shaders/world/world.rgen.slang");

        assertTrue(raygen.contains("float3 evalSampleContrib(float3 sp, float3 lnrm, float3 le, float area, float3 hitPos, float3 n,"));
        assertTrue(raygen.contains("float3 geometricNormal, float3 v, float3 rd, float3 diffAlb, float3 F0, float rough,"));
        assertTrue(raygen.contains("float geometricNdl = dot(geometricNormal, wi);"));
        assertTrue(raygen.contains("if (ndl > 0.0 && geometricNdl > 0.0) {"));
        assertTrue(raygen.contains("else if (!twoSided && sss > 0.0 && geometricNdl < 0.0) {"));

        assertTrue(raygen.contains("float geometricNdl = dot(geometricNormal, lightDir);"));
        assertTrue(raygen.contains("float ndl = celestialNeeEligible && geometricNdl > 0.0"));
        assertTrue(raygen.contains("float backNdl = celestialNeeEligible && geometricNdl < 0.0"));

        assertTrue(raygen.contains("float3 shadeReservoir(Reservoir s, float3 hitPos, float3 n, float3 geometricNormal, float3 v, float3 rd, float3 diffAlb,"));
        assertTrue(raygen.contains("float3 shadowNormal = twoSided ? n : geometricNormal;"));
        assertTrue(raygen.contains("float3 origin = hitPos + side * shadowNormal * RAY_ORIGIN_BIAS;"));

        assertTrue(raygen.contains("rd = reflect(rd, n);"));
        assertTrue(raygen.contains("if (dot(geometricNormal, rd) <= 0.0) {"));
        assertTrue(raygen.contains("if (ndl2 <= 0.0 || dot(geometricNormal, l) <= 0.0) {"));
        assertTrue(raygen.contains("float3 l = sampleEon(n, v, perceptualRough, sampler, diffusePdf);"));
        assertTrue(raygen.contains("if (dot(geometricNormal, l) <= 0.0) {"));
        assertTrue(raygen.contains("rd = cosineDir(n, sampler);"));
        assertTrue(raygen.contains("if (dot(geometricNormal, rd) <= 0.0) {"));
    }

    @Test
    void risPathUsesGeometricNormalForFrontAndBacklightSelection() throws IOException {
        String raygen = source("shaders/world/world.rgen.slang");

        assertTrue(raygen.contains("Reservoir risInitial(float3 hitPos, float3 n, float3 geometricNormal, float3 v, float3 rd, float3 diffAlb, float3 F0,"));
        assertTrue(raygen.contains("float3 risContrib = shadeReservoir(r, hitPos, n, geometricNormal, v, rd, diffAlb, F0, rough, pbr, false,"));
        assertTrue(raygen.contains("Reservoir r = risInitial(hitPos, n, geometricNormal, v, rd, diffAlb, F0, rough, pbr, false,"));
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
